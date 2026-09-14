package com.example.grader.service;

import com.example.grader.dto.BatchProgressResponse;
import com.example.grader.dto.BatchSubmitResponse;
import com.example.grader.entity.*;
import com.example.grader.repository.ExamRepository;
import com.example.grader.repository.ExamResultRepository;
import com.example.grader.repository.GradingBatchRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Service
public class BatchGradingService {

    @Value("${grader.workers.enabled:true}")
    private boolean workersEnabled;

    /**
     * Bản đang chạy có màn soạn đề hay không — dùng để chọn lời hướng dẫn cho đúng người đọc.
     * Bật khi profile "gv" đang hoạt động (xem config/Vai.java).
     */
    @Value("#{environment.acceptsProfiles(T(org.springframework.core.env.Profiles).of('gv'))}")
    private boolean coManSoanDe;

    /** Lưu lại file zip bài nộp để audit/chấm lại khi tranh chấp. */
    @Value("${grader.save-submissions:true}")
    private boolean saveSubmissions;
    @Value("${grader.submissions-dir:submissions}")
    private String submissionsDir;
    /** Số ngày giữ bài nộp (<=0 = giữ mãi). Dọn tự động hằng ngày. */
    @Value("${grader.submissions-retention-days:30}")
    private int submissionsRetentionDays;

    @Autowired
    private GradingService           gradingService;
    @Autowired private ExamService          examService;
    @Autowired private ExamResultRepository resultRepo;
    @Autowired private GradingBatchRepository batchRepo;
    @Autowired private ExamRepository examRepo;
    @Autowired private GradingRuntimeSettingsService runtimeSettings;
    @Autowired private StarterSyncService starterSyncService;

    /** Bản hợp đồng `result.json` mà backend này phát hành — xem SPEC_grader_result_json/. */
    private static final String SCHEMA_VERSION = "2";

    private final ObjectMapper mapper = new ObjectMapper();
    /** Bộ phân loại lỗi testcase (log thô → code/actual/message) — bao quát nhiều loại lỗi Dart/Flutter. */
    private final TestErrorClassifier errorClassifier = new TestErrorClassifier();

    /** Bộ đếm đảm bảo batchId DUY NHẤT ngay cả khi 2 request cùng mili-giây (cùng cột UNIQUE ở DB). */
    private static final java.util.concurrent.atomic.AtomicLong BATCH_SEQ =
            new java.util.concurrent.atomic.AtomicLong();
    /**
     * Mã SV nằm ở BẤT KỲ đâu trong tên thư mục: "Nguyen Van A (HE123456)", "PE_ca1-he150123",
     * "khiempghe186137". Không đặt \b ở đầu vì mã thường dính liền tên tài khoản.
     */
    private static final Pattern STUDENT_ID_ANYWHERE = Pattern.compile("(?i)([a-z]{2}\\d{6,})(?!\\d)");
    private static String genBatchId() {
        return "BATCH_" + System.currentTimeMillis() + "_" + Long.toHexString(BATCH_SEQ.incrementAndGet());
    }
    private ExecutorService executor;
    private final BlockingQueue<GradingJob> jobQueue = new LinkedBlockingQueue<>();
    /** Cache trạng thái pause; trạng thái chuẩn vẫn được lưu trong grading_batches. */
    private final java.util.Set<String> pausedBatchIds = ConcurrentHashMap.newKeySet();

    /**
     * Số bài chấm SONG SONG = số worker đang sống. Giáo viên đổi được ngay giữa phiên chấm
     * (trang Chấm bài tự động → Hiệu năng chấm) nên không dùng pool cố định nữa:
     * tăng thì mở thêm worker, giảm thì worker dư tự rút lui SAU khi chấm xong bài đang cầm
     * (không bao giờ cắt ngang một bài đang chấm dở).
     */
    private final java.util.concurrent.atomic.AtomicInteger targetWorkers =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger liveWorkers =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger workerSeq =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean shuttingDown = false;

    record GradingJob(String studentId, String studentName,
                      String batchId, String examId, String zipPath,
                      String testcasePath) {}   // testcasePath != null = ép dùng (chấm lại đề cũ)
    record StudentInfo(String studentId, String studentName) {}

