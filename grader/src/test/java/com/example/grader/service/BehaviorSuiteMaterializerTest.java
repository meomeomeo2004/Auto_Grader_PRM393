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
        mockGoldenPubspec(artifacts);
        return new BehaviorSuiteMaterializer(authoring, artifacts, staticRules, exams);
    }

    private void mockGoldenPubspec(BehaviorArtifactService artifacts) {
        Path golden = tempDir.resolve("golden-packages.zip");
        assertDoesNotThrow(() -> {
            try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(golden))) {
                zip.putNextEntry(new java.util.zip.ZipEntry("lib/main.dart"));
                zip.write("void main() {}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new java.util.zip.ZipEntry("pubspec.yaml"));
                zip.write("dependencies:\n  flutter: {sdk: flutter}\n  path: ^1.9.0\ndev_dependencies:\n  flutter_lints: ^4.0.0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        });
        BehaviorArtifact goldenArtifact = new BehaviorArtifact();
        goldenArtifact.setStoragePath(golden.toString());
        when(artifacts.active(anyString(), eq(BehaviorArtifactType.GOLDEN_SOLUTION))).thenReturn(goldenArtifact);
    }

    @Test
    void preservesZeroPointCheckpointAndFullScoreOfPaidCheckpoint() throws Exception {
        BehaviorAuthoringService authoring = mock(BehaviorAuthoringService.class);
        BehaviorArtifactService artifacts = mock(BehaviorArtifactService.class);
        ExamRepository exams = mock(ExamRepository.class);
        BehaviorSuiteMaterializer materializer = newMaterializer(authoring, artifacts, exams);
        Map<String, Object> scenario = Map.of(
                "scenario_code", "ZERO_SCORE", "name", "Checkpoint không tính điểm", "weight", 2.0,
                "steps", List.of(Map.of("action", "boot")),
                "checkpoints", List.of(
                        Map.of("id", "PREREQUISITE", "kind", "checkpoint", "weight", 0.0),
                        Map.of("id", "PAID", "kind", "checkpoint", "weight", 2.0,
                                "requires", "PREREQUISITE")));
        when(authoring.previewExecutionPlan("suite-zero")).thenReturn(Map.of(
                "suite", Map.of("id", "suite-zero", "suite_code", "ZERO"),
                "scenarios", List.of(scenario)));

        Map<String, Object> preview = materializer.previewCode("suite-zero", null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) preview.get("files");
        String content = files.stream().filter(file -> "skills_matrix.json".equals(file.get("name")))
                .findFirst().orElseThrow().get("content").toString();
        JsonNode matrix = new ObjectMapper().readTree(content);
        assertEquals(0.0, matrix.path("ZERO_ZERO_SCORE_PREREQUISITE").path("weight").asDouble(), 0.0);
        assertEquals(2.0, matrix.path("ZERO_ZERO_SCORE_PAID").path("weight").asDouble(), 0.0);
        assertEquals(2, matrix.size(), "Checkpoint 0 điểm vẫn phải được thực thi để kiểm tiên quyết");
    }

    @Test
    void preservesFreelyEnteredDecimalScoresWithoutRoundingThemToZero() throws Exception {
        BehaviorAuthoringService authoring = mock(BehaviorAuthoringService.class);
        BehaviorArtifactService artifacts = mock(BehaviorArtifactService.class);
        BehaviorSuiteMaterializer materializer = newMaterializer(authoring, artifacts, mock(ExamRepository.class));
        for (double score : List.of(0.0000001, 0.1234567)) {
            when(authoring.previewExecutionPlan("suite-decimal")).thenReturn(Map.of(
                    "suite", Map.of("id", "suite-decimal", "suite_code", "DECIMAL"),
                    "scenarios", List.of(Map.of(
                            "scenario_code", "TEST", "name", "Điểm thập phân", "weight", score,
                            "steps", List.of(Map.of("action", "boot")),
                            "checkpoints", List.of(Map.of("id", "PAID", "kind", "checkpoint", "weight", score))))));
            Map<String, Object> preview = materializer.previewCode("suite-decimal", null);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files = (List<Map<String, Object>>) preview.get("files");
            String content = files.stream().filter(file -> "skills_matrix.json".equals(file.get("name")))
                    .findFirst().orElseThrow().get("content").toString();
            JsonNode matrix = new ObjectMapper().readTree(content);
            assertEquals(score, matrix.path("DECIMAL_TEST_PAID").path("weight").asDouble(), 0.0);
        }
    }

    @Test
    void rejectsFunctionWithoutValidPositiveCheckpointTotal() {
        BehaviorAuthoringService authoring = mock(BehaviorAuthoringService.class);
        BehaviorSuiteMaterializer materializer = newMaterializer(authoring,
                mock(BehaviorArtifactService.class), mock(ExamRepository.class));
        for (List<Map<String, Object>> checkpoints : List.of(
                List.<Map<String, Object>>of(Map.of("id", "ONLY", "kind", "checkpoint", "weight", 0.0)),
                List.<Map<String, Object>>of(
                        Map.of("id", "FIRST", "kind", "checkpoint", "weight", 1e308),
                        Map.of("id", "SECOND", "kind", "checkpoint", "weight", 1e308)))) {
            when(authoring.previewExecutionPlan("suite-zero")).thenReturn(Map.of(
                "suite", Map.of("id", "suite-zero", "suite_code", "ZERO"),
                "scenarios", List.of(Map.of(
                        "scenario_code", "TEST", "weight", 2.0,
                        "checkpoints", checkpoints))));
            assertThrows(IllegalArgumentException.class, () -> materializer.previewCode("suite-zero", null));
        }
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
        JsonNode contract = new ObjectMapper().readTree(output.resolve("contract.json").toFile());
        assertEquals(List.of("flutter", "path"), new ObjectMapper().convertValue(contract.get("allowed_packages"), List.class),
                "Policy phải đọc dependencies của Golden, không đọc mặc định/runtime hay dev_dependencies");
        // Ràng buộc phiên bản đi ở khoá RIÊNG: bên người chấm thiếu gói thì thêm vào ảnh chấm
        // bằng đúng ràng buộc này. `flutter: {sdk: flutter}` là khối lồng, không phải version
        // điền được vào ô pub — phải quy về rỗng chứ không được đổ nguyên khối ra.
        assertEquals(Map.of("flutter", "", "path", "^1.9.0"),
                new ObjectMapper().convertValue(contract.get("allowed_package_specs"), Map.class));
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
    void componentCheckpointUsesScenarioBudgetAndKeepsUiGroup() throws Exception {
        // Mọi checkpoint chia cùng ngân sách scenario theo tỷ lệ; tiêu chí thành phần chỉ
        // chạy trên viewport đầu và vẫn mang nhóm UI riêng trong matrix.
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
        assertEquals(1.333333, matrix.get("RAR_USER_ADD_USER_UI_VISIBLE_PHONE").get("weight").asDouble(), 0.0001);
        assertEquals(0.888889, matrix.get("RAR_USER_ADD_USER_DB_ROW").get("weight").asDouble(), 0.0001);
        JsonNode comp = matrix.get("RAR_USER_ADD_USER_UI_COMP");
        assertEquals(4.444444, comp.get("weight").asDouble(), 0.0001,
                "trọng số checkpoint phải được quy đổi trong ngân sách 8 điểm của scenario");
        assertEquals("UI", comp.get("testcase_group").asText());
        assertEquals("ui", comp.get("layer").asText());
        assertNull(comp.get("skill_code"),
                "bo khung nang luc: tieu chi khong con mang ma nang luc nao");
        assertEquals("G_UI_DANH_SACH", comp.get("group_id").asText());
        assertEquals("Giao diện — Màn danh sách", comp.get("group_name").asText());
        assertEquals("Màn danh sách — có nút thêm", comp.get("name").asText());
    }

    @Test
    void tieuChiResponsiveChayOKhungDesktopVaMangMaThucThiRieng() throws Exception {
        // Tiêu chí responsive phải chạy ở 1280×800 và mang MÃ THỰC THI RIÊNG, còn tiêu chí
        // thường vẫn ở khung điện thoại 412×838. Dùng chung mã là engine gom cả hai vào một
        // lượt replay rồi đo bố cục desktop trên khung điện thoại — hỏng im lặng, vì tiêu chí
        // vẫn chạy và vẫn cho ra một con số.
        //
        // Cũng khẳng định KHÔNG nhân bản: mỗi tiêu chí đúng một case, nên trọng số không bị
        // chia đôi theo số khung như đường viewports cũ.
        BehaviorAuthoringService authoring = mock(BehaviorAuthoringService.class);
        BehaviorArtifactService artifacts = mock(BehaviorArtifactService.class);
        ExamRepository exams = mock(ExamRepository.class);
        BehaviorSuiteMaterializer materializer = newMaterializer(authoring, artifacts, exams);
        ReflectionTestUtils.setField(materializer, "templateDir", Path.of("..", "grader-base").toString());
        ReflectionTestUtils.setField(materializer, "examsDir", tempDir.toString());

        Map<String, Object> scenario = Map.ofEntries(
                Map.entry("scenario_code", "LIST"),
                Map.entry("name", "Màn danh sách"),
                Map.entry("skill_code", "UI_LAYOUT"),
                Map.entry("weight", 10.0),
                Map.entry("initial_state", Map.of("reset_storage", true)),
                Map.entry("steps", List.of(Map.of(
                        "id", "step_1", "action", "tap", "target", Map.of("label", "Tất cả")))),
                Map.entry("oracle", Map.of("seed", "seed-01", "input", Map.of())),
                Map.entry("checkpoints", List.of(
                        Map.of("id", "UI_COMP", "kind", "component_present",
                                "target", Map.of("label", "Tất cả"), "visible", true,
                                "name", "Có chip Tất cả", "weight", 5.0),
                        Map.of("id", "RESP_1", "kind", "layout_relation", "khung", "desktop",
                                "relation", "auto", "weight", 5.0,
                                "target", Map.of("label", "Tất cả"),
                                "relative_to", Map.of("label", "Ăn uống"),
                                "name", "Responsive — chip đổi hàng"))));
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
        JsonNode sinhRa = new ObjectMapper().readTree(output.resolve("behavior_plan.json").toFile());
        JsonNode cases = sinhRa.get("cases");
        assertEquals(2, cases.size(), "mỗi tiêu chí đúng MỘT case — responsive không được nhân bản");
        JsonNode thuong = null;
        JsonNode resp = null;
        for (JsonNode c : cases) {
            if ("RESP_1".equals(c.get("checkpoint").get("id").asText())) resp = c;
            else thuong = c;
        }
        assertNotNull(thuong, "phải có case của tiêu chí thường");
        assertNotNull(resp, "phải có case của tiêu chí responsive");
        assertEquals(412, thuong.get("viewport").get("width").asInt());
        assertEquals(838, thuong.get("viewport").get("height").asInt(),
                "tiêu chí thường phải ở khung app thật của Pixel 7");
        assertEquals("LIST__VP_1", thuong.get("execution_code").asText());
        assertEquals(1280, resp.get("viewport").get("width").asInt());
        assertEquals(800, resp.get("viewport").get("height").asInt(),
                "tiêu chí responsive phải ở khung desktop");
        assertEquals("LIST__VP_DESKTOP", resp.get("execution_code").asText());
        assertEquals(5.0, resp.get("weight").asDouble(), 0.0001,
                "responsive giữ nguyên phần điểm đã khai, không bị chia theo số khung");
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
        verify(artifacts).active("suite-1", BehaviorArtifactType.GOLDEN_SOLUTION);
        verifyNoInteractions(exams);
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
        mockGoldenPubspec(artifacts);
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
