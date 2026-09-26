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
import java.util.ArrayList;
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

    private static List<String> khoaCua(JsonNode row) {
        List<String> ra = new ArrayList<>();
        row.fieldNames().forEachRemaining(ra::add);
        return ra;
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

        Map<String, Object> preview = materializer.previewCode("suite-zero");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) preview.get("files");
        String content = files.stream().filter(file -> "skills_matrix.json".equals(file.get("name")))
                .findFirst().orElseThrow().get("content").toString();
        JsonNode matrix = new ObjectMapper().readTree(content);
        assertEquals(0.0, matrix.path("ZERO_SCORE_PREREQUISITE").path("weight").asDouble(), 0.0);
        assertEquals(2.0, matrix.path("ZERO_SCORE_PAID").path("weight").asDouble(), 0.0);
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
            Map<String, Object> preview = materializer.previewCode("suite-decimal");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files = (List<Map<String, Object>>) preview.get("files");
            String content = files.stream().filter(file -> "skills_matrix.json".equals(file.get("name")))
                    .findFirst().orElseThrow().get("content").toString();
            JsonNode matrix = new ObjectMapper().readTree(content);
            assertEquals(score, matrix.path("TEST_PAID").path("weight").asDouble(), 0.0);
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
            assertThrows(IllegalArgumentException.class, () -> materializer.previewCode("suite-zero"));
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
                "public_contract", Map.of("allow_coordinate_fallback", false,
                        // Bộ dựng TRƯỚC ngày gỡ value_key: hợp đồng đóng băng trong bản ghi
                        // suite vẫn còn locator đó.
                        "locator_priority", List.of("semantic_id", "value_key", "accessibility_label")),
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
        // value_key đã gỡ khỏi engine nên không được để lọt ra hợp đồng phát đi: bên đọc sẽ
        // tưởng máy chấm còn tìm widget theo khoá đó. Lọc lúc GHI nên bộ cũ cũng sạch theo.
        assertEquals(List.of("semantic_id", "accessibility_label"),
                new ObjectMapper().convertValue(
                        contract.get("public_contract").get("locator_priority"), List.class));
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
        assertEquals(3.0, matrix.get("ADD_USER_UI_VISIBLE_PHONE").get("weight").asDouble(), 0.0001);
        assertEquals(3.0, matrix.get("ADD_USER_UI_VISIBLE_DESKTOP").get("weight").asDouble(), 0.0001);
        assertEquals(2.0, matrix.get("ADD_USER_DB_ROW").get("weight").asDouble(), 0.0001);
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
    void componentCheckpointUsesScenarioBudgetAndIgnoresUiGroup() throws Exception {
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
        assertEquals(1.333333, matrix.get("ADD_USER_UI_VISIBLE_PHONE").get("weight").asDouble(), 0.0001);
        assertEquals(0.888889, matrix.get("ADD_USER_DB_ROW").get("weight").asDouble(), 0.0001);
        JsonNode comp = matrix.get("ADD_USER_UI_COMP");
        assertEquals(4.444444, comp.get("weight").asDouble(), 0.0001,
                "trọng số checkpoint phải được quy đổi trong ngân sách 8 điểm của scenario");
        // Bảng chỉ còn SÁU field có người đọc (21/9/2026). Ghim danh sách ở đây để ai thêm
        // field mới phải sửa test và nói ra được ai sẽ đọc nó.
        // Luồng này chưa khai mã nhóm nên KHÔNG có `group_id` — bảng chỉ mang đúng thứ có
        // người đọc. Ca có nhóm được ghim ở appendsStaticRuleRows…, nơi group_id = "CRUD".
        assertEquals(List.of("name", "runner", "scenario_code", "scenario_name", "weight"),
                new java.util.TreeSet<>(khoaCua(comp)).stream().toList());
        // ui_group trong checkpoint KHÔNG còn sinh nhóm (21/9/2026): nhãn của nó lấy tên MÀN,
        // nên hai màn cùng tên bị gộp một rọ, kéo tiêu chí của nhiều luồng vào chung một dòng
        // điểm. Nhóm nay chỉ đến từ mã nhóm người soạn gõ cho luồng.
        assertNull(comp.get("group_id"), "ui_group không được sinh nhóm nữa");
        assertNull(comp.get("group_name"), "ui_group không được sinh nhóm nữa");
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
        // Plan gom theo LUỒNG: khung điện thoại và khung desktop là hai mã thực thi nên thành
        // hai luồng riêng, mỗi luồng đúng một tiêu chí — responsive không được nhân bản.
        JsonNode luong = sinhRa.get("luong");
        assertEquals(2, luong.size());
        JsonNode thuong = null;
        JsonNode resp = null;
        for (JsonNode l : luong) {
            if ("RESP_1".equals(l.get("cases").get(0).get("checkpoint").get("id").asText())) resp = l;
            else thuong = l;
        }
        assertNotNull(thuong, "phải có luồng của tiêu chí thường");
        assertNotNull(resp, "phải có luồng của tiêu chí responsive");
        assertEquals(1, thuong.get("cases").size());
        assertEquals(1, resp.get("cases").size());
        assertEquals(412, thuong.get("viewport").get("width").asInt());
        assertEquals(838, thuong.get("viewport").get("height").asInt(),
                "tiêu chí thường phải ở khung app thật của Pixel 7");
        assertEquals("LIST__VP_1", thuong.get("execution_code").asText());
        assertEquals(1280, resp.get("viewport").get("width").asInt());
        assertEquals(800, resp.get("viewport").get("height").asInt(),
                "tiêu chí responsive phải ở khung desktop");
        assertEquals("LIST__VP_DESKTOP", resp.get("execution_code").asText());
        assertEquals(5.0, resp.get("cases").get(0).get("weight").asDouble(), 0.0001,
                "responsive giữ nguyên phần điểm đã khai, không bị chia theo số khung");
        // steps và initial_state nằm ở CẤP LUỒNG, không lặp xuống từng tiêu chí.
        assertTrue(thuong.has("steps"), "steps phải ở cấp luồng");
        assertFalse(thuong.get("cases").get(0).has("steps"), "case không được chép lại steps");
        assertFalse(thuong.get("cases").get(0).has("oracle"), "oracle đã bỏ khỏi plan");
    }

    /**
     * Tiêu chí số cột đi tới plan ĐỦ tham số engine cần, ở khung desktop, và dòng bảng điểm
     * mang đúng tên người soạn thấy lúc tick — không phải tên máy tự ghép.
     */
    @Test
    void soCotNhomVaoPlanOKhungDesktopVoiDuThamSo() throws Exception {
        BehaviorAuthoringService authoring = mock(BehaviorAuthoringService.class);
        BehaviorArtifactService artifacts = mock(BehaviorArtifactService.class);
        ExamRepository exams = mock(ExamRepository.class);
        BehaviorSuiteMaterializer materializer = newMaterializer(authoring, artifacts, exams);
        ReflectionTestUtils.setField(materializer, "templateDir", Path.of("..", "grader-base").toString());
        ReflectionTestUtils.setField(materializer, "examsDir", tempDir.toString());

        String ten = "Responsive — nhóm user.# xếp từ 2 cột trở lên ở khung desktop";
        Map<String, Object> scenario = Map.ofEntries(
                Map.entry("scenario_code", "LIST"),
                Map.entry("name", "Màn danh sách"),
                Map.entry("skill_code", "UI_LAYOUT"),
                Map.entry("weight", 5.0),
                Map.entry("initial_state", Map.of("reset_storage", true)),
                Map.entry("steps", List.of()),
                Map.entry("oracle", Map.of("seed", "seed-01", "input", Map.of())),
                Map.entry("checkpoints", List.of(
                        Map.of("id", "COT_1", "kind", "group_columns", "khung", "desktop",
                                "group_pattern", "user.#", "min_columns", 2,
                                "weight", 5.0, "name", ten))));
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
        JsonNode luong = new ObjectMapper().readTree(output.resolve("behavior_plan.json").toFile()).get("luong");
        assertEquals(1, luong.size());
        JsonNode l = luong.get(0);
        assertEquals(1280, l.get("viewport").get("width").asInt(), "đếm cột phải ở khung desktop");
        assertEquals("LIST__VP_DESKTOP", l.get("execution_code").asText());
        JsonNode cp = l.get("cases").get(0).get("checkpoint");
        assertEquals("group_columns", cp.get("kind").asText());
        assertEquals("user.#", cp.get("group_pattern").asText(), "engine cần mẫu để gom nhóm");
        assertEquals(2, cp.get("min_columns").asInt(), "engine cần số cột tối thiểu");

        JsonNode matrix = new ObjectMapper().readTree(output.resolve("skills_matrix.json").toFile());
        assertEquals(1, matrix.size());
        assertEquals(ten, matrix.elements().next().get("name").asText(),
                "dòng bảng điểm phải mang đúng tên tiêu chí người soạn đã tick");
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
        assertEquals("scenario-42", behaviorPlan.path("luong").get(0).path("scenario_id").asText());
    }

    /**
     * Đề KHÔNG dùng database (máy tính cộng trừ): bộ chấm sinh ra không được có database nào,
     * và engine phải được báo tắt hợp đồng.
     *
     * <p>Trước 26/9/2026 writeBundle chép hidden.db VÔ ĐIỀU KIỆN (thiếu là ném "Thiếu artifact bắt
     * buộc"), còn executablePlan ép cứng enabled=true — nên đề không có database không dựng nổi
     * bundle capture, và kể cả dựng được thì engine vẫn đi tìm một file .db không tồn tại.
     */
    @Test
    void deKhongDungDatabaseThiBundleKhongCoDatabaseVaEngineTatHopDong() throws Exception {
        BehaviorAuthoringService authoring = mock(BehaviorAuthoringService.class);
        BehaviorArtifactService artifacts = mock(BehaviorArtifactService.class);
        BehaviorSuiteMaterializer materializer = newMaterializer(authoring, artifacts, mock(ExamRepository.class));
        ReflectionTestUtils.setField(materializer, "templateDir", Path.of("..", "grader-base").toString());
        when(artifacts.khongDungDatabase("suite-calc")).thenReturn(true);
        when(artifacts.activeManifest("suite-calc")).thenReturn(Map.of());

        Map<String, Object> scenario = Map.ofEntries(
                Map.entry("id", "scenario-cong"),
                Map.entry("scenario_code", "CONG"),
                Map.entry("name", "Cộng hai số"),
                Map.entry("weight", 1.0),
                Map.entry("variables", Map.of()),
                Map.entry("initial_state", Map.of("reset_storage", true)),
                Map.entry("steps", List.of(Map.of(
                        "id", "step_1", "action", "tap",
                        "target", Map.of("semantic_id", "calculate.tinh")))),
                Map.entry("viewports", List.of(Map.of(
                        "name", "phone", "width", 412, "height", 915))),
                Map.entry("oracle", Map.of("seed", "seed-1", "input", Map.of())),
                Map.entry("checkpoints", List.of(Map.of(
                        "id", "KET_QUA", "kind", "checkpoint", "scope", "ui",
                        "weight", 1.0, "expect", Map.of("no_exception", true)))));
        when(authoring.previewExecutionPlan("suite-calc")).thenReturn(Map.of(
                "schema_version", "1.0",
                "suite", Map.of("id", "suite-calc", "suite_code", "CALC", "revision", 1),
                "public_contract", Map.of(),
                // Hợp đồng lưu trong suite đã bị tắt lúc tải Golden, nhưng còn sót một đường dẫn cũ:
                // bundle không được trỏ engine vào đó.
                "database_contract", Map.of("enabled", false, "hidden_fixture_path", "/app/test/fixtures/hidden.db"),
                "runtime_config", Map.of("default_timeout_ms", 5000),
                "scenarios", List.of(scenario)));

        Path output = materializer.createCaptureBundle("suite-calc", tempDir.resolve("capture-calc"));

        assertTrue(Files.isDirectory(output.resolve("fixtures")),
                "fixtures vẫn phải có: engine ghi ảnh chuẩn và captured-layout.json vào đây");
        assertFalse(Files.exists(output.resolve("fixtures/hidden.db")));
        assertFalse(Files.exists(output.resolve("fixtures/expected-output.db")));
        verify(artifacts, never()).active("suite-calc", BehaviorArtifactType.HIDDEN_DATABASE);
        verify(artifacts, never()).active("suite-calc", BehaviorArtifactType.OUTPUT_DATABASE);

        JsonNode contract = new ObjectMapper().readTree(output.resolve("behavior_plan.json").toFile())
                .path("database_contract");
        assertFalse(contract.path("enabled").asBoolean(true), "engine phải thấy hợp đồng database đã tắt");
        assertFalse(contract.has("hidden_fixture_path"));
        assertFalse(contract.has("expected_output_path"));
    }

    @Test
    void previewsOnlyBundleContentFilesWithoutArtifacts() {
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

        Map<String, Object> preview = materializer.previewCode("suite-1");

        assertEquals(1, preview.get("criterion_count"));
        List<Map<String, Object>> files = ((List<?>) preview.get("files")).stream()
                .map(item -> (Map<String, Object>) item)
                .toList();
        // Màn xem code chỉ bày NỘI DUNG CỦA BỘ ĐỀ (bỏ 21/9/2026). Runner Dart dùng chung cho
        // mọi đề nên không bày; đường xem riêng từng scenario cũng gỡ vì plan đã gom theo luồng.
        assertEquals(List.of("behavior_plan.json", "skills_matrix.json", "contract.json"),
                files.stream().map(file -> String.valueOf(file.get("name"))).toList());
        assertTrue(files.stream().anyMatch(file -> "behavior_plan.json".equals(file.get("name"))
                && String.valueOf(file.get("content")).contains("SCREEN_CONTRACT")));
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
                Map.entry("group_code", "CRUD"),
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
        // Nhóm đến TỪ MÃ NHÓM người soạn gõ cho luồng, không còn suy từ scenario_code hay
        // ui_group (21/9/2026). Tên luồng đi kèm vì bảng điểm có cột "Luồng" riêng.
        JsonNode behaviorRow = matrix.get("ADD_USER_UI_VISIBLE");
        assertEquals("CRUD", behaviorRow.get("group_id").asText());
        assertNull(behaviorRow.get("group_name"), "group_name đã gỡ: luôn trùng group_id");
        assertEquals("Thêm người dùng", behaviorRow.get("scenario_name").asText());
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

    /**
     * Publish CHỈ mang theo ảnh chuẩn của luồng đang có, và dọn luôn ảnh chết trong kho.
     *
     * Tên tệp khoá theo execution_code, mà mã đó đổi mỗi khi người soạn đổi mã nhóm hoặc tên
     * luồng. Trước 21/9/2026 publish chép cả thư mục nên ảnh tên cũ đi theo mãi — đo trên
     * PE_PRM393_FA26: 29 tệp cho 16 luồng, 15 tệp mồ côi, từng cặp cũ/mới trùng đúng từng byte.
     */
    @Test
    void publishChiChepAnhChuanCuaLuongDangCoVaDonKho() throws Exception {
        BehaviorAuthoringService authoring = mock(BehaviorAuthoringService.class);
        BehaviorArtifactService artifacts = mock(BehaviorArtifactService.class);
        ExamRepository exams = mock(ExamRepository.class);
        StaticRuleService staticRules = mock(StaticRuleService.class);
        when(staticRules.matrixRows(anyString(), anyString())).thenReturn(new LinkedHashMap<>());
        Path kho = tempDir.resolve("kho-anh");
        Files.createDirectories(kho);
        when(artifacts.goldenScreenshotDir(anyString())).thenReturn(kho);
        mockGoldenPubspec(artifacts);
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
                "suite", Map.of("id", "suite-1", "suite_code", "RAR_ANH", "exam_id", "RAR_ANH_EXAM",
                        "name", "RAR anh", "revision", 1),
                "public_contract", Map.of(),
                "database_contract", Map.of("enabled", true, "database_name", "users.db"),
                "runtime_config", Map.of("default_timeout_ms", 5000),
                "scenarios", List.of(scenario));
        when(authoring.executionPlan("suite-1")).thenReturn(plan);
        for (BehaviorArtifactType type : List.of(
                BehaviorArtifactType.STUDENT_DATABASE,
                BehaviorArtifactType.HIDDEN_DATABASE,
                BehaviorArtifactType.OUTPUT_DATABASE)) {
            Path source = tempDir.resolve("anh-" + type.name().toLowerCase() + ".db");
            Files.writeString(source, "fixture");
            BehaviorArtifact artifact = new BehaviorArtifact();
            artifact.setArtifactType(type);
            artifact.setStoragePath(source.toString());
            when(artifacts.active("suite-1", type)).thenReturn(artifact);
        }
        when(exams.findByExamId("RAR_ANH_EXAM")).thenReturn(Optional.empty());
        when(exams.save(any(Exam.class))).thenAnswer(inv -> inv.getArgument(0));

        // Lượt đầu chỉ để biết execution_code thật, khỏi chép lại luật đặt tên của production.
        materializer.materialize("suite-1");
        Path bundle = tempDir.resolve("RAR_ANH_EXAM").resolve("testcase");
        JsonNode luongs = new ObjectMapper()
                .readTree(bundle.resolve("behavior_plan.json").toFile()).get("luong");
        String maDangDung = luongs.get(0).get("execution_code").asText();

        Files.writeString(kho.resolve(maDangDung + ".png"), "anh cua luong dang co");
        Files.writeString(kho.resolve("MA_CU_DA_DOI__VP_1.png"), "anh mo coi");
        materializer.materialize("suite-1");

        Path anhTrongGoi = bundle.resolve("fixtures").resolve("screens");
        assertTrue(Files.isRegularFile(anhTrongGoi.resolve(maDangDung + ".png")),
                "ảnh của luồng đang có phải đi theo gói");
        assertFalse(Files.exists(anhTrongGoi.resolve("MA_CU_DA_DOI__VP_1.png")),
                "ảnh mồ côi không được lọt vào gói bàn giao");
        assertFalse(Files.exists(kho.resolve("MA_CU_DA_DOI__VP_1.png")),
                "ảnh mồ côi phải bị dọn khỏi kho, không thì lần publish sau lại đẻ ra");
        assertTrue(Files.isRegularFile(kho.resolve(maDangDung + ".png")),
                "ảnh đang dùng phải còn nguyên trong kho");
    }
}