    @PostConstruct
    public void startWorkers() {
        if (!workersEnabled) {
            log.info("Grading workers disabled by configuration");
            return;
        }
        try {
            batchRepo.findByStatusInOrderByCreatedAtDesc(List.of(BatchStatus.PAUSED))
                    .forEach(batch -> pausedBatchIds.add(batch.getBatchId()));
        } catch (Exception e) {
            log.warn("Không nạp được danh sách batch tạm dừng: {}", e.getMessage());
        }

        // Cached pool: worker rút lui xong thì thread được tái dùng cho lần tăng mức song song sau.
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "grading-worker-" + workerSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        runtimeSettings.bindConcurrencyApplier(this::applyConcurrency);
        applyConcurrency(runtimeSettings.maxConcurrent());
        log.info("Grading workers started (concurrent: {})", targetWorkers.get());
        recoverPendingJobs();   // hàng đợi bền: nạp lại job QUEUED/GRADING sau restart
        try {                   // Flag Pattern: bật has_results cho dữ liệu cũ (chạy 1 lần)
            int n = examRepo.backfillHasResults();
            if (n > 0) log.info("Đã backfill cờ has_results cho {} đề có sẵn bài chấm", n);
        } catch (Exception e) {
            log.warn("Backfill has_results lỗi: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        shuttingDown = true;
        if (executor != null) executor.shutdownNow();
    }

    /**
     * Đổi số bài chấm song song NGAY khi hệ thống đang chạy.
     *
     * <p>Tăng → mở thêm worker, nhận việc luôn từ hàng đợi. Giảm → worker dư tự thoát khi vòng lặp
     * kế tiếp thấy mình thừa; bài đang chấm dở KHÔNG bị cắt ngang (nếu cắt thì bài đó thành ERROR
     * oan cho sinh viên).
     */
    public synchronized void applyConcurrency(int desired) {
        if (!workersEnabled || executor == null || shuttingDown) return;
        int target = Math.max(1, desired);
        targetWorkers.set(target);
        while (liveWorkers.get() < target) {
            liveWorkers.incrementAndGet();
            executor.submit(this::workerLoop);
        }
    }

    /** Tình trạng thực tế của bộ chấm — để trang cấu hình cho thấy mức mới đã có hiệu lực. */
    public Map<String, Object> workerStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", workersEnabled);
        m.put("targetWorkers", targetWorkers.get());
        m.put("activeWorkers", liveWorkers.get());
        m.put("queuedJobs", jobQueue.size());
        return m;
    }

    private void workerLoop() {
        boolean retired = false;
        try {
            while (!Thread.currentThread().isInterrupted() && !shuttingDown) {
                if (retireIfExcess()) { retired = true; return; }
                GradingJob job = null;
                try {
                    // poll (không phải take) để worker DƯ còn thấy được mức song song mới
                    // ngay cả khi hàng đợi đang rỗng.
                    job = jobQueue.poll(1, TimeUnit.SECONDS);
                    if (job == null) continue;
                    if (pausedBatchIds.contains(job.batchId())) {
                        // Đưa về cuối hàng đợi để batch khác vẫn tiếp tục chạy, tránh chiếm cứng worker.
                        jobQueue.offer(job);
                        Thread.sleep(250);
                        continue;
                    }
                    processJob(job);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable t) {
                    // QUAN TRỌNG: KHÔNG để 1 job lỗi giết chết worker (nếu chết thì các bài
                    // sau sẽ kẹt QUEUED vĩnh viễn). Ghi log rồi tiếp tục nhận job kế tiếp.
                    log.error("Worker gặp lỗi ngoài dự kiến khi xử lý job {}: {}",
                            job != null ? job.studentId() : "?", t.toString(), t);
                }
            }
        } finally {
            if (!retired) liveWorkers.decrementAndGet();
        }
    }

    /** true = worker này thừa so với mức song song hiện tại (đã tự trừ khỏi sổ) và phải dừng. */
    private boolean retireIfExcess() {
        while (true) {
            int live = liveWorkers.get();
            if (live <= targetWorkers.get()) return false;
            if (liveWorkers.compareAndSet(live, live - 1)) return true;
        }
    }

    // ── GV upload hàng loạt ──────────────────────────────────────
    /**
     * Chặn chấm khi khung phát cho sinh viên chưa được đối chiếu với Golden.
     *
     * Vì sao chặn ở đây chứ không chỉ ẩn trên giao diện: lệch schema hay lệch định danh giữa
     * khung phát và Golden làm hỏng CẢ LỚP, và không khâu nào khác nhìn thấy nó. Bộ đề publish
     * từ trước khi có khâu này được miễn trừ nên không đề nào đang chạy bị chặn oan.
     */
    /**
     * Lời báo phải nói đúng việc mà NGƯỜI ĐANG ĐỌC làm được. Bản người chấm không có màn soạn
     * đề lẫn Golden, nên bảo họ "vào phần soạn đề mà kiểm" là chỉ vào một màn hình không tồn
     * tại — họ sẽ ngồi bấm quanh rồi kết luận hệ thống hỏng.
     */
    private void chanNeuChuaDongBo(String examId) {
        if (starterSyncService.chamDuoc(examId)) return;
        String trangThai = starterSyncService.trangThai(examId);
        String cachSua = coManSoanDe
                ? "Vào phần soạn đề, chọn đề rồi nạp gói khung phát cho sinh viên để đối chiếu với Golden."
                : "Bản này không có Golden nên không tự kiểm được. Báo người ra đề kiểm đồng bộ khung phát"
                  + " rồi xuất lại gói bàn giao và gửi sang.";
        throw new IllegalStateException("Đề này chưa qua kiểm đồng bộ khung phát (" + trangThai
                + "). " + cachSua + " Đạt thì mới chấm được.");
    }

    public BatchSubmitResponse enqueueBatch(List<MultipartFile> files, List<String> usernames,
                                            String examId, String createdBy) throws Exception {
        if (usernames == null || usernames.size() != files.size())
            throw new IllegalArgumentException(
                    "Mỗi file .zip phải đi kèm đúng tên thư mục username.");
        Exam exam = examRepo.findByExamId(examId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đề thi: " + examId));
        chanNeuChuaDongBo(examId);
        // Không còn nút "Build Sandbox" thủ công: sandbox được chuẩn bị ngay lúc publish/import.
        // Lần đó có thể hỏng vì Docker chưa bật, nên thử lại tại đây — chấm bài vốn đã cần Docker.
        if (exam.getStatus() != ExamStatus.READY) {
            try {
                examService.buildSandbox(examId);
            } catch (Exception e) {
                throw new IllegalStateException("Đề thi chưa sẵn sàng để chấm: " + e.getMessage(), e);
            }
            exam = examRepo.findByExamId(examId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đề thi: " + examId));
            if (exam.getStatus() != ExamStatus.READY)
                throw new IllegalStateException("Đề thi chưa READY: " + exam.getStatus());
        }

        String batchId = genBatchId();
        GradingBatch batch = new GradingBatch();
        batch.setBatchId(batchId);
        batch.setExamId(examId);
        batch.setTotalFiles(files.size());
        batch.setCreatedBy(actorEmail(createdBy));
        batchRepo.save(batch);

        List<String> queued      = new ArrayList<>();
        List<String> parseErrors = new ArrayList<>();
        List<GradingJob> pendingJobs = new ArrayList<>();   // enqueue SAU khi chốt totalFiles

        for (int index = 0; index < files.size(); index++) {
            MultipartFile file = files.get(index);
            String filename = Objects.requireNonNull(file.getOriginalFilename());
            String username = usernames.get(index) == null ? "" : usernames.get(index).trim();
            String submissionLabel = username + "/" + filename;
            try {
                validateZip(file, filename);
                StudentInfo info = parseStudentInfo(username);

                // Chấm lại = GHI ĐÈ: tái sử dụng bản ghi cũ (cùng SV + đề + mode) nếu có
                ExamResult placeholder = resultRepo
                        .findByStudentIdAndExamIdAndMode(info.studentId(), examId, "submit")
                        .orElseGet(ExamResult::new);

                // Vẫn chặn TRÙNG mã SV trong CÙNG một lần upload
                if (batchId.equals(placeholder.getBatchId()))
                    throw new IllegalArgumentException(
                            "Trùng mã SV " + info.studentId() + " trong cùng lần upload");

                // Stream zip ra ĐĨA ngay (KHÔNG giữ bytes trong RAM) → tránh OOM khi nhiều bài
                Path zip = stageZip(file, examId, batchId, info.studentId());

                placeholder.setStudentId(info.studentId());
                placeholder.setStudentName(info.studentName());
                placeholder.setExamId(examId);
                placeholder.setBatchId(batchId);
                placeholder.setStatus(GradingStatus.QUEUED);
                placeholder.setScore(null);     // reset kết quả lần chấm trước
                placeholder.setDetails(null);
                placeholder.setErrorLog(null);
                placeholder.setDiagnosticCode(null);
                placeholder.setDiagnosticOrigin(null);
                placeholder.setDiagnosticStage(null);
                placeholder.setRequiresManualReview(false);
                placeholder.setGradingStartedAt(null);    // mốc của lượt chấm CŨ không được dính sang
                placeholder.setGradingFinishedAt(null);
                resultRepo.save(placeholder);

                pendingJobs.add(new GradingJob(
                        info.studentId(), info.studentName(),
                        batchId, examId, zip.toString(), null
                ));
                queued.add(info.studentId() + " — " + info.studentName());

            } catch (Exception e) {
                parseErrors.add(submissionLabel + ": " + e.getMessage());
                log.warn("Skip {}: {}", submissionLabel, e.getMessage());
            }
        }

        // totalFiles = SỐ BÀI THỰC SỰ ĐƯA VÀO CHẤM (không tính file bị loại) → batch hoàn tất đúng
        batch.setTotalFiles(pendingJobs.size());
        batchRepo.save(batch);

        // Lưu lại đúng testcase đã dùng cho đợt chấm này (audit / đối chiếu khi nghi ngờ chấm sai)
        if (!pendingJobs.isEmpty()) snapshotTestcase(examId, batchId, exam.getTestcasePath());

        // Đưa vào hàng đợi SAU khi đã chốt totalFiles (để worker không hoàn tất trước khi totalFiles đúng)
        pendingJobs.forEach(jobQueue::add);

        // Tất cả file đều bị loại → đóng batch ngay (không có job nào để kích hoạt checkBatchComplete)
        if (pendingJobs.isEmpty()) {
            batch.setStatus(BatchStatus.COMPLETED);
            batch.setCompletedAt(Instant.now());
            batchRepo.save(batch);
        }

        log.info("[{}] Enqueued {}/{}", batchId, pendingJobs.size(), files.size());
        return new BatchSubmitResponse(batchId, pendingJobs.size(), parseErrors);
    }

    /**
     * NẠP THÊM bài vào một phiên chấm ĐÃ CÓ — dùng khi giáo viên thu bài làm nhiều đợt.
     *
     * <p>Nhận cả phiên đã chấm xong: phiên được MỞ LẠI ({@code IN_PROGRESS}, xoá {@code completedAt})
     * để {@link #checkBatchComplete} còn đóng lại được lần nữa; nếu không, batch đứng nguyên
     * trạng thái cũ và màn hình tiến độ không bao giờ chạy tiếp.
     *
     * <p>Cũng gỡ cờ "đã dừng": phiên từng bị bấm Dừng mà vẫn giữ cờ thì mọi bài mới nạp vào sẽ bị
     * ghi CANCELLED ngay khi worker nhặt lên.
     */
    public BatchSubmitResponse addToBatch(List<MultipartFile> files, List<String> usernames,
                                          String batchId) throws Exception {
        if (usernames == null || usernames.size() != files.size())
            throw new IllegalArgumentException("Mỗi file .zip phải đi kèm đúng tên thư mục username.");
        GradingBatch batch = requireBatch(batchId);
        if (batch.getStatus() == BatchStatus.CANCELLED)
            throw new IllegalArgumentException("Phiên chấm đã bị hủy — hãy tạo phiên mới.");

        String examId = batch.getExamId();
        Exam exam = examRepo.findByExamId(examId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đề thi: " + examId));
        chanNeuChuaDongBo(examId);
        if (exam.getStatus() != ExamStatus.READY)
            throw new IllegalStateException("Đề thi chưa sẵn sàng để chấm: " + exam.getStatus());

        gradingService.clearCancelled(batchId);

        List<String> parseErrors = new ArrayList<>();
        List<GradingJob> pendingJobs = new ArrayList<>();
        java.util.Set<String> seenInThisUpload = new java.util.LinkedHashSet<>();

        for (int index = 0; index < files.size(); index++) {
            MultipartFile file = files.get(index);
            String filename = Objects.requireNonNull(file.getOriginalFilename());
            String username = usernames.get(index) == null ? "" : usernames.get(index).trim();
            String submissionLabel = username + "/" + filename;
            try {
                validateZip(file, filename);
                StudentInfo info = parseStudentInfo(username);
                if (!seenInThisUpload.add(info.studentId()))
                    throw new IllegalArgumentException(
                            "Trùng mã SV " + info.studentId() + " trong cùng lần upload");

                // Cùng SV nộp lại ở đợt sau = GHI ĐÈ kết quả cũ, giống hệt luồng chấm lại.
                ExamResult row = resultRepo
                        .findByStudentIdAndExamIdAndMode(info.studentId(), examId, "submit")
                        .orElseGet(ExamResult::new);
                Path zip = stageZip(file, examId, batchId, info.studentId());

                row.setStudentId(info.studentId());
                row.setStudentName(info.studentName());
                row.setExamId(examId);
                row.setBatchId(batchId);
                row.setStatus(GradingStatus.QUEUED);
                row.setScore(null);
                row.setDetails(null);
                row.setResultJson(null);
                row.setErrorLog(null);
                row.setDiagnosticCode(null);
                row.setDiagnosticOrigin(null);
                row.setDiagnosticStage(null);
                row.setRequiresManualReview(false);
                resultRepo.save(row);

                pendingJobs.add(new GradingJob(info.studentId(), info.studentName(),
                        batchId, examId, zip.toString(), null));
            } catch (Exception e) {
                parseErrors.add(submissionLabel + ": " + e.getMessage());
                log.warn("Skip {}: {}", submissionLabel, e.getMessage());
            }
        }

        if (!pendingJobs.isEmpty()) {
            batch.setTotalFiles((batch.getTotalFiles() == null ? 0 : batch.getTotalFiles()) + pendingJobs.size());
            batch.setStatus(BatchStatus.IN_PROGRESS);
            batch.setCompletedAt(null);
            batchRepo.save(batch);
            pendingJobs.forEach(jobQueue::add);
        }

        log.info("[{}] Nạp thêm {}/{} bài vào phiên đang có", batchId, pendingJobs.size(), files.size());
        return new BatchSubmitResponse(batchId, pendingJobs.size(), parseErrors);
    }

    // ── Xử lý 1 job (TUYỆT ĐỐI không ném exception ra ngoài → worker không bao giờ chết) ──
    private void processJob(GradingJob job) {
        boolean success = false;
        boolean manualReview = false;
        boolean cancelled = false;
        try {
            // Người dùng bấm Dừng khi job này đã rời hàng đợi nhưng chưa tới lượt chạy.
            if (gradingService.isCancelled(job.batchId())) {
                cancelled = true;
                markResultCancelled(job, "Phiên chấm đã bị dừng trước khi bài này được chấm.");
                return;
            }
            log.info("[{}] Chấm: {}", job.batchId(), job.studentId());
            updateStatus(job, GradingStatus.GRADING, null, null, null);

            Path zipPath = Path.of(job.zipPath());     // file đã staged trên đĩa
            if (!Files.exists(zipPath))
                throw new IllegalStateException("Mất file bài nộp: " + zipPath);
            recordSubmissionHash(job, zipPath);        // dấu vân tay bài nộp, ghi khi bằng chứng còn tươi

            Path tempDir = Files.createTempDirectory("grading_" + job.studentId() + "_");
            // Ép testcase (chấm lại đề cũ dùng snapshot); mặc định lấy testcase hiện tại của đề.
            String testcasePath = job.testcasePath() != null ? job.testcasePath()
                    : examRepo.findByExamId(job.examId()).map(Exam::getTestcasePath).orElse(null);
            // Ảnh bằng chứng nằm cạnh zip bài nộp — cùng vòng đời với batch (xoá batch là xoá theo).
            Path evidenceTarget = batchDir(job.examId(), job.batchId())
                    .resolve("_evidence").resolve(job.studentId());
            String resultJson = gradingService.gradeSubmission(
                    job.batchId(), job.studentId(), job.examId(), testcasePath, tempDir, zipPath,
                    evidenceTarget);

            float score = parseScore(resultJson);
            String fullJson = assembleResultJson(job, resultJson);   // JSON đầy đủ cho lịch sử/năng lực
            GradingDiagnosticException diagnostic = diagnoseGraderResult(resultJson, score);
            if (isSystemFault(diagnostic)) {
                manualReview = true;
                updateStatus(job, GradingStatus.MANUAL_REVIEW, null, resultJson, fullJson);
                updateDiagnostic(job, diagnostic);
                log.warn("[{}] MANUAL_REVIEW {} → {}", job.batchId(), job.studentId(),
                        diagnostic.teacherMessage());
            } else {
                updateStatus(job, GradingStatus.DONE, score, resultJson, fullJson);
                if (diagnostic != null) updateDiagnostic(job, diagnostic);
                examRepo.markHasResults(job.examId());   // chỉ kết quả tự động hợp lệ mới bật flag
                log.info("[{}] DONE {} → {} đ", job.batchId(), job.studentId(), score);
                success = true;
            }

        } catch (Exception e) {
            // Container bị GIẾT vì người dùng bấm Dừng → không có bằng chứng nào về bài nộp,
            // tuyệt đối không được ghi thành 0 điểm của sinh viên (nhánh dưới sẽ làm đúng thế).
            if (gradingService.isCancelled(job.batchId())) {
                cancelled = true;
                markResultCancelled(job, "Đã dừng khi bài đang được chấm.");
            } else {
                GradingDiagnosticException diagnostic = diagnoseException(e);
                // MỘT TRỤC DUY NHẤT: lỗi quy được cho bài sinh viên là KẾT QUẢ (0 điểm), không
                // phải sự cố. Trước đây chúng ghi ERROR / score null nên nằm lẫn với Docker chết
                // trên màn hình người chấm, mà lại KHÔNG có điểm — rơi vào khoảng trống.
                boolean systemFault = isSystemFault(diagnostic);
                manualReview = systemFault;
                GradingStatus terminal = systemFault ? GradingStatus.MANUAL_REVIEW : GradingStatus.DONE;
                log.error("[{}] {} {} → {}", job.batchId(), terminal, job.studentId(), diagnostic.teacherMessage());
                try {
                    updateStatus(job, terminal, systemFault ? null : 0f, null, null);
                    if (!systemFault) {
                        examRepo.markHasResults(job.examId());   // 0 điểm vẫn là kết quả hợp lệ
                        success = true;
                    }
                }
                catch (Exception ex) {
                    log.warn("[{}] Không ghi được {} cho {}: {}", job.batchId(), terminal,
                            job.studentId(), ex.getMessage());
                }
                updateDiagnostic(job, diagnostic);
            }
        } finally {
            // Bookkeeping luôn chạy & có bọc lỗi → 1 lỗi ghi DB không làm kẹt batch
            try { if (!saveSubmissions) deleteQuietly(Path.of(job.zipPath())); } catch (Exception ignored) {}
            // Bài bị dừng KHÔNG tính vào done/error — nó chưa từng có kết quả.
            if (!cancelled) {
                try { batchRepo.incrementCounts(job.batchId(), success ? 1 : 0,
                        success || manualReview ? 0 : 1); }
                catch (Exception ex) { log.warn("[{}] incrementCounts lỗi: {}", job.batchId(), ex.getMessage()); }
            }
            try { checkBatchComplete(job.batchId()); }
            catch (Exception ex) { log.warn("[{}] checkBatchComplete lỗi: {}", job.batchId(), ex.getMessage()); }
        }
    }

    /**
     * Ghi SHA-256 của zip bài nộp vào bản ghi kết quả.
     *
     * <p>Bọc lỗi và nuốt: đây là dữ liệu ĐỐI CHỨNG, không phải điều kiện để chấm — đọc file hỏng
     * thì mất một dòng trong log truy vết, không được phép làm hỏng cả lượt chấm.
     */
    private void recordSubmissionHash(GradingJob job, Path zipPath) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(zipPath)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            resultRepo.findByStudentIdAndBatchId(job.studentId(), job.batchId())
                    .ifPresent(r -> { r.setSubmissionHash(hex.toString()); resultRepo.save(r); });
        } catch (Exception e) {
            log.warn("[{}] Không tính được hash bài nộp của {}: {}",
                    job.batchId(), job.studentId(), e.getMessage());
        }
    }

    /** Ghi bài về CANCELLED; bọc lỗi vì đây là đường thoát, không được ném ra ngoài worker. */
    private void markResultCancelled(GradingJob job, String reason) {
        try {
            resultRepo.findByStudentIdAndBatchId(job.studentId(), job.batchId()).ifPresent(r -> {
                r.setStatus(GradingStatus.CANCELLED);
                r.setScore(null);
                r.setErrorLog(reason);
                resultRepo.save(r);
            });
            log.info("[{}] CANCELLED {} — {}", job.batchId(), job.studentId(), reason);
        } catch (Exception e) {
            log.warn("[{}] Không ghi được CANCELLED cho {}: {}",
                    job.batchId(), job.studentId(), e.getMessage());
        }
    }

    private void updateStatus(GradingJob job, GradingStatus status, Float score, String details, String fullJson) {
        resultRepo.findByStudentIdAndBatchId(job.studentId(), job.batchId()).ifPresent(r -> {
            r.setStatus(status);
            // Mốc thời gian ghi ngay tại chỗ đổi trạng thái — đây là nơi DUY NHẤT biết lượt chấm
            // thật sự bắt đầu và kết thúc lúc nào (updatedAt không dùng được: chấm tay cũng đổi nó).
            if (status == GradingStatus.GRADING) {
                r.setGradingStartedAt(Instant.now());
                r.setGradingFinishedAt(null);        // chấm lại: xoá mốc kết thúc của lượt trước
            } else if (status == GradingStatus.DONE
                    || status == GradingStatus.ERROR
                    || status == GradingStatus.MANUAL_REVIEW) {
                r.setGradingFinishedAt(Instant.now());
            }
            if (score    != null) r.setScore(score);
            if (details  != null) r.setDetails(details);
            if (fullJson != null) r.setResultJson(fullJson);
            resultRepo.save(r);
        });
    }

    /**
     * TRỤC QUYẾT ĐỊNH DUY NHẤT của cả lớp này: sự cố có thuộc về HỆ THỐNG chấm không?
     *
     * <p>Chỉ đọc {@code origin}, cố ý KHÔNG đọc {@code manualReview()}. Hai khoá đó từng nói hai
     * chuyện lệch nhau (vd {@code EXTERNAL_PACKAGE} là lỗi bài nhưng lại xin chấm tay), và mỗi
     * nơi đọc một khoá là lý do màn hình phải tự suy lại kết luận. Nay cờ {@code manualReview}
     * chỉ còn là dữ liệu lưu kèm, không điều khiển trạng thái nữa.
     *
     * <p>{@code null} = không có sự cố nào ⇒ không phải lỗi hệ thống.
     */
    private boolean isSystemFault(GradingDiagnosticException diagnostic) {
        return diagnostic != null
                && diagnostic.origin() != GradingDiagnosticException.Origin.STUDENT;
    }

    /** Phân loại exception cấp lượt chấm; không còn biến mọi lỗi thành ERROR 0 điểm giống nhau. */
    private GradingDiagnosticException diagnoseException(Exception error) {
        if (error instanceof GradingDiagnosticException diagnostic) return diagnostic;
        String message = error.getMessage() == null || error.getMessage().isBlank()
                ? error.toString() : error.getMessage();
        String low = message.toLowerCase(java.util.Locale.ROOT);
        if (low.contains("không tìm thấy thư mục lib") || low.contains("không có file .dart")) {
            return new GradingDiagnosticException("SUBMISSION_STRUCTURE_INVALID",
                    GradingDiagnosticException.Origin.STUDENT, "SUBMISSION_PREFLIGHT", false,
                    message, error);
        }
        // Probe 0/0 đã moi được lỗi biên dịch trong lib/ (xem GradingService#diagnoseZeroTests).
        // Không bắt câu này thì nó rơi xuống UNCLASSIFIED và báo động như một sự cố hệ thống,
        // trong khi bằng chứng đã chỉ thẳng vào bài nộp.
        if (low.contains("không biên dịch được với đề")) {
            return new GradingDiagnosticException("STUDENT_COMPILE_ERROR",
                    GradingDiagnosticException.Origin.STUDENT, "SOURCE_COMPILE", false,
                    message, error);
        }
        if (low.contains("mất file bài nộp")) {
            return new GradingDiagnosticException("SUBMISSION_FILE_LOST",
                    GradingDiagnosticException.Origin.ENVIRONMENT, "SUBMISSION_STAGING", true,
                    message, error);
        }
        if (low.contains("test/exam_test.dart") || low.contains("test/grader.dart")
                || low.contains("testcase chưa có") || low.contains("không tìm thấy skills_matrix")) {
            return new GradingDiagnosticException("TESTCASE_RUNNER_ERROR",
                    GradingDiagnosticException.Origin.TESTCASE, "TESTCASE_SETUP", true,
                    message, error);
        }
        if (low.contains("docker") || low.contains("daemon") || low.contains("no space left")
                || low.contains("cannot allocate memory")) {
            return new GradingDiagnosticException("GRADING_ENVIRONMENT_ERROR",
                    GradingDiagnosticException.Origin.ENVIRONMENT, "CONTAINER_RUNTIME", true,
                    message, error);
        }
        if (low.contains("timeout") || low.contains("quá thời gian")) {
            return new GradingDiagnosticException("GRADING_TIMEOUT_UNDETERMINED",
                    GradingDiagnosticException.Origin.UNDETERMINED, "GRADER_TOTAL", true,
                    message + " Chưa đủ bằng chứng để quy lỗi cho bài sinh viên.", error);
        }
        return new GradingDiagnosticException("UNCLASSIFIED_GRADING_ERROR",
                GradingDiagnosticException.Origin.UNDETERMINED, "GRADING_PIPELINE", true,
                message, error);
    }

    /**
     * Một bài chấm xong nhưng 0 điểm vẫn phải có lý do ở cấp lượt chấm. Timeout/runner lỗi
     * chuyển sang chấm tay; bài đã thực thi đủ nhưng không đạt yêu cầu vẫn là kết quả 0 hợp lệ.
     */
    private GradingDiagnosticException diagnoseGraderResult(String graderJson, float score) {
        if (graderJson == null || graderJson.isBlank()) return null;
        try {
            JsonNode root = mapper.readTree(graderJson);
            JsonNode grading = root.path("grading_result");
            String declaredCode = grading.path("diagnostic_code").asText("");
            String declaredOrigin = grading.path("diagnostic_origin").asText("");
            String declaredStage = grading.path("diagnostic_stage").asText("");
            boolean declaredReview = grading.path("requires_manual_review").asBoolean(false);
            String runnerError = grading.path("runner_error").asText("");
            if (!declaredCode.isBlank()) {
                GradingDiagnosticException.Origin origin;
                try {
                    origin = GradingDiagnosticException.Origin.valueOf(declaredOrigin);
                } catch (Exception ignored) {
                    origin = GradingDiagnosticException.Origin.UNDETERMINED;
                }
                String declaredMessage = grading.path("diagnostic_message")
                        .asText("Bộ chấm đã phát hiện một lỗi có cấu trúc.");
                // Với lỗi compile, câu "xem runner_error" không hữu ích trên bảng kết quả vì
                // giáo viên không nhìn thấy trường JSON đó. Đưa bằng chứng đầu tiên lên diagnostic
                // nhưng vẫn giới hạn độ dài; lỗi package ngoài đã có thông điệp riêng ở preflight.
                if (declaredCode.contains("COMPILE") && !runnerError.isBlank()) {
                    declaredMessage += " Lỗi đầu tiên: " + TestErrorClassifier.shorten(runnerError, 700);
                }
                return new GradingDiagnosticException(declaredCode, origin,
                        declaredStage.isBlank() ? "TESTCASE_EXECUTION" : declaredStage,
                        declaredReview,
                        declaredMessage);
            }
            String low = runnerError.toLowerCase(java.util.Locale.ROOT);
            if (low.contains("process_timeout") || low.contains("process timeout")) {
                return new GradingDiagnosticException("TEST_PROCESS_TIMEOUT",
                        GradingDiagnosticException.Origin.UNDETERMINED, "TESTCASE_EXECUTION", true,
                        "Một tiến trình testcase bị timeout. Cần đối chiếu bài mẫu chuẩn và log trước khi quy lỗi cho sinh viên: "
                                + TestErrorClassifier.shorten(runnerError, 1_000));
            }
            if (low.contains("grader_total_timeout")) {
                return new GradingDiagnosticException("GRADER_TOTAL_TIMEOUT",
                        GradingDiagnosticException.Origin.ENVIRONMENT, "GRADER_TOTAL", true,
                        "Hết ngân sách thời gian toàn bộ bộ chấm; cần kiểm tra tải máy hoặc chia lại testcase.");
            }
            if (low.contains("test/exam_test.dart") || low.contains("test/grader.dart")
                    || low.contains("chưa có common runner")) {
                return new GradingDiagnosticException("TESTCASE_RUNNER_ERROR",
                        GradingDiagnosticException.Origin.TESTCASE, "TESTCASE_EXECUTION", true,
                        "Bộ testcase không chạy đúng: " + TestErrorClassifier.shorten(runnerError, 1_000));
            }

            boolean contractViolation = false;
            boolean sourcePolicyViolation = false;
            String contractEvidence = "";
            String sourcePolicyEvidence = "";
            String firstFailureName = "";
            String firstFailureActual = "";
            String firstFailureKind = "";
            int executed = 0;
            int failed = 0;
            JsonNode cases = root.path("test_cases");
            if (cases.isArray()) {
                for (JsonNode tc : cases) {
                    String code = tc.path("error_code").asText("");
                    String actualRaw = tc.path("actual").asText("");
                    String actual = actualRaw.toLowerCase(java.util.Locale.ROOT);
                    String observationKind = tc.path("observation").path("kind").asText("");
                    String status = tc.path("status").asText("");
                    if (tc.path("executed").asBoolean(!"not_run".equals(status))) executed++;
                    if ("failed".equals(status)) {
                        failed++;
                        if (firstFailureName.isBlank()) {
                            firstFailureName = tc.path("name").asText(tc.path("test_id").asText("testcase"));
                            firstFailureActual = actualRaw;
                            firstFailureKind = tc.path("observation").path("kind").asText("");
                        }
                    }
                    if ("SOURCE_POLICY_VIOLATION".equals(code)
                            || "SOURCE_POLICY_VIOLATION".equals(observationKind)
                            || actual.contains("forbidden token")
                            || actual.contains("token b\u1ecb c\u1ea5m")) {
                        sourcePolicyViolation = true;
                        sourcePolicyEvidence = actualRaw;
                    } else if ("CONTRACT_VIOLATION".equals(code)
                            || "SOURCE_CONTRACT_VIOLATION".equals(observationKind)
                            || actual.contains("source contract")
                            || actual.contains("source token")) {
                        contractViolation = true;
                        contractEvidence = actualRaw;
                    }
                }
            }
            if (sourcePolicyViolation) {
                return new GradingDiagnosticException("SOURCE_POLICY_VIOLATION",
                        GradingDiagnosticException.Origin.STUDENT, "SOURCE_POLICY", false,
                        "Bài chứa class/token mà contract của đề đã cấm. Chi tiết: "
                                + TestErrorClassifier.shorten(sourcePolicyEvidence, 800));
            }
            if (contractViolation) {
                return new GradingDiagnosticException("CONTRACT_VIOLATION",
                        GradingDiagnosticException.Origin.STUDENT, "SOURCE_CONTRACT", false,
                        "Bài không tuân theo public contract về file/class/symbol. Chi tiết: "
                                + TestErrorClassifier.shorten(contractEvidence, 800));
            }
            if (score > 0f) return null;
            if ("BOOT_FAILED".equalsIgnoreCase(firstFailureKind)) {
                return diagnoseStudentBootFailure(firstFailureActual);
            }
            String evidence = firstFailureName.isBlank() ? "không có assertion chi tiết"
                    : firstFailureName + ": " + TestErrorClassifier.shorten(firstFailureActual, 800);
            return new GradingDiagnosticException("REQUIREMENTS_NOT_MET",
                    GradingDiagnosticException.Origin.STUDENT, "TESTCASE_EXECUTION", false,
                    "Đã thực thi " + executed + " kịch bản, có " + failed
                            + " kịch bản thất bại và không đạt điểm. Lỗi đầu tiên: " + evidence);
        } catch (Exception ignored) {
            return new GradingDiagnosticException("ZERO_SCORE_UNCLASSIFIED",
                    GradingDiagnosticException.Origin.UNDETERMINED, "RESULT_ASSEMBLY", true,
                    "Bài nhận 0 điểm nhưng kết quả grader không đủ dữ liệu để xác định nguyên nhân.");
        }
    }

    /**
     * `_boot()` có thể thất bại vì nhiều nguyên nhân rất khác nhau. Không được gộp
     * RenderFlex overflow, null/type/range error và lỗi khởi động chưa xác định vào
     * cùng nhãn "ứng dụng bị crash": giáo viên sẽ không biết lỗi nào cần sửa trong bài.
     */
    private GradingDiagnosticException diagnoseStudentBootFailure(String rawFailure) {
        TestErrorClassifier.Result classified = errorClassifier.classify(rawFailure);
        String code = classified.code();
        String evidence = TestErrorClassifier.shorten(rawFailure, 800);
        String message;
        String stage;

        switch (code) {
            case "LAYOUT_OVERFLOW" -> {
                stage = "UI_LAYOUT";
                message = "Ứng dụng đã dựng giao diện nhưng bị tràn RenderFlex khi khởi động. "
                        + "Đây là lỗi layout của bài sinh viên, không phải lỗi testcase. Chi tiết: " + evidence;
            }
            case "BUILD_ERROR" -> {
                stage = "APP_BUILD";
                message = "Ứng dụng phát sinh lỗi trong lúc dựng widget đầu tiên. Chi tiết: " + evidence;
            }
            case "NULL_ERROR", "TYPE_ERROR", "RANGE_ERROR", "FORMAT_ERROR", "STATE_ERROR",
                    "NO_SUCH_METHOD", "EXCEPTION_THROWN" -> {
                stage = "APP_BOOT";
                message = "Ứng dụng phát sinh " + code + " trong lúc khởi động. Chi tiết: " + evidence;
            }
            default -> {
                code = "STUDENT_APP_BOOT_ERROR";
                stage = "APP_BOOT";
                message = "Ứng dụng sinh viên phát sinh lỗi khi khởi động. Chi tiết đầu tiên: " + evidence;
            }
        }
        return new GradingDiagnosticException(code,
                GradingDiagnosticException.Origin.STUDENT, stage, false, message);
    }

    // ── Ghép JSON đầy đủ: student + exam + kết quả chấm ─
    private String assembleResultJson(GradingJob job, String graderJson) {
        try {
            JsonNode g = mapper.readTree(graderJson);
            Exam exam = examRepo.findByExamId(job.examId()).orElse(null);
            String title = (exam != null && exam.getExamName() != null) ? exam.getExamName() : job.examId();

            Map<String, Object> root = new LinkedHashMap<>();
            // Đặt ĐẦU TIÊN: bên đọc biết ngay đang xử lý hợp đồng bản nào. Dữ liệu chấm trước
            // P4 không có khoá này — vắng mặt chính là dấu hiệu "bản 1", đừng bơm ngược vào.
            root.put("schema_version", SCHEMA_VERSION);

            Map<String, Object> student = new LinkedHashMap<>();
            student.put("id", job.studentId());
            student.put("name", job.studentName() != null ? job.studentName() : "");
            root.put("student", student);

            Map<String, Object> examNode = new LinkedHashMap<>();
            examNode.put("code", job.examId());
            examNode.put("title", title);
            examNode.put("total_score", 10);
            root.put("exam", examNode);

            // grading_result: ưu tiên từ grader (mới); fallback dựng từ field cũ
            Map<String, Object> gradingResult;
            if (g.has("grading_result")) {
                gradingResult = mapper.convertValue(g.get("grading_result"),
                        new TypeReference<LinkedHashMap<String, Object>>() {});
            } else {
                int pass  = g.path("soTestPass").asInt(0);
                int total = g.path("tongSoTest").asInt(0);
                gradingResult = new LinkedHashMap<>();
                gradingResult.put("score", g.path("diem").asDouble(0));
                gradingResult.put("passed_tests", pass);
                gradingResult.put("failed_tests", total - pass);
                gradingResult.put("total_tests", total);
            }
            root.put("grading_result", gradingResult);

            // test_cases: ưu tiên từ grader (mới, có skill/expected/actual); fallback từ chiTiet
            List<Map<String, Object>> testCases = new ArrayList<>();
            if (g.has("test_cases")) {
                testCases = toListOfMap(g.get("test_cases"));
            } else if (g.has("chiTiet")) {
                for (JsonNode c : g.get("chiTiet")) {
                    Map<String, Object> tc = new LinkedHashMap<>();
                    tc.put("test_id", c.path("name").asText());
                    tc.put("name", c.path("name").asText());
                    tc.put("status", "PASS".equals(c.path("status").asText()) ? "passed" : "failed");
                    testCases.add(tc);
                }
            }
            // Bổ sung skill_code/difficulty từ skills_matrix.json của đề (nguồn sự thật) cho mỗi testcase
            Map<String, Object> matrix = loadSkillsMatrix(exam);
            enrichTestCases(testCases, matrix);


            // Chuẩn hoá lỗi từng testcase FAIL: log thô của flutter test (Expected/Actual + stack trace
            // dài như "log backend") → actual/error gọn, sạch để FE hiển thị đẹp.
            // error.message là chẩn đoán kỹ thuật; student_safe_summary là hướng dẫn riêng cho SV.
            sanitizeTestCaseErrors(testCases);

            // Dẫn xuất vài field mà engine đời cũ chưa gửi đủ; KHÔNG bơm thêm nhãn phân loại.
            fillDerivedFields(testCases);

            if (!testCases.isEmpty()) root.put("test_cases", testCases);
            gradingResult.putIfAbsent("not_run_tests", countStatus(testCases, "not_run"));

            if (g.has("analyze_result"))
                root.put("analyze_result", mapper.convertValue(g.get("analyze_result"), Object.class));

            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Không dựng được result_json cho {}: {}", job.studentId(), e.getMessage());
            return null;
        }
    }

    /** Chuyển JsonNode mảng test_cases → List&lt;Map&gt; để enrich & tính năng lực. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> toListOfMap(JsonNode arr) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode n : arr) {
            try { out.add(mapper.convertValue(n, Map.class)); } catch (Exception ignored) {}
        }
        return out;
    }

    /** Đọc skills_matrix.json của đề; null khi đề chưa có file (bài cũ, đề đã xoá testcase...). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadSkillsMatrix(Exam exam) {
        if (exam == null || exam.getTestcasePath() == null) return null;
        try {
            Path f = Path.of(exam.getTestcasePath()).resolve("skills_matrix.json");
            if (!Files.exists(f)) return null;
            return mapper.convertValue(
                    mapper.readTree(Files.readString(f, java.nio.charset.StandardCharsets.UTF_8)), Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Dẫn xuất vài field mà engine đời cũ chưa gửi đủ, và DỌN các field không ai đọc.
     *
     * <p>Trước đây chỗ này còn bơm thêm 8 nhãn phân loại (`rubric`, `rubric_label`, `layer`,
     * `chapter`, `category`, `category_label`, `skill_name`, `difficulty_label`) cùng
     * `blocked_by` luôn null, chỉ để thoả một hợp đồng ngoài. Hợp đồng đó đã bỏ; giữ lại
     * chỉ làm result_json phình ra mà không ai đọc.
     *
     * <p>Ở đây chỉ SUY field từ dữ liệu đã có, không bịa giá trị mới.
     */
    private void fillDerivedFields(List<Map<String, Object>> tcs) {
        for (Map<String, Object> tc : tcs) {
            String status = String.valueOf(tc.getOrDefault("status", "")).toLowerCase();
            boolean notRun = "not_run".equals(status);
            if (!(tc.get("executed") instanceof Boolean)) tc.put("executed", !notRun);
            // Mã lỗi PHẲNG cho máy đọc. Rút `error.code` của đề legacy ra TRƯỚC khi bỏ object đó.
            if (tc.get("error_code") == null) {
                Object error = tc.get("error");
                Object code = error instanceof Map<?, ?> m ? m.get("code") : null;
                if (code != null) tc.put("error_code", code);
            }
            attachCaseDiagnostic(tc, status);
            tc.remove("error");
            tc.remove("student_safe_summary");
            // `score` là field DẪN XUẤT: hệ thống không có chấm điểm một phần, đạt thì trọn
            // max_score, không đạt thì 0. Giữ hai số cho cùng một thông tin là mời gọi lệch nhau.
            tc.remove("score");
            // Nhãn phân loại của hợp đồng cũ — không nơi nào đọc.
            for (String key : DEAD_KEYS) tc.remove(key);
        }
    }

    /** Field của hợp đồng cũ, đã xác nhận không nơi nào trong hệ thống đọc tới. */
    private static final List<String> DEAD_KEYS = List.of(
            "blocked_by", "rubric", "rubric_label", "layer",
            "chapter", "category", "category_label", "skill_name", "difficulty_label",
            // `expected`/`expect`: câu đặc tả giống hệt nhau ở mọi bài nộp, không nói gì về
            // BÀI NÀY. Grader của đề legacy vẫn gửi, nên phải dọn ở đây.
            "expected", "expect", "skill");

    private int countStatus(List<Map<String, Object>> tcs, String status) {
        int n = 0;
        for (Map<String, Object> tc : tcs) {
            if (status.equals(String.valueOf(tc.get("status")).toLowerCase())) n++;
        }
        return n;
    }

    /**
     * Bổ sung skill_code / difficulty cho mỗi testcase, đọc từ skills_matrix.json của đề.
     * Chỉ điền khi testcase CHƯA có (không ghi đè dữ liệu grader).
     *
     * <p>2026-08-22 bỏ hẳn phần dựng `expected`: field đó đã gỡ khỏi result.json. Tên tiêu chí
     * do giáo viên viết đã mô tả đủ, và một câu đặc tả thứ hai không nói thêm gì về BÀI NÀY —
     * nó giống hệt nhau ở mọi bài nộp.
     */
    private void enrichTestCases(List<Map<String, Object>> tcs, Map<String, Object> matrix) {
        if (tcs.isEmpty() || matrix == null) return;
        for (Map<String, Object> tc : tcs) {
            Object meta = matrix.get(String.valueOf(tc.get("test_id")));
            if (meta instanceof Map<?, ?> m) {
                putIfAbsent(tc, "skill_code", m.get("skill_code"));
                putIfAbsent(tc, "difficulty", m.get("difficulty"));
            }
        }
    }

    private void putIfAbsent(Map<String, Object> tc, String key, Object val) {
        if (val == null) return;
        Object cur = tc.get(key);
        if (cur == null || String.valueOf(cur).isBlank()) tc.put(key, val);
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  CHUẨN HOÁ LỖI TESTCASE: log thô flutter test → `actual` + `error_code`
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * Với mỗi testcase FAIL, biến `actual` (log THÔ của flutter test — Expected/Actual rồi stack
     * trace dài, "EXCEPTION CAUGHT BY...", cây widget → trông như "log backend") thành:
     *   - `actual`     = ĐIỀU QUAN SÁT ĐƯỢC, tiếng Việt (P5 — ưu tiên kênh quan sát có cấu trúc).
     *   - `error_code` = loại lỗi cho máy đọc, **chỉ mã, không kèm câu diễn giải**.
     *
     * <p><b>P2b đã gỡ `error{code,message}` và `student_safe_summary`.</b> Lý do gỡ, ghi lại kẻo
     * có người thấy tiện mà thêm lại: cả hai đều là câu <b>TRA BẢNG THEO MÃ LỖI</b>, không phải
     * điều quan sát được — mọi exception đều nhận cùng một câu khuyên *"kiểm tra null/ép kiểu/
     * parse"* dù lỗi thật là timeout hay thiếu widget. Bên đọc chuyển tiếp nguyên văn cho sinh
     * viên ⇒ thông tin SAI. Từ P3 `error.message` còn lộ cả semantic key nội bộ.
     *
     * <p>Thay thế: `expected` (yêu cầu của đề) + `actual` (điều quan sát được) đã đủ, và
     * `error_code` giữ lại phần duy nhất có giá trị — mã cho máy gom nhóm.
     */
    private void sanitizeTestCaseErrors(List<Map<String, Object>> tcs) {
        for (Map<String, Object> tc : tcs) {
            String status = String.valueOf(tc.getOrDefault("status", "")).toLowerCase();
            // not_run cũng cần diễn đạt lý do chưa chạy, nhưng KHÔNG phân loại lỗi.
            if (!status.contains("fail")) {
                renderObservation(tc);
                continue;
            }
            Object rawObj = tc.get("actual");
            if (rawObj == null || String.valueOf(rawObj).isBlank()) rawObj = tc.get("error_log");
            String raw = rawObj == null ? "" : String.valueOf(rawObj);
            // TÔN TRỌNG `actual_source`. Có cờ này nghĩa là tầng chấm ĐÃ viết sẵn câu tiếng
            // Việt mô tả đúng thứ nó quan sát được. Bộ bóc log dưới đây chỉ hiểu log thô của
            // flutter test; đem nó chạy lên câu đã sạch thì bóc không ra gì, rồi ghi đè bằng
            // "Không thu được kết quả quan sát" — xoá mất đúng dòng mà phúc khảo cần đọc.
            boolean daCoNguon = tc.get("actual_source") != null
                    && !String.valueOf(tc.get("actual_source")).isBlank();
            if (!raw.isBlank() && !daCoNguon) applyStructuredError(tc, raw);
            // SAU CÙNG: quan sát có cấu trúc ghi đè `actual` do bóc log. Thứ tự này bắt buộc —
            // classifier cần đọc LOG THÔ để ra `error_code` đúng, nên không được thay `actual`
            // trước nó; còn câu cho sinh viên đọc thì quan sát luôn tốt hơn bản đoán từ chữ.
            renderObservation(tc);
        }
    }

    /**
     * P5 — `actual` dựng từ KÊNH QUAN SÁT CÓ CẤU TRÚC của engine, thay cho việc bóc log tiếng Anh.
     *
     * <p>Chạy SAU {@link #applyStructuredError} vì classifier cần đọc LOG THÔ ở `actual` để ra
     * `error_code` đúng. Nhưng câu cho SINH VIÊN đọc thì quan sát luôn thắng: runner tự khai
     * *"tôi kiểm gì, tôi thấy gì"*, còn bóc log là đoán ngược từ chữ tiếng Anh.
     *
     * <p>Đánh dấu `actual_source` để đo được còn bao nhiêu runner chưa chuyển sang kênh này.
     */
    private void renderObservation(Map<String, Object> tc) {
        Object raw = tc.get("observation");
        if (!(raw instanceof Map<?, ?> observation)) return;
        // A1 — `error_code` cũng suy từ quan sát, GHI ĐÈ giá trị classifier vừa bóc từ log. Cùng lý
        // do như `actual`: runner tự khai điều nó khẳng định, còn bóc log là đoán ngược từ chữ.
        // null (kind lạ, hoặc NOT_RUN_*) thì GIỮ giá trị cũ — mã sai tệ hơn không có mã.
        String code = TestObservationRenderer.errorCodeOf(observation);
        if (code != null) tc.put("error_code", code);

        String rendered = TestObservationRenderer.render(
                String.valueOf(tc.getOrDefault("name", "")), observation);
        if (rendered == null || rendered.isBlank()) return;
        tc.put("actual", rendered);
        tc.put("actual_source", "observation");
    }

    /**
     * Phân loại log thô → `error_code` + `actual` chống rỗng.
     *
     * <p>Classifier vẫn còn việc sau P5: nó là nguồn `error_code` duy nhất cho ca **không có
     * quan sát** (exception runtime giữa runner, chưa đi qua chỗ bọc assert), và ở đó nó giữ được
     * độ mịn mà `observation.kind` không mang được (`NULL_ERROR`, `TYPE_ERROR`, `TIMEOUT`…).
     *
     * <p>Chỉ lấy `code`; `message` bị bỏ ở P2b vì nó là câu tra bảng, không phải quan sát.
     */
    private void applyStructuredError(Map<String, Object> tc, String raw) {
        TestErrorClassifier.Result res = errorClassifier.classify(raw);
        String actual = res.actual();
        if (actual != null && !actual.isBlank()) tc.put("actual", actual);
        // Không tách được gì từ log thì nói đúng chừng đó. Câu cũ ("dừng do exception") NÓI SAI
        // nguyên nhân với bài không biên dịch được — ở đó chẳng có exception nào.
        else tc.put("actual", "Không thu được kết quả quan sát cho testcase này");
        tc.put("error_code", res.code());
    }

    /** Giới hạn log dù cột hiện tại là LONGTEXT; bản DB cũ TINYTEXT vẫn được bảo vệ ở fallback. */
    private static final int MAX_ERROR_LOG = 60_000;
    private static final int LEGACY_ERROR_LOG_LIMIT = 240;

    private void updateErrorLog(GradingJob job, String msg) {
        try {
            String text = msg == null ? "" : msg;
            if (text.length() > MAX_ERROR_LOG)
                text = text.substring(0, MAX_ERROR_LOG) + "\n…(đã cắt bớt log lỗi)";
            final String finalText = text;
            resultRepo.findByStudentIdAndBatchId(job.studentId(), job.batchId())
                    .ifPresent(r -> { r.setErrorLog(finalText); resultRepo.save(r); });
        } catch (Exception e) {
            // Tuyệt đối KHÔNG để việc ghi log lỗi (vd cột error_log quá nhỏ) làm chết worker
            log.warn("[{}] Không ghi được error_log cho {}: {}", job.batchId(), job.studentId(), e.getMessage());
        }
    }

    private void updateDiagnostic(GradingJob job, GradingDiagnosticException diagnostic) {
        try {
            String text = diagnostic.teacherMessage();
            // Diagnostic cấp lượt chấm chỉ cần thông điệp ngắn; bằng chứng đầy đủ đã nằm trong
            // details/result_json. Giới hạn này còn giúp máy chưa ALTER được cột TINYTEXT vẫn lưu
            // được code/origin/stage thay vì rollback cả lần cập nhật.
            if (text.length() > LEGACY_ERROR_LOG_LIMIT)
                text = text.substring(0, LEGACY_ERROR_LOG_LIMIT) + "…";
            final String finalText = text;
            resultRepo.findByStudentIdAndBatchId(job.studentId(), job.batchId()).ifPresent(r -> {
                r.setDiagnosticCode(diagnostic.code());
                r.setDiagnosticOrigin(diagnostic.origin().name());
                r.setDiagnosticStage(diagnostic.stage());
                r.setRequiresManualReview(diagnostic.manualReview());
                r.setErrorLog(finalText);
                resultRepo.save(r);
            });
        } catch (Exception e) {
            log.warn("[{}] Không ghi được diagnostic cho {}: {}",
                    job.batchId(), job.studentId(), e.getMessage());
        }
    }

    /**
     * Gắn nguồn và giai đoạn cho từng testcase. Đây là thông tin dành cho giáo viên;
     * không dùng nó để suy đoán nguyên nhân sâu trong code sinh viên.
     */
    private void attachCaseDiagnostic(Map<String, Object> tc, String status) {
        if ("passed".equals(status)) {
            // Dòng đạt không có lỗi để mô tả: GỠ khoá thay vì đặt null. Đặt null nghĩa là
            // "có chỗ cho thông tin này nhưng chưa biết", trong khi sự thật là "không có".
            tc.remove("error_origin");
            tc.remove("error_stage");
            tc.remove("observation");
            tc.remove("violations");
            tc.remove("error_code");
            tc.putIfAbsent("requires_manual_review", false);
            return;
        }

        String code = String.valueOf(tc.getOrDefault("error_code", ""));
        String kind = "";
        Object rawObservation = tc.get("observation");
        if (rawObservation instanceof Map<?, ?> observation) {
            Object rawKind = observation.get("kind");
            kind = rawKind == null ? "" : String.valueOf(rawKind);
        }

        String origin = "STUDENT";
        String stage = "TESTCASE_EXECUTION";
        boolean review = false;
        if ("TIMEOUT".equals(code) || "PROCESS_TIMEOUT".equalsIgnoreCase(kind)) {
            origin = "UNDETERMINED";
            stage = "TESTCASE_EXECUTION";
            review = true;
        } else if ("NOT_RUN_SUITE".equalsIgnoreCase(kind)) {
            origin = "UNDETERMINED";
            stage = "SUITE_STARTUP";
            review = true;
        } else if ("NOT_RUN_BOOT".equalsIgnoreCase(kind) || "EXCEPTION_THROWN".equals(code)) {
            stage = "APP_BOOT_OR_RUNTIME";
        } else if ("CONTRACT_VIOLATION".equals(code) || "COMPILE_ERROR".equals(code)) {
            stage = "SOURCE_CONTRACT_OR_COMPILE";
        }
        tc.putIfAbsent("error_origin", origin);
        tc.putIfAbsent("error_stage", stage);
        tc.putIfAbsent("requires_manual_review", review);
    }

    private void checkBatchComplete(String batchId) {
        batchRepo.findByBatchId(batchId).ifPresent(b -> {
            // Đếm TRẠNG THÁI THẬT từ exam_results → tự lành nếu bộ đếm trên batch bị lệch (crash
            // giữa lúc lưu status và incrementCounts) → batch không còn kẹt IN_PROGRESS vĩnh viễn.
            long done    = resultRepo.countByBatchIdAndStatus(batchId, GradingStatus.DONE);
            long error   = resultRepo.countByBatchIdAndStatus(batchId, GradingStatus.ERROR);
            long review  = resultRepo.countByBatchIdAndStatus(batchId, GradingStatus.MANUAL_REVIEW);
            long pending = resultRepo.countByBatchIdAndStatus(batchId, GradingStatus.QUEUED)
                         + resultRepo.countByBatchIdAndStatus(batchId, GradingStatus.GRADING);
            b.setDoneCount((int) done);      // đồng bộ lại bộ đếm hiển thị (thông báo) theo số THẬT
            b.setErrorCount((int) error);
            // Hết bài chờ/đang chấm ⇒ không worker nào còn cần đọc cờ dừng nữa (bài CANCELLED
            // không bị nạp lại sau restart) → gỡ cờ, tránh tập này phình theo số phiên đã dừng.
            if (pending == 0) gradingService.clearCancelled(batchId);
            // Hoàn tất khi KHÔNG còn bài chờ/đang chấm (không phụ thuộc totalFiles có thể lệch).
            // Guard completedAt: chỉ đóng batch + ghi mốc 1 lần (tránh 2 worker cuối đóng 2 lần).
            if (pending == 0 && b.getCompletedAt() == null) {
                b.setStatus(error == 0 && review == 0 ? BatchStatus.COMPLETED : BatchStatus.PARTIAL);
                b.setCompletedAt(Instant.now());
                pausedBatchIds.remove(batchId);
                log.info("[{}] Batch hoàn tất: {} đạt / {} lỗi / {} cần chấm tay",
                        batchId, done, error, review);
            }
            batchRepo.save(b);
        });
    }

    // ── Staging zip ra đĩa (cũng là nơi lưu audit) ──────────────
    /** Thư mục của 1 batch: submissions/&lt;đề&gt;/&lt;batch&gt;/ (chứa zip bài nộp + snapshot testcase). */
    private Path batchDir(String examId, String batchId) {
        return examService.resolveSibling(submissionsDir).resolve(examId).resolve(batchId);
    }

    private Path stagedZipPath(String examId, String batchId, String studentId) {
        return batchDir(examId, batchId).resolve(studentId + ".zip");
    }

    /**
     * Snapshot testcase ĐANG dùng vào folder batch (submissions/&lt;đề&gt;/&lt;batch&gt;/_testcase/) để
     * sau này đối chiếu / tra lại đúng bộ đề đã chấm, kể cả khi GV upload đè testcase mới. Rất nhẹ.
     */
    private void snapshotTestcase(String examId, String batchId, String testcasePath) {
        try {
            if (testcasePath == null || testcasePath.isBlank()) return;
            Path src = Path.of(testcasePath);
            if (!Files.exists(src)) return;
            Path dst = batchDir(examId, batchId).resolve("_testcase");
            Files.createDirectories(dst);
            try (Stream<Path> walk = Files.walk(src)) {
                for (Path p : walk.toList()) {
                    Path target = dst.resolve(src.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(target);
                    else {
                        if (target.getParent() != null) Files.createDirectories(target.getParent());
                        Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[{}] Snapshot testcase lỗi: {}", batchId, e.getMessage());
        }
    }

    /** Stream zip ra đĩa (KHÔNG nạp toàn bộ vào RAM) → trả về đường dẫn. */
    private Path stageZip(MultipartFile file, String examId, String batchId, String studentId) throws Exception {
        Path zip = stagedZipPath(examId, batchId, studentId);
        Files.createDirectories(zip.getParent());
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, zip, StandardCopyOption.REPLACE_EXISTING);
        }
        return zip;
    }

    private void deleteQuietly(Path p) {
        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
    }

    // ── Hàng đợi bền: nạp lại job QUEUED/GRADING từ DB sau restart ─
    private void recoverPendingJobs() {
        try {
            List<ExamResult> pending = resultRepo.findByStatusIn(
                    List.of(GradingStatus.QUEUED, GradingStatus.GRADING));
            int recovered = 0;
            for (ExamResult r : pending) {
                Path zip = stagedZipPath(r.getExamId(), r.getBatchId(), r.getStudentId());
                if (Files.exists(zip)) {
                    r.setStatus(GradingStatus.QUEUED);
                    resultRepo.save(r);
                    jobQueue.add(new GradingJob(r.getStudentId(), r.getStudentName(),
                            r.getBatchId(), r.getExamId(), zip.toString(), null));
                    recovered++;
                } else {
                    // Mất file staged là sự cố hạ tầng, không phải lỗi bài nộp → phải nổi lên
                    // màn hình người chấm kèm mã, đừng để trơ một dòng ERROR không phân loại.
                    r.setStatus(GradingStatus.MANUAL_REVIEW);
                    r.setScore(null);
                    r.setDiagnosticCode("SUBMISSION_FILE_LOST");
                    r.setDiagnosticOrigin(GradingDiagnosticException.Origin.ENVIRONMENT.name());
                    r.setDiagnosticStage("SUBMISSION_STAGING");
                    r.setRequiresManualReview(true);
                    r.setErrorLog("Mất file bài nộp khi khôi phục sau restart");
                    resultRepo.save(r);
                }
            }
            if (recovered > 0) log.info("♻️ Khôi phục {} bài đang chờ sau restart", recovered);
        } catch (Exception e) {
            log.warn("Khôi phục hàng đợi lỗi: {}", e.getMessage());
        }
    }

    // ── Dọn bài nộp cũ hơn N ngày (chạy 3h sáng mỗi ngày) ───────
    @Scheduled(cron = "0 0 3 * * *")
    public void cleanupOldSubmissions() {
        if (!saveSubmissions || submissionsRetentionDays <= 0) return;
        try {
            Path base = examService.resolveSibling(submissionsDir);
            if (!Files.exists(base)) return;
            Instant cutoff = Instant.now().minus(Duration.ofDays(submissionsRetentionDays));
            int[] deleted = {0};
            try (var walk = Files.walk(base)) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    try {
                        if (Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) {
                            Files.delete(p);
                            deleted[0]++;
                        }
                    } catch (Exception ignored) {}
                });
            }
            if (deleted[0] > 0)
                log.info("🧹 Đã dọn {} bài nộp cũ hơn {} ngày", deleted[0], submissionsRetentionDays);
        } catch (Exception e) {
            log.warn("Dọn bài nộp cũ lỗi: {}", e.getMessage());
        }
    }

    // ── Progress cho GV ──────────────────────────────────────────
    /** Thông báo: 10 phiên chấm gần nhất (app một người dùng → không lọc theo người tạo). */
    public List<GradingBatch> recentBatches() {
        return batchRepo.findTop10ByOrderByCreatedAtDesc();
    }

    /**
     * Các batch còn hoạt động. Dữ liệu lấy từ DB nên UI có thể phục hồi sau khi đổi trang/F5,
     * không phụ thuộc state đang giữ trong trình duyệt.
     */
    public List<Map<String, Object>> activeBatches() {
        List<GradingBatch> candidates = batchRepo.findByStatusInOrderByCreatedAtDesc(
                List.of(BatchStatus.IN_PROGRESS, BatchStatus.PAUSED));
        List<Map<String, Object>> out = new ArrayList<>();
        for (GradingBatch candidate : candidates) {
            BatchProgressResponse progress = getBatchProgress(candidate.getBatchId());
            if (progress.getQueued() + progress.getGrading() == 0) checkBatchComplete(candidate.getBatchId());
            GradingBatch batch = batchRepo.findByBatchId(candidate.getBatchId()).orElse(candidate);
            if (batch.getStatus() != BatchStatus.IN_PROGRESS && batch.getStatus() != BatchStatus.PAUSED) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("batchId", batch.getBatchId());
            row.put("examId", batch.getExamId());
            row.put("status", batch.getStatus().name());
            row.put("total", progress.getTotal());
            row.put("done", progress.getDone());
            row.put("grading", progress.getGrading());
            row.put("queued", progress.getQueued());
            row.put("error", progress.getError());
            row.put("manualReview", progress.getManualReview());
            out.add(row);
        }
        return out;
    }

    public BatchProgressResponse pauseBatch(String batchId) {
        GradingBatch batch = requireBatch(batchId);
        if (batch.getStatus() == BatchStatus.COMPLETED
                || batch.getStatus() == BatchStatus.PARTIAL
                || batch.getStatus() == BatchStatus.CANCELLED)
            throw new IllegalArgumentException("Phiên chấm đã kết thúc nên không thể tạm dừng.");
        batch.setStatus(BatchStatus.PAUSED);
        batchRepo.save(batch);
        pausedBatchIds.add(batchId);
        log.info("[{}] Đã tạm dừng nhận bài mới từ hàng đợi", batchId);
        return getBatchProgress(batchId);
    }

    public BatchProgressResponse resumeBatch(String batchId) {
        GradingBatch batch = requireBatch(batchId);
        if (batch.getStatus() == BatchStatus.COMPLETED
                || batch.getStatus() == BatchStatus.PARTIAL
                || batch.getStatus() == BatchStatus.CANCELLED)
            throw new IllegalArgumentException("Phiên chấm đã kết thúc nên không thể tiếp tục.");
        batch.setStatus(BatchStatus.IN_PROGRESS);
        batchRepo.save(batch);
        pausedBatchIds.remove(batchId);
        log.info("[{}] Tiếp tục phiên chấm", batchId);
        return getBatchProgress(batchId);
    }

    private GradingBatch requireBatch(String batchId) {
        if (batchId == null || batchId.isBlank())
            throw new IllegalArgumentException("Thiếu batchId.");
        return batchRepo.findByBatchId(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiên chấm: " + batchId));
    }

    /** Đọc các file mã nguồn trong bài nộp (zip đã staged) để hiển thị cho GV chấm tay. */
    public List<Map<String, String>> readSubmissionFiles(String examId, String studentId) {
        List<Map<String, String>> out = new ArrayList<>();
        ExamResult r = resultRepo.findByStudentIdAndExamIdAndMode(studentId, examId, "submit").orElse(null);
        if (r == null || r.getBatchId() == null) return out;
        Path zip = stagedZipPath(examId, r.getBatchId(), studentId);
        if (!Files.exists(zip)) return out;

        final int MAX_FILES = 40, MAX_BYTES = 80_000;
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null && out.size() < MAX_FILES) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                String lower = name.toLowerCase();
                if (!(lower.endsWith(".dart") || lower.endsWith(".yaml") || lower.endsWith(".yml")
                        || lower.endsWith(".json") || lower.endsWith(".md") || lower.endsWith(".txt"))) continue;
                if (lower.contains("/.dart_tool/") || lower.contains("/build/") || lower.contains("/.git/")) continue;
                // Đọc CÓ GIỚI HẠN (readNBytes) → 1 entry khổng lồ không thể làm OOM
                byte[] buf = zis.readNBytes(MAX_BYTES + 1);
                String content = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
                if (buf.length > MAX_BYTES) content = content + "\n… (đã cắt bớt)";
                Map<String, String> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("content", content);
                out.add(m);
            }
        } catch (Exception ex) {
            log.warn("Đọc file bài nộp {}/{} lỗi: {}", examId, studentId, ex.getMessage());
        }
        // .dart lên đầu, rồi theo tên
        out.sort((a, b) -> {
            boolean ad = a.get("name").toLowerCase().endsWith(".dart");
            boolean bd = b.get("name").toLowerCase().endsWith(".dart");
            if (ad != bd) return ad ? -1 : 1;
            return a.get("name").compareTo(b.get("name"));
        });
        return out;
    }

