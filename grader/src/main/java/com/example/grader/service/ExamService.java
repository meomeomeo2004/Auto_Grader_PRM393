package com.example.grader.service;

import com.example.grader.dto.ExamSetupResponse;
import com.example.grader.entity.Exam;
import com.example.grader.entity.ExamStatus;
import com.example.grader.repository.ExamRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Setup đề theo cơ chế MOUNT: KHÔNG build image riêng cho từng đề.
 * Chỉ lưu testcase lên đĩa rồi mount vào ảnh nền grading-base lúc chấm.
 *  → upload gần như tức thì, không tích tụ image.
 */
@Service
@Slf4j
public class ExamService {

    private static final long MAX_TESTCASE_ZIP_BYTES = 20L * 1024 * 1024;
    private static final long MAX_TESTCASE_UNZIPPED_BYTES = 50L * 1024 * 1024;
    private static final int MAX_TESTCASE_ZIP_ENTRIES = 200;

    @Value("${grader.template-dir:grader-base}")
    private String templateDir;

    @Value("${grader.exams-dir:exams}")
    private String examsDir;

    @Value("${grader.base-image:grading-base:latest}")
    private String baseImage;

    @Value("${grader.image.prefix:grading-env}")
    private String imagePrefix;

    @Value("${grader.submissions-dir:submissions}")
    private String submissionsDir;

    /** Giới hạn mỗi process Flutter để một testcase treo không chiếm cả lượt chấm. */
    @Value("${grader.runner.process-timeout-seconds:60}")
    private int runnerProcessTimeoutSeconds;

    private final AtomicBoolean baseImageReady = new AtomicBoolean(false);
    private final ObjectMapper mapper = new ObjectMapper();

    // ── Trạng thái build môi trường chấm (thêm/xóa thư viện) — chỉ 1 build tại 1 thời điểm ──
    private final Object envLock = new Object();
    private volatile boolean envBuilding = false;
    private volatile String envStatus  = "IDLE";   // IDLE|RESOLVING|BUILDING|READY|FAILED
    private volatile String envMessage = "";
    private volatile String envLog     = "";
    private volatile long   envAt      = 0;

    @Autowired
    private ExamRepository examRepository;
    @Autowired
    private SyllabusService syllabusService;
    @Autowired
    private com.example.grader.repository.ExamResultRepository resultRepository;
    @Autowired
    private com.example.grader.repository.GradingBatchRepository batchRepository;

