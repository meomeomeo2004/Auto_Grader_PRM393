package com.example.grader.service;

import com.example.grader.entity.Exam;
import com.example.grader.entity.BehaviorArtifact;
import com.example.grader.entity.BehaviorArtifactType;
import com.example.grader.repository.ExamRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BehaviorSuiteMaterializerTest {

    @TempDir
    Path tempDir;

    /** Materializer với luật tĩnh RỖNG — các test chỉ quan tâm phần checkpoint. */
    private BehaviorSuiteMaterializer newMaterializer(BehaviorAuthoringService authoring,
                                                      BehaviorArtifactService artifacts,
                                                      ExamRepository exams) {
        StaticRuleService staticRules = mock(StaticRuleService.class);
        when(staticRules.matrixRows(anyString(), anyString())).thenReturn(new LinkedHashMap<>());
        // Suite test không có ảnh chuẩn — trả thư mục không tồn tại để writeBundle bỏ qua.
        when(artifacts.goldenScreenshotDir(anyString()))
                .thenReturn(tempDir.resolve("golden-screens-missing"));
        return new BehaviorSuiteMaterializer(authoring, artifacts, staticRules, exams);
    }

    @Test
    void materializesGenericRunnerAndSplitsScenarioWeightByCheckpoint() throws Exception {
        BehaviorAuthoringService authoring = mock(BehaviorAuthoringService.class);
        BehaviorArtifactService artifacts = mock(BehaviorArtifactService.class);
        ExamRepository exams = mock(ExamRepository.class);
        BehaviorSuiteMaterializer materializer = newMaterializer(authoring, artifacts, exams);
        ReflectionTestUtils.setField(materializer, "templateDir", Path.of("..", "grader-base").toString());
        ReflectionTestUtils.setField(materializer, "examsDir", tempDir.toString());

        Map<String, Object> scenario = Map.ofEntries(
                Map.entry("scenario_code", "ADD_USER"),
                Map.entry("name", "Thêm người dùng"),
                Map.entry("description", "Nhập dữ liệu và kiểm UI/SQLite"),
                Map.entry("skill_code", "STORAGE_SQLITE_CRUD"),
                Map.entry("weight", 8.0),
                Map.entry("variables", Map.of("field_uid", Map.of("generator", "stable_id"))),
                Map.entry("initial_state", Map.of("reset_storage", true)),
                Map.entry("steps", List.of(Map.of(
                        "id", "step_1", "action", "enter_text",
                        "target", Map.of("semanticId", "field.uid"), "value", "${field_uid}"))),
                Map.entry("viewports", List.of(
                        Map.of("name", "phone", "width", 390, "height", 844),
                        Map.of("name", "desktop", "width", 1280, "height", 800))),
                Map.entry("oracle", Map.of("seed", "seed-01", "input", Map.of())),
                Map.entry("checkpoints", List.of(
                        Map.of("id", "UI_VISIBLE", "kind", "checkpoint", "scope", "ui",
                                "weight", 3.0, "expect", Map.of("visible_texts", List.of("${field_uid}"))),
                        Map.of("id", "DB_ROW", "kind", "database_observation", "weight", 1.0,
                                "table", "users", "operation", "INSERT", "row", Map.of("uid", "${field_uid}")))));
        Map<String, Object> plan = Map.of(
                "schema_version", "1.0",
                "suite", Map.of(
                        "id", "suite-1", "suite_code", "RAR_USER", "exam_id", "RAR_USER_EXAM",
                        "name", "RAR User", "description", "Golden behavior", "revision", 1),
                "public_contract", Map.of("allow_coordinate_fallback", false),
                "database_contract", Map.of("enabled", true, "database_name", "users.db"),
                "runtime_config", Map.of("default_timeout_ms", 5000),
                "scenarios", List.of(scenario));
        when(authoring.executionPlan("suite-1")).thenReturn(plan);
        for (BehaviorArtifactType type : List.of(
                BehaviorArtifactType.STUDENT_DATABASE,
                BehaviorArtifactType.HIDDEN_DATABASE,
                BehaviorArtifactType.OUTPUT_DATABASE)) {
            Path source = tempDir.resolve(type.name().toLowerCase() + ".db");
            Files.writeString(source, "fixture-" + type);
            BehaviorArtifact artifact = new BehaviorArtifact();
            artifact.setArtifactType(type);
            artifact.setStoragePath(source.toString());
            when(artifacts.active("suite-1", type)).thenReturn(artifact);
        }
        when(exams.findByExamId("RAR_USER_EXAM")).thenReturn(Optional.empty());
        when(exams.save(any(Exam.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> result = materializer.materialize("suite-1");

        assertEquals(true, result.get("ready_for_grading"));
        Path output = tempDir.resolve("RAR_USER_EXAM").resolve("testcase");
        for (String file : List.of("exam_test.dart", "grader.dart", "behavior_plan.json",
                "skills_matrix.json", "contract.json", "suite_manifest.json")) {
            assertTrue(Files.exists(output.resolve(file)), file + " phải được sinh");
        }
        String generatedRunner = Files.readString(output.resolve("exam_test.dart"));
        assertTrue(generatedRunner.contains("find.bySemanticsIdentifier(value)"),
                "runner phải phát lại semanticId bằng semantics finder thật");
        assertTrue(generatedRunner.contains("return count == 1;"),
                "action mơ hồ không được ngầm lấy widget đầu tiên");
        JsonNode matrix = new ObjectMapper().readTree(output.resolve("skills_matrix.json").toFile());
        assertEquals(3, matrix.size());
        assertEquals(3.0, matrix.get("RAR_USER_ADD_USER_UI_VISIBLE_PHONE").get("weight").asDouble(), 0.0001);
        assertEquals(3.0, matrix.get("RAR_USER_ADD_USER_UI_VISIBLE_DESKTOP").get("weight").asDouble(), 0.0001);
        assertEquals(2.0, matrix.get("RAR_USER_ADD_USER_DB_ROW").get("weight").asDouble(), 0.0001);
        Map<String, byte[]> firstGeneration = new LinkedHashMap<>();
        for (String file : List.of("exam_test.dart", "grader.dart", "behavior_plan.json",
                "skills_matrix.json", "contract.json", "suite_manifest.json")) {
            firstGeneration.put(file, Files.readAllBytes(output.resolve(file)));
        }
        materializer.materialize("suite-1");
        firstGeneration.forEach((file, content) -> assertArrayEquals(content,
                assertDoesNotThrow(() -> Files.readAllBytes(output.resolve(file))),
                file + " phải giống byte khi sinh lại cùng cấu hình"));
        verify(exams, times(2)).save(argThat(exam -> exam.getTestcasePath().endsWith("testcase")));
    }

    @Test
    void componentCheckpointKeepsAbsoluteWeightAndUiGroup() throws Exception {
        // Tiêu chí giao diện (bảng tick): trọng số TUYỆT ĐỐI, không pha vào phần chia của
        // scenario, chỉ chạy trên viewport đầu, và matrix mang nhóm UI để điểm lẻ nổi lên
        // ở cấp nhóm. Nếu nó lọt vào denominator thì thêm một thành phần giao diện sẽ làm
        // loãng điểm của chính các checkpoint chức năng cùng luồng — đúng lỗi cần chặn.
        BehaviorAuthoringService authoring = mock(BehaviorAuthoringService.class);
        BehaviorArtifactService artifacts = mock(BehaviorArtifactService.class);
        ExamRepository exams = mock(ExamRepository.class);
        BehaviorSuiteMaterializer materializer = newMaterializer(authoring, artifacts, exams);
        ReflectionTestUtils.setField(materializer, "templateDir", Path.of("..", "grader-base").toString());
        ReflectionTestUtils.setField(materializer, "examsDir", tempDir.toString());

        Map<String, Object> scenario = Map.ofEntries(
                Map.entry("scenario_code", "ADD_USER"),
                Map.entry("name", "Thêm người dùng"),
                Map.entry("skill_code", "STORAGE_SQLITE_CRUD"),
                Map.entry("weight", 8.0),
                Map.entry("initial_state", Map.of("reset_storage", true)),
                Map.entry("steps", List.of(Map.of(
                        "id", "step_1", "action", "tap",
                        "target", Map.of("label", "Thêm khoản chi")))),
                Map.entry("viewports", List.of(
                        Map.of("name", "phone", "width", 390, "height", 844),
                        Map.of("name", "desktop", "width", 1280, "height", 800))),
                Map.entry("oracle", Map.of("seed", "seed-01", "input", Map.of())),
                Map.entry("checkpoints", List.of(
                        Map.of("id", "UI_VISIBLE", "kind", "checkpoint", "scope", "ui",
                                "weight", 3.0, "expect", Map.of("visible_texts", List.of("x"))),
                        Map.of("id", "DB_ROW", "kind", "database_observation", "weight", 1.0,
                                "table", "users", "operation", "INSERT", "row", Map.of("uid", "1")),
                        Map.of("id", "UI_COMP", "kind", "component_present",
                                "target", Map.of("label", "Thêm khoản chi"), "visible", true,
                                "name", "Màn danh sách — có nút thêm", "weight", 5.0,
                                "ui_group", Map.of("id", "G_UI_DANH_SACH", "name", "Giao diện — Màn danh sách")))));
        Map<String, Object> plan = Map.of(
                "schema_version", "1.0",
                "suite", Map.of(
                        "id", "suite-1", "suite_code", "RAR_USER", "exam_id", "RAR_USER_EXAM",
                        "name", "RAR User", "description", "Golden behavior", "revision", 1),
                "public_contract", Map.of("allow_coordinate_fallback", false),
                "database_contract", Map.of("enabled", true, "database_name", "users.db"),
                "runtime_config", Map.of("default_timeout_ms", 5000),
                "scenarios", List.of(scenario));
        when(authoring.executionPlan("suite-1")).thenReturn(plan);
        for (BehaviorArtifactType type : List.of(
                BehaviorArtifactType.STUDENT_DATABASE,
                BehaviorArtifactType.HIDDEN_DATABASE,
                BehaviorArtifactType.OUTPUT_DATABASE)) {
            Path source = tempDir.resolve(type.name().toLowerCase() + ".db");
            Files.writeString(source, "fixture-" + type);
            BehaviorArtifact artifact = new BehaviorArtifact();
            artifact.setArtifactType(type);
            artifact.setStoragePath(source.toString());
            when(artifacts.active("suite-1", type)).thenReturn(artifact);
        }
        when(exams.findByExamId("RAR_USER_EXAM")).thenReturn(Optional.empty());
        when(exams.save(any(Exam.class))).thenAnswer(invocation -> invocation.getArgument(0));

        materializer.materialize("suite-1");

        Path output = tempDir.resolve("RAR_USER_EXAM").resolve("testcase");
        JsonNode matrix = new ObjectMapper().readTree(output.resolve("skills_matrix.json").toFile());
        // 2 viewport × UI_VISIBLE + 1 DB_ROW + 1 UI_COMP (chỉ viewport đầu) = 4 dòng.
        assertEquals(4, matrix.size());
        // Phần chia chức năng KHÔNG đổi so với khi chưa có tiêu chí giao diện.
        assertEquals(3.0, matrix.get("RAR_USER_ADD_USER_UI_VISIBLE_PHONE").get("weight").asDouble(), 0.0001);
        assertEquals(2.0, matrix.get("RAR_USER_ADD_USER_DB_ROW").get("weight").asDouble(), 0.0001);
        JsonNode comp = matrix.get("RAR_USER_ADD_USER_UI_COMP");
        assertEquals(5.0, comp.get("weight").asDouble(), 0.0001, "trọng số tuyệt đối, không bị chia");
        assertEquals("UI", comp.get("testcase_group").asText());
        assertEquals("ui", comp.get("layer").asText());
        assertEquals("UI_LAYOUT", comp.get("skill_code").asText());
        assertEquals("G_UI_DANH_SACH", comp.get("group_id").asText());
        assertEquals("Giao diện — Màn danh sách", comp.get("group_name").asText());
        assertEquals("Màn danh sách — có nút thêm", comp.get("name").asText());
    }

    @Test
    void captureBundleDoesNotRequireAnOutputDatabaseAndKeepsScenarioIdentity() throws Exception {
        BehaviorAuthoringService authoring = mock(BehaviorAuthoringService.class);
        BehaviorArtifactService artifacts = mock(BehaviorArtifactService.class);
        ExamRepository exams = mock(ExamRepository.class);
        BehaviorSuiteMaterializer materializer = newMaterializer(authoring, artifacts, exams);
        ReflectionTestUtils.setField(materializer, "templateDir", Path.of("..", "grader-base").toString());

        Map<String, Object> scenario = Map.ofEntries(
                Map.entry("id", "scenario-42"),
                Map.entry("scenario_code", "ADD_USER"),
                Map.entry("name", "Thêm người dùng"),
                Map.entry("skill_code", "STORAGE_SQLITE_CRUD"),
                Map.entry("weight", 1.0),
                Map.entry("variables", Map.of()),
                Map.entry("initial_state", Map.of("reset_storage", true)),
                Map.entry("steps", List.of(Map.of(
                        "id", "step_1", "action", "tap",
                        "target", Map.of("text", "Add")))),
                Map.entry("viewports", List.of(Map.of(
                        "name", "phone", "width", 390, "height", 844))),
                Map.entry("oracle", Map.of("seed", "seed-42", "input", Map.of())),
                Map.entry("checkpoints", List.of(Map.of(
                        "id", "NO_EXCEPTION", "kind", "checkpoint", "scope", "ui",
                        "weight", 1.0, "expect", Map.of("no_exception", true)))));
        Map<String, Object> plan = Map.of(
                "schema_version", "1.0",
                "suite", Map.of("id", "suite-1", "suite_code", "RAR_CAPTURE", "revision", 1),
                "public_contract", Map.of(),
                "database_contract", Map.of("enabled", true, "database_name", "users.db"),
                "runtime_config", Map.of("default_timeout_ms", 5000),
                "scenarios", List.of(scenario));
        when(authoring.previewExecutionPlan("suite-1")).thenReturn(plan);

        for (BehaviorArtifactType type : List.of(
                BehaviorArtifactType.STUDENT_DATABASE,
                BehaviorArtifactType.HIDDEN_DATABASE)) {
            Path source = tempDir.resolve(type.name().toLowerCase() + ".db");
            Files.writeString(source, "fixture-" + type);
            BehaviorArtifact artifact = new BehaviorArtifact();
            artifact.setArtifactType(type);
            artifact.setStoragePath(source.toString());
            when(artifacts.active("suite-1", type)).thenReturn(artifact);
        }
        when(artifacts.activeOptional("suite-1", BehaviorArtifactType.OUTPUT_DATABASE))
                .thenReturn(Optional.empty());
        when(artifacts.activeManifest("suite-1")).thenReturn(Map.of());

        Path output = materializer.createCaptureBundle("suite-1", tempDir.resolve("capture"));

        assertArrayEquals(
                Files.readAllBytes(output.resolve("fixtures/hidden.db")),
                Files.readAllBytes(output.resolve("fixtures/expected-output.db")),
                "Capture bundle chỉ dùng Hidden DB làm placeholder trước khi có Output DB thật");
        JsonNode behaviorPlan = new ObjectMapper().readTree(output.resolve("behavior_plan.json").toFile());
        assertEquals("scenario-42", behaviorPlan.path("cases").get(0).path("scenario_id").asText());
    }

    @Test
    void previewsExactBundleCodeAndCanFocusOneScenarioWithoutArtifacts() {
        BehaviorAuthoringService authoring = mock(BehaviorAuthoringService.class);
        BehaviorArtifactService artifacts = mock(BehaviorArtifactService.class);
        ExamRepository exams = mock(ExamRepository.class);
        BehaviorSuiteMaterializer materializer = newMaterializer(authoring, artifacts, exams);

        Map<String, Object> scenario = Map.ofEntries(
                Map.entry("id", "scenario-screen"),
                Map.entry("scenario_code", "SCREEN_CONTRACT"),
                Map.entry("name", "Cấu trúc màn hình"),
                Map.entry("skill_code", "UI_BUTTONS_SELECTION"),
                Map.entry("weight", 2.0),
                Map.entry("variables", Map.of()),
                Map.entry("initial_state", Map.of("reset_storage", true)),
                Map.entry("steps", List.of(Map.of("id", "boot", "action", "boot", "target", Map.of()))),
                Map.entry("viewports", List.of(Map.of("name", "phone", "width", 390, "height", 844))),
                Map.entry("oracle", Map.of("status", "READY")),
                Map.entry("checkpoints", List.of(Map.of(
                        "id", "FIELD_EMAIL", "kind", "checkpoint", "scope", "ui",
                        "weight", 1.0, "expect", Map.of("semantic_nodes", List.of(
                                Map.of("target", Map.of("label", "Email"), "role", "text_field")))))));
        Map<String, Object> plan = Map.of(
                "schema_version", "1.0",
                "suite", Map.of("id", "suite-1", "suite_code", "RAR_PREVIEW", "revision", 1),
                "public_contract", Map.of("semantic_ids", List.of("field.email")),
                "database_contract", Map.of("enabled", true, "database_name", "users.db"),
                "runtime_config", Map.of("allowed_packages", List.of("flutter")),
                "scenarios", List.of(scenario));
        when(authoring.previewExecutionPlan("suite-1")).thenReturn(plan);

        Map<String, Object> preview = materializer.previewCode("suite-1", "SCREEN_CONTRACT");

        assertEquals(1, preview.get("criterion_count"));
        List<Map<String, Object>> files = ((List<?>) preview.get("files")).stream()
                .map(item -> (Map<String, Object>) item)
                .toList();
        assertEquals("scenario.json", files.get(0).get("name"));
        assertTrue(String.valueOf(files.get(0).get("content")).contains("SCREEN_CONTRACT"));
        assertTrue(files.stream().anyMatch(file -> "exam_test.dart".equals(file.get("name"))
                && String.valueOf(file.get("content")).contains("void main()")));
        assertTrue(files.stream().anyMatch(file -> "skills_matrix.json".equals(file.get("name"))
                && String.valueOf(file.get("content")).contains("FIELD_EMAIL")));
        verifyNoInteractions(artifacts, exams);
    }

    @Test
    void appendsStaticRuleRowsAndChecksGoldenComplianceOnPublish() throws Exception {
        // Nhóm Kiến trúc: luật tĩnh của suite phải đi vào skills_matrix.json khi publish
        // (kèm static_config cho container) và publish phải kiểm lại luật trên Golden.
        BehaviorAuthoringService authoring = mock(BehaviorAuthoringService.class);
        BehaviorArtifactService artifacts = mock(BehaviorArtifactService.class);
        ExamRepository exams = mock(ExamRepository.class);
        StaticRuleService staticRules = mock(StaticRuleService.class);
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        rows.put("RAR_USER_STATIC_ARCH_MODEL", new LinkedHashMap<>(Map.of(
                "instance_id", "RAR_USER_STATIC_ARCH_MODEL",
                "runner", "STATIC_ANALYSIS",
                "static_rule", "source_pattern",
                "weight", 5.0,
                "group_id", "G_KIENTRUC",
                "group_name", "Kiến trúc mã nguồn",
                "static_config", Map.of("require", List.of(
                        Map.of("label", "Tách Model", "paths", List.of("lib/models/**")))))));
        when(staticRules.matrixRows("suite-1", "RAR_USER")).thenReturn(rows);
        when(artifacts.goldenScreenshotDir(anyString()))
                .thenReturn(tempDir.resolve("golden-screens-missing"));
        BehaviorSuiteMaterializer materializer =
                new BehaviorSuiteMaterializer(authoring, artifacts, staticRules, exams);
        ReflectionTestUtils.setField(materializer, "templateDir", Path.of("..", "grader-base").toString());
        ReflectionTestUtils.setField(materializer, "examsDir", tempDir.toString());

        Map<String, Object> scenario = Map.ofEntries(
                Map.entry("scenario_code", "ADD_USER"),
                Map.entry("name", "Thêm người dùng"),
                Map.entry("skill_code", "STORAGE_SQLITE_CRUD"),
                Map.entry("weight", 8.0),
                Map.entry("initial_state", Map.of("reset_storage", true)),
                Map.entry("steps", List.of(Map.of(
                        "id", "step_1", "action", "tap", "target", Map.of("text", "Add")))),
                Map.entry("viewports", List.of(Map.of("name", "phone", "width", 390, "height", 844))),
                Map.entry("oracle", Map.of("seed", "seed-01", "input", Map.of())),
                Map.entry("checkpoints", List.of(Map.of(
                        "id", "UI_VISIBLE", "kind", "checkpoint", "scope", "ui",
                        "weight", 1.0, "expect", Map.of("visible_texts", List.of("x"))))));
        Map<String, Object> plan = Map.of(
                "schema_version", "1.0",
                "suite", Map.of(
                        "id", "suite-1", "suite_code", "RAR_USER", "exam_id", "RAR_USER_EXAM",
                        "name", "RAR User", "description", "Golden behavior", "revision", 1),
                "public_contract", Map.of(),
                "database_contract", Map.of("enabled", true, "database_name", "users.db"),
                "runtime_config", Map.of("default_timeout_ms", 5000),
                "scenarios", List.of(scenario));
        when(authoring.executionPlan("suite-1")).thenReturn(plan);
        for (BehaviorArtifactType type : List.of(
                BehaviorArtifactType.STUDENT_DATABASE,
                BehaviorArtifactType.HIDDEN_DATABASE,
                BehaviorArtifactType.OUTPUT_DATABASE)) {
            Path source = tempDir.resolve(type.name().toLowerCase() + ".db");
            Files.writeString(source, "fixture-" + type);
            BehaviorArtifact artifact = new BehaviorArtifact();
            artifact.setArtifactType(type);
            artifact.setStoragePath(source.toString());
            when(artifacts.active("suite-1", type)).thenReturn(artifact);
        }
        when(exams.findByExamId("RAR_USER_EXAM")).thenReturn(Optional.empty());
        when(exams.save(any(Exam.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> result = materializer.materialize("suite-1");

        verify(staticRules).requireGoldenCompliance("suite-1");
        assertEquals(2, result.get("criterion_count"),
                "criterion_count phải đếm cả dòng tĩnh, không chỉ checkpoint");
        Path output = tempDir.resolve("RAR_USER_EXAM").resolve("testcase");
        JsonNode matrix = new ObjectMapper().readTree(output.resolve("skills_matrix.json").toFile());
        JsonNode row = matrix.get("RAR_USER_STATIC_ARCH_MODEL");
        assertNotNull(row, "dòng luật tĩnh phải nằm trong skills_matrix.json");
        assertEquals("STATIC_ANALYSIS", row.get("runner").asText());
        assertEquals("source_pattern", row.get("static_rule").asText());
        assertEquals("lib/models/**",
                row.get("static_config").get("require").get(0).get("paths").get(0).asText());
        // Dòng behavior giờ cũng có nhóm mặc định theo scenario để phiếu tay đối chiếu được.
        JsonNode behaviorRow = matrix.get("RAR_USER_ADD_USER_UI_VISIBLE");
        assertEquals("G_ADD_USER", behaviorRow.get("group_id").asText());
        assertEquals("Thêm người dùng", behaviorRow.get("group_name").asText());
    }

    @Test
    void semanticFingerprintIgnoresDatabaseIdentityButNotBehavior() {
        BehaviorAuthoringService authoring = mock(BehaviorAuthoringService.class);
        BehaviorArtifactService artifacts = mock(BehaviorArtifactService.class);
        BehaviorSuiteMaterializer materializer = newMaterializer(
                authoring, artifacts, mock(ExamRepository.class));
        Map<String, Object> scenarioA = new LinkedHashMap<>();
        scenarioA.put("id", "random-id-a");
        scenarioA.put("suite_id", "suite-a");
        scenarioA.put("scenario_code", "ADD_USER");
        scenarioA.put("name", "Add user");
        scenarioA.put("weight", 2.0);
        scenarioA.put("steps", List.of(Map.of(
                "id", "step_1", "action", "tap", "target", Map.of("semanticId", "action.add"))));
        scenarioA.put("checkpoints", List.of(Map.of(
                "id", "checkpoint_1", "kind", "checkpoint",
                "expect", Map.of("visible_texts", List.of("Saved")))));
        scenarioA.put("viewports", List.of(Map.of("name", "phone", "width", 390, "height", 844)));
        scenarioA.put("oracle", Map.of(
                "id", "oracle-a", "created_at", "2026-01-01T00:00:00Z",
                "seed", "rar-v1-fixed", "input", Map.of()));
        Map<String, Object> scenarioB = new LinkedHashMap<>(scenarioA);
        scenarioB.put("id", "random-id-b");
        scenarioB.put("suite_id", "suite-b");
        scenarioB.put("oracle", Map.of(
                "id", "oracle-b", "created_at", "2026-08-21T00:00:00Z",
                "seed", "rar-v1-fixed", "input", Map.of()));
        Map<String, Object> planA = Map.of(
                "public_contract", Map.of(), "database_contract", Map.of(),
                "runtime_config", Map.of(), "scenarios", List.of(scenarioA));
        Map<String, Object> planB = Map.of(
                "public_contract", Map.of(), "database_contract", Map.of(),
                "runtime_config", Map.of(), "scenarios", List.of(scenarioB));

        assertEquals(materializer.semanticFingerprint(planA), materializer.semanticFingerprint(planB));

        scenarioB.put("steps", List.of(Map.of(
                "id", "step_1", "action", "tap", "target", Map.of("semanticId", "action.delete"))));
        assertNotEquals(materializer.semanticFingerprint(planA), materializer.semanticFingerprint(planB));
    }
}
