package com.example.grader.service;

import com.example.grader.entity.BehaviorScenario;
import com.example.grader.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class BehaviorAuthoringServiceTest {

    @Autowired BehaviorAuthoringService service;
    @Autowired OracleSnapshotRepository oracleRepository;
    @Autowired BehaviorScenarioRepository scenarioRepository;
    @Autowired GoldenRecordingRepository recordingRepository;
    @Autowired BehaviorSuiteRepository suiteRepository;
    @Autowired GoldenAppRepository goldenAppRepository;
    @Autowired GoldenValidationRunRepository validationRunRepository;

    @AfterEach
    void cleanup() {
        validationRunRepository.deleteAll();
        oracleRepository.deleteAll();
        scenarioRepository.deleteAll();
        recordingRepository.deleteAll();
        suiteRepository.deleteAll();
        goldenAppRepository.deleteAll();
    }

    @Test
    void identicalRecordingsProduceIdenticalReplaySemanticsAndSeed() {
        Map<String, Object> first = createEquivalentScenario("DETERMINISTIC_A");
        Map<String, Object> second = createEquivalentScenario("DETERMINISTIC_B");

        for (String field : List.of("variables", "initial_state", "steps", "checkpoints", "viewports")) {
            assertEquals(first.get(field), second.get(field), field + " phải giống nhau");
        }
        Map<?, ?> firstOracle = (Map<?, ?>) first.get("oracle");
        Map<?, ?> secondOracle = (Map<?, ?>) second.get("oracle");
        assertEquals(firstOracle.get("seed"), secondOracle.get("seed"));
        assertTrue(String.valueOf(firstOracle.get("seed")).startsWith("rar-v1-"));
    }

    @Test
    void deleteSuiteRemovesItsDomainRowsAndUnusedGoldenApp() {
        Map<String, Object> scenario = createEquivalentScenario("DELETE_GOLDEN_SUITE");
        String suiteId = String.valueOf(scenario.get("suite_id"));
        String scenarioId = String.valueOf(scenario.get("id"));
        String goldenId = suiteRepository.findById(suiteId).orElseThrow().getGoldenAppId();

        Map<String, Object> deleted = service.deleteSuite(suiteId);

        assertEquals(true, deleted.get("deleted"));
        assertFalse(suiteRepository.existsById(suiteId));
        assertFalse(scenarioRepository.existsById(scenarioId));
        assertTrue(recordingRepository.findBySuiteIdOrderByStartedAtDesc(suiteId).isEmpty());
        assertFalse(goldenAppRepository.existsById(goldenId));
    }

    @Test
    void repeatedInputUsesLatestVersionForUiAndDatabaseConsistency() {
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "Golden versioned input",
                "runtime_url", "http://localhost:9010",
                "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "VERSIONED_INPUT",
                "name", "Versioned input suite",
                "golden_app_id", golden.get("id")));
        Map<String, Object> recording = service.startRecording(String.valueOf(suite.get("id")), Map.of(
                "name", "Add final user"));
        String recordingId = String.valueOf(recording.get("id"));
        service.appendEvent(recordingId, Map.of(
                "kind", "action", "action", "enter_text",
                "target", Map.of("label", "Email", "hint", "example@gmail.com"),
                "value", "invalid"));
        service.appendEvent(recordingId, Map.of(
                "kind", "action", "action", "enter_text",
                "target", Map.of("label", "Email"),
                "value", "final@example.com"));
        service.appendEvent(recordingId, Map.of(
                "kind", "action", "action", "tap",
                "target", Map.of("semanticId", "action.add")));
        service.appendEvent(recordingId, Map.of(
                "kind", "checkpoint", "action", "observe_ui",
                "expect", Map.of("visible_texts", List.of("final@example.com"), "no_exception", true)));
        service.stopRecording(recordingId, Map.of());

        Map<String, Object> scenario = service.abstractRecording(recordingId, Map.of(
                "scenario_code", "ADD_FINAL_USER"));
        // Hai enter_text liên tiếp vào cùng semantic control là một lần nhập logic:
        // DOM Flutter có thể rebuild giữa chừng nhưng testcase chỉ replay giá trị cuối.
        List<?> steps = (List<?>) scenario.get("steps");
        assertEquals(2, steps.size());
        assertEquals("final@example.com", ((Map<?, ?>) steps.get(0)).get("value"));
        assertEquals("tap", ((Map<?, ?>) steps.get(1)).get("action"));

        // Row phải dùng ĐÚNG giá trị thật (không phải "${var}"): applyDerivedDatabaseCheckpoints
        // giờ nâng checkpoint thành entity_consistency bằng cách so khớp literal giữa row DB và
        // text đã thấy ở checkpoint UI (dòng "final@example.com" ở trên) — không biến hoá nữa.
        Map<String, Object> completed = service.applyDerivedDatabaseCheckpoints(
                String.valueOf(scenario.get("id")),
                List.of(Map.of(
                        "kind", "database_observation",
                        "table", "users",
                        "operation", "INSERT",
                        "row", Map.of("email", "final@example.com"))),
                "c".repeat(64));
        List<?> checkpoints = (List<?>) completed.get("checkpoints");
        Map<?, ?> consistency = checkpoints.stream()
                .map(Map.class::cast)
                .filter(item -> "entity_consistency".equals(item.get("kind")))
                .findFirst().orElseThrow();
        assertEquals("cross_layer", consistency.get("scope"));
        assertEquals(List.of("final@example.com"), consistency.get("ui_values"));
        assertEquals("final@example.com", ((Map<?, ?>) consistency.get("row")).get("email"));
    }

    @Test
    void deleteScenarioRemovesScenarioOracleAndSourceRecording() {
        Map<String, Object> scenario = createEquivalentScenario("DELETE_SINGLE_SCENARIO");
        String scenarioId = String.valueOf(scenario.get("id"));
        String recordingId = String.valueOf(scenario.get("source_recording_id"));

        Map<String, Object> deleted = service.deleteScenario(scenarioId);

        assertEquals(true, deleted.get("deleted"));
        assertFalse(scenarioRepository.existsById(scenarioId));
        assertFalse(recordingRepository.existsById(recordingId));
        assertTrue(oracleRepository.findByScenarioIdOrderByCreatedAtDesc(scenarioId).isEmpty());
    }

    @Test
    void revisesExistingScenarioThroughRecordingUiWithoutCreatingDuplicate() {
        Map<String, Object> original = createEquivalentScenario("REVISE_EXISTING_SCENARIO");
        String scenarioId = String.valueOf(original.get("id"));
        String originalRecordingId = String.valueOf(original.get("source_recording_id"));

        Map<String, Object> revision = service.startScenarioRevision(scenarioId);
        String revisionRecordingId = String.valueOf(revision.get("id"));
        assertEquals(scenarioId, revision.get("revision_scenario_id"));
        assertNotEquals(originalRecordingId, revisionRecordingId);
        assertEquals(3, ((List<?>) revision.get("raw_trace")).size());

        // The teacher can remove an old action and append a new action using the
        // same Record -> Abstract controls used while creating a scenario.
        service.deleteEvent(revisionRecordingId, 2);
        service.appendEvent(revisionRecordingId, Map.of(
                "kind", "action", "action", "tap",
                "target", Map.of("semanticId", "action.save", "role", "button")));
        service.stopRecording(revisionRecordingId, Map.of());

        Map<String, Object> updated = service.abstractRecording(revisionRecordingId, Map.of(
                "replace_scenario_id", scenarioId,
                "scenario_code", original.get("scenario_code"),
                "name", "Add and save user",
                "weight", 3.0,
                "viewports", original.get("viewports")));

        assertEquals(scenarioId, updated.get("id"));
        assertEquals("Add and save user", updated.get("name"));
        assertEquals(revisionRecordingId, updated.get("source_recording_id"));
        assertEquals(1, scenarioRepository.countBySuiteIdAndEnabledTrue(String.valueOf(original.get("suite_id"))));
        assertEquals(2, ((List<?>) updated.get("steps")).size());
        assertEquals(1, ((List<?>) updated.get("checkpoints")).size());
        assertEquals("PENDING", ((Map<?, ?>) updated.get("oracle")).get("status"));
    }

    @Test
    void cancellingScenarioRevisionKeepsOriginalScenarioUntouched() {
        Map<String, Object> original = createEquivalentScenario("CANCEL_SCENARIO_REVISION");
        String scenarioId = String.valueOf(original.get("id"));
        Map<String, Object> revision = service.startScenarioRevision(scenarioId);

        Map<String, Object> cancelled = service.cancelRecording(String.valueOf(revision.get("id")));

        assertEquals(true, cancelled.get("cancelled"));
        assertTrue(scenarioRepository.existsById(scenarioId));
        assertEquals(original.get("source_recording_id"),
                scenarioRepository.findById(scenarioId).orElseThrow().getSourceRecordingId());
        assertFalse(recordingRepository.existsById(String.valueOf(revision.get("id"))));
    }

    @Test
    void checkpointOnlyRecordingGetsImplicitBootAndBecomesScenario() {
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "Golden screen contract",
                "runtime_url", "http://localhost:9010",
                "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "CHECKPOINT_ONLY",
                "name", "Checkpoint-only suite",
                "golden_app_id", golden.get("id")));
        Map<String, Object> recording = service.startRecording(String.valueOf(suite.get("id")), Map.of(
                "name", "Screen contract"));
        String recordingId = String.valueOf(recording.get("id"));
        service.appendEvent(recordingId, Map.of(
                "kind", "checkpoint",
                "action", "observe_ui",
                "expect", Map.of("visible_texts", List.of("User Manager"), "no_exception", true)));
        service.stopRecording(recordingId, Map.of());

        Map<String, Object> scenario = service.abstractRecording(recordingId, Map.of(
                "scenario_code", "SCREEN_CONTRACT",
                "name", "Screen contract",
                "weight", 1.0));

        List<?> steps = (List<?>) scenario.get("steps");
        assertEquals(1, steps.size());
        assertEquals("boot", ((Map<?, ?>) steps.get(0)).get("action"));
        assertEquals(1, ((List<?>) scenario.get("checkpoints")).size());
        assertEquals("ABSTRACTED", recordingRepository.findById(recordingId).orElseThrow().getStatus().name());
    }

    @Test
    void ch7ComponentEventsSurviveAbstractionIntoScenarioCheckpoints() {
        // Bug thật gặp phải khi chấm thử 1 bài qua đúng luồng sản phẩm (record → stop →
        // abstract): appendEvent lưu đúng 8 event component_* Chương 7 vào raw_trace, nhưng
        // abstractRecording lọc checkpoint theo 1 danh sách kind cứng KHÔNG có 8 kind Ch.7 —
        // rơi vào nhánh else im lặng, mất trắng, scenario sinh ra 0 tiêu chí Chương 7. Test
        // này khoá lại đúng hành vi: mỗi kind Ch.7 phải còn nguyên trong checkpoints sau abstract.
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "Golden Ch7", "runtime_url", "http://localhost:9010", "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "CH7_ABSTRACT", "name", "Ch7 abstract suite", "golden_app_id", golden.get("id")));
        Map<String, Object> recording = service.startRecording(String.valueOf(suite.get("id")), Map.of(
                "name", "Ch7 demo", "initial_state", Map.of("reset_storage", false)));
        String recordingId = String.valueOf(recording.get("id"));
        service.appendEvent(recordingId, Map.of(
                "kind", "action", "action", "tap", "target", Map.of("label", "Mở demo Chương 7")));
        // Đích của tiêu chí Ch.7 là ĐỊNH DANH, không phải ValueKey: đề chỉ còn một hệ định
        // danh cho sinh viên. Khai bằng ValueKey phải bị chặn ngay lúc ghi.
        assertThrows(IllegalArgumentException.class, () -> service.appendEvent(recordingId, Map.of(
                "kind", "component_stack_order", "target", Map.of("valueKey", "ch7.stack"),
                "expect", Map.of("bottom_key", "ch7.stack.bottom", "top_key", "ch7.stack.top"))));
        service.appendEvent(recordingId, Map.of(
                "kind", "component_stack_order", "target", Map.of("semantic_id", "ch7.stack"),
                "expect", Map.of("bottom_key", "ch7.stack.bottom", "top_key", "ch7.stack.top")));
        service.appendEvent(recordingId, Map.of(
                "kind", "component_table", "target", Map.of("semantic_id", "ch7.table"),
                "expect", Map.of("row_count", "3")));
        service.stopRecording(recordingId, Map.of());

        Map<String, Object> scenario = service.abstractRecording(recordingId, Map.of(
                "scenario_code", "CH7_DEMO", "name", "Ch7 demo", "weight", 5.0));

        List<?> checkpoints = (List<?>) scenario.get("checkpoints");
        List<String> kinds = checkpoints.stream().map(item -> String.valueOf(((Map<?, ?>) item).get("kind"))).toList();
        assertTrue(kinds.contains("component_stack_order"),
                "component_stack_order phải còn trong checkpoints sau abstract, danh sách hiện có: " + kinds);
        assertTrue(kinds.contains("component_table"),
                "component_table phải còn trong checkpoints sau abstract, danh sách hiện có: " + kinds);
    }

    /**
     * SỐ CỘT CỦA NHÓM LẶP (25/9/2026) — tiêu chí responsive "danh sách thành lưới".
     *
     * <p>Khoá hai thứ. Một: appendEvent chặn ngay lúc ghi mọi dạng engine không chấm nổi —
     * tới lượt capture mới nổ thì người soạn đã mất cả phiên record. Hai: abstract KHÔNG âm
     * thầm bỏ event này, đúng cái bẫy đã nuốt cả 8 kind Ch.7 ở bài kiểm ngay trên.
     */
    @Test
    void soCotNhomSongQuaAbstractVaGiuDuThamSo() {
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "Golden luoi", "runtime_url", "http://localhost:9010", "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "LUOI_ABSTRACT", "name", "Luoi abstract", "golden_app_id", golden.get("id")));
        Map<String, Object> recording = service.startRecording(String.valueOf(suite.get("id")), Map.of(
                "name", "Danh sach", "initial_state", Map.of("reset_storage", false)));
        String recordingId = String.valueOf(recording.get("id"));
        service.appendEvent(recordingId, Map.of(
                "kind", "action", "action", "tap", "target", Map.of("label", "Users List")));

        assertThrows(IllegalArgumentException.class, () -> service.appendEvent(recordingId, Map.of(
                "kind", "group_columns", "khung", "desktop", "group_pattern", "user.1", "min_columns", 2)),
                "mẫu không có # thì chỉ khớp đúng một định danh — nhóm một phần tử, luôn trượt");
        assertThrows(IllegalArgumentException.class, () -> service.appendEvent(recordingId, Map.of(
                "kind", "group_columns", "khung", "desktop", "group_pattern", "user.#", "min_columns", 1)),
                "tối thiểu 1 cột thì danh sách dọc cũng đạt");
        assertThrows(IllegalArgumentException.class, () -> service.appendEvent(recordingId, Map.of(
                "kind", "group_columns", "group_pattern", "user.#", "min_columns", 2)),
                "ở khung điện thoại danh sách đúng đề là một cột — Golden tự trượt");

        service.appendEvent(recordingId, Map.of(
                "kind", "group_columns", "khung", "desktop", "group_pattern", "user.#", "min_columns", 2,
                "name", "Responsive — nhóm user.# xếp từ 2 cột trở lên ở khung desktop"));
        service.stopRecording(recordingId, Map.of());

        Map<String, Object> scenario = service.abstractRecording(recordingId, Map.of(
                "scenario_code", "LIST_GRID", "name", "Danh sach", "weight", 5.0));

        Map<?, ?> cot = ((List<?>) scenario.get("checkpoints")).stream()
                .map(item -> (Map<?, ?>) item)
                .filter(item -> "group_columns".equals(item.get("kind")))
                .findFirst().orElse(null);
        assertNotNull(cot, "group_columns phải còn trong checkpoints sau abstract");
        assertEquals("user.#", cot.get("group_pattern"));
        assertEquals(2, ((Number) cot.get("min_columns")).intValue());
        assertEquals("desktop", cot.get("khung"),
                "mất cờ khung là tiêu chí rơi về khung điện thoại và Golden tự trượt");
    }

    @Test
    void stoppedRecordingCanBeDiscardedAfterAbstractFailure() {
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "Golden recoverable recording",
                "runtime_url", "http://localhost:9010",
                "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "RECOVER_STOPPED",
                "name", "Recover stopped suite",
                "golden_app_id", golden.get("id")));
        Map<String, Object> recording = service.startRecording(String.valueOf(suite.get("id")), Map.of(
                "name", "Recover me"));
        String recordingId = String.valueOf(recording.get("id"));
        service.appendEvent(recordingId, Map.of(
                "kind", "action", "action", "boot"));
        service.stopRecording(recordingId, Map.of());

        Map<String, Object> cancelled = service.cancelRecording(recordingId);

        assertEquals(true, cancelled.get("cancelled"));
        assertFalse(recordingRepository.existsById(recordingId));
    }

    /**
     * Gói 2 kế hoạch "Định danh Semantics": định danh máy đo trên Golden lúc capture được
     * nướng vào BƯỚC theo nhãn/chữ, nhãn cũ giữ nguyên làm đường lui; không ghi đè định
     * danh gõ tay; không đụng checkpoint (Q2); chạy lại không nướng chồng.
     */
    @Test
    void capturedIdentifiersBakeIntoStepsOnlyAndNeverOverrideHandTypedOnes() throws Exception {
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "Golden identifiers",
                "runtime_url", "http://localhost:9010",
                "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "IDENTIFIER_BAKE",
                "name", "Bake identifiers",
                "golden_app_id", golden.get("id")));
        Map<String, Object> recording = service.startRecording(String.valueOf(suite.get("id")), Map.of(
                "name", "Thêm khoản chi",
                "viewport", Map.of("width", 390, "height", 844, "device_pixel_ratio", 1)));
        String recordingId = String.valueOf(recording.get("id"));
        // Bước theo nhãn và theo chữ: nhận định danh máy đo được.
        service.appendEvent(recordingId, Map.of("kind", "action", "action", "tap",
                "target", Map.of("label", "Thêm khoản chi")));
        service.appendEvent(recordingId, Map.of("kind", "action", "action", "tap",
                "target", Map.of("text", "Lưu")));
        // Đã gõ tay định danh: máy KHÔNG được ghi đè dù nhãn trùng bảng tra.
        service.appendEvent(recordingId, Map.of("kind", "action", "action", "tap",
                "target", Map.of("semanticId", "tay.luu", "label", "Thêm khoản chi")));
        // Nhãn HAI DÒNG của dòng danh sách (title⏎subtitle) — đúng ca đã làm HE230112 mất
        // 2.9 điểm; định danh phải nướng được qua chuỗi có ký tự xuống dòng đi qua JSON + DB.
        String nhanDong = "Trà sữa cuối tuần\n62.000 VND · ANUONG · 2026-08-20";
        service.appendEvent(recordingId, Map.of("kind", "action", "action", "tap",
                "target", Map.of("label", nhanDong)));
        service.appendEvent(recordingId, Map.of("kind", "checkpoint", "action", "observe_ui",
                "expect", Map.of("visible_texts", List.of("Tổng tháng: 0 VND"), "no_exception", true)));
        service.stopRecording(recordingId, Map.of());
        Map<String, Object> scenario = service.abstractRecording(recordingId, Map.of(
                "scenario_code", "ADD_EXPENSE",
                "name", "Thêm khoản chi",
                "weight", 1.0,
                "viewports", List.of(Map.of(
                        "name", "phone", "width", 390, "height", 844, "device_pixel_ratio", 1))));
        String scenarioId = String.valueOf(scenario.get("id"));
        String checkpointsTruoc = scenarioRepository.findById(scenarioId).orElseThrow().getCheckpointsJson();

        int daNuong = service.applyCapturedIdentifiers(scenarioId, List.of(
                Map.of("label", "Thêm khoản chi", "semantic_id", "chi_tieu.them"),
                Map.of("text", "Lưu", "semantic_id", "chi_tieu.form.luu"),
                Map.of("label", nhanDong, "semantic_id", "chi_tieu.dong.6"),
                Map.of("label", "Không có bước nào như vậy", "semantic_id", "bo.qua")));
        assertEquals(3, daNuong);

        BehaviorScenario saved = scenarioRepository.findById(scenarioId).orElseThrow();
        List<Map<String, Object>> steps = new ObjectMapper()
                .readValue(saved.getStepsJson(), new TypeReference<List<Map<String, Object>>>() {});
        Map<?, ?> t1 = (Map<?, ?>) steps.get(0).get("target");
        assertEquals("chi_tieu.them", t1.get("semantic_id"));
        assertEquals("Thêm khoản chi", t1.get("label"), "nhãn cũ phải còn làm đường lui");
        Map<?, ?> t2 = (Map<?, ?>) steps.get(1).get("target");
        assertEquals("chi_tieu.form.luu", t2.get("semantic_id"));
        assertEquals("Lưu", t2.get("text"));
        Map<?, ?> t3 = (Map<?, ?>) steps.get(2).get("target");
        assertEquals("tay.luu", t3.get("semanticId"), "định danh gõ tay phải giữ nguyên");
        assertNull(t3.get("semantic_id"));
        Map<?, ?> t4 = (Map<?, ?>) steps.get(3).get("target");
        assertEquals("chi_tieu.dong.6", t4.get("semantic_id"), "nhãn hai dòng phải tra được");
        assertEquals(nhanDong, t4.get("label"));
        assertEquals(checkpointsTruoc, saved.getCheckpointsJson(), "checkpoint không được đụng");

        // Chạy lại với bảng tra khác: bước đã có định danh không bị đổi.
        assertEquals(0, service.applyCapturedIdentifiers(scenarioId, List.of(
                Map.of("label", "Thêm khoản chi", "semantic_id", "khac.di"))));
        Map<String, Object> phu = service.identifierCoverage(List.of(saved));
        assertEquals(4, phu.get("steps_with_identifier"));
        assertEquals(4, phu.get("steps_total"));
    }

    private Map<String, Object> createEquivalentScenario(String suiteCode) {
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "Golden " + suiteCode,
                "runtime_url", "http://localhost:9010",
                "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", suiteCode,
                "name", "Deterministic suite",
                "golden_app_id", golden.get("id")));
        Map<String, Object> recording = service.startRecording(String.valueOf(suite.get("id")), Map.of(
                "name", "Add user",
                "viewport", Map.of("width", 390, "height", 844, "device_pixel_ratio", 1)));
        String recordingId = String.valueOf(recording.get("id"));
        service.appendEvent(recordingId, Map.of(
                "kind", "action", "action", "enter_text",
                "target", Map.of("semanticId", "field.email"),
                "value", "student@example.com"));
        service.appendEvent(recordingId, Map.of(
                "kind", "action", "action", "tap",
                "target", Map.of("semanticId", "action.add")));
        service.appendEvent(recordingId, Map.of(
                "kind", "checkpoint", "action", "observe_ui",
                "expect", Map.of("visible_texts", List.of("student@example.com"), "no_exception", true)));
        service.stopRecording(recordingId, Map.of());
        return service.abstractRecording(recordingId, Map.of(
                "scenario_code", "ADD_USER",
                "name", "Add user",
                "weight", 2.0,
                "viewports", List.of(Map.of(
                        "name", "phone", "width", 390, "height", 844,
                        "device_pixel_ratio", 1))));
    }

    @Test
    void recordsAbstractsAndPublishesWithoutPerExamDartCode() {
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "User Manager đáp án",
                "runtime_url", "http://localhost:9010/golden",
                "platform", "WEB",
                "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "USER_CRUD_RAR",
                "name", "User CRUD Record Replay",
                "golden_app_id", golden.get("id"),
                "database_contract", Map.of(
                        "enabled", true,
                        "driver", "sqlite",
                        "tables", List.of(Map.of("name", "users", "primary_key", "uid")))));

        Map<String, Object> recording = service.startRecording(String.valueOf(suite.get("id")), Map.of(
                "name", "Thêm người dùng",
                "viewport", Map.of("id", "mobile", "width", 390, "height", 844)));
        String recordingId = String.valueOf(recording.get("id"));
        service.appendEvent(recordingId, Map.of(
                "kind", "action",
                "action", "enter_text",
                "target", Map.of("semanticId", "field.uid", "role", "textbox"),
                "value", "SV01"));
        service.appendEvent(recordingId, Map.of(
                "kind", "action",
                "action", "tap",
                "target", Map.of("semanticId", "action.add", "role", "button", "text", "Add")));
        service.appendEvent(recordingId, Map.of(
                "kind", "database_observation",
                "checkpoint", true,
                "table", "users",
                "operation", "INSERT",
                "row", Map.of("uid", "SV01")));
        service.stopRecording(recordingId, Map.of(
                "final_observation", Map.of("visible_texts", List.of("SV01"))));

        Map<String, Object> scenario = service.abstractRecording(recordingId, Map.of(
                "scenario_code", "ADD_USER",
                "weight", 3.0));
        assertEquals("ADD_USER", scenario.get("scenario_code"));
        // Không còn biến hoá: giá trị "SV01" đã gõ được giữ nguyên trong step, không đổi thành "${...}".
        List<?> steps = (List<?>) scenario.get("steps");
        assertEquals("SV01", ((Map<?, ?>) steps.get(0)).get("value"));
        assertFalse(((List<?>) scenario.get("checkpoints")).isEmpty());

        // Production gọi bước này sau khi Docker replay Golden trên Hidden DB và
        // capture Output DB. Unit test không cần Docker nên hoàn thiện oracle bằng
        // một diff rỗng; checkpoint DB đã được record trực tiếp ở phía trên.
        service.applyDerivedDatabaseCheckpoints(
                String.valueOf(scenario.get("id")),
                List.of(),
                "a".repeat(64));

        Map<String, Object> published = service.publish(String.valueOf(suite.get("id")));
        assertEquals("PUBLISHED", published.get("status"));
        assertEquals(true, published.get("ready_for_replay"));

        Map<String, Object> plan = service.executionPlan(String.valueOf(suite.get("id")));
        assertEquals("1.0", plan.get("schema_version"));
        assertEquals(1, ((List<?>) plan.get("scenarios")).size());
    }

    @Test
    void recapturingOracleAfterGoldenChangeUpdatesGoldenShaSoPublishSucceeds() {
        // Bug thật gặp phải khi publish thử sau khi thay Golden Solution: recapture oracle
        // của scenario ĐÃ CÓ SẴN qua applyDerivedDatabaseCheckpoints (đường updateScenario
        // dùng, khác đường abstractRecording lần đầu) chỉ cập nhật nội dung/observation chứ
        // KHÔNG đụng golden_sha256 — oracle mãi mãi "READY" nhưng trỏ sha Golden CŨ, nên
        // publish() cứ báo "chưa có oracle READY khớp phiên bản Golden hiện tại" dù đã
        // recapture bao nhiêu lần. Test khoá lại: truyền goldenSha256 mới phải cập nhật
        // đúng, và publish() phải thành công sau đó.
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "Golden resha", "runtime_url", "http://localhost:9010", "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "RESHA_AFTER_GOLDEN_CHANGE", "name", "Resha suite", "golden_app_id", golden.get("id")));
        Map<String, Object> recording = service.startRecording(String.valueOf(suite.get("id")), Map.of("name", "Add user"));
        String recordingId = String.valueOf(recording.get("id"));
        service.appendEvent(recordingId, Map.of(
                "kind", "action", "action", "tap", "target", Map.of("semanticId", "action.add")));
        service.appendEvent(recordingId, Map.of(
                "kind", "checkpoint", "action", "observe_ui",
                "expect", Map.of("visible_texts", List.of("ok"), "no_exception", true)));
        service.stopRecording(recordingId, Map.of());
        Map<String, Object> scenario = service.abstractRecording(recordingId, Map.of(
                "scenario_code", "RESHA_SCENARIO", "weight", 1.0));
        String scenarioId = String.valueOf(scenario.get("id"));
        service.applyDerivedDatabaseCheckpoints(scenarioId, List.of(), "a".repeat(64));

        Map<String, Object> published = service.publish(String.valueOf(suite.get("id")));
        assertEquals("PUBLISHED", published.get("status"));

        // Mô phỏng đổi Golden Solution (như markGoldenSolutionReady làm khi upload version mới).
        com.example.grader.entity.GoldenApp goldenRow = goldenAppRepository.findById(String.valueOf(golden.get("id"))).orElseThrow();
        goldenRow.setArtifactSha256("b".repeat(64));
        goldenAppRepository.save(goldenRow);

        IllegalStateException blocked = assertThrows(IllegalStateException.class,
                () -> service.publish(String.valueOf(suite.get("id"))));
        assertTrue(blocked.getMessage().contains("oracle READY"),
                "Sau khi đổi Golden mà chưa recapture, publish phải chặn lại: " + blocked.getMessage());

        // Recapture với sha Golden MỚI — mô phỏng đúng những gì GoldenOracleCaptureService làm.
        service.applyDerivedDatabaseCheckpoints(scenarioId, List.of(), "c".repeat(64), "b".repeat(64));

        com.example.grader.entity.OracleSnapshot refreshed =
                oracleRepository.findFirstByScenarioIdOrderByCreatedAtDesc(scenarioId).orElseThrow();
        assertEquals("b".repeat(64), refreshed.getGoldenSha256(),
                "Recapture phải cập nhật golden_sha256 sang phiên bản Golden vừa dùng để replay");

        Map<String, Object> republished = service.publish(String.valueOf(suite.get("id")));
        assertEquals("PUBLISHED", republished.get("status"));
    }

    @Test
    void refusesRawCoordinateActionsWithoutSemanticTarget() {
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "Golden",
                "runtime_url", "http://localhost:9010",
                "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "NO_COORDINATE_MACRO",
                "name", "Không macro toạ độ",
                "golden_app_id", golden.get("id")));
        Map<String, Object> recording = service.startRecording(String.valueOf(suite.get("id")), Map.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.appendEvent(String.valueOf(recording.get("id")), Map.of(
                        "kind", "action", "action", "tap", "x", 20, "y", 40)));
        assertTrue(error.getMessage().contains("target ngữ nghĩa"));
    }

    @Test
    void recordsSemanticUiStateAndRejectsUnsupportedRole() {
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "Golden semantic",
                "runtime_url", "http://localhost:9010",
                "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "SEMANTIC_UI_STATE",
                "name", "Semantic UI state",
                "golden_app_id", golden.get("id")));
        Map<String, Object> recording = service.startRecording(String.valueOf(suite.get("id")), Map.of());
        String recordingId = String.valueOf(recording.get("id"));

        Map<String, Object> semanticNode = Map.of(
                "target", Map.of("label", "Email"),
                "role", "text_field",
                "visible", true,
                "value", "student@example.com",
                "enabled", true);
        Map<String, Object> appended = service.appendEvent(recordingId, Map.of(
                "kind", "checkpoint",
                "action", "observe_ui",
                "expect", Map.of(
                        "semantic_nodes", List.of(semanticNode),
                        "no_exception", true)));
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) appended.get("event");
        assertEquals("semantic_nodes", ((Map<?, ?>) event.get("expect")).keySet().stream()
                .filter("semantic_nodes"::equals).findFirst().orElseThrow());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.appendEvent(recordingId, Map.of(
                        "kind", "checkpoint",
                        "expect", Map.of("semantic_nodes", List.of(Map.of(
                                "target", Map.of("label", "Email"),
                                "role", "unknown_widget"))))));
        assertTrue(error.getMessage().contains("Loại semantic node"));
    }

    @Test
    void deletesRecordedEventAndResequencesRemainingTrace() {
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "Golden",
                "runtime_url", "http://localhost:9010",
                "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "DELETE_RECORDED_EVENT",
                "name", "Delete recorded event",
                "golden_app_id", golden.get("id")));
        Map<String, Object> recording = service.startRecording(String.valueOf(suite.get("id")), Map.of());
        String recordingId = String.valueOf(recording.get("id"));

        service.appendEvent(recordingId, Map.of(
                "kind", "action", "action", "tap",
                "target", Map.of("text", "Add")));
        service.appendEvent(recordingId, Map.of(
                "kind", "checkpoint", "action", "observe_ui",
                "expect", Map.of("visible_texts", List.of("Saved"), "no_exception", true)));
        service.appendEvent(recordingId, Map.of(
                "kind", "action", "action", "tap",
                "target", Map.of("text", "Close")));

        Map<String, Object> deleted = service.deleteEvent(recordingId, 2);
        assertEquals(2, deleted.get("event_count"));

        Map<String, Object> refreshed = service.getRecording(recordingId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trace = (List<Map<String, Object>>) refreshed.get("raw_trace");
        assertEquals(2, trace.size());
        assertEquals(1, trace.get(0).get("sequence"));
        assertEquals("Add", ((Map<?, ?>) trace.get(0).get("target")).get("text"));
        assertEquals(2, trace.get(1).get("sequence"));
        assertEquals("Close", ((Map<?, ?>) trace.get(1).get("target")).get("text"));

        service.stopRecording(recordingId, Map.of());
        assertThrows(IllegalStateException.class, () -> service.deleteEvent(recordingId, 1));
    }

    @Test
    void stopAndAbstractAreRetrySafeAndReplayChangesInvalidateOracle() {
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "Golden", "runtime_url", "http://localhost:9010", "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "RETRY_SAFE_RAR",
                "name", "Retry safe",
                "golden_app_id", golden.get("id")));
        Map<String, Object> recording = service.startRecording(String.valueOf(suite.get("id")), Map.of());
        String recordingId = String.valueOf(recording.get("id"));
        service.appendEvent(recordingId, Map.of(
                "kind", "action", "action", "tap",
                "target", Map.of("text", "Add")));

        Map<String, Object> firstStop = service.stopRecording(recordingId, Map.of());
        Map<String, Object> secondStop = service.stopRecording(recordingId, Map.of());
        assertEquals("STOPPED", firstStop.get("status"));
        assertEquals("STOPPED", secondStop.get("status"));

        Map<String, Object> firstAbstract = service.abstractRecording(recordingId, Map.of(
                "scenario_code", "ADD_USER"));
        Map<String, Object> secondAbstract = service.abstractRecording(recordingId, Map.of(
                "scenario_code", "IGNORED_ON_RETRY"));
        assertEquals(firstAbstract.get("id"), secondAbstract.get("id"));
        assertEquals("PENDING", ((Map<?, ?>) firstAbstract.get("oracle")).get("status"));

        String scenarioId = String.valueOf(firstAbstract.get("id"));
        service.applyDerivedDatabaseCheckpoints(
                scenarioId, List.of(), "b".repeat(64));
        assertEquals("READY", oracleRepository
                .findFirstByScenarioIdOrderByCreatedAtDesc(scenarioId).orElseThrow().getStatus().name());

        service.updateScenario(scenarioId, Map.of(
                "steps", List.of(Map.of(
                        "id", "step_1", "action", "tap", "target", Map.of("text", "Save")))));
        assertEquals("STALE", oracleRepository
                .findFirstByScenarioIdOrderByCreatedAtDesc(scenarioId).orElseThrow().getStatus().name());
    }

    /**
     * Gói "Icon": luật LẶP đo trên Golden phải nướng được vào cả tiêu chí "có mặt" (trước
     * đây loại này không nhận gì từ capture), và phải BỎ khi lượt đo mới chỉ thấy một thể
     * hiện — giữ luật cũ thì bài đúng trượt oan vì "thiếu dòng".
     */
    @Test
    void capturedRepeatBakesIntoPresentCriteriaAndClearsWhenNoLongerRepeated() throws Exception {
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "Golden icon",
                "runtime_url", "http://localhost:9010",
                "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "ICON_REPEAT",
                "name", "Bake repeat",
                "golden_app_id", golden.get("id")));
        Map<String, Object> recording = service.startRecording(String.valueOf(suite.get("id")), Map.of(
                "name", "Màn chính",
                "viewport", Map.of("width", 412, "height", 915, "device_pixel_ratio", 1)));
        String recordingId = String.valueOf(recording.get("id"));
        service.appendEvent(recordingId, Map.of("kind", "action", "action", "tap",
                "target", Map.of("label", "Tất cả")));
        service.appendEvent(recordingId, Map.of("kind", "component_present", "action", "observe_ui",
                "checkpoint", true, "stage", "ASSERT",
                "target", Map.of("icon", "delete_outline"), "visible", true,
                "name", "Màn hình — có icon delete_outline"));
        service.appendEvent(recordingId, Map.of("kind", "component_color", "action", "observe_ui",
                "checkpoint", true, "stage", "ASSERT",
                "target", Map.of("icon", "delete_outline"), "visible", true,
                "name", "Màn hình — đúng màu icon delete_outline"));
        service.stopRecording(recordingId, Map.of());
        Map<String, Object> scenario = service.abstractRecording(recordingId, Map.of(
                "scenario_code", "UI_MAIN",
                "name", "Màn chính",
                "weight", 1.0,
                "viewports", List.of(Map.of(
                        "name", "phone", "width", 412, "height", 915, "device_pixel_ratio", 1))));
        String scenarioId = String.valueOf(scenario.get("id"));
        List<Map<String, Object>> chots = docChots(scenarioId);
        String idCoMat = String.valueOf(chots.get(0).get("id"));
        String idMau = String.valueOf(chots.get(1).get("id"));

        Map<String, Object> lap = Map.of(
                "count", 6, "per_row", true, "row_id_prefix", "chi_tieu.dong.",
                "row_right_pct", 5.8, "row_center_y_pct", 50.0);
        assertEquals(2, service.applyCapturedLayout(scenarioId, Map.of(
                idCoMat, Map.of("repeat", lap),
                idMau, Map.of("color", "#3F4947", "repeat", lap))));
        chots = docChots(scenarioId);
        assertEquals(lap, ((Map<?, ?>) chots.get(0).get("expect")).get("repeat"),
                "tiêu chí có mặt phải nhận luật lặp");
        Map<?, ?> mongDoiMau = (Map<?, ?>) chots.get(1).get("expect");
        assertEquals("#3F4947", mongDoiMau.get("color"));
        assertEquals(lap, mongDoiMau.get("repeat"));

        // Lượt đo sau chỉ còn một thể hiện (dữ liệu mẫu đổi): luật lặp phải biến mất.
        assertEquals(2, service.applyCapturedLayout(scenarioId, Map.of(
                idCoMat, Map.of("test_id", "x"),
                idMau, Map.of("color", "#111111"))));
        chots = docChots(scenarioId);
        assertNull(((Map<?, ?>) chots.get(0).get("expect")).get("repeat"));
        assertNull(((Map<?, ?>) chots.get(1).get("expect")).get("repeat"));
        assertEquals("#111111", ((Map<?, ?>) chots.get(1).get("expect")).get("color"));

        // Quy ước cũ giữ nguyên: nướng lại cùng một màu vẫn tính là đã nướng. Nhưng tiêu chí
        // "có mặt" KHÔNG có luật lặp thì không tính — tránh thổi phồng số báo cho người ra
        // đề chỉ vì loại tiêu chí này nay cũng đi qua hàm.
        assertEquals(1, service.applyCapturedLayout(scenarioId, Map.of(
                idCoMat, Map.of("test_id", "x"),
                idMau, Map.of("color", "#111111"))));
    }

    private List<Map<String, Object>> docChots(String scenarioId) throws Exception {
        return new ObjectMapper().readValue(
                scenarioRepository.findById(scenarioId).orElseThrow().getCheckpointsJson(),
                new TypeReference<List<Map<String, Object>>>() {});
    }

    @Test
    void recordsRouteActionsRouteStateAndRelativeLayoutWithoutCoordinates() {
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "Golden routing", "runtime_url", "http://localhost:9010", "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "ROUTE_LAYOUT", "name", "Route and layout",
                "golden_app_id", golden.get("id")));
        Map<String, Object> recording = service.startRecording(String.valueOf(suite.get("id")), Map.of());
        String recordingId = String.valueOf(recording.get("id"));

        service.appendEvent(recordingId, Map.of(
                "kind", "action", "action", "boot_with_uri", "uri", "/movies/42?tab=cast"));
        service.appendEvent(recordingId, Map.of(
                "kind", "action", "action", "browser_back"));
        service.appendEvent(recordingId, Map.of(
                "kind", "action", "action", "browser_forward"));
        service.appendEvent(recordingId, Map.of(
                "kind", "route_state", "expect", Map.of(
                        "path", "/movies/42", "query", Map.of("tab", "cast"), "can_pop", true)));
        service.appendEvent(recordingId, Map.of(
                "kind", "layout_relation",
                "target", Map.of("semanticId", "field.email"),
                "relative_to", Map.of("semanticId", "action.save"),
                "relation", "above", "tolerance_pct", 4));
        service.stopRecording(recordingId, Map.of());

        Map<String, Object> scenario = service.abstractRecording(recordingId, Map.of(
                "scenario_code", "DETAIL_ROUTE"));
        List<?> steps = (List<?>) scenario.get("steps");
        assertEquals("boot_with_uri", ((Map<?, ?>) steps.get(0)).get("action"));
        assertEquals("/movies/42?tab=cast", ((Map<?, ?>) steps.get(0)).get("uri"));
        List<?> checkpoints = (List<?>) scenario.get("checkpoints");
        assertEquals(List.of("route_state", "layout_relation"), checkpoints.stream()
                .map(Map.class::cast).map(item -> item.get("kind")).toList());

        Map<?, ?> layout = checkpoints.stream().map(Map.class::cast)
                .filter(item -> "layout_relation".equals(item.get("kind"))).findFirst().orElseThrow();
        assertEquals(1, service.applyCapturedLayout(String.valueOf(scenario.get("id")), Map.of(
                String.valueOf(layout.get("id")), Map.of("relation", "same_row"))));
        Map<String, Object> refreshedSuite = service.getSuite(String.valueOf(suite.get("id")));
        Map<String, Object> refreshed = (Map<String, Object>) ((List<?>) refreshedSuite.get("scenarios")).get(0);
        Map<?, ?> bakedLayout = ((List<?>) refreshed.get("checkpoints")).stream().map(Map.class::cast)
                .filter(item -> "layout_relation".equals(item.get("kind"))).findFirst().orElseThrow();
        assertEquals("same_row", ((Map<?, ?>) bakedLayout.get("expect")).get("relation"));
    }

    @Test
    void rejectsUnsafeOrIncompleteRouteAndLayoutEvents() {
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "Golden invalid routing", "runtime_url", "http://localhost:9010", "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "INVALID_ROUTE_LAYOUT", "name", "Invalid route and layout",
                "golden_app_id", golden.get("id")));
        Map<String, Object> recording = service.startRecording(String.valueOf(suite.get("id")), Map.of());
        String recordingId = String.valueOf(recording.get("id"));

        assertThrows(IllegalArgumentException.class, () -> service.appendEvent(recordingId, Map.of(
                "kind", "action", "action", "open_uri", "uri", "file:///secret")));
        assertThrows(IllegalArgumentException.class, () -> service.appendEvent(recordingId, Map.of(
                "kind", "route_state", "expect", Map.of())));
        assertThrows(IllegalArgumentException.class, () -> service.appendEvent(recordingId, Map.of(
                "kind", "layout_relation", "target", Map.of("text", "A"), "relation", "above")));
        service.appendEvent(recordingId, Map.of("kind", "action", "action", "boot"));
        assertThrows(IllegalArgumentException.class, () -> service.appendEvent(recordingId, Map.of(
                "kind", "action", "action", "boot_with_uri", "uri", "/too-late")));
    }

    /**
     * Gói SharedPreferences: tiêu chí "giá trị đã lưu" phải bị chặn khi thiếu khoá, phải sống
     * qua abstractRecording (nếu rơi thì scenario im lặng mất tiêu chí), và phải nhận được giá
     * trị chuẩn do máy đo trên Golden thay vì người ra đề gõ tay.
     */
    @Test
    void preferencesCriterionNeedsKeyAndTakesCapturedValue() throws Exception {
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "Golden prefs",
                "runtime_url", "http://localhost:9010",
                "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "PREFS_BAKE",
                "name", "Bake preferences",
                "golden_app_id", golden.get("id")));
        Map<String, Object> recording = service.startRecording(String.valueOf(suite.get("id")), Map.of(
                "name", "Bat che do toi",
                "viewport", Map.of("width", 412, "height", 915, "device_pixel_ratio", 1),
                "initial_state", Map.of("reset_storage", true,
                        "preferences", Map.of("che_do_toi", false))));
        String recordingId = String.valueOf(recording.get("id"));

        assertThrows(IllegalArgumentException.class, () -> service.appendEvent(recordingId, Map.of(
                "kind", "preferences_observation", "action", "observe_ui")),
                "thiếu khoá thì phải chặn ngay lúc ghi");

        service.appendEvent(recordingId, Map.of("kind", "action", "action", "tap",
                "target", Map.of("semanticId", "cong_tac_che_do")));
        service.appendEvent(recordingId, Map.of("kind", "preferences_observation",
                "action", "observe_ui", "key", "che_do_toi",
                "name", "Bo nho app - da luu khoa che_do_toi"));
        service.stopRecording(recordingId, Map.of());
        Map<String, Object> scenario = service.abstractRecording(recordingId, Map.of(
                "scenario_code", "DARK_MODE",
                "name", "Bat che do toi",
                "weight", 1.0,
                "viewports", List.of(Map.of(
                        "name", "phone", "width", 412, "height", 915, "device_pixel_ratio", 1))));
        String scenarioId = String.valueOf(scenario.get("id"));

        List<Map<String, Object>> chots = docChots(scenarioId);
        assertEquals(1, chots.size(), "tiêu chí phải sống qua abstractRecording");
        assertEquals("preferences_observation", chots.get(0).get("kind"));
        assertEquals("che_do_toi", chots.get(0).get("key"));
        // Trạng thái đầu của kho đi kèm scenario, engine đọc để dựng kho trước khi mở app.
        Map<?, ?> banDau = (Map<?, ?>) scenario.get("initial_state");
        assertEquals(Map.of("che_do_toi", false), banDau.get("preferences"));

        assertEquals(1, service.applyCapturedLayout(scenarioId, Map.of(
                String.valueOf(chots.get(0).get("id")), Map.of("observed", true))));
        assertEquals(true, ((Map<?, ?>) docChots(scenarioId).get(0).get("expect")).get("value"));
    }

    // ── Thừa kế điểm khi sinh lại testcase (chốt 20/9) ──────────────────────────────────
    // Sửa một dòng giao diện trong Golden là phải upload lại rồi sinh lại testcase cho từng
    // luồng. Trước đây lượt sinh lại ghi đè trắng checkpointsJson nên công chia điểm mất sạch.

    @Test
    void giuDiemVaRangBuocKhiCheckpointKhongDoi() {
        List<Map<String, Object>> cu = List.of(
                chot("cp_1", "ui_state", "them", 8.0, null),
                chot("cp_2", "ui_state", "danh_sach", 2.5, "cp_1"));
        List<Map<String, Object>> moi = new ArrayList<>(List.of(
                chot("database_diff_1", "ui_state", "them", 1.0, null),
                chot("database_diff_2", "ui_state", "danh_sach", 1.0, null)));

        assertTrue(thuaKe(cu, moi));
        assertEquals(8.0, moi.get(0).get("weight"));
        assertEquals(2.5, moi.get(1).get("weight"));
        assertEquals("cp_1", moi.get(1).get("requires"));
        // id cũ phải theo sang: `requires` của tiêu chí con đang trỏ vào đúng chuỗi này.
        assertEquals("cp_1", moi.get(0).get("id"));
    }

    @Test
    void resetKhiGiaTriMongDoiDoi() {
        List<Map<String, Object>> cu = List.of(chot("cp_1", "ui_state", "them", 8.0, null));
        Map<String, Object> khac = chot("database_diff_1", "ui_state", "them", 1.0, null);
        khac.put("expect", Map.of("visible", false));
        List<Map<String, Object>> moi = new ArrayList<>(List.of(khac));

        assertFalse(thuaKe(cu, moi));
        assertEquals(1.0, moi.get(0).get("weight"), "nội dung đổi thì reset, không đoán điểm cũ thuộc về ai");
    }

    @Test
    void themMotCheckpointLaResetCaScenario() {
        List<Map<String, Object>> cu = List.of(chot("cp_1", "ui_state", "them", 8.0, null));
        List<Map<String, Object>> moi = new ArrayList<>(List.of(
                chot("database_diff_1", "ui_state", "them", 1.0, null),
                chot("database_diff_2", "ui_state", "sua", 1.0, null)));

        assertFalse(thuaKe(cu, moi), "được ăn cả ngã về không: khác số lượng là reset sạch");
        assertEquals(1.0, moi.get(0).get("weight"));
    }

    /** Bên cũ đọc từ JSON đã lưu, bên mới vừa dựng trong bộ nhớ — 5 và 5.0 là một giá trị. */
    @Test
    void soSanhKhongPhanBietIntegerVaDouble() {
        Map<String, Object> cuChot = chot("cp_1", "layout_relation", "danh_sach", 4.0, null);
        cuChot.put("tolerance_pct", 5);
        Map<String, Object> moiChot = chot("database_diff_1", "layout_relation", "danh_sach", 1.0, null);
        moiChot.put("tolerance_pct", 5.0);
        List<Map<String, Object>> moi = new ArrayList<>(List.of(moiChot));

        assertTrue(thuaKe(List.of(cuChot), moi));
        assertEquals(4.0, moi.get(0).get("weight"));
    }

    /**
     * Checkpoint CSDL tự sinh chỉ xuất hiện SAU lượt replay Docker, nên lúc so nó chưa có trong
     * danh sách mới. Tính nó vào phép so thì lần nào cũng ra "khác" và cơ chế thừa kế chết cứng.
     */
    @Test
    void checkpointCsdlTuSinhKhongLamLechPhepSo() {
        Map<String, Object> csdl = chot("database_diff_9", "database_observation", "", 3.0, null);
        csdl.put("generated_from", "hidden_output_diff");
        List<Map<String, Object>> cu = List.of(chot("cp_1", "ui_state", "them", 8.0, null), csdl);
        List<Map<String, Object>> moi = new ArrayList<>(List.of(chot("x", "ui_state", "them", 1.0, null)));

        assertTrue(thuaKe(cu, moi));
        assertEquals(8.0, moi.get(0).get("weight"));
    }

    private Map<String, Object> chot(String id, String kind, String target, double weight, String requires) {
        Map<String, Object> ra = new LinkedHashMap<>();
        ra.put("id", id);
        ra.put("kind", kind);
        ra.put("target", Map.of("semantic_id", target));
        ra.put("expect", Map.of("visible", true));
        ra.put("weight", weight);
        if (requires != null) ra.put("requires", requires);
        return ra;
    }

    private boolean thuaKe(List<Map<String, Object>> cu, List<Map<String, Object>> moi) {
        return Boolean.TRUE.equals(
                ReflectionTestUtils.invokeMethod(service, "thuaKeDiemNeuKhongDoi", cu, moi));
    }

    /**
     * Ca thật của người soạn: bấm "Sửa thao tác" rồi bấm thẳng "Sinh lại testcase", không đụng
     * vào action hay checkpoint nào. Điểm và ràng buộc phải còn nguyên.
     *
     * Vì sao phải có tiêu chí VỊ TRÍ trong ca này: giá trị chuẩn của nó do engine đo trên Golden
     * rồi nướng vào sau capture, KHÔNG nằm trong raw_trace — nên bản dựng lại từ trace không thể
     * có. Đo thật 20/9: bên cũ mang expect{center_x…}, bên mới không có khoá expect nào, và chỉ
     * một tiêu chí lệch kiểu đó là đủ kéo cả scenario về reset.
     */
    @Test
    @SuppressWarnings("unchecked")
    void suaThaoTacRoiSinhLaiNgayThiGiuNguyenDiemVaRangBuoc() throws Exception {
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "G", "runtime_url", "http://localhost:9010", "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "REVISE_KEEP", "name", "Giu diem", "golden_app_id", golden.get("id")));
        Map<String, Object> rec = service.startRecording(String.valueOf(suite.get("id")), Map.of(
                "name", "Validate so am",
                "viewport", Map.of("width", 390, "height", 844, "device_pixel_ratio", 1)));
        String recId = String.valueOf(rec.get("id"));
        service.appendEvent(recId, Map.of("kind", "action", "action", "enter_text",
                "target", Map.of("semanticId", "form.so_tien"), "value", "-5"));
        service.appendEvent(recId, Map.of("kind", "action", "action", "tap",
                "target", Map.of("semanticId", "form.luu")));
        service.appendEvent(recId, Map.of("kind", "checkpoint", "action", "observe_ui",
                "expect", Map.of("visible_texts", List.of("So tien phai lon hon 0"), "no_exception", true)));
        service.appendEvent(recId, Map.of("kind", "component_position",
                "target", Map.of("semanticId", "form.canh_bao")));
        service.stopRecording(recId, Map.of());
        Map<String, Object> scenario = service.abstractRecording(recId, Map.of(
                "scenario_code", "VALIDATE_AM", "name", "Validate so am", "weight", 3.0,
                "viewports", List.of(Map.of("name", "phone", "width", 390, "height", 844,
                        "device_pixel_ratio", 1))));
        String scenarioId = String.valueOf(scenario.get("id"));

        // Capture nướng toạ độ chuẩn vào tiêu chí vị trí.
        List<Map<String, Object>> chots = docChots(scenarioId);
        String idViTri = chots.stream().filter(c -> "component_position".equals(c.get("kind")))
                .map(c -> String.valueOf(c.get("id"))).findFirst().orElseThrow();
        assertEquals(1, service.applyCapturedLayout(scenarioId, Map.of(idViTri, Map.of(
                "center_x", 50.0, "center_y", 30.0, "width", 80.0, "height", 6.0))));

        // Giảng viên chia điểm và ràng tiêu chí vị trí vào tiêu chí chữ.
        chots = docChots(scenarioId);
        String idChu = chots.stream().filter(c -> !idViTri.equals(String.valueOf(c.get("id"))))
                .map(c -> String.valueOf(c.get("id"))).findFirst().orElseThrow();
        for (Map<String, Object> c : chots) {
            c.put("weight", 7.5);
            if (idViTri.equals(String.valueOf(c.get("id")))) c.put("requires", idChu);
        }
        service.updateScenario(scenarioId, Map.of("checkpoints", chots, "weight", 15.0));

        // Bấm "Sửa thao tác" rồi bấm thẳng "Sinh lại testcase".
        Map<String, Object> revision = service.startScenarioRevision(scenarioId);
        String revId = String.valueOf(revision.get("id"));
        service.stopRecording(revId, Map.of());
        service.abstractRecording(revId, Map.of());

        List<Map<String, Object>> sau = docChots(scenarioId);
        assertEquals(2, sau.size());
        for (Map<String, Object> c : sau) {
            assertEquals(7.5, ((Number) c.get("weight")).doubleValue(),
                    "điểm checkpoint phải sống qua lượt sinh lại không đổi gì");
        }
        Map<String, Object> viTri = sau.stream()
                .filter(c -> "component_position".equals(c.get("kind"))).findFirst().orElseThrow();
        assertEquals(idChu, viTri.get("requires"), "ràng buộc cha–con phải còn");
        assertEquals(idViTri, viTri.get("id"), "id cũ phải giữ, requires của người khác trỏ vào nó");
        assertEquals(15.0, scenarioRepository.findById(scenarioId).orElseThrow().getWeight(),
                "điểm cấp hàm test cũng phải còn khi không truyền weight");
    }

    /**
     * "Duyệt lại scenario" phải báo ĐÚNG thứ publish sẽ chặn, không nghiêm hơn cũng không nhẹ
     * hơn — nếu lệch thì nó thành một cổng thứ hai, người soạn sửa xong vẫn không publish được.
     */
    @Test
    void duyetLaiScenarioBaoDungThuPublishSeChan() throws Exception {
        String sha = "a".repeat(64);
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "G", "runtime_url", "http://localhost:9010", "ready", true,
                "artifact_sha256", sha));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "SOAT_VS_PUBLISH", "name", "Soat", "golden_app_id", golden.get("id")));
        String suiteId = String.valueOf(suite.get("id"));
        Map<String, Object> rec = service.startRecording(suiteId, Map.of(
                "name", "Them", "viewport", Map.of("width", 390, "height", 844, "device_pixel_ratio", 1)));
        String recId = String.valueOf(rec.get("id"));
        service.appendEvent(recId, Map.of("kind", "action", "action", "tap",
                "target", Map.of("semanticId", "form.luu")));
        service.appendEvent(recId, Map.of("kind", "checkpoint", "action", "observe_ui",
                "expect", Map.of("visible_texts", List.of("Da luu"), "no_exception", true)));
        service.stopRecording(recId, Map.of());
        service.abstractRecording(recId, Map.of(
                "scenario_code", "THEM", "name", "Them", "weight", 5.0,
                "output_database_sha256", "b".repeat(64),
                "viewports", List.of(Map.of("name", "phone", "width", 390, "height", 844,
                        "device_pixel_ratio", 1))));

        Map<String, Object> truoc = service.scenarioReadiness(suiteId);
        assertEquals(List.of(), truoc.get("failed"), "oracle vừa sinh thì phải xanh");

        // Upload LẠI ĐÚNG bản Golden cũ: sha không đổi một ký tự.
        service.markGoldenSolutionReady(suiteId, Map.of("id", "art-1", "sha256", sha));
        assertEquals(false, service.scenarioReadiness(suiteId).get("golden_ready"),
                "vừa upload thì Golden App về REGISTERED — phải báo cần Build & mở Golden lại");
        // Giả lập runtime build xong để soi riêng cổng oracle.
        com.example.grader.entity.GoldenApp app =
                goldenAppRepository.findById(String.valueOf(golden.get("id"))).orElseThrow();
        app.setStatus(com.example.grader.entity.GoldenAppStatus.READY);
        goldenAppRepository.save(app);

        Map<String, Object> sau = service.scenarioReadiness(suiteId);
        assertEquals(List.of("THEM"), sau.get("failed"),
                "upload lại chính bản cũ vẫn làm mọi oracle STALE — đây là hành vi của hệ thống, "
                + "không phải của phép duyệt");
        // Và publish chặn y hệt: phép duyệt không nghiêm hơn publish.
        IllegalStateException loi = assertThrows(IllegalStateException.class, () -> service.publish(suiteId));
        assertTrue(loi.getMessage().contains("oracle READY"), loi.getMessage());
    }

    /**
     * Hai ô người soạn gõ nay là MÃ NHÓM + TÊN LUỒNG (chốt 21/9/2026). Mã luồng — khoá sinh ra
     * test_id — do máy dựng, và phải duy nhất dù sáu luồng cùng một nhóm.
     */
    @Test
    void maLuongSinhTuMaNhomVaTenLuongVaLuonDuyNhat() {
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "G", "runtime_url", "http://localhost:9010", "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "NHOM_MA", "name", "Nhom", "golden_app_id", golden.get("id")));
        String suiteId = String.valueOf(suite.get("id"));

        Map<String, Object> a = taoLuong(suiteId, "filter", "lọc học tập");
        Map<String, Object> b = taoLuong(suiteId, "FILTER", "lọc đi lại");

        // Bỏ dấu trước khi dựng mã: để nguyên thì "lọc học tập" ra "L_C_H_C_T_P".
        assertEquals("FILTER_LOC_HOC_TAP", a.get("scenario_code"));
        assertEquals("FILTER_LOC_DI_LAI", b.get("scenario_code"));
        assertEquals("FILTER", a.get("group_code"));
        assertEquals("FILTER", b.get("group_code"));

        // Không khai mã nhóm thì luồng không thuộc nhóm nào — trạng thái hợp lệ, không phải lỗi.
        Map<String, Object> c = taoLuong(suiteId, "", "sắp xếp");
        assertEquals("SAP_XEP", c.get("scenario_code"));
        assertEquals("", c.get("group_code"));

        // Trùng cả nhóm lẫn tên thì máy phải tự tách mã, vì trùng mã là chung một lượt replay.
        Map<String, Object> d = taoLuong(suiteId, "FILTER", "lọc học tập");
        assertEquals("FILTER_LOC_HOC_TAP_2", d.get("scenario_code"));
    }

    private Map<String, Object> taoLuong(String suiteId, String maNhom, String ten) {
        Map<String, Object> rec = service.startRecording(suiteId, Map.of(
                "name", ten, "viewport", Map.of("width", 390, "height", 844, "device_pixel_ratio", 1)));
        String recId = String.valueOf(rec.get("id"));
        service.appendEvent(recId, Map.of("kind", "action", "action", "tap",
                "target", Map.of("semanticId", "nut.loc")));
        service.appendEvent(recId, Map.of("kind", "checkpoint", "action", "observe_ui",
                "expect", Map.of("visible_texts", List.of(ten), "no_exception", true)));
        service.stopRecording(recId, Map.of());
        return service.abstractRecording(recId, Map.of(
                "group_code", maNhom, "name", ten, "weight", 2.0,
                "viewports", List.of(Map.of("name", "phone", "width", 390, "height", 844,
                        "device_pixel_ratio", 1))));
    }

    /**
     * Bước đi bằng ĐỊNH DANH phải nhận đường lui đo trên Golden: nhãn cho nút có chữ, hình
     * dạng (kiểu widget + mã icon) cho nút chỉ có icon.
     *
     * Vì sao cần: recorder chốt đúng một khoá rồi dừng nên target không mang nhãn nào; bài
     * quên gắn định danh là bước hỏng và cả lượt thành `not_run`. Đo trên PE_PRM393_FA26
     * ngày 21/9/2026: riêng `chi_tieu.them` đứng đầu năm lượt, gánh 38/94 điểm.
     */
    @Test
    @SuppressWarnings("unchecked")
    void buocDiBangDinhDanhNhanDuocDuongLui() throws Exception {
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "G", "runtime_url", "http://localhost:9010", "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "DUONG_LUI", "name", "Duong lui", "golden_app_id", golden.get("id")));
        String suiteId = String.valueOf(suite.get("id"));
        String scenarioId = String.valueOf(taoLuong(suiteId, "CRUD", "thêm chi tiêu").get("id"));

        assertEquals(1, service.applyCapturedTargets(scenarioId, Map.of(), Map.of(
                "nut.loc", Map.of(
                        "label", "Thêm khoản chi",
                        "shape", Map.of("type", "FloatingActionButton", "icon", 57415)))));

        Map<String, Object> target = docTargetBuocDau(scenarioId);
        assertEquals("nut.loc", target.get("semanticId"), "định danh vẫn là khoá chính");
        assertEquals("Thêm khoản chi", target.get("label"));
        List<Map<String, Object>> lui = (List<Map<String, Object>>) target.get("fallback");
        assertEquals("FloatingActionButton", lui.get(0).get("type"));

        // Không có mục nào khớp định danh thì để nguyên, không dựng target rỗng.
        assertEquals(0, service.applyCapturedTargets(scenarioId, Map.of(),
                Map.of("dinh.danh.khac", Map.of("label", "X"))));

        // Bước mang CẢ hai khoá (nhãn ghi hình trước, định danh nướng vào sau) vẫn phải nhận
        // đường lui theo NHÃN khi bảng theo định danh không có mục — đây là đường của mọi bộ
        // đề đã nâng cấp định danh bằng applyCapturedIdentifiers.
        assertEquals(1, service.applyCapturedTargets(scenarioId,
                Map.of("Thêm khoản chi", Map.of("type", "ElevatedButton")),
                Map.of()));
        List<Map<String, Object>> luiTheoNhan =
                (List<Map<String, Object>>) docTargetBuocDau(scenarioId).get("fallback");
        assertEquals("ElevatedButton", luiTheoNhan.get(0).get("type"));
    }

    /**
     * Nút chỉ có icon nhưng khai tooltip: engine thu khoá `tooltip` riêng (Flutter không biến
     * tooltip thành nhãn), backend phải nướng đúng khoá đó — nhét sang `label` thì engine tìm
     * bằng bySemanticsLabel ra 0 và đường lui thành vô dụng.
     */
    @Test
    void nutChiCoIconNhanDuongLuiTheoTooltip() throws Exception {
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "G", "runtime_url", "http://localhost:9010", "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "DUONG_LUI_TIP", "name", "Duong lui tooltip", "golden_app_id", golden.get("id")));
        String scenarioId = String.valueOf(
                taoLuong(String.valueOf(suite.get("id")), "CRUD", "thêm chi tiêu").get("id"));

        assertEquals(1, service.applyCapturedTargets(scenarioId, Map.of(), Map.of(
                "nut.loc", Map.of("tooltip", "Thêm khoản chi"))));
        Map<String, Object> target = docTargetBuocDau(scenarioId);
        assertEquals("nut.loc", target.get("semanticId"), "định danh vẫn là khoá chính");
        assertEquals("Thêm khoản chi", target.get("tooltip"));
        assertFalse(target.containsKey("label"), "tooltip không được nhét sang ô nhãn");
        assertFalse(target.containsKey("fallback"));

    }

    /**
     * "Sinh lại toàn bộ" chạy lại capture trên CHÍNH các bước đã lưu, không dựng lại từ
     * raw_trace — nên lần đo mới phải thắng lần nướng cũ. Giữ bản cũ thì Golden đổi chữ nút
     * xong, đường lui vẫn trỏ vào chữ không còn trên màn và bài quên định danh trượt oan.
     * Lần đo không báo giá trị thì để nguyên (xem applyCapturedTargets).
     */
    @Test
    void sinhLaiSauKhiGoldenDoiChuThiDuongLuiTheoChuMoi() throws Exception {
        Map<String, Object> golden = service.registerGoldenApp(Map.of(
                "name", "G", "runtime_url", "http://localhost:9010", "ready", true));
        Map<String, Object> suite = service.createSuite(Map.of(
                "suite_code", "DUONG_LUI_MOI", "name", "Duong lui moi", "golden_app_id", golden.get("id")));
        String scenarioId = String.valueOf(
                taoLuong(String.valueOf(suite.get("id")), "CRUD", "thêm chi tiêu").get("id"));

        service.applyCapturedTargets(scenarioId, Map.of(), Map.of(
                "nut.loc", Map.of("label", "Lưu", "tooltip", "Lưu khoản chi")));
        assertEquals(1, service.applyCapturedTargets(scenarioId, Map.of(), Map.of(
                "nut.loc", Map.of("label", "Lưu lại", "tooltip", "Lưu lại khoản chi"))));
        Map<String, Object> target = docTargetBuocDau(scenarioId);
        assertEquals("Lưu lại", target.get("label"), "Golden đổi chữ thì đường lui theo chữ mới");
        assertEquals("Lưu lại khoản chi", target.get("tooltip"));
        assertEquals("nut.loc", target.get("semanticId"));

        // Lần đo chỉ còn hình dạng (chữ thành trùng trên màn): nhãn cũ để nguyên, hình dạng vào.
        assertEquals(1, service.applyCapturedTargets(scenarioId, Map.of(), Map.of(
                "nut.loc", Map.of("shape", Map.of("type", "ElevatedButton")))));
        Map<String, Object> sau = docTargetBuocDau(scenarioId);
        assertEquals("Lưu lại", sau.get("label"));
        assertEquals("Lưu lại khoản chi", sau.get("tooltip"));
        assertTrue(sau.containsKey("fallback"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> docTargetBuocDau(String scenarioId) throws Exception {
        List<Map<String, Object>> steps = new ObjectMapper().readValue(
                scenarioRepository.findById(scenarioId).orElseThrow().getStepsJson(),
                new TypeReference<List<Map<String, Object>>>() {});
        return (Map<String, Object>) steps.get(0).get("target");
    }
}
