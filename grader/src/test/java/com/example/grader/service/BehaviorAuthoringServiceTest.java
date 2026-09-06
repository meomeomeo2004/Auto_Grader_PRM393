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
                "target", Map.of("semanticId", "field.email"),
                "value", "invalid"));
        service.appendEvent(recordingId, Map.of(
                "kind", "action", "action", "enter_text",
                "target", Map.of("semanticId", "field.email"),
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
        Map<?, ?> variables = (Map<?, ?>) scenario.get("variables");
        assertTrue(variables.containsKey("field_email"));
        assertTrue(variables.containsKey("field_email_2"));
        List<?> steps = (List<?>) scenario.get("steps");
        assertEquals("${field_email}", ((Map<?, ?>) steps.get(0)).get("value"));
        assertEquals("${field_email_2}", ((Map<?, ?>) steps.get(1)).get("value"));

        Map<String, Object> completed = service.applyDerivedDatabaseCheckpoints(
                String.valueOf(scenario.get("id")),
                List.of(Map.of(
                        "kind", "database_observation",
                        "table", "users",
                        "operation", "INSERT",
                        "row", Map.of("email", "generated-final@example.test"))),
                "c".repeat(64));
        List<?> checkpoints = (List<?>) completed.get("checkpoints");
        Map<?, ?> consistency = checkpoints.stream()
                .map(Map.class::cast)
                .filter(item -> "entity_consistency".equals(item.get("kind")))
                .findFirst().orElseThrow();
        assertEquals("cross_layer", consistency.get("scope"));
        assertEquals(List.of("${field_email_2}"), consistency.get("ui_values"));
        assertEquals("${field_email_2}", ((Map<?, ?>) consistency.get("row")).get("email"));
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
        String nhanDong = "Trà sữa cuối tuần\n62.000 ₫ · ANUONG · 2026-08-20";
        service.appendEvent(recordingId, Map.of("kind", "action", "action", "tap",
                "target", Map.of("label", nhanDong)));
        service.appendEvent(recordingId, Map.of("kind", "checkpoint", "action", "observe_ui",
                "expect", Map.of("visible_texts", List.of("Tổng tháng: 0 ₫"), "no_exception", true)));
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
        @SuppressWarnings("unchecked")
        Map<String, Object> variables = (Map<String, Object>) scenario.get("variables");
        assertTrue(variables.containsKey("field_uid"));
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
}
