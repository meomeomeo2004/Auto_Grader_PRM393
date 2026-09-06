package com.example.grader.service;

import com.example.grader.repository.*;
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
        service.appendEvent(recordingId, Map.of(
                "kind", "component_stack_order", "target", Map.of("valueKey", "ch7.stack"),
                "expect", Map.of("bottom_key", "ch7.stack.bottom", "top_key", "ch7.stack.top")));
        service.appendEvent(recordingId, Map.of(
                "kind", "component_table", "target", Map.of("valueKey", "ch7.table"),
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
}
