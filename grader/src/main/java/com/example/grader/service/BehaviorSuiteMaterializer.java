package com.example.grader.service;

import com.example.grader.entity.Exam;
import com.example.grader.entity.ExamStatus;
import com.example.grader.entity.BehaviorArtifact;
import com.example.grader.entity.BehaviorArtifactType;
import com.example.grader.repository.ExamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Đóng gói execution plan thành bộ runner dùng chung có thể mount trực tiếp vào Docker. */
@Service
public class BehaviorSuiteMaterializer {

    @Value("${grader.template-dir:grader-base}")
    private String templateDir;

    @Value("${grader.exams-dir:exams}")
    private String examsDir;

    private final BehaviorAuthoringService authoring;
    private final BehaviorArtifactService artifacts;
    private final StaticRuleService staticRules;
    private final ExamRepository exams;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public BehaviorSuiteMaterializer(BehaviorAuthoringService authoring,
                                     BehaviorArtifactService artifacts,
                                     StaticRuleService staticRules,
                                     ExamRepository exams) {
        this.authoring = authoring;
        this.artifacts = artifacts;
        this.staticRules = staticRules;
        this.exams = exams;
    }

    @Transactional
    public Map<String, Object> materialize(String suiteId) {
        Map<String, Object> plan = authoring.executionPlan(suiteId);
        Map<String, Object> suite = map(plan.get("suite"));
        String suiteCode = ExamService.safeId(text(suite, "suite_code"), "bộ chấm");
        String configuredExamId = text(suite, "exam_id");
        String examId = ExamService.safeId(
                configuredExamId.isBlank() ? suiteCode : configuredExamId,
                "đề");

        List<Map<String, Object>> cases = expandCases(plan, suiteCode);
        if (cases.isEmpty()) throw new IllegalStateException("Bộ chấm không có checkpoint để publish");
        // Golden có thể đã đổi sau khi lưu luật tĩnh — kiểm lại trước khi phát hành.
        staticRules.requireGoldenCompliance(suiteId);
        Map<String, Object> matrix = fullMatrix(suiteId, suiteCode, cases);

        Path examDir = examsRoot().resolve(examId).normalize();
        Path target = examDir.resolve("testcase").normalize();
        if (!target.startsWith(examDir)) throw new IllegalStateException("Đường dẫn publish không an toàn");

        Path staging = null;
        Path backup = null;
        try {
            Files.createDirectories(examDir);
            staging = Files.createTempDirectory(examDir, ".rar-staging-");
            writeBundle(staging, suiteId, plan, suite, suiteCode, cases, matrix, true, false);

            if (Files.exists(target)) {
                backup = examDir.resolve(".testcase-rar-backup-" + UUID.randomUUID()).normalize();
                Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            moveDirectory(staging, target);
            staging = null;
            if (backup != null) {
                deleteRecursively(backup);
                backup = null;
            }

            Exam exam = exams.findByExamId(examId).orElseGet(Exam::new);
            if (exam.getExamId() == null) exam.setExamId(examId);
            exam.setExamName(text(suite, "name"));
            exam.setTestcasePath(target.toAbsolutePath().toString());
            exam.setStatus(ExamStatus.READY);
            exam.setTestcaseStatus("PUBLISHED");
            exam.setTestcaseVersion((exam.getTestcaseVersion() == null ? 0 : exam.getTestcaseVersion()) + 1);
            exam.setTestcasePublishedAt(Instant.now());
            exams.save(exam);
            donAnhMoCoi(suiteId, maThucThiDangDung(cases));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("exam_id", examId);
            result.put("testcase_path", target.toAbsolutePath().toString());
            result.put("scenario_count", list(plan.get("scenarios")).size());
            result.put("criterion_count", matrix.size());
            result.put("files", List.of(
                    "exam_test.dart", "grader.dart", "behavior_plan.json",
                    "skills_matrix.json", "contract.json", "suite_manifest.json",
                    "fixtures/hidden.db", "fixtures/expected-output.db"));
            result.put("ready_for_grading", true);
            return result;
        } catch (Exception e) {
            try {
                if (staging != null) deleteRecursively(staging);
                if (backup != null && Files.exists(backup) && !Files.exists(target)) {
                    moveDirectory(backup, target);
                }
            } catch (Exception ignored) {
            }
            throw new IllegalStateException("Không publish được bộ Record–Replay: " + e.getMessage(), e);
        }
    }

    /**
     * Sinh bundle tạm để chạy chính Golden Solution. Không ghi Exam, không thay testcase đang
     * dùng để chấm và không yêu cầu suite đã publish.
     */
    public Path createValidationBundle(String suiteId, Path root) {
        Map<String, Object> plan = authoring.previewExecutionPlan(suiteId);
        Map<String, Object> suite = map(plan.get("suite"));
        String suiteCode = ExamService.safeId(text(suite, "suite_code"), "bộ chấm");
        List<Map<String, Object>> cases = expandCases(plan, suiteCode);
        if (cases.isEmpty()) throw new IllegalStateException("Bộ chấm không có checkpoint để preflight");
        Path target = root.toAbsolutePath().normalize().resolve("test");
        try {
            Files.createDirectories(target);
            writeBundle(target, suiteId, plan, suite, suiteCode, cases,
                    fullMatrix(suiteId, suiteCode, cases), true, false);
            return target;
        } catch (Exception e) {
            throw new IllegalStateException("Không sinh được bundle preflight: " + e.getMessage(), e);
        }
    }

    /**
     * Sinh runner tạm cho bước lấy oracle. Ở thời điểm này Output Database chưa tồn tại;
     * runner sẽ khởi tạo từ Hidden DB, replay Golden Solution và tự capture DB sau thao tác.
     */
    public Path createCaptureBundle(String suiteId, Path root) {
        Map<String, Object> plan = authoring.previewExecutionPlan(suiteId);
        Map<String, Object> suite = map(plan.get("suite"));
        String suiteCode = ExamService.safeId(text(suite, "suite_code"), "bộ chấm");
        List<Map<String, Object>> cases = expandCases(plan, suiteCode);
        if (cases.isEmpty()) {
            throw new IllegalStateException("Record chưa tạo được checkpoint tối thiểu để capture Golden");
        }
        Path target = root.toAbsolutePath().normalize().resolve("test");
        try {
            Files.createDirectories(target);
            writeBundle(target, suiteId, plan, suite, suiteCode, cases,
                    fullMatrix(suiteId, suiteCode, cases), false, true);
            return target;
        } catch (Exception e) {
            throw new IllegalStateException("Không sinh được bundle capture Golden: " + e.getMessage(), e);
        }
    }

    /**
     * Trả về đúng nội dung runner và dữ liệu điều khiển sẽ được sinh cho bộ chấm.
     * Đây là thao tác chỉ đọc: không ghi Exam, không thay bundle đã publish và không cần copy fixture DB.
     */
    public Map<String, Object> previewCode(String suiteId) {
        try {
            Map<String, Object> plan = authoring.previewExecutionPlan(suiteId);
            Map<String, Object> suite = map(plan.get("suite"));
            String suiteCode = ExamService.safeId(text(suite, "suite_code"), "bộ chấm");
            List<Map<String, Object>> cases = expandCases(plan, suiteCode);
            Map<String, Object> matrix = fullMatrix(suiteId, suiteCode, cases);

            // CHỈ ba file NỘI DUNG CỦA BỘ ĐỀ (bỏ 21/9/2026).
            //
            // exam_test.dart và grader.dart đã gỡ khỏi màn xem: chúng là runner DÙNG CHUNG cho
            // mọi đề, 4.300 dòng Dart không đổi theo bộ chấm nào — bày ra chỉ khiến người soạn
            // phải lướt qua chúng để tới thứ mình cần xem.
            //
            // Đường xem riêng từng scenario cũng gỡ: từ khi plan gom theo luồng, mỗi luồng đã là
            // một khối liền mạch trong behavior_plan.json, nên tách ra file riêng không cho thêm
            // thông tin nào mà lại đẻ thêm một trạng thái "đang chọn scenario nào" để nhầm.
            List<Map<String, Object>> files = new ArrayList<>();
            files.add(previewFile(
                    "behavior_plan.json",
                    "Toàn bộ action, checkpoint và oracle mà runner sẽ replay.",
                    "BUNDLE",
                    json(executablePlan(plan, suite, cases, false))));
            files.add(previewFile(
                    "skills_matrix.json",
                    "Danh sách testcase, trọng số và checkpoint dùng để tính điểm.",
                    "BUNDLE",
                    json(matrix)));
            files.add(previewFile(
                    "contract.json",
                    "Contract công khai và package được phép của bộ chấm.",
                    "BUNDLE",
                    json(publicContract(suiteId, plan))));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("suite_id", suiteId);
            result.put("suite_code", suiteCode);
            result.put("scenario_count", list(plan.get("scenarios")).size());
            result.put("criterion_count", matrix.size());
            result.put("files", files);
            return result;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Không tạo được bản xem code: " + e.getMessage(), e);
        }
    }

    /**
     * Sinh hai lần từ cùng trạng thái đã lưu và so hash từng file. Semantic fingerprint
     * bỏ UUID/thời gian để hai suite tương đương có thể được đối chiếu đúng nghĩa.
     */
    public Map<String, Object> determinismReport(String suiteId) {
        Map<String, Object> first = previewCode(suiteId);
        Map<String, Object> second = previewCode(suiteId);
        Map<String, String> firstHashes = previewHashes(first);
        Map<String, String> secondHashes = previewHashes(second);
        Map<String, Object> plan = authoring.previewExecutionPlan(suiteId);
        List<String> legacyScenarios = new ArrayList<>();
        for (Object raw : list(plan.get("scenarios"))) {
            Map<String, Object> scenario = map(raw);
            String seed = text(map(scenario.get("oracle")), "seed");
            if (!seed.isBlank() && !seed.startsWith("rar-v1-")) {
                legacyScenarios.add(text(scenario, "scenario_code"));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("suite_id", suiteId);
        result.put("exact_generation_repeatable", firstHashes.equals(secondHashes));
        result.put("reauthoring_deterministic", legacyScenarios.isEmpty());
        result.put("semantic_fingerprint", semanticFingerprint(plan));
        result.put("file_sha256", firstHashes);
        result.put("legacy_random_seed_scenarios", legacyScenarios);
        result.put("note", legacyScenarios.isEmpty()
                ? "Cùng action, checkpoint, contract, viewport, trọng số và fixture sẽ sinh cùng nội dung chấm."
                : "Bundle hiện tại sinh lặp ổn định, nhưng scenario cũ còn seed ngẫu nhiên; hãy record lại scenario đó để tái tạo độc lập tuyệt đối.");
        return result;
    }

    /**
     * Gỡ bundle đã publish nếu manifest xác nhận nó thuộc đúng bộ chấm này.
     *
     * @return mã đề mà bộ chấm này sở hữu, hoặc null nếu không sở hữu đề nào — người gọi
     *         dùng nó để dọn nốt phía chấm bài (kết quả, mẻ chấm, bài nộp).
     */
    public String deletePublishedBundleIfOwned(String suiteId) {
        Map<String, Object> suite = authoring.getSuite(suiteId);
        String configuredExamId = text(suite, "exam_id");
        String suiteCode = ExamService.safeId(text(suite, "suite_code"), "bộ chấm");
        String examId = ExamService.safeId(configuredExamId.isBlank() ? suiteCode : configuredExamId, "đề");
        Path examDir = examsRoot().resolve(examId).normalize();
        Path target = examDir.resolve("testcase").normalize();
        Path manifest = target.resolve("suite_manifest.json");
        try {
            if (Files.isRegularFile(manifest)) {
                Map<String, Object> content = mapper.readValue(manifest.toFile(), Map.class);
                if (suiteId.equals(text(content, "suite_id"))) {
                    deleteRecursively(target);
                    return examId;
                }
            }
            return null;
        } catch (Exception e) {
            throw new IllegalStateException("Không gỡ được bundle đã publish: " + e.getMessage(), e);
        }
    }

    private void writeBundle(Path target,
                             String suiteId,
                             Map<String, Object> plan,
                             Map<String, Object> suite,
                             String suiteCode,
                             List<Map<String, Object>> cases,
                             Map<String, Object> matrix,
                             boolean requireOutputDatabase,
                             boolean includeInternalIdentity) throws Exception {
        copyResource("behavior-replay-engine/exam_test.dart", target.resolve("exam_test.dart"));
        copyResource("behavior-replay-engine/grader.dart", target.resolve("grader.dart"));

        Path fixtures = target.resolve("fixtures");
        Files.createDirectories(fixtures);
        // student.db KHONG con duoc chep vao bundle: engine chi doc hidden_fixture_path,
        // khong bao gio mo student.db. Viec canh schema nay do khau kiem dong bo khung phat lam.
        copyArtifact(suiteId, BehaviorArtifactType.HIDDEN_DATABASE, fixtures.resolve("hidden.db"));
        if (requireOutputDatabase
                || artifacts.activeOptional(suiteId, BehaviorArtifactType.OUTPUT_DATABASE).isPresent()) {
            copyArtifact(suiteId, BehaviorArtifactType.OUTPUT_DATABASE, fixtures.resolve("expected-output.db"));
        } else {
            // File chỉ là placeholder của bundle capture; không được dùng làm oracle.
            // Output thật sẽ được exam_test.dart ghi sang captured-output.db.
            copyArtifact(suiteId, BehaviorArtifactType.HIDDEN_DATABASE, fixtures.resolve("expected-output.db"));
        }

        writeJson(target.resolve("behavior_plan.json"),
                executablePlan(plan, suite, cases, includeInternalIdentity));
        writeJson(target.resolve("skills_matrix.json"), matrix);
        writeJson(target.resolve("contract.json"), publicContract(suiteId, plan));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("engine", "GOLDEN_BEHAVIOR_RECORD_REPLAY");
        manifest.put("engine_version", "1.0.0");
        manifest.put("suite_id", suiteId);
        manifest.put("suite_code", suiteCode);
        manifest.put("revision", suite.getOrDefault("revision", 1));
        manifest.put("scenario_count", list(plan.get("scenarios")).size());
        manifest.put("criterion_count", matrix.size());
        manifest.put("artifact_manifest", Optional.ofNullable(artifacts.activeManifest(suiteId)).orElse(Map.of()));
        writeJson(target.resolve("suite_manifest.json"), manifest);

        // Ảnh chuẩn cho screen_match: chép nguyên thư mục <artifactRoot>/<suite>/golden_screenshot
        // (mỗi luồng một tệp <execution_code>.png, capture ghi đè) vào bộ đề đã publish.
        Path screens = artifacts.goldenScreenshotDir(suiteId);
        if (Files.isDirectory(screens)) {
            Path dest = target.resolve("fixtures").resolve("screens");
            Files.createDirectories(dest);
            // CHỈ chép ảnh của luồng ĐANG CÓ. Tên tệp khoá theo execution_code, mà mã đó đổi
            // mỗi khi người soạn đổi mã nhóm hoặc tên luồng — ảnh tên cũ nằm lại trong kho và
            // trước đây được chép nguyên si sang gói bàn giao. Đo 21/9/2026 trên
            // PE_PRM393_FA26: 29 tệp cho 16 luồng, 15 tệp mồ côi, từng cặp cũ/mới trùng đúng
            // từng byte — nửa MB ảnh chết đi theo mỗi bản bàn giao.
            Set<String> maDangDung = maThucThiDangDung(cases);
            try (var pngs = Files.list(screens)) {
                for (Path png : pngs.filter(f -> f.getFileName().toString().endsWith(".png")).toList()) {
                    // Tập mã rỗng thì chép tất: thà mang thừa ảnh còn hơn im lặng ship bộ
                    // chấm KHÔNG có ảnh đối chứng nào vì một lỗi suy mã.
                    if (!maDangDung.isEmpty() && !maDangDung.contains(tenKhongDuoi(png))) continue;
                    Files.copy(png, dest.resolve(png.getFileName().toString()),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Mã thực thi của mọi case trong bản đang publish — khoá nhận diện ảnh chuẩn. */
    private Set<String> maThucThiDangDung(List<Map<String, Object>> cases) {
        Set<String> ra = new LinkedHashSet<>();
        for (Map<String, Object> item : cases) {
            String ma = text(item, "execution_code");
            if (!ma.isBlank()) ra.add(ma);
        }
        return ra;
    }

    private static String tenKhongDuoi(Path png) {
        String ten = png.getFileName().toString();
        return ten.substring(0, ten.length() - ".png".length());
    }

    /**
     * Xoá khỏi KHO những ảnh chuẩn không còn luồng nào trỏ tới. Lọc lúc chép chỉ giữ cho gói
     * bàn giao sạch; không dọn kho thì ảnh chết vẫn nằm đó mãi và mỗi lần đổi mã lại đẻ thêm.
     *
     * Nuốt lỗi: dọn kho là việc phụ, không được làm hỏng một lượt publish đã thành công.
     */
    private void donAnhMoCoi(String suiteId, Set<String> maDangDung) {
        if (maDangDung.isEmpty()) return;
        try {
            Path screens = artifacts.goldenScreenshotDir(suiteId);
            if (!Files.isDirectory(screens)) return;
            try (var pngs = Files.list(screens)) {
                for (Path png : pngs.filter(f -> f.getFileName().toString().endsWith(".png")).toList()) {
                    if (!maDangDung.contains(tenKhongDuoi(png))) Files.deleteIfExists(png);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Checkpoint mang trọng số TUYỆT ĐỐI (điểm khai lúc tick, không chia theo scenario)
     * và chỉ chạy trên viewport đầu: thành phần giao diện và so ảnh bố cục.
     */
    private static final java.util.Set<String> ABSOLUTE_WEIGHT_KINDS =
            java.util.Set.of("component_present", "component_position", "layout_relation", "component_color",
                    "theme_color", "screen_match",
                    // Ch.7 — cùng lý lẽ với component_present: mỗi tiêu chí là MỘT khẳng
                    // định nhị phân về một widget cụ thể, không phải một bước trong luồng
                    // hành vi nên không nên bị pha loãng theo trọng số scenario.
                    "component_scroll_direction", "component_scroll_to_end", "component_stack_order",
                    "component_indexed_switch", "component_bottom_sheet", "component_table",
                    "component_sliver_collapse", "component_expanded");
    private static final java.util.Set<String> SINGLE_VIEWPORT_KINDS =
            java.util.Set.of("route_state");

    /**
     * KHUNG DESKTOP — dùng cho kĩ năng responsive, và CHỈ cho nó.
     *
     * <p>1280×800 là cỡ laptop phổ biến nhất và đủ xa 412 để lộ bố cục cứng: app không khai
     * breakpoint nào thì ở đây vẫn xếp dọc y như trên điện thoại, còn app biết co giãn thì
     * đổi quan hệ (dưới nhau → cùng hàng). Chính chỗ ĐỔI đó là thứ được chấm.
     *
     * <p>Vì sao khoá cứng một cỡ: cùng lý lẽ với khung điện thoại — mỗi đề một cỡ thì không
     * so được đề này với đề kia, mà hậu quả chỉ lộ ra sau khi đã chấm.
     */
    static final Map<String, Object> KHUNG_DESKTOP = Map.of(
            "name", "desktop", "width", 1280, "height", 800, "device_pixel_ratio", 1);

    /** Hậu tố mã thực thi của lượt replay ở khung desktop. */
    static final String HAU_TO_DESKTOP = "__VP_DESKTOP";

    /** Checkpoint khai cờ này thì chạy ở khung desktop thay vì khung điện thoại. */
    static final String KHUNG_DESKTOP_CO = "desktop";
    private static final java.util.Set<String> CH7_KINDS = java.util.Set.of(
            "component_scroll_direction", "component_scroll_to_end", "component_stack_order",
            "component_indexed_switch", "component_bottom_sheet", "component_table",
            "component_sliver_collapse", "component_expanded");

    private List<Map<String, Object>> expandCases(Map<String, Object> plan, String suiteCode) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object rawScenario : list(plan.get("scenarios"))) {
            Map<String, Object> scenario = map(rawScenario);
            List<Object> checkpoints = list(scenario.get("checkpoints"));
            if (checkpoints.isEmpty()) continue;
            List<Object> scenarioViewports = list(scenario.get("viewports"));
            if (scenarioViewports.isEmpty()) {
                scenarioViewports = List.of(Map.of(
                        // KHUNG APP THẬT trên máy ảo Pixel 7 API 34: 412×838 dp — KHÔNG phải
                        // 412×915. Màn là 411,43×914,29 dp nhưng hệ điều hành giữ lại 77 dp
                        // cho thanh trạng thái (51,8) và thanh cử chỉ (24); đo bằng
                        // `adb shell dumpsys window displays` → mAppBounds=Rect(0,136-1080,2337).
                        // Phải khớp mặc định trong exam_test.dart._applyViewport.
                        // Mật độ để 1 vì chấm bố cục đo bằng dp, mật độ chỉ ảnh hưởng độ nét
                        // ảnh bằng chứng.
                        "name", "phone", "width", 412, "height", 838, "device_pixel_ratio", 1));
            }
            double scenarioWeight = number(scenario.get("weight"), 1.0);
            // Tiêu chí GIAO DIỆN mang trọng số TUYỆT ĐỐI (điểm của nhóm chia đều lúc tick),
            // KHÔNG tham gia phần chia trọng số của scenario — nếu tham gia, thêm một thành
            // phần giao diện sẽ pha loãng điểm của chính các checkpoint chức năng cùng luồng.
            // MỘT luật điểm duy nhất: mọi checkpoint — kể cả tiêu chí giao diện — chia
            // trọng số hàm theo tỷ lệ. Trước đây tiêu chí giao diện mang điểm TUYỆT ĐỐI
            // cộng ngoài hàm, nên UI_MAIN khai 14đ mà ruột có thể phình vượt 14 — mâu
            // thuẫn với bảng Chia điểm và thẻ ngân sách 100 (tổng con phải bằng cha).
            double checkpointTotal = checkpoints.stream()
                    .map(BehaviorSuiteMaterializer::map)
                    // Checkpoint 0 điểm vẫn chạy để kiểm tiên quyết, không được tự tăng điểm.
                    .mapToDouble(item -> Math.max(0.0, number(item.get("weight"), 1.0)))
                    .sum();
            if (!Double.isFinite(checkpointTotal) || checkpointTotal <= 0) {
                throw new IllegalArgumentException("Hàm test " + text(scenario, "scenario_code")
                        + " phải có ít nhất một checkpoint lớn hơn 0 điểm");
            }
            int index = 0;
            for (Object rawCheckpoint : checkpoints) {
                Map<String, Object> checkpoint = map(rawCheckpoint);
                index++;
                String checkpointId = text(checkpoint, "id");
                if (checkpointId.isBlank()) checkpointId = "CHECKPOINT_" + index;
                boolean databaseCheckpoint = "database_observation".equals(text(checkpoint, "kind"))
                        || "database".equals(text(checkpoint, "scope"));
                boolean componentCheckpoint = ABSOLUTE_WEIGHT_KINDS.contains(text(checkpoint, "kind"));
                // Thành phần giao diện không đổi theo bề ngang màn hình (đó là việc của tầng
                // bố cục) → chỉ chạy trên viewport đầu, khỏi nhân bản testcase lẫn trọng số.
                boolean singleViewport = SINGLE_VIEWPORT_KINDS.contains(text(checkpoint, "kind"));
                // TIÊU CHÍ RESPONSIVE: chạy ở khung desktop thay vì khung điện thoại.
                //
                // Nó KHÔNG đi qua đường nhân bản viewport bên dưới. Đường đó nhân mỗi
                // checkpoint ra mọi khung rồi chia trọng số cho số khung — nghĩa là một tiêu
                // chí đạt ở khung này mà trượt ở khung kia chỉ mất một nửa điểm. Responsive
                // là kĩ năng RIÊNG có điểm riêng: tiêu chí của nó chỉ tồn tại ở đúng một
                // khung, giữ nguyên phần điểm đã khai, và không đụng tới tiêu chí nào khác.
                boolean khungDesktop = KHUNG_DESKTOP_CO.equals(text(checkpoint, "khung"));
                List<Object> checkpointViewports = khungDesktop
                        ? List.of(KHUNG_DESKTOP)
                        : databaseCheckpoint || componentCheckpoint || singleViewport
                                ? List.of(first(scenarioViewports))
                                : scenarioViewports;
                double rawWeight = Math.max(0.0, number(checkpoint.get("weight"), 1.0));
                // Khi đã chia đúng tổng thì giữ điểm khai, tránh sai số do nhân/chia lại.
                double checkpointWeight = Math.abs(checkpointTotal - scenarioWeight)
                        <= Math.ulp(scenarioWeight) * Math.max(8, checkpoints.size() * 2)
                        ? rawWeight : scenarioWeight * (rawWeight / checkpointTotal);
                int viewportIndex = 0;
                for (Object rawViewport : checkpointViewports) {
                    viewportIndex++;
                    Map<String, Object> viewport = map(rawViewport);
                    String viewportName = text(viewport, "name");
                    if (viewportName.isBlank()) viewportName = "viewport_" + viewportIndex;
                    // Mã thực thi quyết định case nào chạy CHUNG một lượt replay. Khung desktop
                    // phải có mã riêng, không thì nó bị gom vào lượt chạy ở khung điện thoại và
                    // đo bố cục sai khung.
                    String executionCode = text(scenario, "scenario_code")
                            + (khungDesktop ? HAU_TO_DESKTOP : "__VP_" + viewportIndex);
                    String testSuffix = checkpointViewports.size() > 1 ? "_" + viewportName : "";
                    // test_id = [mã nhóm]_[tên luồng]_[checkpoint] (chốt 21/9/2026). Mã đề đã bỏ
                    // khỏi tiền tố: mọi tiêu chí trong file đều thuộc cùng một đề, nhắc lại mã đề
                    // ở từng dòng chỉ làm bảng điểm dài ra mà không phân biệt thêm được gì.
                    // scenario_code đã mang sẵn phần [mã nhóm]_[tên luồng].
                    String testId = safeTestId(text(scenario, "scenario_code")
                            + "_" + checkpointId + testSuffix);
                    double itemWeight = checkpointWeight / checkpointViewports.size();
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("test_id", testId);
                    item.put("scenario_id", scenario.get("id"));
                    item.put("scenario_code", scenario.get("scenario_code"));
                    item.put("group_code", scenario.get("group_code"));
                    item.put("scenario_name", scenario.get("name"));
                    item.put("execution_code", executionCode);
                    item.put("name", checkpointName(scenario, checkpoint, index)
                            + (checkpointViewports.size() > 1 ? " [" + viewportName + "]" : ""));
                    item.put("description", scenario.get("description"));
                    // Không làm tròn điểm tự nhập: điểm rất nhỏ vẫn phải giữ nguyên khi publish.
                    item.put("weight", itemWeight);
                    item.put("initial_state", scenario.get("initial_state"));
                    item.put("steps", scenario.get("steps"));
                    item.put("viewport", viewport);
                    item.put("checkpoint", checkpoint);
                    item.put("oracle", scenario.get("oracle"));
                    out.add(item);
                }
            }
        }
        return out;
    }

    /**
     * Ma trận ĐẦY ĐỦ của bộ đề = dòng sinh từ checkpoint + dòng luật tĩnh của suite.
     * Luật tĩnh sống trong DB (static_rules_json) nên republish không nuốt mất chúng.
     */
    private Map<String, Object> fullMatrix(String suiteId,
                                           String suiteCode,
                                           List<Map<String, Object>> cases) {
        Map<String, Object> matrix = buildMatrix(cases);
        staticRules.matrixRows(suiteId, suiteCode).forEach((id, row) -> {
            if (matrix.containsKey(id)) {
                throw new IllegalStateException("Mã tiêu chí tĩnh trùng với checkpoint: " + id);
            }
            matrix.put(id, row);
        });
        return matrix;
    }

    private Map<String, Object> buildMatrix(List<Map<String, Object>> cases) {
        Map<String, Object> matrix = new LinkedHashMap<>();
        for (Map<String, Object> item : cases) {
            Map<String, Object> checkpoint = map(item.get("checkpoint"));
            boolean component = ABSOLUTE_WEIGHT_KINDS.contains(text(checkpoint, "kind"));
            // SÁU FIELD, KHÔNG HƠN (chốt 21/9/2026). Trước đó bảng có mười lăm, đo lại thì chín
            // cái không có một người đọc nào trong toàn bộ mã nguồn:
            //   · instance_id      — chép y nguyên KHOÁ của chính dòng này
            //   · checkpoint_id    — không ai đọc
            //   · execution_code   — engine đọc bản trong behavior_plan.json, không đọc bản này
            //   · description      — luôn null, màn soạn không có ô nhập mô tả luồng
            //   · expected         — một câu y hệt nhau ở mọi dòng, không mang tin gì
            //   · difficulty       — suy máy móc từ loại tiêu chí rồi tắc ở result.json
            //   · layer, testcase_group — chỉ TestCaseTaxonomy đọc, mà lớp đó không ai gọi
            //   · group_name       — luôn bằng group_id kể từ khi nhóm do người soạn gõ
            // Thêm field mới vào đây thì phải chỉ ra được NGƯỜI ĐỌC, không thì nó lại nằm đó
            // mười tháng và người sau phải đi đo lại từ đầu.
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("runner", "BEHAVIOR_REPLAY");
            metadata.put("scenario_code", item.get("scenario_code"));
            // Tên luồng phải đi kèm: bảng điểm có cột "Luồng" riêng, và nó đọc từ đây.
            metadata.put("scenario_name", item.get("scenario_name"));
            // NHÓM CHỈ ĐẾN TỪ MÃ NHÓM NGƯỜI SOẠN GÕ (chốt 21/9/2026). Để trống = không thuộc
            // nhóm nào, và bảng điểm xếp những luồng đó xuống cuối với ô nhóm ghi "—".
            //
            // Hai đường suy nhóm cũ đã gỡ, ghi lại kẻo có người thấy tiện mà thêm lại:
            //   · theo `ui_group` của bảng tick giao diện — nhãn là tên MÀN, nên hai màn cùng
            //     đặt tên "Màn hình" bị gộp một rọ, kéo tiêu chí của nhiều luồng khác nhau vào
            //     chung một dòng điểm. Ca thật FA26: 23 tiêu chí của ba màn dồn vào một nhóm.
            //   · mỗi scenario là một nhóm — khi đó "nhóm" và "luồng" là một, cột Nhóm không
            //     nói thêm gì, mà lại chặn mất việc gom sáu luồng lọc vào nhóm FILTER.
            String maNhom = text(item, "group_code");
            if (!maNhom.isBlank()) metadata.put("group_id", maNhom);
            metadata.put("name", component && !text(checkpoint, "name").isBlank()
                    ? checkpoint.get("name") : item.get("name"));
            metadata.put("weight", item.get("weight"));
            matrix.put(String.valueOf(item.get("test_id")), metadata);
        }
        return matrix;
    }

    private Map<String, Object> publicContract(String suiteId, Map<String, Object> plan) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("engine", "GOLDEN_BEHAVIOR_RECORD_REPLAY");
        contract.put("schema_version", plan.get("schema_version"));
        contract.put("public_contract", boLocatorDaGo(map(plan.get("public_contract"))));
        contract.put("database_contract", plan.get("database_contract"));
        // Policy của bài nộp chỉ sinh từ chính Golden; cấu hình runtime cũ không còn là nguồn.
        BehaviorArtifact golden = artifacts.active(suiteId, BehaviorArtifactType.GOLDEN_SOLUTION);
        Map<String, Object> goi = PubspecDependencies.readZip(Path.of(golden.getStoragePath()));
        contract.put("allowed_packages", new ArrayList<>(goi.keySet()));
        // RÀNG BUỘC PHIÊN BẢN đi kèm, ở khoá RIÊNG chứ không nhét vào mảng trên: đường chấm
        // (`SubmissionPackagePolicy`) đọc `allowed_packages` bằng `asText()` nên đổi phần tử
        // thành object là mọi tên hoá rỗng và bài nào cũng bị coi là import ngoài luật.
        //
        // Bên người chấm cần con số này: họ thiếu package thì phải thêm vào ảnh chấm, mà thêm
        // không có ràng buộc là để pub tự chọn bản mới nhất — khác bản Golden đã ghi hình thì
        // giao diện lệch đi một chút cũng đủ trượt hàng loạt tiêu chí vị trí.
        // Chỉ giữ ràng buộc dạng CHUỖI. Khai kiểu git/path là một khối lồng, không phải thứ
        // điền được vào ô version của pub — để rỗng cho người chấm tự xử còn hơn đưa họ một
        // giá trị không dán vào đâu được.
        Map<String, String> rangBuoc = new LinkedHashMap<>();
        goi.forEach((ten, v) -> rangBuoc.put(ten, v instanceof String s ? s : ""));
        contract.put("allowed_package_specs", rangBuoc);
        return contract;
    }

    /** Cách tìm widget engine KHÔNG còn cài — không được để lọt vào hợp đồng phát đi. */
    private static final Set<String> LOCATOR_DA_GO = Set.of("value_key", "valueKey");

    /**
     * Bỏ locator đã gỡ khỏi `locator_priority` trước khi ghi contract.json.
     *
     * <p>`public_contract` bị ĐÓNG BĂNG trong bản ghi bộ chấm từ lúc tạo suite, còn
     * {@code defaultPublicContract()} chỉ áp lúc tạo mới. Nên bộ dựng trước ngày gỡ `value_key`
     * vẫn mang nó theo vào mọi lần xuất gói — hợp đồng quảng cáo một cách tìm mà engine không
     * hiểu. Lọc ở khâu GHI thì mọi bộ cũ sạch theo, không phải đụng vào dữ liệu đã lưu.
     */
    private Map<String, Object> boLocatorDaGo(Map<String, Object> publicContract) {
        if (!(publicContract.get("locator_priority") instanceof List<?> thuTu)) return publicContract;
        List<Object> conLai = thuTu.stream()
                .filter(x -> !LOCATOR_DA_GO.contains(String.valueOf(x)))
                .collect(Collectors.toList());
        if (conLai.size() == thuTu.size()) return publicContract;
        Map<String, Object> ra = new LinkedHashMap<>(publicContract);
        ra.put("locator_priority", conLai);
        return ra;
    }

    private Map<String, Object> executablePlan(Map<String, Object> plan,
                                               Map<String, Object> suite,
                                               List<Map<String, Object>> cases,
                                               boolean includeInternalIdentity) {
        Map<String, Object> databaseContract = new LinkedHashMap<>(map(plan.get("database_contract")));
        databaseContract.put("enabled", true);
        databaseContract.put("hidden_fixture_path", "/app/test/fixtures/hidden.db");
        databaseContract.put("expected_output_path", "/app/test/fixtures/expected-output.db");

        Map<String, Object> executable = new LinkedHashMap<>();
        executable.put("schema_version", plan.get("schema_version"));
        executable.put("suite", gradingSuite(suite, includeInternalIdentity));
        executable.put("public_contract", plan.get("public_contract"));
        executable.put("database_contract", databaseContract);
        executable.put("runtime_config", plan.get("runtime_config"));
        executable.put("luong", nhomTheoLuong(cases, includeInternalIdentity));
        return executable;
    }

    /**
     * Gom case theo LUỒNG (execution_code) và đẩy phần dùng chung lên cấp luồng.
     *
     * <p>Trước 21/9/2026 plan là một danh sách phẳng, mỗi dòng tự mang đủ steps, initial_state,
     * viewport và oracle. Đo trên PE_PRM393_FA26: 75 dòng nhưng chỉ <b>15</b> bản steps khác
     * nhau và <b>1</b> bản initial_state — 29% file là bản sao byte-đối-byte của dòng phía trên.
     * Luồng "màn thêm chi tiêu" có 22 tiêu chí nên dãy thao tác của nó bị chép lại 22 lần, và
     * người mở file ra không biết mình đang đọc lặp hay đọc nhầm chỗ.
     *
     * <p>Phần lặp đó KHÔNG ai đọc: engine gom case theo execution_code rồi lấy steps từ case đầu
     * của nhóm ({@code final testCase = cases.first} trong exam_test.dart). Còn {@code oracle}
     * thì bỏ hẳn — không có dòng code nào trong engine đọc {@code testCase['oracle']}, giá trị
     * chuẩn đã được nướng vào {@code checkpoint.expect} từ lúc capture.
     *
     * <p>Đây là phép biến đổi lúc GHI: danh sách {@code cases} trong bộ nhớ giữ nguyên, nên vân
     * tay ngữ nghĩa của plan ({@link #semanticFingerprint}) không đổi và cổng publish không bị
     * ảnh hưởng.
     */
    private List<Map<String, Object>> nhomTheoLuong(List<Map<String, Object>> cases,
                                                    boolean includeInternalIdentity) {
        List<String> capLuong = List.of("execution_code", "scenario_id", "scenario_code",
                "scenario_name", "group_code", "description", "viewport", "initial_state", "steps");
        List<String> capCase = List.of("test_id", "name", "weight", "checkpoint");
        Map<String, Map<String, Object>> theoMa = new LinkedHashMap<>();
        for (Map<String, Object> item : cases) {
            Map<String, Object> luong = theoMa.computeIfAbsent(text(item, "execution_code"), ma -> {
                Map<String, Object> moi = new LinkedHashMap<>();
                for (String khoa : capLuong) {
                    if ("scenario_id".equals(khoa) && !includeInternalIdentity) continue;
                    if (item.containsKey(khoa)) moi.put(khoa, item.get(khoa));
                }
                moi.put("cases", new ArrayList<Map<String, Object>>());
                return moi;
            });
            Map<String, Object> rieng = new LinkedHashMap<>();
            for (String khoa : capCase) {
                if (item.containsKey(khoa)) rieng.put(khoa, item.get(khoa));
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ds = (List<Map<String, Object>>) luong.get("cases");
            ds.add(rieng);
        }
        return new ArrayList<>(theoMa.values());
    }

    private Map<String, Object> gradingSuite(Map<String, Object> suite, boolean includeInternalIdentity) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (includeInternalIdentity) out.put("id", suite.get("id"));
        for (String key : List.of("suite_code", "exam_id", "name", "description", "schema_version", "revision")) {
            if (suite.containsKey(key)) out.put(key, suite.get(key));
        }
        return out;
    }

    private Map<String, Object> gradingCase(Map<String, Object> source, boolean includeInternalIdentity) {
        Map<String, Object> out = new LinkedHashMap<>(source);
        if (!includeInternalIdentity) out.remove("scenario_id");
        Map<String, Object> oracle = map(out.get("oracle"));
        if (!oracle.isEmpty()) {
            Map<String, Object> stableOracle = new LinkedHashMap<>();
            for (String key : List.of("seed", "status", "input", "ui_observation", "database_observation")) {
                if (oracle.containsKey(key)) stableOracle.put(key, oracle.get(key));
            }
            out.put("oracle", stableOracle);
        }
        return out;
    }

    private Map<String, String> previewHashes(Map<String, Object> preview) {
        Map<String, String> hashes = new TreeMap<>();
        for (Object raw : list(preview.get("files"))) {
            Map<String, Object> file = map(raw);
            hashes.put(text(file, "name"), sha256(String.valueOf(file.getOrDefault("content", ""))));
        }
        return hashes;
    }

    private Object canonicalSemanticPlan(Map<String, Object> plan) {
        Map<String, Object> result = new TreeMap<>();
        result.put("public_contract", plan.get("public_contract"));
        result.put("database_contract", plan.get("database_contract"));
        result.put("runtime_config", plan.get("runtime_config"));
        List<Map<String, Object>> semanticScenarios = list(plan.get("scenarios")).stream()
                .map(BehaviorSuiteMaterializer::map)
                .sorted(Comparator.comparing(item -> text(item, "scenario_code")))
                .map(item -> {
                    Map<String, Object> stable = new TreeMap<>();
                    for (String key : List.of("scenario_code", "name", "description", "display_order",
                            "weight", "enabled", "initial_state", "steps", "checkpoints", "viewports")) {
                        if (item.containsKey(key)) stable.put(key, item.get(key));
                    }
                    Map<String, Object> oracle = map(item.get("oracle"));
                    if (!oracle.isEmpty()) stable.put("oracle", gradingCase(Map.of("oracle", oracle), false).get("oracle"));
                    return stable;
                }).toList();
        result.put("scenarios", semanticScenarios);
        return result;
    }

    String semanticFingerprint(Map<String, Object> plan) {
        return sha256(canonicalSemanticPlan(plan));
    }

    private String sha256(Object value) {
        try {
            byte[] bytes = (value instanceof String text ? text : mapper.writeValueAsString(value))
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("Không tính được SHA-256", e);
        }
    }

    private Map<String, Object> previewFile(String name,
                                            String description,
                                            String scope,
                                            String content) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("name", name);
        file.put("description", description);
        file.put("scope", scope);
        file.put("content", content);
        return file;
    }

    private String json(Object value) throws Exception {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n";
    }

    private String checkpointName(Map<String, Object> scenario, Map<String, Object> checkpoint, int index) {
        String label = text(checkpoint, "name");
        if (label.isBlank()) label = text(checkpoint, "label");
        if (label.isBlank()) label = text(checkpoint, "id");
        if (label.isBlank()) label = "Checkpoint " + index;
        return text(scenario, "name") + " — " + label;
    }

    private String safeTestId(String value) {
        String safe = value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_-]+", "_")
                .replaceAll("^_+|_+$", "");
        return safe.length() <= 180 ? safe : safe.substring(0, 180);
    }

    private Path examsRoot() {
        Path configured = Path.of(examsDir);
        if (configured.isAbsolute()) return configured.normalize();
        Path template = locateTemplateDir();
        Path root = template.getParent();
        return (root == null ? configured.toAbsolutePath() : root.resolve(configured)).normalize();
    }

    private Path locateTemplateDir() {
        Path configured = Path.of(templateDir);
        String name = configured.getFileName() == null ? "grader-base" : configured.getFileName().toString();
        Path cursor = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && cursor != null; depth++, cursor = cursor.getParent()) {
            for (Path candidate : List.of(cursor.resolve(configured), cursor.resolve(name))) {
                if (Files.exists(candidate.resolve("Dockerfile.base"))) return candidate.normalize();
            }
        }
        return configured.toAbsolutePath().normalize();
    }

    private void copyResource(String resource, Path target) throws Exception {
        try (InputStream input = new ClassPathResource(resource).getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void copyArtifact(String suiteId, BehaviorArtifactType type, Path target) throws Exception {
        BehaviorArtifact artifact = artifacts.active(suiteId, type);
        Path source = Path.of(artifact.getStoragePath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException("Artifact " + type + " không còn tồn tại trên đĩa");
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void writeJson(Path file, Object value) throws Exception {
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n",
                StandardCharsets.UTF_8);
    }

    private void moveDirectory(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static Object first(List<Object> values) {
        return values.isEmpty() ? Map.of() : values.get(0);
    }

    private static List<Object> list(Object value) {
        return value instanceof List<?> source ? new ArrayList<>(source) : new ArrayList<>();
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((key, item) -> out.put(String.valueOf(key), item));
        return out;
    }

    private static String text(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