    /**
     * Đọc các file TESTCASE đã dùng để chấm 1 SV (để đối chiếu khi bài bị 0/0). Ưu tiên snapshot
     * của batch đã chấm (_testcase/); nếu chưa có (batch cũ) thì đọc testcase HIỆN TẠI của đề.
     */
    public List<Map<String, String>> readTestcaseFiles(String examId, String studentId) {
        List<Map<String, String>> out = new ArrayList<>();
        Path dir = null;

        ExamResult r = resultRepo.findByStudentIdAndExamIdAndMode(studentId, examId, "submit").orElse(null);
        // Bài CHƯA chấm xong thì chưa tồn tại "testcase đã dùng để chấm". Trước đây rơi xuống
        // nhánh dự phòng và trả về bộ testcase HIỆN TẠI của đề — người xem tưởng đó là bản đã
        // chấm bài này, trong khi bài còn nằm trong hàng đợi và bộ đó vẫn có thể bị sửa trước
        // khi tới lượt. Chặn thẳng, nói rõ lý do.
        if (r != null && (r.getStatus() == GradingStatus.QUEUED || r.getStatus() == GradingStatus.GRADING))
            throw new IllegalStateException("Bài của " + studentId + " đang "
                    + (r.getStatus() == GradingStatus.QUEUED ? "chờ trong hàng đợi" : "được chấm")
                    + " nên chưa có bản testcase đã dùng để chấm. Hãy đợi chấm xong rồi mở lại.");
        if (r != null && r.getBatchId() != null) {
            Path snap = batchDir(examId, r.getBatchId()).resolve("_testcase");
            if (Files.exists(snap)) dir = snap;
        }
        if (dir == null) {
            String tc = examRepo.findByExamId(examId).map(Exam::getTestcasePath).orElse(null);
            if (tc != null && !tc.isBlank() && Files.exists(Path.of(tc))) dir = Path.of(tc);
        }
        if (dir == null) return out;

        final Path base = dir;
        final int MAX_BYTES = 200_000;
        try (Stream<Path> s = Files.walk(base)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String name = base.relativize(p).toString().replace('\\', '/');
                String lower = name.toLowerCase();
                if (!(lower.endsWith(".dart") || lower.endsWith(".json") || lower.endsWith(".yaml")
                        || lower.endsWith(".yml") || lower.endsWith(".md") || lower.endsWith(".txt"))) continue;
                String content = new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8);
                if (content.length() > MAX_BYTES) content = content.substring(0, MAX_BYTES) + "\n… (đã cắt bớt)";
                Map<String, String> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("content", content);
                out.add(m);
            }
        } catch (Exception ex) {
            log.warn("Đọc testcase {}/{} lỗi: {}", examId, studentId, ex.getMessage());
        }
        out.sort((a, b) -> rankTestcaseFile(a.get("name")) - rankTestcaseFile(b.get("name")));
        return out;
    }

    /** exam_test.dart → skills_matrix.json → grader.dart → còn lại (cho dễ đối chiếu). */
    private int rankTestcaseFile(String name) {
        String n = name.toLowerCase();
        if (n.endsWith("exam_test.dart"))     return 0;
        if (n.endsWith("skills_matrix.json")) return 1;
        if (n.endsWith("grader.dart"))        return 2;
        return 3;
    }

    /**
     * CHẤM LẠI 1 bài từ zip ĐÃ LƯU (không cần upload lại). Tạo batch mới (số liệu sạch + để lại
     * dấu vết chấm lại), copy zip + snapshot testcase, rồi đưa vào hàng đợi. Trả batchId mới.
     *
     * Testcase: ưu tiên testcase HIỆN TẠI của đề; nếu đề đã xóa / testcase mất thì DÙNG SNAPSHOT của
     * batch gốc (submissions/&lt;đề&gt;/&lt;batch&gt;/_testcase/) → vẫn chấm lại được bài của ĐỀ CŨ.
     */
    public String regradeStudent(String examId, String studentId, String createdBy) throws Exception {
        ExamService.safeId(examId, "đề"); ExamService.safeId(studentId, "SV");
        ExamResult r = resultRepo.findByStudentIdAndExamIdAndMode(studentId, examId, "submit")
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bài nộp của " + studentId));
        if (r.getBatchId() == null)
            throw new IllegalArgumentException("Bài nộp thiếu batchId — không thể chấm lại.");

        Path oldZip = stagedZipPath(examId, r.getBatchId(), studentId);
        if (!Files.exists(oldZip))
            throw new IllegalArgumentException("Bài nộp không còn được lưu trên đĩa — không thể chấm lại.");

        // Resolve testcase: hiện tại của đề → nếu mất thì snapshot của batch gốc (đề cũ vẫn chấm lại được)
        String tc = resolveTestcasePath(examId, r.getBatchId());
        if (tc == null)
            throw new IllegalArgumentException(
                    "Đề đã bị xóa và không còn snapshot testcase của bài này — không thể chấm lại.");

        String newBatchId = genBatchId();
        GradingBatch batch = new GradingBatch();
        batch.setBatchId(newBatchId);
        batch.setExamId(examId);
        batch.setTotalFiles(1);
        batch.setCreatedBy(actorEmail(createdBy));
        batchRepo.save(batch);

        // Copy zip sang folder batch mới + snapshot testcase đã resolve (audit lần chấm lại)
        Path newZip = stagedZipPath(examId, newBatchId, studentId);
        Files.createDirectories(newZip.getParent());
        Files.copy(oldZip, newZip, StandardCopyOption.REPLACE_EXISTING);
        snapshotTestcase(examId, newBatchId, tc);

        // Ghi đè chính bản ghi này: trỏ sang batch mới, reset kết quả lần chấm trước.
        // Giữ lại điểm cũ TRƯỚC khi xoá — màn hình Lịch sử cần nó để cảnh báo lệch điểm.
        r.setPreviousScore(r.getScore());
        r.setBatchId(newBatchId);
        r.setStatus(GradingStatus.QUEUED);
        r.setScore(null);
        r.setDetails(null);
        r.setErrorLog(null);
        r.setResultJson(null);
        resultRepo.save(r);

        jobQueue.add(new GradingJob(studentId, r.getStudentName(), newBatchId, examId, newZip.toString(), tc));
        log.info("[{}] Chấm lại {} (đề {})", newBatchId, studentId, examId);
        return newBatchId;
    }

    /** Testcase để chấm lại: ưu tiên testcase HIỆN TẠI của đề; mất thì dùng SNAPSHOT của batch gốc. null = không có. */
    private String resolveTestcasePath(String examId, String batchId) {
        String tc = examRepo.findByExamId(examId).map(Exam::getTestcasePath).orElse(null);
        if (tc != null && !tc.isBlank() && Files.exists(Path.of(tc))) return tc;
        Path snap = batchDir(examId, batchId).resolve("_testcase");
        if (Files.exists(snap)) return snap.toAbsolutePath().toString();
        return null;
    }

    /**
     * CHẤM LẠI NHIỀU bài cùng lúc → gộp vào MỘT batch mới (theo dõi tiến độ chung). Bỏ qua bài mất
     * file/đề. Trả { batchId, queued, skipped:[...] }.
     */
    public Map<String, Object> regradeStudents(String examId, List<String> studentIds, String createdBy) throws Exception {
        ExamService.safeId(examId, "đề");
        if (studentIds == null || studentIds.isEmpty())
            throw new IllegalArgumentException("Chưa chọn bài nào để chấm lại.");

        String newBatchId = genBatchId();
        GradingBatch batch = new GradingBatch();
        batch.setBatchId(newBatchId);
        batch.setExamId(examId);
        batch.setCreatedBy(actorEmail(createdBy));
        batchRepo.save(batch);

        List<GradingJob> jobs = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        String snapTc = null;
        for (String sid : new java.util.LinkedHashSet<>(studentIds)) {
            try {
                ExamResult r = resultRepo.findByStudentIdAndExamIdAndMode(sid, examId, "submit").orElse(null);
                if (r == null || r.getBatchId() == null) { skipped.add(sid); continue; }
                Path oldZip = stagedZipPath(examId, r.getBatchId(), sid);
                if (!Files.exists(oldZip)) { skipped.add(sid); continue; }
                String tc = resolveTestcasePath(examId, r.getBatchId());
                if (tc == null) { skipped.add(sid); continue; }
                if (snapTc == null) snapTc = tc;

                Path newZip = stagedZipPath(examId, newBatchId, sid);
                Files.createDirectories(newZip.getParent());
                Files.copy(oldZip, newZip, StandardCopyOption.REPLACE_EXISTING);

                r.setPreviousScore(r.getScore());   // so sánh lệch điểm sau khi chấm lại
                r.setBatchId(newBatchId);
                r.setStatus(GradingStatus.QUEUED);
                r.setScore(null); r.setDetails(null); r.setErrorLog(null); r.setResultJson(null);
                resultRepo.save(r);

                jobs.add(new GradingJob(sid, r.getStudentName(), newBatchId, examId, newZip.toString(), tc));
            } catch (Exception e) {
                skipped.add(sid);
            }
        }

        batch.setTotalFiles(jobs.size());
        batchRepo.save(batch);
        if (snapTc != null) snapshotTestcase(examId, newBatchId, snapTc);

        if (jobs.isEmpty()) {
            batch.setStatus(BatchStatus.COMPLETED);
            batch.setCompletedAt(Instant.now());
            batchRepo.save(batch);
            throw new IllegalArgumentException(
                    "Không bài nào chấm lại được (mất file bài nộp hoặc testcase). Bỏ qua: " + String.join(", ", skipped));
        }
        jobs.forEach(jobQueue::add);
        log.info("[{}] Chấm lại {} bài (đề {}), bỏ qua {}", newBatchId, jobs.size(), examId, skipped.size());

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("batchId", newBatchId);
        res.put("queued", jobs.size());
        res.put("skipped", skipped);
        return res;
    }

    /** Chấm lại TOÀN BỘ bài đã nộp của 1 đề (gom 1 batch). Dùng cho nút "Chấm lại đề" ở Kho đề. */
    public Map<String, Object> regradeExam(String examId, String createdBy) throws Exception {
        ExamService.safeId(examId, "đề");
        List<String> ids = resultRepo.findSubmitStudentIds(examId);
        if (ids.isEmpty())
            throw new IllegalArgumentException("Đề " + examId + " chưa có bài nộp nào để chấm lại.");
        // Golden suite đã publish là một snapshot bất biến. Chấm lại phải dùng đúng bundle
        // đã được preflight, không âm thầm thay engine giữa các lần chấm.
        return regradeStudents(examId, ids, createdBy);
    }

    // ── Dừng / hủy phiên chấm đang chạy ──────────────────────────

    /**
     * DỪNG phiên chấm: bỏ các bài còn nằm trong hàng đợi, giết container đang chạy, nhưng GIỮ
     * NGUYÊN kết quả của những bài đã chấm xong. Bài chưa chấm chuyển sang CANCELLED — KHÔNG phải
     * ERROR, vì không có bằng chứng nào để quy lỗi cho bài nộp.
     */
    public Map<String, Object> stopBatch(String batchId) {
        GradingBatch batch = batchRepo.findByBatchId(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiên chấm: " + batchId));
        // Bật cờ TRƯỚC mọi thứ: worker đang cầm job của phiên này phải thấy ngay khi nó bắt lỗi.
        gradingService.markCancelled(batchId);
        // Nếu batch đang PAUSED thì phải gỡ cờ này trước khi rút hàng đợi. Nếu không, một worker
        // vừa lấy job đúng lúc có thể tự đưa job trở lại hàng đợi mãi và phiên không dừng được.
        pausedBatchIds.remove(batchId);

        // Rút job của phiên này khỏi hàng đợi; job của phiên khác được trả lại nguyên vẹn.
        List<GradingJob> drained = new ArrayList<>();
        jobQueue.drainTo(drained);
        int dequeued = 0;
        for (GradingJob j : drained) {
            if (batchId.equals(j.batchId())) dequeued++;
            else jobQueue.add(j);
        }

        int cancelledRows = 0;
        for (ExamResult r : resultRepo.findByBatchIdOrderByStudentId(batchId)) {
            if (r.getStatus() == GradingStatus.QUEUED) {
                r.setStatus(GradingStatus.CANCELLED);
                r.setScore(null);
                r.setErrorLog("Phiên chấm đã bị dừng theo yêu cầu người dùng.");
                resultRepo.save(r);
                cancelledRows++;
            }
        }

        // Bài ĐANG chấm: giết container để worker thoát ngay, thay vì chờ hết watchdog từng bài.
        int killed = gradingService.killRunning(batchId);

        // PARTIAL chứ KHÔNG phải CANCELLED: Dừng = kết thúc sớm nhưng GIỮ kết quả, và giáo viên
        // còn được nạp thêm bài để chấm tiếp (addToBatch chỉ từ chối CANCELLED). Trước đây đặt
        // CANCELLED nên bấm Dừng xong là phiên bị khóa vĩnh viễn — đúng lỗi đã gặp.
        // completedAt != null cũng là chốt chặn để checkBatchComplete không ghi đè trạng thái này.
        batch.setStatus(BatchStatus.PARTIAL);
        if (batch.getCompletedAt() == null) batch.setCompletedAt(Instant.now());
        batchRepo.save(batch);

        log.info("[{}] Dừng phiên chấm: bỏ {} bài trong hàng đợi, {} bài chuyển CANCELLED, giết {} container",
                batchId, dequeued, cancelledRows, killed);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("batchId", batchId);
        res.put("dequeued", dequeued);
        res.put("cancelled", cancelledRows);
        res.put("killedContainers", killed);
        return res;
    }

    /**
     * HỦY phiên chấm: dừng như trên rồi XÓA dữ liệu của phiên — bản ghi kết quả, zip bài nộp và
     * snapshot testcase — coi như chưa từng chấm.
     *
     * <p>Ngoại lệ: bài đã có ĐIỂM CHẤM TAY thì không xóa bản ghi. Các cột manual_* nằm CÙNG HÀNG
     * với kết quả tự động, xóa hàng là mất luôn công chấm tay của giáo viên; những bài đó chỉ bị
     * xóa phần kết quả tự động.
     */
    public Map<String, Object> cancelBatch(String batchId) {
        Map<String, Object> res = new LinkedHashMap<>(stopBatch(batchId));
        GradingBatch batch = batchRepo.findByBatchId(batchId).orElse(null);

        int deleted = 0, keptManual = 0;
        for (ExamResult r : resultRepo.findByBatchIdOrderByStudentId(batchId)) {
            boolean hasManual = r.getManualScore() != null
                    || (r.getManualJson() != null && !r.getManualJson().isBlank());
            if (hasManual) {
                r.setStatus(GradingStatus.CANCELLED);
                r.setScore(null);
                r.setDetails(null);
                r.setResultJson(null);
                r.setErrorLog("Phiên chấm đã bị hủy; chỉ giữ lại phần chấm tay.");
                resultRepo.save(r);
                keptManual++;
            } else {
                resultRepo.delete(r);
                deleted++;
            }
        }

        // Xóa zip bài nộp + snapshot testcase. Dùng examId/batchId LẤY TỪ DB (không phải chuỗi
        // người dùng gửi lên) nên đường dẫn chắc chắn nằm trong submissions/.
        if (batch != null) {
            deleteDirQuietly(batchDir(batch.getExamId(), batch.getBatchId()));
            batch.setStatus(BatchStatus.CANCELLED);   // stopBatch chỉ đặt PARTIAL; Hủy mới là CANCELLED
            batch.setDoneCount(0);      // bản ghi kết quả đã bị xóa → bộ đếm cũ thành vô nghĩa
            batch.setErrorCount(0);
            batchRepo.save(batch);
        }

        log.info("[{}] Hủy phiên chấm: xóa {} bản ghi, giữ {} bài đã có điểm chấm tay",
                batchId, deleted, keptManual);
        res.put("deleted", deleted);
        res.put("keptManual", keptManual);
        return res;
    }

    /**
     * Dừng MỌI phiên chấm còn sống của một bộ testcase. Gọi ngay trước khi xóa bộ đó.
     *
     * <p>Xóa bộ là xóa thẳng hàng `grading_batches` và `exam_results` của nó. Nếu lúc ấy còn
     * worker đang cầm job của bộ này, nó chấm xong rồi ghi kết quả trở lại — sinh ra bản ghi
     * mồ côi trỏ vào một bộ không còn tồn tại, mà màn hình thì không còn đường nào gỡ vì bộ
     * đã biến mất khỏi danh sách. Dừng trước thì hàng đợi được rút và container bị giết, nên
     * đến lúc xóa không còn ai viết vào nữa.
     *
     * @return số phiên thực sự phải dừng
     */
    public int dungMoiPhienCuaDe(String examId) {
        int daDung = 0;
        for (GradingBatch b : batchRepo.findByExamIdOrderByCreatedAtDesc(examId)) {
            BatchStatus st = b.getStatus();
            // COMPLETED/CANCELLED/PARTIAL là phiên đã kết thúc — không còn worker nào cầm job.
            if (st != BatchStatus.IN_PROGRESS && st != BatchStatus.PAUSED) continue;
            try {
                stopBatch(b.getBatchId());
                daDung++;
            } catch (Exception e) {
                log.warn("Không dừng được phiên {} của bộ {}: {}", b.getBatchId(), examId, e.getMessage());
            }
        }
        return daDung;
    }

    /** Xóa cả cây thư mục, nuốt mọi lỗi (Windows có thể đang khóa file trong thư mục). */
    private void deleteDirQuietly(Path dir) {
        try {
            if (!Files.exists(dir)) return;
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); } catch (Exception ignored) {}
                });
            }
        } catch (Exception e) {
            log.warn("Không xóa được thư mục phiên chấm {}: {}", dir, e.getMessage());
        }
    }

    public BatchProgressResponse getBatchProgress(String batchId) {
        // Đếm trạng thái bằng COUNT ở DB; danh sách dùng projection NHẸ (không kéo result_json) →
        // poll 3s với batch lớn không còn tốn RAM/băng thông.
        long done    = resultRepo.countByBatchIdAndStatus(batchId, GradingStatus.DONE);
        long grading = resultRepo.countByBatchIdAndStatus(batchId, GradingStatus.GRADING);
        long queued  = resultRepo.countByBatchIdAndStatus(batchId, GradingStatus.QUEUED);
        long error   = resultRepo.countByBatchIdAndStatus(batchId, GradingStatus.ERROR);
        long review  = resultRepo.countByBatchIdAndStatus(batchId, GradingStatus.MANUAL_REVIEW);
        long stopped = resultRepo.countByBatchIdAndStatus(batchId, GradingStatus.CANCELLED);
        List<com.example.grader.dto.ResultRow> rows = resultRepo.findRowsByBatchId(batchId);
        GradingBatch batch = batchRepo.findByBatchId(batchId).orElse(null);
        String status = batch == null || batch.getStatus() == null
                ? (batch == null ? "UNKNOWN" : BatchStatus.IN_PROGRESS.name())
                : batch.getStatus().name();
        return new BatchProgressResponse(batchId, rows.size(), done, grading, queued, error, review,
                stopped, status, rows, error + review, systemIncidents(rows),
                batch == null ? null : batch.getExamId());
    }

    /**
     * Gom bài {@code SYSTEM_BLOCKED} THEO NGUYÊN NHÂN thay vì theo bài.
     *
     * <p>Sự cố hạ tầng gần như luôn trúng cả loạt: Docker chết một cái là 20 bài cùng hỏng. Liệt
     * kê 20 dòng giống nhau bắt người chấm tự nhận ra đó là MỘT sự cố; gom lại thì họ đọc một
     * dòng, và có sẵn danh sách mã SV để chấm lại đúng nhóm đó sau khi sửa máy.
     *
     * <p>Bài {@code SCORED} (kể cả 0 điểm do làm sai) không bao giờ vào đây — đó là kết quả, không
     * phải sự cố.
     */
    private List<Map<String, Object>> systemIncidents(List<com.example.grader.dto.ResultRow> rows) {
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        for (com.example.grader.dto.ResultRow r : rows) {
            if (r.outcome() != GradingOutcome.SYSTEM_BLOCKED) continue;
            String code = r.diagnosticCode() == null || r.diagnosticCode().isBlank()
                    ? "UNCLASSIFIED_GRADING_ERROR" : r.diagnosticCode();
            Map<String, Object> group = byCode.computeIfAbsent(code, key -> {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("code", key);
                g.put("origin", r.diagnosticOrigin());
                g.put("originLabel", SystemIncidentCatalog.originLabel(r.diagnosticOrigin()));
                g.put("label", SystemIncidentCatalog.label(key, r.diagnosticOrigin()));
                g.put("message", r.errorLog());
                g.put("count", 0);
                g.put("studentIds", new ArrayList<String>());
                return g;
            });
            group.put("count", (int) group.get("count") + 1);
            @SuppressWarnings("unchecked")
            List<String> ids = (List<String>) group.get("studentIds");
            ids.add(r.studentId());
        }
        List<Map<String, Object>> out = new ArrayList<>(byCode.values());
        out.sort((a, b) -> (int) b.get("count") - (int) a.get("count"));   // nhóm to lên trước
        return out;
    }

    // ── Helpers ──────────────────────────────────────────────────
    private float parseScore(String json) {
        try {
            JsonNode n = mapper.readTree(json);
            if (n.has("diem"))  return (float) n.get("diem").asDouble();
            if (n.has("score")) return (float) n.get("score").asDouble();
        } catch (Exception ignored) {}
        return 0f;
    }

    private String actorEmail(String createdBy) {
        return (createdBy == null || createdBy.isBlank()) ? "unknown" : createdBy.trim();
    }

    /**
     * Suy mã SV + tên hiển thị từ TÊN THƯ MỤC bài nộp.
     *
     * <p>Tên thư mục KHÔNG bị ràng buộc: giáo viên tải về từ LMS thì thư mục hay mang tên kiểu
     * "Nguyễn Văn A (HE123456)", "PE_ca1 - he150123", có dấu, có khoảng trắng, có ngoặc. Trước
     * đây {@code safeId} chặn thẳng những tên đó và cả lô bài bị từ chối. Giờ:
     * <ol>
     *   <li>mã SV lấy theo mẫu 2 chữ + ≥6 số ở BẤT KỲ đâu trong tên (HE123456, he150123…);</li>
     *   <li>không có mẫu đó thì rút gọn cả tên thành mã an toàn (bỏ dấu, thay ký tự lạ bằng "_").</li>
     * </ol>
     * Tên gốc luôn được giữ nguyên ở {@code studentName} để hiện lên bảng điểm.
     *
     * <p>Mã SV còn dùng làm TÊN THƯ MỤC khi lưu bài và là cột {@code student_id} varchar(20),
     * nên bắt buộc cắt ở 20 ký tự và chỉ chứa ký tự an toàn.
     */
    StudentInfo parseStudentInfo(String username) {
        String normalized = username == null ? "" : username.trim();
        if (normalized.isEmpty())
            throw new IllegalArgumentException("Thiếu tên thư mục bài nộp.");

        Matcher matcher = STUDENT_ID_ANYWHERE.matcher(normalized);
        String studentId = matcher.find() ? matcher.group(1) : slugStudentId(normalized);
        studentId = studentId.toUpperCase(java.util.Locale.ROOT);
        if (studentId.length() > 20) studentId = studentId.substring(0, 20);
        if (studentId.isBlank())
            throw new IllegalArgumentException("Không suy được mã SV từ tên thư mục: " + normalized);
        return new StudentInfo(studentId, normalized);
    }

    /** Bỏ dấu tiếng Việt rồi thay mọi ký tự ngoài [A-Za-z0-9_-] bằng "_". */
    private String slugStudentId(String raw) {
        String noAccent = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd').replace('Đ', 'D');
        return noAccent.replaceAll("[^A-Za-z0-9_-]+", "_")
                .replaceAll("[_-]{2,}", "_")            // "B - ca" → "B_ca", không để "_-_"
                .replaceAll("^[_-]+|[_-]+$", "");
    }

    void validateZip(MultipartFile f, String name) throws Exception {
        if (name == null || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip"))
            throw new IllegalArgumentException("Mỗi thư mục bài nộp phải chứa một file .zip (thường là lib.zip)");
        if (f.isEmpty())                          throw new IllegalArgumentException("File rỗng");
        if (f.getSize() > 50L * 1024 * 1024)     throw new IllegalArgumentException("Quá 50MB");
    }
}
