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
            exam.setTeacherNote(text(suite, "description"));
            exam.setTestcasePath(target.toAbsolutePath().toString());
            exam.setStatus(ExamStatus.READY);
            exam.setTestcaseStatus("PUBLISHED");
            exam.setTestcaseVersion((exam.getTestcaseVersion() == null ? 0 : exam.getTestcaseVersion()) + 1);
            exam.setTestcasePublishedAt(Instant.now());
            exams.save(exam);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("exam_id", examId);
            result.put("testcase_path", target.toAbsolutePath().toString());
            result.put("scenario_count", list(plan.get("scenarios")).size());
            result.put("criterion_count", matrix.size());
            result.put("files", List.of(
                    "exam_test.dart", "grader.dart", "behavior_plan.json",
                    "skills_matrix.json", "contract.json", "suite_manifest.json",
                    "fixtures/student.db", "fixtures/hidden.db", "fixtures/expected-output.db"));
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
    public Map<String, Object> previewCode(String suiteId, String selectedScenarioCode) {
        try {
            Map<String, Object> plan = authoring.previewExecutionPlan(suiteId);
            Map<String, Object> suite = map(plan.get("suite"));
            String suiteCode = ExamService.safeId(text(suite, "suite_code"), "bộ chấm");
            List<Map<String, Object>> cases = expandCases(plan, suiteCode);
            Map<String, Object> matrix = fullMatrix(suiteId, suiteCode, cases);

            String requestedCode = selectedScenarioCode == null ? "" : selectedScenarioCode.trim();
            Map<String, Object> selectedScenario = new LinkedHashMap<>();
            if (!requestedCode.isBlank()) {
                selectedScenario = list(plan.get("scenarios")).stream()
                        .map(BehaviorSuiteMaterializer::map)
                        .filter(item -> requestedCode.equalsIgnoreCase(text(item, "scenario_code")))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Không tìm thấy scenario " + requestedCode + " trong bộ chấm"));
            }

            List<Map<String, Object>> files = new ArrayList<>();
            if (!selectedScenario.isEmpty()) {
                String scenarioCode = text(selectedScenario, "scenario_code");
                Map<String, Object> scenarioMatrix = new LinkedHashMap<>();
                matrix.forEach((testId, rawMetadata) -> {
                    Map<String, Object> metadata = map(rawMetadata);
                    if (scenarioCode.equals(String.valueOf(metadata.get("scenario_code")))) {
                        scenarioMatrix.put(testId, metadata);
                    }
                });
                files.add(previewFile(
                        "scenario.json",
                        "Scenario đang chọn: action, checkpoint, viewport, biến và oracle.",
                        "SCENARIO",
                        json(selectedScenario)));
                files.add(previewFile(
                        "scenario_testcases.json",
                        "Các đầu điểm thực tế được tách ra từ checkpoint của scenario này.",
                        "SCENARIO",
                        json(scenarioMatrix)));
            }

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
                    "exam_test.dart",
                    "Runner Dart dùng chung; nội dung từng scenario được đọc từ behavior_plan.json.",
                    "ENGINE",
                    readResource("behavior-replay-engine/exam_test.dart")));
            files.add(previewFile(
                    "grader.dart",
                    "Script tổng hợp kết quả flutter test thành điểm và JSON báo cáo.",
                    "ENGINE",
                    readResource("behavior-replay-engine/grader.dart")));
            files.add(previewFile(
                    "contract.json",
                    "Contract công khai và package được phép của bộ chấm.",
                    "BUNDLE",
                    json(publicContract(plan))));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("suite_id", suiteId);
            result.put("suite_code", suiteCode);
            result.put("selected_scenario_code",
                    selectedScenario.isEmpty() ? null : selectedScenario.get("scenario_code"));
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
        Map<String, Object> first = previewCode(suiteId, null);
        Map<String, Object> second = previewCode(suiteId, null);
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
        copyArtifact(suiteId, BehaviorArtifactType.STUDENT_DATABASE, fixtures.resolve("student.db"));
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
        writeJson(target.resolve("contract.json"), publicContract(plan));
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
            try (var pngs = Files.list(screens)) {
                for (Path png : pngs.filter(f -> f.getFileName().toString().endsWith(".png")).toList()) {
                    Files.copy(png, dest.resolve(png.getFileName().toString()),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /**
     * Checkpoint mang trọng số TUYỆT ĐỐI (điểm khai lúc tick, không chia theo scenario)
     * và chỉ chạy trên viewport đầu: thành phần giao diện và so ảnh bố cục.
     */
    private static final java.util.Set<String> ABSOLUTE_WEIGHT_KINDS =
            java.util.Set.of("component_present", "component_position", "component_color",
                    "theme_color", "screen_match");

    private List<Map<String, Object>> expandCases(Map<String, Object> plan, String suiteCode) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object rawScenario : list(plan.get("scenarios"))) {
            Map<String, Object> scenario = map(rawScenario);
            List<Object> checkpoints = list(scenario.get("checkpoints"));
            if (checkpoints.isEmpty()) continue;
            List<Object> scenarioViewports = list(scenario.get("viewports"));
            if (scenarioViewports.isEmpty()) {
                scenarioViewports = List.of(Map.of(
                        // Máy Android tầm trung (Pixel): 412×915 dp. Khung desktop đã bỏ —
                        // sinh viên chỉ làm bài trên máy ảo Android. Mật độ để 1 vì chấm bố
                        // cục đo bằng dp, mật độ chỉ ảnh hưởng độ nét ảnh bằng chứng.
                        "name", "phone", "width", 412, "height", 915, "device_pixel_ratio", 1));
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
                    .mapToDouble(item -> Math.max(0.0001, number(item.get("weight"), 1.0)))
                    .sum();
            if (checkpointTotal <= 0) checkpointTotal = 1.0;
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
                List<Object> checkpointViewports = databaseCheckpoint || componentCheckpoint
                        ? List.of(first(scenarioViewports))
                        : scenarioViewports;
                double checkpointWeight = scenarioWeight
                        * Math.max(0.0001, number(checkpoint.get("weight"), 1.0))
                        / checkpointTotal;
                int viewportIndex = 0;
                for (Object rawViewport : checkpointViewports) {
                    viewportIndex++;
                    Map<String, Object> viewport = map(rawViewport);
                    String viewportName = text(viewport, "name");
                    if (viewportName.isBlank()) viewportName = "viewport_" + viewportIndex;
                    String executionCode = text(scenario, "scenario_code") + "__VP_" + viewportIndex;
                    String testSuffix = checkpointViewports.size() > 1 ? "_" + viewportName : "";
                    String testId = safeTestId(suiteCode + "_" + text(scenario, "scenario_code")
                            + "_" + checkpointId + testSuffix);
                    double itemWeight = checkpointWeight / checkpointViewports.size();
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("test_id", testId);
                    item.put("scenario_id", scenario.get("id"));
                    item.put("scenario_code", scenario.get("scenario_code"));
                    item.put("scenario_name", scenario.get("name"));
                    item.put("execution_code", executionCode);
                    item.put("name", checkpointName(scenario, checkpoint, index)
                            + (checkpointViewports.size() > 1 ? " [" + viewportName + "]" : ""));
                    item.put("description", scenario.get("description"));
                    item.put("skill_code", scenario.getOrDefault("skill_code", "UI_BUTTONS_SELECTION"));
                    item.put("weight", Math.round(itemWeight * 1_000_000d) / 1_000_000d);
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
            String expected = text(checkpoint, "expected");
            if (expected.isBlank()) expected = "Kết quả phải khớp observation của Golden App.";
            boolean component = ABSOLUTE_WEIGHT_KINDS.contains(text(checkpoint, "kind"));
            Map<String, Object> uiGroup = map(checkpoint.get("ui_group"));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("instance_id", item.get("test_id"));
            metadata.put("runner", "BEHAVIOR_REPLAY");
            metadata.put("scenario_code", item.get("scenario_code"));
            metadata.put("execution_code", item.get("execution_code"));
            metadata.put("checkpoint_id", checkpoint.get("id"));
            metadata.put("skill_code", component ? "UI_LAYOUT" : item.get("skill_code"));
            metadata.put("testcase_group", component ? "UI" : "BEHAVIOR");
            metadata.put("layer", component ? "ui" : "behavior");
            // Nhóm là cấp mà điểm lẻ nổi lên (đạt 3/4 thành phần = 15/20) và là cấp đối
            // chiếu với phiếu chấm tay — thiếu group_id thì mỗi dòng lẻ loi không quy về đâu.
            // Dòng behavior không có ui_group thì mỗi SCENARIO là một nhóm: "Thêm khoản chi"
            // trên phiếu tay chính là toàn bộ checkpoint của luồng thêm.
            if (!uiGroup.isEmpty()) {
                metadata.put("group_id", uiGroup.get("id"));
                metadata.put("group_name", uiGroup.get("name"));
            } else if (!text(item, "scenario_code").isBlank()) {
                metadata.put("group_id", "G_" + text(item, "scenario_code"));
                metadata.put("group_name", text(item, "scenario_name").isBlank()
                        ? text(item, "scenario_code") : text(item, "scenario_name"));
            }
            metadata.put("name", component && !text(checkpoint, "name").isBlank()
                    ? checkpoint.get("name") : item.get("name"));
            metadata.put("description", item.get("description"));
            metadata.put("expected", expected);
            metadata.put("difficulty", component ? "basic" : "intermediate");
            metadata.put("weight", item.get("weight"));
            matrix.put(String.valueOf(item.get("test_id")), metadata);
        }
        return matrix;
    }

    private Map<String, Object> publicContract(Map<String, Object> plan) {
        Map<String, Object> runtime = map(plan.get("runtime_config"));
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("engine", "GOLDEN_BEHAVIOR_RECORD_REPLAY");
        contract.put("schema_version", plan.get("schema_version"));
        contract.put("public_contract", plan.get("public_contract"));
        contract.put("database_contract", plan.get("database_contract"));
        contract.put("allowed_packages", runtime.getOrDefault("allowed_packages", List.of(
                "flutter", "flutter_test", "path", "sqflite", "sqflite_common_ffi")));
        return contract;
    }

    private Map<String, Object> executablePlan(Map<String, Object> plan,
                                               Map<String, Object> suite,
                                               List<Map<String, Object>> cases,
                                               boolean includeInternalIdentity) {
        Map<String, Object> databaseContract = new LinkedHashMap<>(map(plan.get("database_contract")));
        databaseContract.put("enabled", true);
        databaseContract.put("student_fixture_path", "/app/test/fixtures/student.db");
        databaseContract.put("hidden_fixture_path", "/app/test/fixtures/hidden.db");
        databaseContract.put("expected_output_path", "/app/test/fixtures/expected-output.db");

        Map<String, Object> executable = new LinkedHashMap<>();
        executable.put("schema_version", plan.get("schema_version"));
        executable.put("suite", gradingSuite(suite, includeInternalIdentity));
        executable.put("public_contract", plan.get("public_contract"));
        executable.put("database_contract", databaseContract);
        executable.put("runtime_config", plan.get("runtime_config"));
        executable.put("cases", cases.stream()
                .map(item -> gradingCase(item, includeInternalIdentity)).toList());
        return executable;
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
                    for (String key : List.of("scenario_code", "name", "skill_code", "description", "display_order",
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

    private String readResource(String resource) throws Exception {
        try (InputStream input = new ClassPathResource(resource).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
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