    /**
     * Chặn PATH TRAVERSAL: mã đề/SV chỉ được chứa chữ/số/_/- (vd PE_50, FLUTTER_PE_01, HE123456).
     * Mọi nơi ghép id vào đường dẫn file/lệnh docker PHẢI gọi hàm này trước.
     */
    public static String safeId(String id, String what) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,60}"))
            throw new IllegalArgumentException(
                    "Mã " + what + " không hợp lệ: '" + id + "' (chỉ gồm a-z, A-Z, 0-9, _, -).");
        return id;
    }

    // ── Rubric chấm tay: đọc tiêu chí từ skills_matrix.json của đề ──
    public List<Map<String, Object>> getCriteria(String examId) throws Exception {
        Exam exam = examRepository.findByExamId(examId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đề: " + examId));
        String tc = exam.getTestcasePath();
        List<Map<String, Object>> out = new ArrayList<>();
        if (tc == null || tc.isBlank()) return out;
        Path f = Path.of(tc).resolve("skills_matrix.json");
        if (!Files.exists(f)) return out;

        JsonNode root = mapper.readTree(Files.readString(f, StandardCharsets.UTF_8));
        root.fields().forEachRemaining(e -> {
            JsonNode v = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("testId", e.getKey());
            m.put("name", v.path("name").asText(""));
            m.put("skill", v.path("skill").asText(""));
            m.put("weight", v.path("weight").asDouble(0));
            m.put("description", v.path("description").asText(""));
            m.put("expected", v.path("expected").asText(""));
            out.add(m);
        });
        return out;
    }

    /** Thư mục gốc chứa các đề (exams/), nơi mỗi đề là <exams>/<id>/testcase/. */
    private Path examsRoot() {
        return resolveExamsDir(locateTemplateDir());
    }

    /**
     * Đọc nguyên văn skills_matrix.json của 1 đề. Ưu tiên testcasePath trong DB; nếu không
     * có thì đọc trực tiếp thư mục trên đĩa <exams>/<examId>/testcase/ (đề mẫu chưa upload).
     */
    public String readSkillsMatrixJson(String examId) throws Exception {
        safeId(examId, "đề");
        Exam exam = examRepository.findByExamId(examId).orElse(null);
        if (exam != null && exam.getTestcasePath() != null && !exam.getTestcasePath().isBlank()) {
            Path f = Path.of(exam.getTestcasePath()).resolve("skills_matrix.json");
            if (Files.exists(f)) return Files.readString(f, StandardCharsets.UTF_8);
        }
        Path disk = examsRoot().resolve(examId).resolve("testcase").resolve("skills_matrix.json");
        if (Files.exists(disk)) return Files.readString(disk, StandardCharsets.UTF_8);
        throw new IllegalArgumentException("Không tìm thấy skills_matrix.json của đề " + examId);
    }

    /**
     * Danh sách đề có testcase — gộp đề đã cấu hình trong DB và các thư mục đề trên đĩa
     * (exams/&lt;id&gt;/testcase/skills_matrix.json), để đánh giá độ phủ ngay cả khi chưa upload.
     */
    public List<Map<String, Object>> listExams() {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();

        // 1) Đề trong DB
        for (Exam e : examRepository.findAll()) {
            boolean hasTc = e.getTestcasePath() != null && !e.getTestcasePath().isBlank()
                    && Files.exists(Path.of(e.getTestcasePath()).resolve("skills_matrix.json"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("examId", e.getExamId());
            m.put("examName", e.getExamName() != null ? e.getExamName() : e.getExamId());
            m.put("teacherNote", e.getTeacherNote() != null ? e.getTeacherNote() : "");
            m.put("status", e.getStatus() != null ? e.getStatus().name() : null);
            m.put("testcaseStatus", e.getTestcaseStatus() != null ? e.getTestcaseStatus() : "DRAFT");
            m.put("testcaseVersion", e.getTestcaseVersion());
            m.put("hasTestcase", hasTc);
            // Mở được màn builder khi có cấu hình, HOẶC matrix còn template_id để dựng lại cấu hình.
            // Testcase viết tay không có template_id → sửa bằng trình sửa file thay vì builder.
            m.put("editable", false);
            m.put("configRecovered", false);
            m.put("fileEditable", false);
            byId.put(e.getExamId(), m);
        }

        // 2) Thư mục đề trên đĩa
        try {
            Path root = examsRoot();
            if (Files.isDirectory(root)) {
                try (Stream<Path> s = Files.list(root)) {
                    for (Path d : s.filter(Files::isDirectory).toList()) {
                        if (!Files.exists(d.resolve("testcase").resolve("skills_matrix.json"))) continue;
                        String id = d.getFileName().toString();
                        Map<String, Object> existing = byId.get(id);
                        if (existing != null) { existing.put("hasTestcase", true); continue; }
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("examId", id);
                        m.put("examName", id);
                        m.put("status", "ON_DISK");
                        m.put("testcaseStatus", "PUBLISHED");
                        m.put("hasTestcase", true);
                        // Chỉ có thư mục trên đĩa: vẫn sửa được — hệ thống tự đăng ký bản ghi
                        // ngay lần thao tác đầu tiên (xem ensureExamRecord).
                        m.put("editable", false);
                        m.put("configRecovered", false);
                        m.put("fileEditable", false);
                        byId.put(id, m);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Quét thư mục exams lỗi: {}", e.getMessage());
        }

        // Bổ sung số bài đã nộp + có sẵn đề bài/starter để tải hay không (cho trang Kho đề)
        for (Map<String, Object> m : byId.values()) {
            String id = String.valueOf(m.get("examId"));
            try { m.put("resultCount", resultRepository.findSubmitStudentIds(id).size()); }
            catch (Exception e) { m.put("resultCount", 0); }
            Path h = handoutDirOf(id);
            m.put("hasDeBai", Files.exists(h.resolve("de_bai.md")));
            m.put("hasStarter", Files.isDirectory(h.resolve("starter")));
            m.put("hasSolution", Files.isDirectory(h.resolve("solution")));
        }

        List<Map<String, Object>> out = new ArrayList<>(byId.values());
        out.sort((a, b) -> String.valueOf(a.get("examId")).compareTo(String.valueOf(b.get("examId"))));
        return out;
    }

    /**
     * Đọc các file testcase của 1 ĐỀ (theo examId) để xem/đối chiếu trong trang Kho đề:
     * exam_test.dart, skills_matrix.json, grader.dart... Ưu tiên testcasePath trong DB, fallback đĩa.
     */
    public List<Map<String, String>> readExamTestcaseFiles(String examId) {
        safeId(examId, "đề");
        List<Map<String, String>> out = new ArrayList<>();
        Path dir = null;
        Exam exam = examRepository.findByExamId(examId).orElse(null);
        if (exam != null && exam.getTestcasePath() != null && !exam.getTestcasePath().isBlank()
                && Files.exists(Path.of(exam.getTestcasePath()))) {
            dir = Path.of(exam.getTestcasePath());
        } else {
            Path disk = examsRoot().resolve(examId).resolve("testcase");
            if (Files.exists(disk)) dir = disk;
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
                String content = Files.readString(p, StandardCharsets.UTF_8);
                if (content.length() > MAX_BYTES) content = content.substring(0, MAX_BYTES) + "\n… (đã cắt bớt)";
                Map<String, String> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("content", content);
                out.add(m);
            }
        } catch (Exception e) {
            log.warn("Đọc testcase đề {} lỗi: {}", examId, e.getMessage());
        }
        out.sort((a, b) -> rankTestcaseFile(a.get("name")) - rankTestcaseFile(b.get("name")));
        return out;
    }

    /** exam_test.dart → skills_matrix.json → grader.dart → còn lại (cho dễ đọc trên UI). */
    private int rankTestcaseFile(String name) {
        String n = name.toLowerCase();
        if (n.endsWith("exam_test.dart"))     return 0;
        if (n.endsWith("skills_matrix.json")) return 1;
        if (n.endsWith("grader.dart"))        return 2;
        return 3;
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  BỘ PHÁT CHO SV (handout = đề bài + khung starter) — LƯU RIÊNG ngoài testcase/
    //  testcase/ được MOUNT vào container lúc chấm → nhét file lạ vào sẽ lẫn vào bài chấm,
    //  nên đề bài & starter lưu ở <exams>/<examId>/handout/ (KHÔNG mount).
    // ════════════════════════════════════════════════════════════════════════════

    /** Thư mục testcase của 1 đề (ưu tiên DB, fallback đĩa); null nếu chưa có. */
    private Path testcaseDirOf(String examId) {
        Exam exam = examRepository.findByExamId(examId).orElse(null);
        if (exam != null && exam.getTestcasePath() != null && !exam.getTestcasePath().isBlank()
                && Files.exists(Path.of(exam.getTestcasePath()))) return Path.of(exam.getTestcasePath());
        Path disk = examsRoot().resolve(examId).resolve("testcase");
        return Files.exists(disk) ? disk : null;
    }

    /** Ba file bắt buộc + hai file hợp đồng — chỉ những file này được sửa trực tiếp. */
    private static final Set<String> EDITABLE_TESTCASE_FILES = Set.of(
            "exam_test.dart", "grader.dart", "skills_matrix.json", "contract.json", "contract.md");

    /** Trần an toàn cho trình sửa; file testcase thật lớn nhất mới ~150 KB. */
    private static final long MAX_EDITABLE_FILE_BYTES = 2L * 1024 * 1024;

    /**
     * Đọc file testcase để SỬA — khác {@link #readExamTestcaseFiles} ở chỗ trả về NGUYÊN VẸN.
     *
     * <p>Hàm đọc để XEM cắt nội dung ở 200.000 ký tự cho nhẹ trang; nếu trình sửa dùng chung hàm
     * đó thì giáo viên bấm Lưu là ghi đè bản đã bị cắt — mất trắng phần đuôi của file mà không
     * có dấu hiệu gì. File quá lớn thì báo lỗi thay vì đưa ra bản cụt.
     */
    public List<Map<String, String>> readEditableTestcaseFiles(String examId) {
        safeId(examId, "đề");
        Path dir = testcaseDirOf(examId);
        List<Map<String, String>> out = new ArrayList<>();
        if (dir == null) return out;
        for (String name : List.of("exam_test.dart", "skills_matrix.json", "grader.dart",
                "contract.json", "contract.md")) {
            Path file = dir.resolve(name);
            if (!Files.isRegularFile(file)) continue;
            try {
                if (Files.size(file) > MAX_EDITABLE_FILE_BYTES)
                    throw new IllegalStateException(name + " lớn hơn 2 MB nên không mở trong trình sửa được; "
                            + "hãy sửa trực tiếp trên đĩa rồi tải lại.");
                Map<String, String> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("content", Files.readString(file, StandardCharsets.UTF_8));
                out.add(m);
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Không đọc được " + name + ": " + e.getMessage(), e);
            }
        }
        return out;
    }

    /**
     * Ghi đè file testcase của một bộ — dùng được cho MỌI bộ, kể cả bộ dựng bằng builder.
     *
     * <p>Bộ dựng bằng builder vẫn sinh lại toàn bộ file mỗi lần bấm Lưu trong màn "Tạo bộ testcase",
     * nên sửa tay ở đây có thể bị ghi đè. Trước đây trường hợp đó bị CHẶN; giờ chỉ cảnh báo, vì
     * chặn hẳn thì không còn cách nào vá nhanh một dòng trong exam_test.dart đang chấm dở.
     *
     * <p>Bản cũ luôn được snapshot trước khi ghi, để sửa hỏng còn đường đối chiếu. Lưu thành công
     * = bộ chuyển sang PUBLISHED (Hoàn tất) và dựng lại sandbox để chấm được ngay.
     *
     * @return { files: tên file đã ghi, status, warning }
     */
    public synchronized Map<String, Object> saveExamTestcaseFiles(String examId, List<Map<String, String>> files) {
        safeId(examId, "đề");
        Exam exam = ensureExamRecord(examId);   // bộ mới chỉ có trên đĩa vẫn sửa được
        Path dir = testcaseDirOf(examId);
        if (dir == null)
            throw new IllegalStateException("Bộ " + examId + " chưa có thư mục testcase trên đĩa.");
        // Bộ mở được bằng builder (có config, hoặc dựng lại được từ skills_matrix.json) thì lần Lưu
        // kế tiếp trong builder sẽ sinh đè file sửa tay — nói trước để giáo viên tự chọn đường sửa.
        if (files == null || files.isEmpty())
            throw new IllegalArgumentException("Không có nội dung file nào để lưu.");

        // Kiểm tra TOÀN BỘ trước khi ghi: ghi được nửa chừng rồi mới phát hiện JSON hỏng là để lại
        // một bộ testcase không chấm được.
        Map<String, String> pending = new LinkedHashMap<>();
        for (Map<String, String> f : files) {
            String name = f == null ? null : f.get("name");
            String content = f == null ? null : f.get("content");
            if (name == null || name.isBlank() || content == null) continue;
            String clean = name.trim().replace('\\', '/');
            if (!EDITABLE_TESTCASE_FILES.contains(clean))
                throw new IllegalArgumentException("Chỉ sửa được các file: "
                        + String.join(", ", EDITABLE_TESTCASE_FILES) + " (nhận được: " + name + ")");
            if (clean.endsWith(".json")) {
                try {
                    mapper.readTree(content);
                } catch (Exception e) {
                    throw new IllegalArgumentException(clean + " không phải JSON hợp lệ: " + e.getMessage());
                }
            }
            pending.put(clean, content);
        }
        if (pending.isEmpty()) throw new IllegalArgumentException("Không có file hợp lệ nào để lưu.");
        String matrixWarning = pending.containsKey("skills_matrix.json")
                ? checkEditedSkillsMatrix(dir.resolve("skills_matrix.json"), pending.get("skills_matrix.json"))
                : null;

        snapshotCurrentTestcase(examId);
        List<String> written = new ArrayList<>();
        try {
            for (Map.Entry<String, String> e : pending.entrySet()) {
                Files.writeString(dir.resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
                written.add(e.getKey());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Ghi file testcase thất bại: " + e.getMessage(), e);
        }

        // Bấm Lưu = chốt bản chính thức: bản nháp (bộ vừa clone) chuyển sang Hoàn tất và
        // được dựng sandbox ngay, đúng như luồng Lưu bên builder.
        exam.setTestcaseStatus("PUBLISHED");
        exam.setTestcaseVersion(exam.getTestcaseVersion() == null ? 1 : exam.getTestcaseVersion() + 1);
        exam.setTestcasePublishedAt(Instant.now());
        examRepository.save(exam);

        String sandboxWarning = null;
        try { buildSandbox(examId); }
        catch (Exception e) {
            // Docker tắt thì file đã lưu xong rồi; chỉ là chưa chấm được cho tới khi dựng lại.
            sandboxWarning = "Đã lưu file nhưng chưa dựng được sandbox: " + e.getMessage();
            log.warn("Build sandbox sau khi sửa file của {} lỗi: {}", examId, e.getMessage());
        }

        log.info("✏️ Đã sửa {} file testcase của bộ {}", written.size(), examId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("files", written);
        out.put("status", "PUBLISHED");
        String warning = ((matrixWarning == null ? "" : matrixWarning + " ")
                + (sandboxWarning == null ? "" : sandboxWarning)).trim();
        if (!warning.isBlank()) out.put("warning", warning);
        return out;
    }

    /**
     * Nhân bản một bộ NHẬP TỪ ZIP: chép nguyên thư mục testcase + bộ phát cho SV rồi tạo bản ghi
     * mới. Bộ dựng bằng builder đi đường khác (clone cấu hình rồi sinh lại file).
     */
    public synchronized Map<String, Object> cloneImportedExam(String rawSourceId, String rawTargetId,
                                                              String examName, String teacherNote, String actor) {
        String sourceId = safeId(rawSourceId, "bộ testcase nguồn");
        String targetId = safeId(rawTargetId, "bộ testcase mới");
        if (targetId.length() > 50)
            throw new IllegalArgumentException("Mã bộ testcase mới không được dài quá 50 ký tự.");
        if (sourceId.equalsIgnoreCase(targetId))
            throw new IllegalArgumentException("Mã bộ testcase bản sao phải khác mã bộ nguồn.");
        if (examName == null || examName.isBlank())
            throw new IllegalArgumentException("Vui lòng nhập tên bộ testcase bản sao.");
        if (examRepository.existsByExamId(targetId))
            throw new IllegalStateException("Mã bộ testcase " + targetId + " đã tồn tại.");

        Exam source = ensureExamRecord(sourceId);   // bộ mới chỉ có trên đĩa vẫn nhân bản được
        Path sourceDir = testcaseDirOf(sourceId);
        if (sourceDir == null)
            throw new IllegalStateException("Bộ " + sourceId + " không còn thư mục testcase để nhân bản.");

        Path targetExamDir = examsRoot().resolve(targetId);
        if (Files.exists(targetExamDir))
            throw new IllegalStateException("Thư mục của bộ testcase " + targetId + " đã tồn tại.");
        Path targetDir = targetExamDir.resolve("testcase");

        try {
            copyTree(sourceDir, targetDir);
        } catch (Exception e) {
            deleteQuietly(targetExamDir);
            throw new IllegalStateException("Không chép được thư mục testcase: " + e.getMessage(), e);
        }

        Exam clone = new Exam();
        clone.setExamId(targetId);
        clone.setExamName(examName.trim());
        clone.setTeacherNote(teacherNote == null ? "" : teacherNote.trim());
        clone.setTestcasePath(targetDir.toAbsolutePath().normalize().toString());
        clone.setAllowedPackages(source.getAllowedPackages());
        clone.setStatus(ExamStatus.BUILDING);          // sandbox dựng lại khi bấm Lưu
        // Bản sao là NHÁP cho tới khi người dùng bấm Lưu trong trình sửa file — giống hệt
        // luồng clone của bộ dựng bằng builder, để hai loại bộ không hành xử khác nhau.
        clone.setTestcaseStatus("DRAFT");
        clone.setTestcaseVersion(1);
        clone.setCreatedBy(actor);
        try {
            examRepository.save(clone);
        } catch (Exception e) {
            deleteQuietly(targetExamDir);
            throw new IllegalStateException("Không lưu được bộ testcase bản sao: " + e.getMessage(), e);
        }

        try { cloneHandout(sourceId, targetId); }
        catch (Exception e) { log.warn("Chép bộ phát SV từ {} sang {} lỗi: {}", sourceId, targetId, e.getMessage()); }

        // KHÔNG dựng sandbox ở đây: bản sao còn là nháp, sandbox sẽ dựng lúc bấm Lưu
        // (xem saveExamTestcaseFiles) — dựng sớm chỉ tốn thời gian cho bản có thể bị sửa tiếp.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exam_id", targetId);
        out.put("exam_name", clone.getExamName());
        out.put("source_exam_id", sourceId);
        out.put("editable", false);      // vẫn là bộ file, không có cấu hình builder
        out.put("status", "DRAFT");
        log.info("📑 Đã nhân bản bộ testcase {} → {} (nháp)", sourceId, targetId);
        return out;
    }

    /**
     * Lấy bản ghi của một bộ testcase, TỰ ĐĂNG KÝ nếu nó mới chỉ có thư mục trên đĩa.
     *
     * <p>Thư mục {@code exams/<id>/testcase} có thể tồn tại mà không có hàng trong bảng exams
     * (chép tay vào, hoặc bản ghi bị xoá lúc dọn dẹp). Trước đây những bộ đó xem được nhưng
     * không sửa/đổi tên/nhân bản được. Nhận nuôi ngay lần thao tác đầu tiên để mọi bộ trong Kho
     * đều dùng được như nhau, thay vì bắt giáo viên xoá đi nhập lại.
     */
    public synchronized Exam ensureExamRecord(String examId) {
        safeId(examId, "đề");
        Exam existing = examRepository.findByExamId(examId).orElse(null);
        if (existing != null) return existing;

        Path dir = examsRoot().resolve(examId).resolve("testcase");
        if (!Files.exists(dir.resolve("skills_matrix.json")))
            throw new IllegalArgumentException("Không tìm thấy bộ testcase: " + examId);

        Exam adopted = new Exam();
        adopted.setExamId(examId);
        adopted.setExamName(examId);
        adopted.setTeacherNote("");
        adopted.setTestcasePath(dir.toAbsolutePath().normalize().toString());
        adopted.setStatus(ExamStatus.BUILDING);      // sandbox dựng lại khi cần chấm
        adopted.setTestcaseStatus("PUBLISHED");
        adopted.setTestcaseVersion(1);
        adopted.setTestcasePublishedAt(Instant.now());
        Exam saved = examRepository.save(adopted);
        log.info("📥 Đã đưa bộ testcase {} (chỉ có trên đĩa) vào hệ thống để sửa được", examId);
        return saved;
    }

    /** Chép nguyên cây thư mục; chặn đường dẫn thoát ra ngoài đích khi tên file bất thường. */
    private void copyTree(Path source, Path target) throws Exception {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path output = target.resolve(source.relativize(path)).normalize();
                if (!output.startsWith(target))
                    throw new IllegalStateException("Đường dẫn không hợp lệ khi chép: " + path);
                if (Files.isDirectory(path)) Files.createDirectories(output);
                else {
                    Files.createDirectories(output.getParent());
                    Files.copy(path, output, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Dọn thư mục vừa tạo dở khi một bước sau đó thất bại; lỗi dọn không được che lỗi gốc. */
    private void deleteQuietly(Path dir) {
        try { deleteRecursively(dir); }
        catch (Exception e) { log.warn("Không dọn được thư mục {}: {}", dir, e.getMessage()); }
    }

    /** Sao chép snapshot testcase hiện tại trước khi Publish để đề cũ vẫn đối chiếu được. */
    public boolean snapshotCurrentTestcase(String examId) {
        safeId(examId, "đề");
        Path source = testcaseDirOf(examId);
        if (source == null || !Files.isDirectory(source)) return false;
        Path target = source.resolveSibling("testcase-archive")
                .resolve(String.valueOf(Instant.now().toEpochMilli()));
        try {
            Files.createDirectories(target);
            try (Stream<Path> walk = Files.walk(source)) {
                for (Path p : walk.filter(Files::isRegularFile).toList()) {
                    Path out = target.resolve(source.relativize(p));
                    Files.createDirectories(out.getParent());
                    Files.copy(p, out);
                }
            }
            log.info("🧊 Đã snapshot testcase trước Publish của {} → {}", examId, target);
            return true;
        } catch (Exception e) {
            log.warn("Không snapshot được testcase của {}: {}", examId, e.getMessage());
            return false;
        }
    }

    /** Thư mục handout (đề bài + starter) của 1 đề — luôn nằm CẠNH testcase/ (<exams>/<id>/handout). */
    private Path handoutDirOf(String examId) {
        Exam exam = examRepository.findByExamId(examId).orElse(null);
        if (exam != null && exam.getTestcasePath() != null && !exam.getTestcasePath().isBlank()) {
            Path parent = Path.of(exam.getTestcasePath()).getParent();
            if (parent != null) return parent.resolve("handout");
        }
        return examsRoot().resolve(examId).resolve("handout");
    }

    /**
     * Sao chép nguyên bộ phát cho sinh viên khi nhân bản tài liệu của một đề.
     * Golden suite được quản lý riêng; hàm này chỉ giữ kèm đề bài, starter và lời giải mẫu.
     */
    public void cloneHandout(String sourceExamId, String targetExamId) throws Exception {
        safeId(sourceExamId, "bộ testcase nguồn");
        safeId(targetExamId, "bộ testcase mới");
        Path source = handoutDirOf(sourceExamId);
        if (!Files.isDirectory(source)) return;

        Path target = handoutDirOf(targetExamId);
        if (Files.exists(target)) deleteRecursively(target);
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path output = target.resolve(source.relativize(path)).normalize();
                if (!output.startsWith(target))
                    throw new IllegalStateException("Đường dẫn handout không hợp lệ khi clone.");
                if (Files.isDirectory(path)) Files.createDirectories(output);
                else {
                    Files.createDirectories(output.getParent());
                    Files.copy(path, output, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Lưu BỘ PHÁT CHO SV của 1 đề: đề bài (de_bai.md) + khung starter (lib/…). Gọi SAU khi
     * {@link #setupExamFromZipBytes} thành công. Ghi đè bản cũ. Lỗi ghi handout KHÔNG làm hỏng đề
     * (testcase đã lưu xong) — chỉ log cảnh báo.
     *   <exams>/<examId>/handout/de_bai.md
     *   <exams>/<examId>/handout/starter/<lib/...>
     *   <exams>/<examId>/handout/solution/<lib/...>   (lời giải mẫu để GV tải về tham khảo)
     */
    public void saveHandout(String examId, String deBai, List<Map<String, String>> starter,
                            List<Map<String, String>> solution) {
        safeId(examId, "đề");
        try {
            Path handout = handoutDirOf(examId);
            if (Files.exists(handout)) deleteRecursively(handout);   // làm mới (đề có thể lưu lại nhiều lần)

            boolean any = false;
            if (deBai != null && !deBai.isBlank()) {
                Files.createDirectories(handout);
                Files.writeString(handout.resolve("de_bai.md"), deBai, StandardCharsets.UTF_8);
                any = true;
            }
            any |= writeHandoutFiles(handout.resolve("starter"), starter);     // khung code phát SV
            any |= writeHandoutFiles(handout.resolve("solution"), solution);   // lời giải mẫu (KHÔNG phát SV)
            if (any) log.info("📄 Đã lưu bộ phát SV (đề bài + starter + lời giải mẫu) cho đề {} → {}", examId, handout);
        } catch (IllegalArgumentException e) {
            throw e;   // tên file xấu → để caller biết (không nuốt lỗi bảo mật)
        } catch (Exception e) {
            log.warn("Lưu handout cho đề {} lỗi: {} — bỏ qua (testcase vẫn lưu được).", examId, e.getMessage());
        }
    }

    /**
     * Lưu ĐỀ BÀI + HÌNH MINH HỌA giao diện do trợ lý AI soạn, KHÔNG đụng tới starter/solution
     * đã có (khác {@link #saveHandout} vốn làm mới cả thư mục handout).
     *
     * <p>Hình lưu ở {@code handout/mockup/<id>.svg} và được nhúng vào cuối de_bai.md bằng đường
     * dẫn tương đối, nên bản đề tải về vẫn xem được hình khi giải nén cùng thư mục.
     *
     * @return danh sách tên file đã ghi
     */
    public List<String> saveDeBaiWithMockups(String examId, String deBai,
                                             List<Map<String, String>> mockups) throws Exception {
        safeId(examId, "đề");
        if ((deBai == null || deBai.isBlank()) && (mockups == null || mockups.isEmpty()))
            throw new IllegalArgumentException("Không có nội dung đề bài để lưu.");

        Path handout = handoutDirOf(examId);
        Files.createDirectories(handout);
        List<String> written = new ArrayList<>();

        Path mockupDir = handout.resolve("mockup");
        if (mockups != null && !mockups.isEmpty()) {
            if (Files.exists(mockupDir)) deleteRecursively(mockupDir);   // bản vẽ cũ không được lẫn vào
            Files.createDirectories(mockupDir);
            for (Map<String, String> m : mockups) {
                String id = m == null ? null : m.get("id");
                String svg = m == null ? null : m.get("svg");
                if (id == null || id.isBlank() || svg == null || svg.isBlank()) continue;
                String name = id.toLowerCase().replaceAll("[^a-z0-9_-]", "-") + ".svg";
                Path out = mockupDir.resolve(name).normalize();
                if (!out.startsWith(mockupDir))
                    throw new IllegalArgumentException("Tên hình không hợp lệ: " + id);
                Files.writeString(out, svg, StandardCharsets.UTF_8);
                written.add("mockup/" + name);
            }
        }

        if (deBai != null && !deBai.isBlank()) {
            Files.writeString(handout.resolve("de_bai.md"), deBai, StandardCharsets.UTF_8);
            written.add("de_bai.md");
        }

        // Bản GỘP: đề bài + hình minh họa trong MỘT file tự chứa, để phát cho sinh viên hay in
        // ra chỉ cần cầm đúng một file. .md và .svg vẫn giữ vì đó là bản nguồn để sửa tiếp.
        Files.writeString(handout.resolve("de_bai.html"),
                buildHandoutHtml(examId), StandardCharsets.UTF_8);
        written.add("de_bai.html");

        log.info("📄 Đã lưu đề bài + {} hình minh họa cho đề {}", written.size(), examId);
        return written;
    }

    /** Ghép đề bài (.md) và toàn bộ hình (.svg) đang có trên đĩa thành một trang HTML tự chứa. */
    public String buildHandoutHtml(String examId) throws Exception {
        safeId(examId, "đề");
        Exam exam = examRepository.findByExamId(examId).orElse(null);
        String md = readDeBai(examId);
        return HandoutDocument.toHtml(examId,
                exam == null ? null : exam.getExamName(),
                md == null ? "" : md,
                readMockups(examId));
    }

    /** Hình minh họa của một bộ, sắp theo tên file để thứ tự luôn ổn định. */
    public List<HandoutDocument.Mockup> readMockups(String examId) throws Exception {
        safeId(examId, "đề");
        Path dir = handoutDirOf(examId).resolve("mockup");
        List<HandoutDocument.Mockup> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".svg"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList()) {
                String id = f.getFileName().toString().replaceFirst("\\.svg$", "");
                String svg = Files.readString(f, StandardCharsets.UTF_8);
                // Tiêu đề hình đã được vẽ trong SVG; ở đây chỉ cần một tên đọc được.
                out.add(new HandoutDocument.Mockup(id, id.replace('-', ' '), svg));
            }
        }
        return out;
    }

    /**
     * Dựng bản .docx tải về: đề bài + hình minh họa.
     *
     * @param images ảnh PNG do TRÌNH DUYỆT đổi từ SVG ({@code {id, png_base64, width, height}}).
     *               Máy chủ không có thư viện rasterize SVG, mà Word thì không hiện SVG ổn định —
     *               nên phần vẽ ảnh giao cho trình duyệt, nơi vốn đã hiển thị đúng hình đó.
     *               Bỏ trống = bản .docx chỉ có chữ.
     */
    public byte[] buildHandoutDocx(String examId, List<Map<String, Object>> images) throws Exception {
        safeId(examId, "đề");
        Exam exam = examRepository.findByExamId(examId).orElse(null);
        String md = readDeBai(examId);
        if ((md == null || md.isBlank()) && (images == null || images.isEmpty()))
            throw new IllegalArgumentException("Bộ " + examId + " chưa có đề bài để tải về.");

        DocxWriter docx = new DocxWriter();
        docx.heading(exam != null && exam.getExamName() != null && !exam.getExamName().isBlank()
                ? exam.getExamName() : examId, 1);
        docx.paragraph("Mã bộ testcase: " + examId);

        int ordered = 0;
        for (HandoutDocument.Block block : HandoutDocument.parse(md == null ? "" : md)) {
            switch (block.type()) {
                case "h1", "h2" -> { docx.heading(block.text(), 2); ordered = 0; }
                case "h3" -> { docx.heading(block.text(), 3); ordered = 0; }
                case "li" -> { docx.bullet(block.text(), false, 0); ordered = 0; }
                case "ol" -> docx.bullet(block.text(), true, ++ordered);
                case "code" -> { docx.code(block.text()); ordered = 0; }
                default -> { docx.paragraph(block.text()); ordered = 0; }
            }
        }

        if (images != null && !images.isEmpty()) {
            docx.heading("Hình minh họa giao diện", 2);
            for (Map<String, Object> image : images) {
                if (image == null) continue;
                String base64 = String.valueOf(image.getOrDefault("png_base64", ""));
                if (base64.isBlank()) continue;
                // Trình duyệt gửi data URI hay chuỗi base64 thuần đều nhận.
                int comma = base64.indexOf(',');
                if (base64.startsWith("data:") && comma > 0) base64 = base64.substring(comma + 1);
                byte[] png;
                try { png = java.util.Base64.getDecoder().decode(base64.trim()); }
                catch (Exception e) { continue; }
                docx.image(png, (int) toDouble(image.get("width"), 0), (int) toDouble(image.get("height"), 0));
            }
        }
        return docx.build();
    }

    private double toDouble(Object value, double fallback) {
        if (value instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (Exception e) { return fallback; }
    }

    /**
     * Lưu KHUNG STARTER (lib/…) phát cho sinh viên. Chỉ làm mới thư mục {@code handout/starter},
     * giữ nguyên đề bài, hình minh họa và lời giải mẫu đã lưu trước đó.
     *
     * @param files danh sách {name, content}; {@code name} là đường dẫn tương đối kiểu lib/…
     * @return tên các file đã ghi
     */
    public List<String> saveStarterFiles(String examId, List<Map<String, String>> files) throws Exception {
        safeId(examId, "đề");
        if (files == null || files.isEmpty())
            throw new IllegalArgumentException("Không có file starter nào để lưu.");

        Path starter = handoutDirOf(examId).resolve("starter");
        // Kiểm tên TRƯỚC khi động vào đĩa: nếu để writeHandoutFiles ném giữa chừng thì khung cũ
        // đã bị xoá và khung mới mới ghi được một nửa — giáo viên nhận về bộ starter thiếu file.
        for (Map<String, String> f : files) {
            String name = f == null ? null : f.get("name");
            if (name == null || name.isBlank()) continue;
            if (!starter.resolve(name).normalize().startsWith(starter))
                throw new IllegalArgumentException("Tên file không hợp lệ: " + name);
        }

        if (Files.exists(starter)) deleteRecursively(starter);   // khung cũ không được lẫn vào khung mới
        Files.createDirectories(starter);
        if (!writeHandoutFiles(starter, files))
            throw new IllegalArgumentException("Không có file starter hợp lệ để lưu.");

        List<String> written = new ArrayList<>();
        for (Map<String, String> f : files) {
            String name = f == null ? null : f.get("name");
            if (name != null && !name.isBlank()) written.add(name);
        }
        log.info("📦 Đã lưu khung starter ({} file) cho đề {}", written.size(), examId);
        return written;
    }

    /**
     * Ghi danh sách file {name, content} (lib/…) vào 1 thư mục con của handout (starter/ hoặc solution/).
     * Chặn path traversal ('../') trong tên file. Trả {@code true} nếu có ghi ít nhất 1 file.
     */
    private boolean writeHandoutFiles(Path subRoot, List<Map<String, String>> files) throws Exception {
        if (files == null) return false;
        boolean any = false;
        for (Map<String, String> f : files) {
            String name = f == null ? null : f.get("name");
            if (name == null || name.isBlank()) continue;
            Path out = subRoot.resolve(name).normalize();
            if (!out.startsWith(subRoot))   // chặn path traversal ('../') trong tên file
                throw new IllegalArgumentException("Tên file không hợp lệ: " + name);
            Files.createDirectories(out.getParent());
            Files.writeString(out, f.getOrDefault("content", ""), StandardCharsets.UTF_8);
            any = true;
        }
        return any;
    }

    /**
     * ZIP testcase có thể tải về rồi upload lại mà không đổi hành vi chấm.
     *
     * <p>Ba file thực thi chưa đủ với common engine vì {@code contract.json} còn quyết định
     * có bắt buộc ValueKey hay không và policy package của bài sinh viên. Bộ cũ chưa có
     * contract được xuất kèm một contract tương thích với hành vi fallback hiện tại.</p>
     */
    public byte[] zipTestcase(String examId) throws Exception {
        safeId(examId, "đề");
        Path dir = testcaseDirOf(examId);
        if (dir == null) return null;
        Map<String, String> files = new LinkedHashMap<>();
        for (String f : List.of("exam_test.dart", "grader.dart", "skills_matrix.json")) {
            Path p = dir.resolve(f);
            if (!Files.isRegularFile(p)) return null;
            files.put(f, Files.readString(p, StandardCharsets.UTF_8));
        }
        Path contract = dir.resolve("contract.json");
        files.put("contract.json", Files.exists(contract)
                ? Files.readString(contract, StandardCharsets.UTF_8)
                : "{\n  \"schema_version\": 1,\n  \"require_keys\": false\n}\n");
        return zipBytes(files);
    }

    /** Đọc đề bài (de_bai.md) đã lưu trong handout/; null nếu đề chưa có (đề cũ / upload tay). */
    public String readDeBai(String examId) throws Exception {
        safeId(examId, "đề");
        Path f = handoutDirOf(examId).resolve("de_bai.md");
        return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
    }

    /** ZIP khung starter (handout/starter/<lib/…>) để phát SV; null nếu đề chưa có starter. */
    public byte[] zipStarter(String examId) throws Exception {
        return zipHandoutSubdir(examId, "starter");
    }

    /** ZIP lời giải mẫu (handout/solution/<lib/…>) để GV tải về tham khảo; null nếu đề chưa có. */
    public byte[] zipSolution(String examId) throws Exception {
        return zipHandoutSubdir(examId, "solution");
    }

    /** ZIP toàn bộ file trong 1 thư mục con của handout (starter/ hoặc solution/); null nếu thư mục trống/không có. */
    private byte[] zipHandoutSubdir(String examId, String sub) throws Exception {
        safeId(examId, "đề");
        Path root = handoutDirOf(examId).resolve(sub);
        if (!Files.isDirectory(root)) return null;
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).toList())
                files.put(root.relativize(p).toString().replace('\\', '/'), Files.readString(p, StandardCharsets.UTF_8));
        }
        return files.isEmpty() ? null : zipBytes(files);
    }

    private byte[] zipBytes(Map<String, String> files) throws Exception {
        var bos = new java.io.ByteArrayOutputStream();
        try (var zos = new java.util.zip.ZipOutputStream(bos)) {
            for (var e : files.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .toList()) {
                var entry = new java.util.zip.ZipEntry(e.getKey());
                // Không để thời điểm tải xuống làm hai ZIP có cùng nội dung khác checksum.
                entry.setTime(0L);
                zos.putNextEntry(entry);
                zos.write((e.getValue() == null ? "" : e.getValue()).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /**
     * Nhập bộ testcase viết thủ công từ ZIP. Tên/mã lấy từ tên file, ZIP chỉ dùng để vận chuyển
     * rồi bị bỏ; trên đĩa giữ ba file thực thi và contract đã cung cấp hoặc được backend tự sinh
     * để Build Sandbox mount trực tiếp.
     */
    public synchronized Map<String, Object> importManualTestcase(
            String originalFilename, String teacherNote, byte[] zipBytes, String actor) throws Exception {
        String examName = manualTestcaseName(originalFilename);
        String examId = manualTestcaseId(examName);
        if (zipBytes == null || zipBytes.length == 0)
            throw new IllegalArgumentException("File ZIP testcase đang rỗng.");
        if (zipBytes.length > MAX_TESTCASE_ZIP_BYTES)
            throw new IllegalArgumentException("File ZIP testcase vượt quá giới hạn 20 MB.");
        if (examRepository.findByExamId(examId).isPresent())
            throw new IllegalStateException("Mã bộ testcase " + examId
                    + " đã tồn tại. Hãy đổi tên file ZIP rồi thử lại.");

        Path root = examsRoot();
        Files.createDirectories(root);
        Path examDir = root.resolve(examId);
        if (Files.exists(examDir))
            throw new IllegalStateException("Thư mục của bộ testcase " + examId
                    + " đã tồn tại. Hãy đổi tên file ZIP hoặc dọn thư mục cũ.");

        Path staging = Files.createTempDirectory(root, "." + examId + "-import-");
        Path testcaseDir = examDir.resolve("testcase");
        try {
            unzip(zipBytes, staging);
            validateRequiredFiles(staging);
            ensurePortableContract(staging);
            validateSkillCodes(staging);

            Files.createDirectories(examDir);
            try {
                Files.move(staging, testcaseDir, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staging, testcaseDir);
            }
            Exam exam = new Exam();
            exam.setExamId(examId);
            exam.setExamName(examName);
            exam.setTeacherNote(teacherNote == null ? "" : teacherNote.trim());
            exam.setTestcasePath(testcaseDir.toAbsolutePath().normalize().toString());
            exam.setStatus(ExamStatus.BUILDING);
            exam.setTestcaseStatus("PUBLISHED");
            exam.setTestcaseVersion(1);
            exam.setTestcasePublishedAt(Instant.now());
            exam.setCreatedBy(actor);
            examRepository.save(exam);

            // Nhập xong là chuẩn bị sandbox luôn (không còn nút Build Sandbox thủ công). Docker
            // tắt thì vẫn giữ được bộ testcase vừa nhập — lúc chấm sẽ thử chuẩn bị lại.
            String status = "BUILDING";
            try {
                buildSandbox(examId);
                status = "READY";
            } catch (Exception e) {
                log.warn("Không chuẩn bị được sandbox cho {} ngay sau khi nhập ZIP: {}", examId, e.getMessage());
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("examId", examId);
            out.put("examName", examName);
            out.put("status", status);
            out.put("testcaseStatus", "PUBLISHED");
            out.put("hasTestcase", true);
            return out;
        } catch (Exception e) {
            try { deleteRecursively(staging); } catch (Exception ignored) {}
            try { deleteRecursively(examDir); } catch (Exception ignored) {}
            throw e;
        }
    }

    private String manualTestcaseName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank())
            throw new IllegalArgumentException("Không đọc được tên file ZIP.");
        String filename = originalFilename.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1).trim();
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".zip"))
            throw new IllegalArgumentException("Chỉ chấp nhận file testcase định dạng .zip.");
        String name = filename.substring(0, filename.length() - 4).trim();
        if (name.isBlank()) throw new IllegalArgumentException("Tên file ZIP không hợp lệ.");
        return name;
    }

    private String manualTestcaseId(String examName) {
        String ascii = Normalizer.normalize(examName, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd')
                .replace('Đ', 'D');
        String id = ascii.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^[_-]+|[_-]+$", "");
        if (id.length() > 60) id = id.substring(0, 60).replaceAll("[_-]+$", "");
        return safeId(id, "bộ testcase");
    }

    // ── Setup đề: chỉ lưu testcase (mount lúc chấm), KHÔNG build image ──
    public ExamSetupResponse setupExam(String examId, String examName,
                                       String teacherNote, MultipartFile testcaseZip) throws Exception {
        return setupExamFromZipBytes(examId, examName, teacherNote, testcaseZip.getBytes());
    }

    /** Như {@link #setupExam} nhưng nhận thẳng bytes của file zip để tái dùng pipeline validate hiện có. */
    public ExamSetupResponse setupExamFromZipBytes(String examId, String examName,
                                                   String teacherNote, byte[] zipBytes) throws Exception {
        safeId(examId, "đề");                 // chặn path traversal khi tạo exams/<examId>/testcase
        Path tmplDir = locateTemplateDir();
        ensureBaseImage(tmplDir);   // vẫn cần ảnh nền (container chạy từ đây)

        Path testcaseDir = resolveExamsDir(tmplDir).resolve(examId).resolve("testcase");
        if (Files.exists(testcaseDir)) archiveTestcase(examId, testcaseDir);   // giữ lịch sử thay vì xoá hẳn
        Files.createDirectories(testcaseDir);

        unzip(zipBytes, testcaseDir);
        ensurePortableContract(testcaseDir);
        prepareSandboxFiles(testcaseDir);

        Exam exam = examRepository.findByExamId(examId).orElse(new Exam());
        exam.setExamId(examId);
        exam.setImageName(baseImage);
        exam.setTestcasePath(testcaseDir.toAbsolutePath().normalize().toString());
        exam.setStatus(ExamStatus.READY);
        exam.setTestcaseStatus("PUBLISHED");
        if (exam.getTestcaseVersion() == null) exam.setTestcaseVersion(1);
        if (exam.getTestcasePublishedAt() == null) exam.setTestcasePublishedAt(Instant.now());
        if (examName    != null && !examName.isBlank())    exam.setExamName(examName.trim());
        if (teacherNote != null && !teacherNote.isBlank()) exam.setTeacherNote(teacherNote.trim());
        examRepository.save(exam);

        log.info("✅ Đề {} sẵn sàng (mount testcase, không build image): {}", examId, testcaseDir);
        return new ExamSetupResponse(examId, baseImage, "READY");
    }

    /**
     * Chuẩn bị sandbox trực tiếp từ thư mục testcase đã sinh, không cần nén rồi upload lại ZIP.
     * Ảnh nền Docker dùng chung được bảo đảm một lần; mỗi đề chỉ lưu đường dẫn thư mục để mount.
     */
    public ExamSetupResponse buildSandbox(String rawExamId) throws Exception {
        String examId = safeId(rawExamId, "bộ testcase");
        Exam exam = examRepository.findByExamId(examId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bộ testcase: " + examId));
        if (!"PUBLISHED".equalsIgnoreCase(exam.getTestcaseStatus())) {
            throw new IllegalStateException(
                    "Bộ testcase chưa được lưu chính thức. Hãy bấm Lưu trước khi Build Sandbox.");
        }

        Path testcaseDir = testcaseDirOf(examId);
        if (testcaseDir == null || !Files.isDirectory(testcaseDir)) {
            throw new IllegalStateException("Không tìm thấy thư mục testcase của " + examId);
        }

        // Bắt lỗi bộ chấm trước khi tốn thời gian kiểm tra/build ảnh nền Docker.
        prepareSandboxFiles(testcaseDir);
        ensureBaseImage(locateTemplateDir());

        exam.setImageName(baseImage);
        exam.setTestcasePath(testcaseDir.toAbsolutePath().normalize().toString());
        exam.setStatus(ExamStatus.READY);
        examRepository.save(exam);

        log.info("Sandbox của {} đã sẵn sàng từ thư mục {}", examId, testcaseDir);
        return new ExamSetupResponse(examId, baseImage, "READY");
    }

    /** Dùng chung một pipeline kiểm tra cho ZIP upload và thư mục do testcase builder sinh ra. */
    private void prepareSandboxFiles(Path testcaseDir) throws Exception {
        validateRequiredFiles(testcaseDir);
        normalizeExamTestNames(testcaseDir);       // Giữ tên test khớp rubric.
        normalizeGraderRubricKeys(testcaseDir);    // Giữ key kết quả khớp skills_matrix.
        normalizeGraderExecution(testcaseDir);     // Chặn retry/process treo quá thời gian.
        ensureTestcaseImportsAvailable(testcaseDir);
        validateTestcaseImports(testcaseDir);
        validateSkillCodes(testcaseDir);
    }

    /** Giữ tương thích với nơi chỉ yêu cầu đổi mã. */
    public Map<String, Object> renameExam(String rawOldId, String rawNewId) {
        return renameExam(rawOldId, rawNewId, null);
    }

    /**
     * Đổi mã và/hoặc tên bộ testcase. Mã đề là KHOÁ tự nhiên rải khắp hệ thống (tên thư mục exams/ và
     * submissions/, cột exam_id của kết quả & phiên chấm, trường exam_id trong config,
     * exam.code trong result_json) nên phải đổi đồng loạt — đổi mỗi bản ghi `exams` sẽ làm
     * mồ côi toàn bộ lịch sử chấm.
     * Thư mục đổi trước, DB đổi sau: DB có @Transactional tự rollback, còn file thì tự trả
     * về chỗ cũ trong catch.
    */
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> renameExam(String rawOldId, String rawNewId, String rawNewName) {
        String oldId = safeId(rawOldId, "bộ testcase");
        String newId = safeId(rawNewId, "bộ testcase mới");
        if (newId.length() > 50)
            throw new IllegalArgumentException("Mã bộ testcase mới không được dài quá 50 ký tự.");
        Exam exam = ensureExamRecord(oldId);   // bộ mới chỉ có trên đĩa vẫn đổi tên được
        String newName = rawNewName == null ? null : rawNewName.trim();
        if (rawNewName != null && newName.isBlank())
            throw new IllegalArgumentException("Tên bộ testcase không được để trống.");
        if (newName != null && newName.length() > 200)
            throw new IllegalArgumentException("Tên bộ testcase không được dài quá 200 ký tự.");

        boolean idChanged = !oldId.equals(newId);
        boolean nameChanged = newName != null && !newName.equals(exam.getExamName());
        if (!idChanged && !nameChanged)
            throw new IllegalArgumentException("Mã và tên bộ testcase chưa có thay đổi.");
        if (idChanged && examRepository.existsByExamId(newId))
            throw new IllegalStateException("Mã bộ testcase " + newId + " đã tồn tại.");

        // Batch đang chạy giữ nguyên đường dẫn cũ trong bộ nhớ → đổi thư mục lúc này là hỏng phiên chấm.
        if (idChanged) {
            boolean grading = batchRepository.findByExamIdOrderByCreatedAtDesc(oldId).stream()
                    .anyMatch(b -> b.getStatus() == com.example.grader.entity.BatchStatus.IN_PROGRESS);
            if (grading)
                throw new IllegalStateException("Bộ " + oldId + " đang có phiên chấm chạy dở. "
                        + "Hãy đợi chấm xong rồi đổi mã.");
        }

        Path oldDir = examsRoot().resolve(oldId);
        Path newDir = examsRoot().resolve(newId);
        Path oldSub = resolveSibling(submissionsDir).resolve(oldId);
        Path newSub = resolveSibling(submissionsDir).resolve(newId);
        if (idChanged && Files.exists(newDir))
            throw new IllegalStateException("Thư mục của bộ testcase " + newId + " đã tồn tại.");
        if (idChanged && Files.exists(newSub))
            throw new IllegalStateException("Thư mục bài nộp của bộ testcase " + newId + " đã tồn tại.");

        boolean examDirMoved = false;
        boolean subDirMoved = false;
        try {
            if (idChanged && Files.isDirectory(oldDir)) { Files.move(oldDir, newDir); examDirMoved = true; }
            if (idChanged && Files.isDirectory(oldSub)) { Files.move(oldSub, newSub); subDirMoved = true; }

            exam.setExamId(newId);
            if (newName != null) exam.setExamName(newName);
            if (idChanged) exam.setTestcasePath(rebaseExamPath(exam.getTestcasePath(), oldDir, newDir));
            exam.setTestcaseConfigJson(withJsonText(exam.getTestcaseConfigJson(), "exam_id", newId));
            if (newName != null)
                exam.setTestcaseConfigJson(withJsonText(exam.getTestcaseConfigJson(), "exam_name", newName));
            examRepository.save(exam);

            // result_json là bản ghi ĐÃ CHỐT gửi cho bot NLP: exam.code phải khớp mã mới.
            int results = 0;
            int batches = 0;
            if (idChanged) {
                for (com.example.grader.entity.ExamResult r : resultRepository.findByExamId(oldId)) {
                    r.setExamId(newId);
                    r.setResultJson(withResultExamCode(r.getResultJson(), newId));
                    resultRepository.save(r);
                    results++;
                }
                for (com.example.grader.entity.GradingBatch b : batchRepository.findByExamIdOrderByCreatedAtDesc(oldId)) {
                    b.setExamId(newId);
                    batchRepository.save(b);
                    batches++;
                }
            }

            // testcase-config.json trên đĩa cũng ghi mã đề; lệch với DB thì lần mở lại builder sẽ sai.
            Path configFile = newDir.resolve("testcase").resolve("testcase-config.json");
            if (Files.exists(configFile)) {
                String updated = withJsonText(Files.readString(configFile, StandardCharsets.UTF_8), "exam_id", newId);
                if (newName != null) updated = withJsonText(updated, "exam_name", newName);
                if (updated != null) Files.writeString(configFile, updated, StandardCharsets.UTF_8);
            }

            log.info("✏️ Đổi bộ testcase {} → {} / {} ({} kết quả, {} phiên chấm)",
                    oldId, newId, exam.getExamName(), results, batches);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("exam_id", newId);
            out.put("exam_name", exam.getExamName());
            out.put("old_exam_id", oldId);
            out.put("results_updated", results);
            out.put("batches_updated", batches);
            return out;
        } catch (Exception e) {
            // Trả thư mục về chỗ cũ; phần DB do @Transactional tự rollback khi ném tiếp.
            if (subDirMoved)  try { Files.move(newSub, oldSub); } catch (Exception ignored) {}
            if (examDirMoved) try { Files.move(newDir, oldDir); } catch (Exception ignored) {}
            if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) throw (RuntimeException) e;
            throw new IllegalStateException("Không đổi được mã bộ testcase: " + e.getMessage(), e);
        }
    }

    /**
     * Đường dẫn testcase trỏ vào thư mục đề cũ → đổi phần gốc, giữ nguyên phần đuôi.
     * Trỏ ra ngoài thư mục đề thì GIỮ NGUYÊN: chỗ đó không hề được di chuyển.
     */
    private String rebaseExamPath(String current, Path oldDir, Path newDir) {
        Path from = oldDir.toAbsolutePath().normalize();
        Path to = newDir.toAbsolutePath().normalize();
        if (current == null || current.isBlank()) return to.resolve("testcase").toString();
        Path p = Path.of(current).toAbsolutePath().normalize();
        return p.startsWith(from) ? to.resolve(from.relativize(p)).toString() : current;
    }

    /** Ghi đè MỘT trường chuỗi ở gốc JSON; JSON rỗng/hỏng thì trả nguyên trạng để không mất dữ liệu. */
    private String withJsonText(String json, String field, String value) {
        if (json == null || json.isBlank()) return json;
        try {
            JsonNode root = mapper.readTree(json);
            if (!root.isObject()) return json;
            ((com.fasterxml.jackson.databind.node.ObjectNode) root).put(field, value);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Không cập nhật được trường {} trong JSON khi đổi mã đề: {}", field, e.getMessage());
            return json;
        }
    }

    /** result_json giữ mã đề ở exam.code (xem BatchGradingService.assembleResultJson). */
    private String withResultExamCode(String resultJson, String newId) {
        if (resultJson == null || resultJson.isBlank()) return resultJson;
        try {
            JsonNode root = mapper.readTree(resultJson);
            JsonNode examNode = root.path("exam");
            if (!examNode.isObject()) return resultJson;
            ((com.fasterxml.jackson.databind.node.ObjectNode) examNode).put("code", newId);
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Không cập nhật được exam.code trong result_json khi đổi mã đề: {}", e.getMessage());
            return resultJson;
        }
    }

    // ── Xóa đề: gỡ ảnh legacy + testcase + TOÀN BỘ bài nộp (submissions) + bản ghi đề (DB) ──
    public Map<String, Object> deleteExam(String examId) {
        safeId(examId, "đề");
        boolean imageRemoved = false;
        try {
            imageRemoved = runDocker(
                    List.of("docker", "rmi", "-f", imagePrefix + "-" + examId.toLowerCase()),
                    "rmi-" + examId) == 0;
        } catch (Exception e) {
            log.warn("Không gỡ được ảnh legacy của {}: {}", examId, e.getMessage());
        }
        try {
            Path examDir = resolveExamsDir(locateTemplateDir()).resolve(examId);
            if (Files.exists(examDir)) deleteRecursively(examDir);
        } catch (Exception ignored) {}

        // Xóa CẢ thư mục bài nộp submissions/<đề>/ (zip + snapshot testcase) → giải phóng dung lượng.
        boolean submissionsRemoved = false;
        try {
            Path subDir = resolveSibling(submissionsDir).resolve(examId);
            if (Files.exists(subDir)) { deleteRecursively(subDir); submissionsRemoved = true; }
        } catch (Exception e) {
            log.warn("Không xóa được submissions của {}: {}", examId, e.getMessage());
        }

        // Lịch sử chấm phải đi cùng đề. Trước đây chỉ xoá bản ghi `exam` nên trang chấm
        // bài vẫn liệt kê đủ điểm của bộ chấm đã bị xoá — người dùng tưởng xoá hụt.
        long resultsRemoved = resultRepository.deleteByExamId(examId);
        long batchesRemoved = batchRepository.deleteByExamId(examId);

        boolean dbRemoved = examRepository.findByExamId(examId)
                .map(e -> { examRepository.delete(e); return true; })
                .orElse(false);

        log.info("🗑️ Đã xóa đề {} (ảnh legacy: {}, submissions: {}, kết quả: {}, mẻ: {}, DB: {})",
                examId, imageRemoved, submissionsRemoved, resultsRemoved, batchesRemoved, dbRemoved);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("examId", examId);
        r.put("imageRemoved", imageRemoved);
        r.put("submissionsRemoved", submissionsRemoved);
        r.put("resultsRemoved", resultsRemoved);
        r.put("batchesRemoved", batchesRemoved);
        r.put("dbRecordRemoved", dbRemoved);
        return r;
    }

    /** Thư mục cạnh grader-base (gốc repo)/<name>, dùng chung cho exams/ và submissions/. */
    public Path resolveSibling(String name) {
        Path p = Path.of(name);
        if (p.isAbsolute()) return p.normalize();
        Path root = locateTemplateDir().getParent();
        return (root != null ? root.resolve(name) : p.toAbsolutePath()).normalize();
    }

    // ── Ảnh nền dùng chung: build 1 lần duy nhất ────────────────
    private synchronized void ensureBaseImage(Path base) throws Exception {
        if (baseImageReady.get()) return;

        if (dockerImageExists(baseImage)) {
            log.info("✅ Ảnh nền {} đã có sẵn", baseImage);
            baseImageReady.set(true);
            return;
        }
        if (!Files.exists(base.resolve("Dockerfile.base")))
            throw new IllegalStateException(
                    "Không tìm thấy Dockerfile.base trong " + base.toAbsolutePath()
                  + ". Đặt biến môi trường GRADER_TEMPLATE_DIR trỏ tới thư mục grader-base "
                  + "(vd <repo>/grader-base) hoặc chạy backend từ thư mục gốc repo.");

        log.info("🏗️  Chưa có ảnh nền {} — build lần đầu (có thể mất vài phút)...", baseImage);
        int exit = runDocker(List.of(
                "docker", "build",
                "-f", base.resolve("Dockerfile.base").toAbsolutePath().toString(),
                "-t", baseImage,
                base.toAbsolutePath().toString()
        ), "base-build");
        if (exit != 0)
            throw new RuntimeException("Build ảnh nền thất bại (exit " + exit + ").");

        log.info("✅ Ảnh nền {} đã sẵn sàng", baseImage);
        baseImageReady.set(true);
    }

    // ── Định vị grader-base (dò ngược lên từ CWD) ───────────────
    private Path locateTemplateDir() {
        Path configured = Path.of(templateDir);
        String name = configured.getFileName() != null
                ? configured.getFileName().toString() : "grader-base";

        List<Path> tried = new ArrayList<>();
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++) {
            for (Path c : new Path[]{ p.resolve(configured), p.resolve(name) }) {
                tried.add(c);
                if (Files.exists(c.resolve("Dockerfile.base")))
                    return c.toAbsolutePath().normalize();
            }
            p = p.getParent();
        }
        log.warn("⚠️ Không định vị được grader-base (Dockerfile.base). Đã thử: {}", tried);
        return configured.toAbsolutePath().normalize();
    }

    private Path resolveExamsDir(Path tmplDir) {
        Path configured = Path.of(examsDir);
        if (configured.isAbsolute()) return configured.normalize();
        Path root = tmplDir.getParent();
        return (root != null ? root.resolve(examsDir) : configured.toAbsolutePath()).normalize();
    }

    // ── Helpers docker ──────────────────────────────────────────
    private boolean dockerImageExists(String image) {
        try {
            Process pr = new ProcessBuilder("docker", "image", "inspect", image)
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(pr.getInputStream(), StandardCharsets.UTF_8))) {
                while (r.readLine() != null) { /* drain */ }
            }
            return pr.waitFor(30, TimeUnit.SECONDS) && pr.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private int runDocker(List<String> command, String tag) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("DOCKER_BUILDKIT", "1");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) log.debug("[{}] {}", tag, line);
        }
        return process.waitFor();
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  QUẢN LÝ THƯ VIỆN MÔI TRƯỜNG CHẤM (thêm/xóa package trong pubspec.base.yaml)
    // ════════════════════════════════════════════════════════════════════════════

    private Path basePubspec() { return locateTemplateDir().resolve("pubspec.base.yaml"); }

    /**
     * Hai file dự án cho KHUNG STARTER phát cho sinh viên: {@code pubspec.yaml} và (nếu lấy được)
     * {@code pubspec.lock}.
     *
     * <p>pubspec lấy NGUYÊN VĂN từ {@code pubspec.base.yaml} — cùng nguồn với môi trường chấm.
     * Chép tay một bản khác là mở đường cho cảnh "máy em chạy được mà chấm 0 điểm": bài dùng
     * package không có trong ảnh chấm sẽ trượt, còn tên dự án lệch thì import
     * {@code package:exam_project/…} gãy.
     *
     * <p>pubspec.lock KHÔNG viết tay được (cần đúng phiên bản đã resolve + hash), nên lấy thẳng
     * bản đã resolve trong ảnh chấm. Không có Docker/ảnh thì bỏ qua file này — sinh viên chạy
     * {@code flutter pub get} là có; thà thiếu còn hơn phát một lock sai làm build hỏng.
     */
    public List<Map<String, String>> starterProjectFiles() {
        List<Map<String, String>> out = new ArrayList<>();
        try {
            Path pubspec = basePubspec();
            if (!Files.exists(pubspec)) return out;
            out.add(Map.of(
                    "name", "pubspec.yaml",
                    "content", "# Khai sẵn theo đúng môi trường chấm — KHÔNG thêm package ngoài danh sách này.\n"
                            + Files.readString(pubspec, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.warn("Không đọc được pubspec.base.yaml cho khung starter: {}", e.getMessage());
            return out;
        }
        String lock = readLockFromBaseImage();
        if (lock != null && !lock.isBlank()) {
            out.add(Map.of("name", "pubspec.lock", "content", lock));
        }
        return out;
    }

    /** Đọc /app/pubspec.lock trong ảnh nền; null khi không có Docker hoặc chưa build ảnh. */
    private String readLockFromBaseImage() {
        if (!dockerImageExists(baseImage)) return null;
        try {
            Process pr = new ProcessBuilder("docker", "run", "--rm", baseImage,
                    "cat", "/app/pubspec.lock").start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(pr.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            if (!pr.waitFor(60, TimeUnit.SECONDS) || pr.exitValue() != 0) return null;
            return sb.toString();
        } catch (Exception e) {
            log.warn("Không lấy được pubspec.lock từ ảnh nền: {}", e.getMessage());
            return null;
        }
    }

    /** Tên các package ĐỌC ĐƯỢC từ pubspec.lock của ảnh nền; rỗng khi chưa đọc lần nào. */
    private volatile Set<String> goiTrongAnh = Set.of();
    private volatile String goiTrongAnhCuaAnh = "";

    /**
     * Mọi package mà bài sinh viên import được trong ảnh chấm, gồm CẢ package phụ thuộc kéo theo.
     *
     * <p>Nguồn là {@code pubspec.lock} trong chính ảnh nền, không phải khối dependencies của
     * {@code pubspec.base.yaml}: {@code sqflite_common} và {@code riverpod} không được khai trực
     * tiếp nhưng vẫn import được vì nằm trong package graph đã resolve. Lấy nhầm nguồn là chặn oan
     * đúng những tên bộ đề hiện tại đang cho phép.
     *
     * <p>Trả về TẬP RỖNG khi không đọc được (Docker tắt, chưa build ảnh). Người gọi phải hiểu rỗng
     * là "không biết" chứ không phải "không có gì": không biết thì đừng chặn ai.
     */
    public Set<String> goiCoTrongAnhCham() {
        if (!goiTrongAnh.isEmpty() && baseImage.equals(goiTrongAnhCuaAnh)) return goiTrongAnh;
        Set<String> ten = goiTuLock(readLockFromBaseImage());
        if (ten.isEmpty()) return Set.of();
        goiTrongAnh = ten;
        goiTrongAnhCuaAnh = baseImage;
        return goiTrongAnh;
    }

    /**
     * Bóc tên package từ nội dung pubspec.lock. Tách riêng khỏi phần gọi Docker để kiểm được
     * bằng test mà không cần ảnh nền.
     *
     * <p>Trả TẬP RỖNG khi không bóc được tên nào ngoài hai gói lõi: nội dung hỏng hay rỗng đều
     * phải quy về "không biết", chứ không được thành "ảnh chỉ có flutter" rồi chặn oan cả bộ đề.
     */
    static Set<String> goiTuLock(String lock) {
        if (lock == null || lock.isBlank()) return Set.of();
        Set<String> ten = new LinkedHashSet<>(Set.of("flutter", "flutter_test"));
        // Mỗi package là một khoá thụt ĐÚNG 2 dấu cách dưới "packages:"; khối "sdks:" ở cuối
        // file cũng thụt như vậy nên phải bám theo khối đang đứng, không bắt bừa theo thụt đầu.
        boolean trongKhoi = false;
        for (String raw : lock.split("\\R", -1)) {
            String dong = raw.replace("\t", "  ");
            if (dong.isBlank() || dong.trim().startsWith("#")) continue;
            if (!dong.startsWith(" ")) { trongKhoi = dong.split(":")[0].trim().equals("packages"); continue; }
            if (!trongKhoi) continue;
            Matcher m = Pattern.compile("^ {2}([A-Za-z0-9_]+):\\s*$").matcher(dong);
            if (m.find()) ten.add(m.group(1));
        }
        return ten.size() <= 2 ? Set.of() : Set.copyOf(ten);
    }

    /**
     * Bảng tick cho màn soạn đề: gói khai thẳng trong ảnh, và gói kéo theo để riêng.
     *
     * <p>Tách hai nhóm vì hai ý nghĩa khác nhau. Nhóm khai thẳng là thứ đề thường cho phép,
     * chừng chục cái. Nhóm kéo theo tám chục cái, import được nhưng hiếm khi đề nhắc tới; trộn
     * chung là bảng tick không đọc nổi.
     *
     * <p>{@code image_read=false} nghĩa là chưa đọc được ảnh. Màn soạn đề phải hiểu đó là "không
     * biết" và giữ nguyên danh sách đã lưu, chứ không phải "ảnh không có gói nào".
     */
    public Map<String, Object> goiChoManSoanDe() {
        Set<String> tatCa = goiCoTrongAnhCham();
        List<Map<String, Object>> khaiThang = listManagedPackages();
        Set<String> tenKhaiThang = new LinkedHashSet<>();
        khaiThang.forEach(item -> tenKhaiThang.add(String.valueOf(item.get("name"))));
        tenKhaiThang.add("flutter");
        tenKhaiThang.add("flutter_test");
        List<String> keoTheo = tatCa.stream()
                .filter(ten -> !tenKhaiThang.contains(ten))
                .sorted()
                .toList();
        Map<String, Object> ra = new LinkedHashMap<>();
        ra.put("image_read", !tatCa.isEmpty());
        ra.put("direct", khaiThang);
        ra.put("transitive", keoTheo);
        return ra;
    }

    /** Danh sách package trong khối dependencies: [{name, version, protected}]. `flutter` = lõi (không xóa). */
    public List<Map<String, Object>> listManagedPackages() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Path pubspec = basePubspec();
            if (!Files.exists(pubspec)) return out;
            boolean inDeps = false;
            java.util.regex.Pattern entry = java.util.regex.Pattern.compile("^ {2}([A-Za-z0-9_]+):\\s*(.*)$");
            for (String raw : Files.readAllLines(pubspec, StandardCharsets.UTF_8)) {
                String line = raw.replace("\t", "  ");
                if (line.isBlank() || line.trim().startsWith("#")) continue;
                // Đọc CẢ dev_dependencies: flutter_test nằm ở đó. Bỏ khối này thì trang thư viện
                // bảo "flutter_test là gói lõi" mà không bày ra dòng nào, còn bảng tick bên soạn
                // đề thì thiếu đúng gói mọi bài đều cần.
                if (!line.startsWith(" ")) {
                    String khoi = line.split(":")[0].trim();
                    inDeps = khoi.equals("dependencies") || khoi.equals("dev_dependencies");
                    continue;
                }
                if (!inDeps) continue;
                java.util.regex.Matcher m = entry.matcher(line);
                if (!m.find()) continue;
                String name = m.group(1), val = m.group(2).trim();
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("name", name);
                boolean isBlock = val.isEmpty();                 // vd flutter: → sdk block ở dòng dưới
                p.put("version", isBlock ? "(flutter sdk)" : val);
                // flutter_lints chỉ dùng cho phân tích mã, không import được trong bài — khoá
                // luôn để không ai xóa nhầm rồi tưởng đã cấm được gì.
                p.put("protected", isBlock || name.equals("flutter") || name.equals("flutter_test")
                        || name.equals("flutter_lints"));
                out.add(p);
            }
        } catch (Exception e) {
            log.warn("listManagedPackages lỗi: {}", e.getMessage());
        }
        return out;
    }

    public Map<String, Object> buildStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", envStatus);
        m.put("message", envMessage);
        m.put("log", envLog);
        m.put("at", envAt);
        m.put("building", envBuilding);
        return m;
    }

    private void setEnv(String status, String message, String logTail) {
        envStatus = status; envMessage = message;
        if (logTail != null) envLog = logTail;
        envAt = System.currentTimeMillis();
    }

    /**
     * Ghi danh sách package (desired = các package SỬA ĐƯỢC, không gồm flutter lõi) vào
     * pubspec.base.yaml rồi CẬP NHẬT thư viện vào ảnh nền HIỆN CÓ (docker commit, KHÔNG build
     * ảnh mới) — chạy nền. Lỗi resolve/pub get → HOÀN TÁC pubspec, ảnh nền giữ nguyên.
     */
    public Map<String, Object> applyPackages(List<Map<String, Object>> desired) {
        synchronized (envLock) {
            if (envBuilding) throw new IllegalStateException("Đang build môi trường, vui lòng đợi build xong.");
            envBuilding = true;
            setEnv("RESOLVING", "Đang chuẩn bị môi trường...", "");
        }
        new Thread(() -> {
            try { doApplyPackages(desired); }
            catch (Exception e) { log.warn("applyPackages thread lỗi: {}", e.getMessage()); }
            finally { envBuilding = false; }
        }, "env-build").start();
        return buildStatus();
    }

    private void doApplyPackages(List<Map<String, Object>> desired) {
        try {
            doApplyPackagesBlocking(desired);
        } catch (Exception e) {
            setEnv("FAILED", "Lỗi: " + e.getMessage() + " — đã hoàn tác thay đổi.", envLog);
            ExamService.log.warn("❌ Cập nhật thư viện vào ảnh nền thất bại: {}", e.getMessage());
        }
    }

    private void doApplyPackagesBlocking(List<Map<String, Object>> desired) throws Exception {
        Path pubspec = basePubspec();
        String snapshot = null;
        StringBuilder logBuf = new StringBuilder();
        try {
            // Đảm bảo ĐÃ CÓ ảnh nền (build 1 lần duy nhất nếu máy chưa có); sau đó chỉ CẬP NHẬT tại chỗ.
            ensureBaseImage(locateTemplateDir());

            snapshot = Files.readString(pubspec, StandardCharsets.UTF_8);

            // 1) Resolve version cho package chưa khai version (flutter pub add trong container ephemeral)
            List<String[]> resolved = resolveVersions(desired);

            // 2) Ghi khối dependencies vào pubspec.base.yaml (nguồn sự thật để validate khi upload đề)
            writeDependenciesBlock(pubspec, resolved);

            // 3) CẬP NHẬT thư viện vào ẢNH NỀN HIỆN CÓ bằng docker commit — KHÔNG build/tạo ảnh mới,
            //    nên không tích tụ image dangling, không phình dung lượng đĩa.
            setEnv("BUILDING", "Đang cập nhật thư viện vào ảnh nền chấm...", envLog);
            updateBaseImageInPlace(pubspec, logBuf);

            baseImageReady.set(true);
            setEnv("READY", "Đã cập nhật môi trường chấm (" + resolved.size() + " thư viện).", tail(logBuf.toString()));
            ExamService.log.info("✅ Cập nhật thư viện vào ảnh nền xong: {} thư viện", resolved.size());

        } catch (Exception e) {
            if (snapshot != null) {
                try { Files.writeString(pubspec, snapshot, StandardCharsets.UTF_8); } // HOÀN TÁC để không kẹt pubspec hỏng
                catch (Exception ex) { log.warn("Revert pubspec lỗi: {}", ex.getMessage()); }
            }
            throw e;
        }
    }

    /**
     * Cập nhật danh sách thư viện vào CHÍNH ảnh nền {@code grading-base:latest} đang có, thay vì
     * build một ảnh mới. Cách làm:
     *   1) chạy 1 container "giữ-sống" từ chính ảnh nền hiện có,
     *   2) ghi pubspec mới vào /app rồi {@code flutter pub get} (tải gói về pub-cache + cập nhật
     *      pubspec.lock và .dart_tool/package_config.json) — y như bước build gốc,
     *   3) {@code docker commit} container đó NGƯỢC LẠI đúng tag {@code grading-base:latest}.
     * Layer nền (~4.5GB) được DÙNG CHUNG nên chỉ phát sinh 1 lớp diff nhỏ; ảnh cũ trở thành lớp cha
     * của ảnh mới (không tốn dung lượng riêng) → "tạo image 1 lần, dùng mãi mãi".
     */
    private void updateBaseImageInPlace(Path pubspec, StringBuilder out) throws Exception {
        String content = Files.readString(pubspec, StandardCharsets.UTF_8);
        String cid = "grader-env-update-" + System.currentTimeMillis();
        try {
            // 1) Container giữ-sống từ CHÍNH ảnh nền hiện có (ghi đè CMD = sleep, ENTRYPOINT vẫn null).
            if (runDockerCapture(List.of(
                    "docker", "run", "-d", "--name", cid, baseImage, "sleep", "infinity"), out) != 0)
                throw new RuntimeException("Không khởi động được container cập nhật môi trường.");

            // 2) Ghi pubspec mới vào /app (qua stdin để khỏi phụ thuộc mount/đường dẫn Windows) rồi pub get.
            String script = "cat > /app/pubspec.yaml"
                    + " && cp /app/pubspec.yaml /app/pubspec_base.yaml"
                    + " && cd /app && flutter pub get";
            if (runDockerWithStdin(List.of("docker", "exec", "-i", "-w", "/app", cid, "bash", "-c", script),
                    content, out) != 0)
                throw new RuntimeException("flutter pub get thất bại trong ảnh nền (xem log).");

            // 3) Đóng băng thay đổi vào ĐÚNG tag ảnh nền. --change khôi phục CMD chấm bài (vì CMD đã bị
            //    ghi đè thành "sleep infinity" ở bước 1). Dùng CMD dạng SHELL (không có dấu nháy kép)
            //    vì dạng JSON exec ["..."] không sống sót qua cách quote tham số của Windows.
            //    ENTRYPOINT/WORKDIR/ENV giữ nguyên.
            if (runDockerCapture(List.of("docker", "commit",
                    "--change", "CMD ./run_grader.sh", cid, baseImage), out) != 0)
                throw new RuntimeException("docker commit thất bại.");
        } finally {
            try { runDocker(List.of("docker", "rm", "-f", cid), "rm-env-update"); }
            catch (Exception ignored) {}
        }
        // Best-effort: dọn các ảnh <none> rác còn sót lại (vd từ cơ chế build cũ) để thu hồi dung lượng.
        try { runDocker(List.of("docker", "image", "prune", "-f"), "img-prune"); }
        catch (Exception ignored) {}
    }

    /** Trả [name, versionConstraint] theo thứ tự desired; resolve version qua `flutter pub add` khi để trống. */
    private List<String[]> resolveVersions(List<Map<String, Object>> desired) throws Exception {
        List<String> toResolve = new ArrayList<>();
        Map<String, String> given = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (Map<String, Object> p : desired) {
            String name = p.get("name") == null ? "" : p.get("name").toString().trim();
            if (name.isEmpty()) continue;
            if (name.equals("flutter") || name.equals("flutter_test")) continue;   // lõi, bỏ qua
            if (!name.matches("[a-z][a-z0-9_]*"))
                throw new IllegalArgumentException("Tên package không hợp lệ: '" + name + "' (chỉ a-z, 0-9, _).");
            if (order.contains(name)) continue;
            order.add(name);
            String ver = p.get("version") == null ? "" : p.get("version").toString().trim();
            if (ver.isEmpty() || ver.equals("(flutter sdk)")) toResolve.add(name); else given.put(name, ver);
        }
        Map<String, String> resolved = toResolve.isEmpty() ? Map.of() : runPubAddResolve(toResolve);
        List<String[]> out = new ArrayList<>();
        for (String name : order) out.add(new String[]{ name, given.getOrDefault(name, resolved.getOrDefault(name, "any")) });
        return out;
    }

    /** Chạy `flutter pub add` trong container để lấy version tương thích (đồng thời validate package tồn tại). */
    private Map<String, String> runPubAddResolve(List<String> names) throws Exception {
        setEnv("RESOLVING", "Đang resolve version: " + String.join(", ", names) + " ...", envLog);
        List<String> cmd = new ArrayList<>(List.of(
                "docker", "run", "--rm", "--entrypoint", "bash", baseImage,
                "-c", "cd /app && flutter pub add " + String.join(" ", names)
                    + " 2>&1; echo '---PUBSPEC---'; cat pubspec.yaml"));
        StringBuilder out = new StringBuilder();
        runDockerCapture(cmd, out);
        String s = out.toString();
        int idx = s.indexOf("---PUBSPEC---");
        String pubspecPart = idx >= 0 ? s.substring(idx) : "";
        Map<String, String> res = new LinkedHashMap<>();
        for (String name : names) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?m)^ {2}" + java.util.regex.Pattern.quote(name) + ":\\s*(\\S+)").matcher(pubspecPart);
            if (m.find()) res.put(name, m.group(1));
            else throw new IllegalArgumentException(
                    "Không thêm được thư viện '" + name + "' (sai tên hoặc không tương thích Flutter SDK). "
                  + "Chi tiết: " + firstError(s));
        }
        return res;
    }

    private String firstError(String out) {
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.contains("not found") || t.contains("version solving failed")
                    || t.toLowerCase().contains("error") || t.contains("Because")) {
                return t.length() > 200 ? t.substring(0, 200) : t;
            }
        }
        return "không rõ (xem log).";
    }

    /** Ghi lại khối `dependencies:` của pubspec.base.yaml (giữ nguyên các phần khác của file). */
    private void writeDependenciesBlock(Path pubspec, List<String[]> deps) throws Exception {
        List<String> lines = Files.readAllLines(pubspec, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        boolean done = false;
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (!done && line.stripTrailing().equals("dependencies:")) {
                out.add("dependencies:");
                out.add("  # Quản lý qua trang \"Thư viện chấm\". 'flutter' là lõi (không xóa).");
                out.add("  flutter:");
                out.add("    sdk: flutter");
                for (String[] d : deps) out.add("  " + d[0] + ": " + d[1]);
                out.add("");
                // bỏ qua khối dependencies cũ tới section kế (dòng ở cột 0) hoặc hết file
                i++;
                while (i < lines.size()) {
                    String l = lines.get(i);
                    if (!l.isEmpty() && !l.startsWith(" ") && !l.startsWith("\t")) break;
                    i++;
                }
                done = true;
                continue;
            }
            out.add(line);
            i++;
        }
        Files.writeString(pubspec, String.join("\n", out) + "\n", StandardCharsets.UTF_8);
    }

    /** Như runDocker nhưng GOM output (giữ ~120 dòng cuối) để hiển thị tiến độ build cho GV. */
    private int runDockerCapture(List<String> command, StringBuilder out) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("DOCKER_BUILDKIT", "1");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        java.util.Deque<String> ring = new java.util.ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append("\n");
                ring.addLast(line);
                while (ring.size() > 120) ring.removeFirst();
                if ("BUILDING".equals(envStatus)) envLog = String.join("\n", ring);
            }
        }
        return process.waitFor();
    }

    /** Như runDockerCapture nhưng ĐẨY {@code stdin} vào tiến trình (vd ghi pubspec qua `cat > file`). */
    private int runDockerWithStdin(List<String> command, String stdin, StringBuilder out) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("DOCKER_BUILDKIT", "1");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        // Ghi & ĐÓNG stdin trước (gửi EOF cho `cat`) — nội dung nhỏ nên không nghẽn pipe.
        try (var w = process.getOutputStream()) {
            w.write(stdin.getBytes(StandardCharsets.UTF_8));
            w.flush();
        } catch (Exception ignored) {}
        java.util.Deque<String> ring = new java.util.ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append("\n");
                ring.addLast(line);
                while (ring.size() > 120) ring.removeFirst();
                if ("BUILDING".equals(envStatus)) envLog = String.join("\n", ring);
            }
        }
        return process.waitFor();
    }

    /**
     * Kiểm tra CÚ PHÁP một file Dart bằng `dart format` trong ảnh nền (chỉ parse, không cần
     * pub get nên nhanh và không phụ thuộc bài sinh viên). Nội dung đẩy qua stdin để khỏi
     * mount thư mục tạm trên Windows.
     *
     * @return null nếu parse được; thông báo lỗi của Dart nếu sai cú pháp.
     * @throws IllegalStateException khi không dùng được Docker — caller phải phân biệt
     *         "chưa kiểm tra được" với "code sai".
     */
    public String checkDartSyntax(String dartSource) {
        if (!dockerImageExists(baseImage))
            throw new IllegalStateException("chưa có ảnh nền " + baseImage + " hoặc Docker chưa chạy");
        StringBuilder out = new StringBuilder();
        try {
            int exit = runDockerWithStdin(List.of(
                    "docker", "run", "--rm", "-i", "--entrypoint", "bash", baseImage,
                    "-c", "cat > /tmp/syntax_check.dart && dart format --output=none /tmp/syntax_check.dart"),
                    dartSource, out);
            if (exit == 0) return null;
            String log = out.toString().trim();
            return log.isEmpty() ? "Dart báo lỗi cú pháp (exit " + exit + ")." : log;
        } catch (Exception e) {
            throw new IllegalStateException("không chạy được Docker: " + e.getMessage(), e);
        }
    }

    private String tail(String s) {
        String[] arr = s.split("\n");
        int from = Math.max(0, arr.length - 40);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < arr.length; i++) sb.append(arr[i]).append("\n");
        return sb.toString().trim();
    }

    // ── Validate + unzip + xóa đệ quy ───────────────────────────
    private void validateRequiredFiles(Path dir) throws Exception {
        for (String f : List.of("exam_test.dart", "grader.dart", "skills_matrix.json")) {
            Path p = dir.resolve(f);
            if (!Files.exists(p))
                throw new IllegalArgumentException("Thiếu file bắt buộc: " + f);
            if (Files.size(p) == 0)
                throw new IllegalArgumentException(
                        "File bắt buộc bị RỖNG (0 byte): " + f
                      + " — kiểm tra lại nội dung file trước khi nén zip.");
        }
        if (!Files.readString(dir.resolve("grader.dart"), StandardCharsets.UTF_8).contains("main"))
            throw new IllegalArgumentException(
                    "grader.dart không có hàm main() — file có thể sai nội dung/encoding.");
    }

    /**
     * Contract là tùy chọn khi upload. Bộ testcase không dùng Key vẫn chỉ cần ba file thực thi;
     * backend tạo contract tương thích để hành vi đó được giữ nguyên khi tải xuống/upload lại.
     * Nếu người dùng có gửi contract thì vẫn kiểm tra chặt để tránh âm thầm chấm sai policy.
     */
    private void ensurePortableContract(Path dir) throws Exception {
        Path contract = dir.resolve("contract.json");
        if (!Files.isRegularFile(contract)) {
            Files.writeString(contract,
                    "{\n  \"schema_version\": 1,\n  \"require_keys\": false\n}\n",
                    StandardCharsets.UTF_8);
            return;
        }
        if (Files.size(contract) == 0) {
            throw new IllegalArgumentException("contract.json được cung cấp nhưng bị RỖNG (0 byte)");
        }
        try {
            JsonNode root = mapper.readTree(Files.readString(contract, StandardCharsets.UTF_8));
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("contract.json phải là một JSON object.");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("contract.json không phải JSON hợp lệ: " + e.getMessage());
        }
    }

    /**
     * Bỏ wrapper group(...) trong exam_test.dart để flutter test --machine trả đúng tên test
     * dạng "TC_MODEL_01" thay vì "A. User model TC_MODEL_01".
     */
    private void normalizeExamTestNames(Path testcaseDir) {
        Path f = testcaseDir.resolve("exam_test.dart");
        try {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            if (lines.stream().noneMatch(s -> s.trim().startsWith("group("))) return;

            List<String> out = new ArrayList<>();
            List<Integer> groupDepths = new ArrayList<>();
            int depth = 0;
            int removed = 0;
            for (String line : lines) {
                int delta = braceDelta(line);
                String trim = line.trim();
                if (trim.startsWith("group(")) {
                    depth += delta;
                    groupDepths.add(depth);
                    removed++;
                    continue;
                }

                if (!groupDepths.isEmpty()
                        && trim.equals("});")
                        && depth == groupDepths.get(groupDepths.size() - 1)
                        && depth + delta == groupDepths.get(groupDepths.size() - 1) - 1) {
                    depth += delta;
                    groupDepths.remove(groupDepths.size() - 1);
                    continue;
                }

                out.add(line);
                depth += delta;
            }

            if (!groupDepths.isEmpty() || depth != 0) {
                log.warn("Không bỏ group exam_test.dart vì brace không cân bằng: {}", f);
                return;
            }
            Files.write(f, out, StandardCharsets.UTF_8);
            log.info("Đã bỏ {} group wrapper trong exam_test.dart để tên test khớp rubric: {}", removed, f);
        } catch (Exception e) {
            log.warn("Tự bỏ group trong exam_test.dart lỗi: {}", e.getMessage());
        }
    }

    private int braceDelta(String line) {
        int delta = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '{') delta++;
            else if (c == '}') delta--;
        }
        return delta;
    }

    /**
     * Dart test machine output trả tên đầy đủ gồm cả group, vd "A. User model TC_MODEL_01".
     * Rubric thường chỉ khai key "TC_MODEL_01"; tự vá grader cũ để lookup theo key rubric thật.
     */
    private void normalizeGraderRubricKeys(Path testcaseDir) {
        Path f = testcaseDir.resolve("grader.dart");
        try {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            if (src.contains("rubricKeyFor(") || !src.contains("testDone") || !src.contains("matrix")) return;

            String patched = src;
            boolean changed = false;
            if (patched.contains("final name = idToName[id] ?? '';")) {
                patched = patched.replace(
                        "final name = idToName[id] ?? '';",
                        "final rawName = idToName[id] ?? '';\n          final name = rubricKeyFor(rawName);");
                changed = true;
            }
            if (patched.contains("final name = testIdToName[id] ?? '';")) {
                patched = patched.replace(
                        "final name = testIdToName[id] ?? '';",
                        "final rawName = testIdToName[id] ?? '';\n        final name = rubricKeyFor(rawName);");
                changed = true;
            }
            if (!changed) return;

            patched = patched
                    .replace("if (name.isEmpty || name.startsWith('loading ') || hidden)",
                             "if (rawName.isEmpty || rawName.startsWith('loading ') || hidden)")
                    .replace("if (name.isEmpty || name.startsWith('loading ') || hidden) continue;",
                             "if (rawName.isEmpty || rawName.startsWith('loading ') || hidden) continue;");

            String withHelper = insertRubricKeyHelper(patched);
            if (withHelper.equals(patched)) {
                log.warn("Không tự vá được grader.dart để normalize rubric key: {}", f);
                return;
            }
            Files.writeString(f, withHelper, StandardCharsets.UTF_8);
            log.info("Đã tự vá grader.dart để normalize tên testcase theo skills_matrix.json: {}", f);
        } catch (Exception e) {
            log.warn("Tự vá grader.dart để normalize rubric key lỗi: {}", e.getMessage());
        }
    }

    private String insertRubricKeyHelper(String src) {
        String helper = """

  String rubricKeyFor(String rawName) {
    if (matrix.containsKey(rawName)) return rawName;

    // Flutter machine output ghép tên group vào tên test, vd:
    // "A. User model TC_MODEL_01". Rubric chỉ dùng key "TC_MODEL_01".
    final matches = RegExp(r'TC_[A-Za-z0-9_]+').allMatches(rawName).toList();
    for (final m in matches.reversed) {
      final key = m.group(0)!;
      if (matrix.containsKey(key)) return key;
    }
    return rawName;
  }
""";
        for (String marker : List.of("  // 3)", "  final lines = result.stdout")) {
            int i = src.indexOf(marker);
            if (i >= 0) return src.substring(0, i) + helper + "\n" + src.substring(i);
        }
        return src;
    }

    /**
     * Tối ưu runner layered-v9 ngay lúc upload để mọi batch dùng cùng chính sách an toàn.
     *
     * Runner cũ đã gom test theo nhóm nhưng lại chạy lại TỪNG testcase fail. Với bài thiếu
     * nhiều file, hàng chục lần khởi động Flutter nối tiếp nhau làm vượt timeout của Docker.
     * Kết quả nhóm đã đủ để chấm; retry từng ID chỉ dành cho chẩn đoán nên bỏ khỏi đường nóng.
     * Đồng thời thay Process.run bằng wrapper có timeout để một process Flutter bị treo tự kết thúc.
     */
    private void normalizeGraderExecution(Path testcaseDir) {
        Path f = testcaseDir.resolve("grader.dart");
        try {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            String patched = src;
            if (patched.contains("failedNames")
                    && patched.contains("Future<Map<String, _TestRun>> _runFlutterGroup")) {
                Pattern retryBlock = Pattern.compile(
                        "(?s)\\n    var runs = await _runWidgetGroup\\(names\\);.*?\\n    for \\(final id in names\\) \\{");
                Matcher matcher = retryBlock.matcher(patched);
                if (matcher.find()) {
                    patched = matcher.replaceFirst(Matcher.quoteReplacement(
                            "\n    final runs = await _runWidgetGroup(names);\n"
                                    + "    for (final id in names) {"));
                }
            }

            if (patched.contains("Process.run(")
                    && patched.contains("Future<Map<String, _TestRun>> _runFlutterGroup")
                    && !patched.contains("Future<ProcessResult> _runProcess(")) {
                if (patched.contains("import 'dart:io';")) {
                    patched = patched.replace("import 'dart:io';",
                            "import 'dart:async';\nimport 'dart:io';");
                }
                patched = patched.replace("Process.run(", "_runProcess(");

                String helper = """

Future<ProcessResult> _runProcess(
  String executable,
  List<String> arguments, {
  Map<String, String>? environment,
  String? workingDirectory,
  bool runInShell = false,
}) async {
  const limit = Duration(seconds: %d);
  final process = await Process.start(
    executable,
    arguments,
    environment: environment,
    workingDirectory: workingDirectory,
    runInShell: runInShell,
  );
  final stdoutFuture = process.stdout.transform(utf8.decoder).join();
  final stderrFuture = process.stderr.transform(utf8.decoder).join();
  try {
    final code = await process.exitCode.timeout(limit);
    return ProcessResult(
      process.pid,
      code,
      await stdoutFuture,
      await stderrFuture,
    );
  } on TimeoutException {
    process.kill();
    final out = await stdoutFuture.timeout(
      const Duration(seconds: 2),
      onTimeout: () => '',
    );
    final err = await stderrFuture.timeout(
      const Duration(seconds: 2),
      onTimeout: () => '',
    );
    return ProcessResult(
      process.pid,
      -1,
      out,
      '${err}\\nGRADER_PROCESS_TIMEOUT after %ds',
    );
  }
}
""".formatted(runnerProcessTimeoutSeconds, runnerProcessTimeoutSeconds);

                int marker = patched.indexOf("Future<void> main()");
                if (marker >= 0) {
                    patched = patched.substring(0, marker) + helper + "\n" + patched.substring(marker);
                }
            }

            if (!patched.equals(src)) {
                Files.writeString(f, patched, StandardCharsets.UTF_8);
                log.info("Đã tối ưu grader.dart: bỏ retry hàng loạt, timeout process {}s: {}",
                        runnerProcessTimeoutSeconds, f);
            }
        } catch (Exception e) {
            log.warn("Tự tối ưu execution của grader.dart lỗi: {}", e.getMessage());
        }
    }

    /**
     * CHẶN testcase import package KHÔNG có trong môi trường chấm (chỉ có những gì khai trong
     * pubspec.base.yaml: flutter, flutter_test...). Đây là nguyên nhân khiến CẢ LỚP bị 0/0 oan
     * (vd đề import 'package:intl/intl.dart' → exam_test.dart không biên dịch được). Bắt ngay lúc upload.
     */
    private void validateTestcaseImports(Path testcaseDir) throws Exception {
        Set<String> allowed = allowedPackages();
        Set<String> bad = missingTestcasePackages(testcaseDir, allowed);
        if (!bad.isEmpty())
            throw new IllegalArgumentException(missingPackageMessage(bad, allowed, null));
    }

    /**
     * Khi upload testcase, nếu đề import package chưa có trong môi trường chấm thì tự thêm vào
     * pubspec.base.yaml và cập nhật ảnh grading-base ngay. Nếu Docker/pub get lỗi, báo rõ tên package
     * để GV thêm thủ công ở trang "Thư viện chấm".
     */
    private void ensureTestcaseImportsAvailable(Path testcaseDir) throws Exception {
        Set<String> allowed = allowedPackages();
        Set<String> missing = missingTestcasePackages(testcaseDir, allowed);
        if (missing.isEmpty()) return;

        synchronized (envLock) {
            if (envBuilding)
                throw new IllegalStateException("Môi trường chấm đang cập nhật thư viện, vui lòng đợi build xong rồi upload lại testcase.");
            envBuilding = true;
            setEnv("RESOLVING", "Testcase cần thêm thư viện: " + String.join(", ", missing), "");
        }
        try {
            List<Map<String, Object>> desired = editablePackagesWithMissing(missing);
            doApplyPackagesBlocking(desired);
        } catch (Exception e) {
            throw new IllegalArgumentException(missingPackageMessage(missing, allowed, e.getMessage()));
        } finally {
            envBuilding = false;
        }

        Set<String> stillMissing = missingTestcasePackages(testcaseDir, allowedPackages());
        if (!stillMissing.isEmpty())
            throw new IllegalArgumentException(missingPackageMessage(stillMissing, allowedPackages(), null));
    }

    private Set<String> missingTestcasePackages(Path testcaseDir, Set<String> allowed) throws Exception {
        java.util.regex.Pattern p =
                java.util.regex.Pattern.compile("import\\s+['\"]package:([A-Za-z0-9_]+)/");
        Set<String> bad = new LinkedHashSet<>();
        List<Path> dartFiles;
        try (Stream<Path> s = Files.walk(testcaseDir)) {
            dartFiles = s.filter(f -> f.toString().endsWith(".dart")).toList();
        }
        for (Path f : dartFiles) {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                java.util.regex.Matcher m = p.matcher(line);
                if (m.find() && !allowed.contains(m.group(1))) bad.add(m.group(1));
            }
        }
        return bad;
    }

    private List<Map<String, Object>> editablePackagesWithMissing(Set<String> missing) {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> p : listManagedPackages()) {
            if (Boolean.TRUE.equals(p.get("protected"))) continue;
            String name = String.valueOf(p.getOrDefault("name", "")).trim();
            if (name.isBlank()) continue;
            Map<String, Object> copy = new LinkedHashMap<>();
            copy.put("name", name);
            Object version = p.get("version");
            if (version != null && !String.valueOf(version).isBlank()) copy.put("version", version);
            byName.put(name, copy);
        }
        for (String name : missing) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", name);
            p.put("version", ""); // để flutter pub add resolve version tương thích SDK trong ảnh nền
            byName.putIfAbsent(name, p);
        }
        return new ArrayList<>(byName.values());
    }

    private String missingPackageMessage(Set<String> missing, Set<String> allowed, String cause) {
        StringBuilder msg = new StringBuilder();
        msg.append("Testcase dùng package chưa có trong môi trường chấm: ")
           .append(String.join(", ", missing))
           .append(". Backend đã thử tự thêm vào grading-base nhưng chưa thành công.");
        if (cause != null && !cause.isBlank()) msg.append(" Chi tiết: ").append(cause);
        msg.append(" Hãy vào trang \"Thư viện chấm\" thêm package: ")
           .append(String.join(", ", missing))
           .append(" rồi đợi trạng thái READY, hoặc thêm vào grader-base/pubspec.base.yaml và build/cập nhật lại ảnh nền. ")
           .append("Môi trường hiện có: ").append(String.join(", ", allowed)).append(".");
        return msg.toString();
    }

    /** Tập package được phép = các dependency khai trong pubspec.base.yaml (nguồn sự thật của ảnh nền). */
    private Set<String> allowedPackages() {
        Set<String> allowed = new HashSet<>(Set.of("flutter", "flutter_test"));
        try {
            Path pubspec = locateTemplateDir().resolve("pubspec.base.yaml");
            if (Files.exists(pubspec)) {
                boolean inDeps = false;
                java.util.regex.Pattern dep = java.util.regex.Pattern.compile("^ {2}([A-Za-z0-9_]+)\\s*:");
                for (String raw : Files.readAllLines(pubspec, StandardCharsets.UTF_8)) {
                    String line = raw.replace("\t", "  ");
                    if (line.isBlank() || line.trim().startsWith("#")) continue;
                    if (!line.startsWith(" ")) {                       // header ở cột 0
                        String key = line.split(":")[0].trim();
                        inDeps = key.equals("dependencies") || key.equals("dev_dependencies");
                        continue;
                    }
                    java.util.regex.Matcher m = dep.matcher(line);     // entry "  name:" (2 space)
                    if (inDeps && m.find()) allowed.add(m.group(1));
                }
            }
        } catch (Exception e) {
            log.warn("Đọc pubspec.base.yaml để lấy danh sách package cho phép lỗi: {}", e.getMessage());
        }
        return allowed;
    }

    /**
     * Kiểm tra skill_code trong skills_matrix.json (nếu giảng viên có khai) phải nằm trong
     * syllabus và chưa bị deprecate. Testcase KHÔNG khai skill_code được bỏ qua (tương thích đề cũ).
     */
    private void validateSkillCodes(Path testcaseDir) throws Exception {
        Path f = testcaseDir.resolve("skills_matrix.json");
        if (!Files.exists(f)) return;
        checkSkillsMatrixProblems(Files.readString(f, StandardCharsets.UTF_8));
    }

    /**
     * Kiểm skill_code khi SỬA FILE — nới hơn lúc upload ZIP một bậc: lỗi ĐÃ CÓ SẴN trong bộ đang
     * lưu chỉ được cảnh báo. Chặn cứng thì những bộ cũ (mã kỹ năng về sau bị bỏ khỏi syllabus)
     * không bao giờ lưu lại được, mà bấm Lưu lại chính là bước chuyển Nháp → Hoàn tất: bản clone
     * của các bộ đó sẽ mắc kẹt ở Nháp vĩnh viễn. Lỗi MỚI phát sinh trong lần sửa này vẫn chặn.
     *
     * @return cảnh báo cần hiện cho giáo viên, hoặc null nếu matrix sạch
     */
    private String checkEditedSkillsMatrix(Path currentFile, String newJson) {
        List<Map<String, Object>> problems = skillCodeErrors(newJson);
        if (problems.isEmpty()) return null;

        Set<String> before = new HashSet<>();
        try {
            if (Files.isRegularFile(currentFile))
                skillCodeErrors(Files.readString(currentFile, StandardCharsets.UTF_8))
                        .forEach(p -> before.add(problemKey(p)));
        } catch (Exception ignored) {
            // Đọc bản cũ hỏng thì coi như không có lỗi cũ → mọi lỗi tính là mới (chặt tay hơn).
        }
        List<Map<String, Object>> added = problems.stream()
                .filter(p -> !before.contains(problemKey(p))).toList();
        if (!added.isEmpty())
            throw new IllegalArgumentException(
                    "skills_matrix.json có skill_code không hợp lệ: " + describeProblems(added));

        List<Map<String, Object>> shown = problems.size() > 5 ? problems.subList(0, 5) : problems;
        return "Bộ này vẫn còn " + problems.size() + " skill_code không có trong syllabus ("
                + describeProblems(shown) + (problems.size() > 5 ? "; …" : "")
                + ") — các tiêu chí đó không vào được bảng năng lực.";
    }

    private List<Map<String, Object>> skillCodeErrors(String matrixJson) {
        return syllabusService.validateSkillsMatrix(matrixJson).stream()
                .filter(p -> !"warning".equals(p.get("severity"))).toList();
    }

    private String problemKey(Map<String, Object> problem) {
        return problem.get("testId") + "|" + problem.get("skillCode") + "|" + problem.get("issue");
    }

    /** Dùng cho lúc upload ZIP — chặt tay: mọi skill_code lạ đều chặn. */
    private void checkSkillsMatrixProblems(String matrixJson) {
        List<Map<String, Object>> problems = syllabusService.validateSkillsMatrix(matrixJson);
        if (problems.isEmpty()) return;

        // "error" (skill_code sai/deprecated, difficulty không hợp lệ) → CHẶN upload.
        // "warning" (vd weight lệch độ khó) → chỉ ghi log, vẫn cho upload (grader tự suy weight từ difficulty).
        List<Map<String, Object>> errors   = problems.stream()
                .filter(p -> !"warning".equals(p.get("severity"))).toList();
        List<Map<String, Object>> warnings = problems.stream()
                .filter(p ->  "warning".equals(p.get("severity"))).toList();

        if (!warnings.isEmpty())
            log.warn("⚠️ skills_matrix.json có {} cảnh báo: {}", warnings.size(), describeProblems(warnings));
        if (!errors.isEmpty())
            throw new IllegalArgumentException(
                    "skills_matrix.json có skill_code không hợp lệ: " + describeProblems(errors));
    }

    private String describeProblems(List<Map<String, Object>> problems) {
        return problems.stream()
                .map(p -> p.get("testId") + " → " + p.get("skillCode") + " (" + p.get("issue") + ")")
                .collect(java.util.stream.Collectors.joining("; "));
    }

    /**
     * Lưu phiên bản testcase CŨ trước khi upload đè (di chuyển sang testcase-archive/&lt;epochMillis&gt;/)
     * để sau này còn tra lại đúng bộ đề đã từng dùng nếu nghi ngờ ra đề/chấm sai. Lỗi archive → xoá đè.
     */
    private void archiveTestcase(String examId, Path testcaseDir) {
        try {
            Path archiveDir = testcaseDir.resolveSibling("testcase-archive")
                    .resolve(String.valueOf(Instant.now().toEpochMilli()));
            Files.createDirectories(archiveDir.getParent());
            Files.move(testcaseDir, archiveDir);
            log.info("🗂️ Đã lưu phiên bản testcase cũ của {} → {}", examId, archiveDir);
        } catch (Exception e) {
            log.warn("Không lưu được phiên bản testcase cũ của {}: {} — chuyển sang xoá đè.",
                    examId, e.getMessage());
            try { deleteRecursively(testcaseDir); } catch (Exception ignored) {}
        }
    }

    private void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
        }
    }

    private void unzip(byte[] bytes, Path dest) throws Exception {
        try (var zis = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            int entryCount = 0;
            long totalBytes = 0;
            byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                if (++entryCount > MAX_TESTCASE_ZIP_ENTRIES)
                    throw new IllegalArgumentException("ZIP có quá nhiều file (tối đa "
                            + MAX_TESTCASE_ZIP_ENTRIES + ").");
                Path out = dest.resolve(entry.getName()).normalize();
                if (!out.startsWith(dest))
                    throw new IllegalArgumentException("Zip Slip: " + entry.getName());
                if (entry.isDirectory()) { Files.createDirectories(out); }
                else {
                    Files.createDirectories(out.getParent());
                    try (var output = Files.newOutputStream(out)) {
                        int read;
                        while ((read = zis.read(buffer)) != -1) {
                            totalBytes += read;
                            if (totalBytes > MAX_TESTCASE_UNZIPPED_BYTES)
                                throw new IllegalArgumentException(
                                        "Nội dung ZIP sau giải nén vượt quá giới hạn 50 MB.");
                            output.write(buffer, 0, read);
                        }
                    }
                }
            }
        }
    }
}
