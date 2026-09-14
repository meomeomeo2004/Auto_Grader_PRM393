package com.example.grader.controller;

import com.example.grader.entity.BehaviorArtifactType;
import com.example.grader.service.BehaviorArtifactService;
import com.example.grader.service.BehaviorAuthoringService;
import com.example.grader.service.BehaviorSuiteMaterializer;
import com.example.grader.service.ExamService;
import com.example.grader.service.GoldenValidationService;
import com.example.grader.service.GoldenRuntimeService;
import com.example.grader.service.GoldenOracleCaptureService;
import com.example.grader.service.StaticRuleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.example.grader.config.Vai;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** API duy nhất cho luồng Golden App → Record → Abstract → Publish. */
@Profile(Vai.GIANG_VIEN)   // Soạn bộ chấm Golden — chỉ bản giảng viên.
@RestController
@RequestMapping("/api/behavior-authoring")
@CrossOrigin(origins = "*")
public class BehaviorAuthoringController {

    private final BehaviorAuthoringService service;
    private final BehaviorSuiteMaterializer materializer;
    private final BehaviorArtifactService artifactService;
    private final GoldenValidationService validationService;
    private final GoldenRuntimeService runtimeService;
    private final GoldenOracleCaptureService captureService;
    private final StaticRuleService staticRuleService;
    private final ExamService examService;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public BehaviorAuthoringController(BehaviorAuthoringService service,
                                       BehaviorSuiteMaterializer materializer,
                                       BehaviorArtifactService artifactService,
                                       GoldenValidationService validationService,
                                       GoldenRuntimeService runtimeService,
                                       GoldenOracleCaptureService captureService,
                                       StaticRuleService staticRuleService,
                                       ExamService examService) {
        this.examService = examService;
        this.service = service;
        this.materializer = materializer;
        this.artifactService = artifactService;
        this.validationService = validationService;
        this.runtimeService = runtimeService;
        this.captureService = captureService;
        this.staticRuleService = staticRuleService;
    }

    /** Luật chấm tĩnh (Kiến trúc/lint): trạng thái đã lưu + preset kèm đối chứng Golden. */
    @GetMapping("/suites/{id}/static-rules")
    public ResponseEntity<?> staticRules(@PathVariable String id) {
        return call(() -> staticRuleService.view(id));
    }

    @PutMapping("/suites/{id}/static-rules")
    public ResponseEntity<?> saveStaticRules(@PathVariable String id,
                                             @RequestBody Map<String, Object> body) {
        return call(() -> staticRuleService.save(id, body));
    }

    @PostMapping("/golden-apps")
    public ResponseEntity<?> registerGoldenApp(@RequestBody Map<String, Object> body) {
        return call(() -> service.registerGoldenApp(body));
    }

    @GetMapping("/golden-apps")
    public ResponseEntity<?> listGoldenApps(@RequestParam(value = "examId", required = false) String examId) {
        return call(() -> service.listGoldenApps(examId));
    }

    @GetMapping("/golden-apps/{id}")
    public ResponseEntity<?> getGoldenApp(@PathVariable String id) {
        return call(() -> service.getGoldenApp(id));
    }

    @PostMapping("/suites")
    public ResponseEntity<?> createSuite(@RequestBody Map<String, Object> body) {
        return call(() -> {
            Map<String, Object> suite = service.createSuite(body);
            String suiteId = String.valueOf(suite.get("id"));
            artifactService.writeGenerated(
                    suiteId,
                    BehaviorArtifactType.GRADING_ENVIRONMENT,
                    "grading-environment.json",
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(suite.get("runtime_config")),
                    Map.of("generated_from", "suite.runtime_config"));
            return suite;
        });
    }

    @GetMapping("/suites")
    public ResponseEntity<?> listSuites(@RequestParam(value = "examId", required = false) String examId) {
        return call(() -> service.listSuites(examId));
    }

    @GetMapping("/suites/{id}")
    public ResponseEntity<?> getSuite(@PathVariable String id) {
        return call(() -> service.getSuite(id));
    }

    @PutMapping("/suites/{id}")
    public ResponseEntity<?> updateSuite(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return call(() -> {
            // Đổi tên database phải soi lại mã Golden TRƯỚC khi lưu — lệch tên là replay
            // boot database trống và chấm sai âm thầm (đã xảy ra 29/8).
            if (body.containsKey("database_contract")) {
                Map<String, Object> contract = map(body.get("database_contract"));
                Object name = contract.containsKey("database_name") ? contract.get("database_name") : contract.get("name");
                artifactService.crossCheckDeclaredDatabaseName(id, name == null ? "" : String.valueOf(name).trim());
            }
            Map<String, Object> suite = service.updateSuite(id, body);
            if (body.containsKey("runtime_config")) {
                artifactService.writeGenerated(
                        id,
                        BehaviorArtifactType.GRADING_ENVIRONMENT,
                        "grading-environment.json",
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(suite.get("runtime_config")),
                        Map.of("generated_from", "suite.runtime_config"));
            }
            return suite;
        });
    }

    @DeleteMapping("/suites/{id}")
    public ResponseEntity<?> deleteSuite(@PathVariable String id) {
        return call(() -> {
            // Chỉ gỡ testcase publish khi manifest xác nhận đúng chủ sở hữu.
            String examId = materializer.deletePublishedBundleIfOwned(id);
            // Đề sinh ra từ bộ chấm này thì phải đi cùng nó: xoá cả bản ghi đề, lịch sử
            // chấm, mẻ chấm và thư mục bài nộp. Trước đây chỉ gỡ thư mục testcase nên
            // trang chấm bài vẫn còn nguyên đề và bảng điểm của bộ chấm đã xoá.
            Map<String, Object> examCleanup = examId == null ? Map.of() : examService.deleteExam(examId);
            runtimeService.deleteSuiteRuntime(id);
            artifactService.deleteSuiteArtifacts(id);
            Map<String, Object> out = new LinkedHashMap<>(service.deleteSuite(id));
            out.put("exam_cleanup", examCleanup);
            return out;
        });
    }

    @PostMapping("/suites/{id}/recordings")
    public ResponseEntity<?> startRecording(@PathVariable String id,
                                            @RequestBody(required = false) Map<String, Object> body) {
        return call(() -> {
            artifactService.requireRecordingInputs(id);
            return service.startRecording(id, new LinkedHashMap<>(body == null ? Map.of() : body));
        });
    }

    @PostMapping("/scenarios/{id}/revision-recording")
    public ResponseEntity<?> startScenarioRevision(@PathVariable String id) {
        return call(() -> service.startScenarioRevision(id));
    }

    @PostMapping("/recordings/{id}/events")
    public ResponseEntity<?> appendEvent(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return call(() -> service.appendEvent(id, body));
    }

    @PutMapping("/recordings/{id}/events/{sequence}")
    public ResponseEntity<?> updateEvent(@PathVariable String id, @PathVariable int sequence,
                                         @RequestBody Map<String, Object> body) {
        // Hai việc sửa được trên một dòng đã ghi: điểm của checkpoint, và GIÁ TRỊ NHẬP
        // của bước gõ chữ. Cái sau là đường chính thức để khai chữ — recorder không còn
        // đoán nữa.
        if (body.containsKey("value")) {
            return call(() -> service.updateEventValue(id, sequence,
                    body.get("value") == null ? "" : String.valueOf(body.get("value"))));
        }
        return call(() -> service.updateEventWeight(id, sequence,
                body.get("weight") instanceof Number number ? number.doubleValue() : 1.0));
    }

    @DeleteMapping("/recordings/{id}/events/{sequence}")
    public ResponseEntity<?> deleteEvent(@PathVariable String id, @PathVariable int sequence) {
        return call(() -> service.deleteEvent(id, sequence));
    }

    @DeleteMapping("/recordings/{id}")
    public ResponseEntity<?> cancelRecording(@PathVariable String id) {
        return call(() -> service.cancelRecording(id));
    }

    @PostMapping("/recordings/{id}/stop")
    public ResponseEntity<?> stopRecording(@PathVariable String id,
                                           @RequestBody(required = false) Map<String, Object> body) {
        return call(() -> {
            Map<String, Object> recording = service.stopRecording(id, body == null ? Map.of() : body);
            String suiteId = String.valueOf(recording.get("suite_id"));
            Map<String, Object> suiteView = service.getSuite(suiteId);
            List<Map<String, Object>> sessions = list(suiteView.get("recordings")).stream()
                    .map(BehaviorAuthoringController::map)
                    .toList();
            Map<String, Object> recordFile = new LinkedHashMap<>();
            recordFile.put("schema_version", "1.0");
            recordFile.put("sessions", sessions);
            List<Map<String, Object>> automationSteps = sessions.stream()
                    .flatMap(session -> list(session.get("raw_trace")).stream())
                    .map(BehaviorAuthoringController::map)
                    .filter(item -> "action".equals(String.valueOf(item.get("kind"))))
                    .map(this::automationRow)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            // Phiên CHỈ có checkpoint (hợp đồng màn hình: quét UI rồi tick, không bấm gì)
            // là hợp lệ — abstractRecording cũng tự chèn bước boot cho đúng ca này. Nhưng
            // validator của AUTOMATION_RECORD đòi steps không rỗng, nên thiếu bước boot
            // ở đây là /stop chết ngay trước khi kịp sinh testcase (ca thật 29/8: lưu 28
            // tiêu chí màn chính rồi bấm Dừng là đổ).
            if (automationSteps.isEmpty()) {
                Map<String, Object> boot = new LinkedHashMap<>();
                boot.put("stage", "ACTION");
                boot.put("attribute", "none");
                boot.put("attributeValue", "");
                boot.put("valueType", "string");
                boot.put("value", "");
                boot.put("action", "boot");
                boot.put("browser", "flutter_tester");
                boot.put("target", Map.of());
                boot.put("id", "step_1");
                boot.put("timeout_ms", 5_000);
                automationSteps.add(boot);
            }
            recordFile.put("steps", automationSteps);
            recordFile.put("observations", sessions.stream()
                    .flatMap(session -> list(session.get("raw_trace")).stream())
                    .map(BehaviorAuthoringController::map)
                    .filter(item -> !"action".equals(String.valueOf(item.get("kind"))))
                    .toList());
            artifactService.writeGenerated(
                    suiteId,
                    BehaviorArtifactType.AUTOMATION_RECORD,
                    "automation-record.json",
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(recordFile),
                    Map.of("recording_id", recording.get("id")));
            return recording;
        });
    }

    @PostMapping("/recordings/{id}/abstract")
    public ResponseEntity<?> abstractRecording(@PathVariable String id,
                                               @RequestBody(required = false) Map<String, Object> body) {
        return call(() -> {
            Map<String, Object> request = new LinkedHashMap<>(body == null ? Map.of() : body);
            Map<String, Object> recording = service.getRecording(id);
            String recordingSuiteId = String.valueOf(recording.get("suite_id"));
            Map<String, Object> scenario = service.abstractRecording(id, request);
            Map<String, Object> oracle = map(scenario.get("oracle"));
            Map<String, Object> capture = Map.of();
            if (!"READY".equals(String.valueOf(oracle.get("status")))) {
                capture = captureService.capture(
                        recordingSuiteId, String.valueOf(scenario.get("id")));
                scenario = map(capture.get("scenario"));
                // Cảnh báo hỏng-im-lặng phải theo response ra tới toast, không được rơi ở đây.
                if (capture.get("capture_warning") != null) {
                    scenario = new LinkedHashMap<>(scenario);
                    scenario.put("capture_warning", capture.get("capture_warning"));
                }
            }
            writeTestcaseDefinition(recordingSuiteId, scenario.get("scenario_code"));
            Map<String, Object> result = new LinkedHashMap<>(scenario);
            if (!capture.isEmpty()) {
                result.put("output_database", capture.get("output_database"));
                result.put("database_checkpoint_count", capture.get("database_checkpoint_count"));
                result.put("materialized_variables", capture.get("materialized_variables"));
                // Hai khoá này màn soạn đề ĐANG ĐỌC: số bước vừa nhận định danh, và kiểm kê
                // icon để người ra đề tick thành tiêu chí. Thiếu chúng thì thông báo im lặng
                // và bảng icon không bao giờ hiện.
                result.put("identifier_step_count", capture.get("identifier_step_count"));
                result.put("icons", capture.get("icons"));
                result.put("images", capture.get("images"));
            }
            return result;
        });
    }

    @PutMapping("/scenarios/{id}")
    public ResponseEntity<?> updateScenario(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return call(() -> {
            Map<String, Object> scenario = service.updateScenario(id, body);
            String suiteId = String.valueOf(scenario.get("suite_id"));
            boolean replayChanged = body.containsKey("variables")
                    || body.containsKey("initial_state")
                    || body.containsKey("steps")
                    || body.containsKey("checkpoints")
                    || body.containsKey("viewports");
            if (replayChanged) {
                Map<String, Object> capture = captureService.capture(suiteId, id);
                scenario = new LinkedHashMap<>(map(capture.get("scenario")));
                if (capture.get("capture_warning") != null) {
                    scenario.put("capture_warning", capture.get("capture_warning"));
                }
                // Kiểm kê icon cũng phải theo đường sửa scenario: người ra đề thêm tiêu chí
                // icon xong là capture chạy lại ngay, bảng icon phải cập nhật theo.
                scenario.put("icons", capture.get("icons"));
                scenario.put("images", capture.get("images"));
            }
            writeTestcaseDefinition(suiteId, scenario.get("scenario_code"));
            return scenario;
        });
    }

    @DeleteMapping("/scenarios/{id}")
    public ResponseEntity<?> deleteScenario(@PathVariable String id) {
        return call(() -> {
            Map<String, Object> deleted = service.deleteScenario(id);
            writeTestcaseDefinition(String.valueOf(deleted.get("suite_id")),
                    deleted.get("scenario_code"));
            return deleted;
        });
    }

    @PostMapping("/scenarios/{id}/oracle")
    public ResponseEntity<?> saveOracle(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return call(() -> service.saveOracle(id, body));
    }

    @PostMapping("/suites/{id}/publish")
    public ResponseEntity<?> publish(@PathVariable String id) {
        return call(() -> {
            artifactService.requireComplete(id);
            validationService.requirePassed(id);
            Map<String, Object> published = service.publish(id);
            Map<String, Object> artifact = materializer.materialize(id);
            return Map.of("suite", published, "artifact", artifact);
        });
    }

    @PostMapping("/suites/{id}/validate-golden")
    public ResponseEntity<?> validateGolden(@PathVariable String id) {
        return call(() -> validationService.validate(id));
    }

    @GetMapping("/suites/{id}/validate-golden")
    public ResponseEntity<?> latestGoldenValidation(@PathVariable String id) {
        return call(() -> validationService.latest(id));
    }

    @PostMapping("/suites/{id}/runtime/deploy")
    public ResponseEntity<?> deployGoldenRuntime(@PathVariable String id) {
        return call(() -> runtimeService.deploy(id));
    }

    @GetMapping("/suites/{id}/runtime")
    public ResponseEntity<?> goldenRuntimeStatus(@PathVariable String id) {
        return call(() -> runtimeService.status(id));
    }

    @GetMapping({"/runtime/{suiteId}", "/runtime/{suiteId}/", "/runtime/{suiteId}/**"})
    public ResponseEntity<Resource> goldenRuntimeAsset(@PathVariable String suiteId,
                                                       HttpServletRequest request) {
        String prefix = "/api/behavior-authoring/runtime/" + suiteId;
        String uri = request.getRequestURI();
        String assetPath = uri.length() <= prefix.length() ? "" : uri.substring(prefix.length());
        GoldenRuntimeService.RuntimeFile file = runtimeService.resource(suiteId, assetPath);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                // KHÔNG cache: URL runtime giữ nguyên giữa các lần deploy, mà main.dart.js
                // đổi nội dung theo từng bản build. Để immutable thì rebuild xong trình duyệt
                // vẫn chạy mã CŨ (service worker của Flutter càng giữ chặt) — người soạn đề
                // tưởng sửa không ăn. Localhost nên tải lại mỗi lần là rẻ.
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(file.resource());
    }

    @PostMapping(value = "/suites/{id}/artifacts/{type}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadArtifact(@PathVariable String id,
                                            @PathVariable BehaviorArtifactType type,
                                            @RequestPart("file") MultipartFile file,
                                            @RequestPart(value = "metadata", required = false) String metadata) {
        return call(() -> {
            if (type != BehaviorArtifactType.STUDENT_DATABASE
                    && type != BehaviorArtifactType.HIDDEN_DATABASE
                    && type != BehaviorArtifactType.GOLDEN_SOLUTION) {
                throw new IllegalArgumentException(
                        "Chỉ được tải lên Database phát sinh viên, Database ẩn hoặc Golden Solution. "
                                + type + " phải do hệ thống sinh từ phiên record.");
            }
            Map<String, Object> artifact = artifactService.upload(id, type, file, metadata);
            if (type == BehaviorArtifactType.GOLDEN_SOLUTION) {
                service.markGoldenSolutionReady(id, artifact);
            } else if (type == BehaviorArtifactType.HIDDEN_DATABASE) {
                service.invalidateSuiteOracles(id);
            }
            return artifact;
        });
    }

    @GetMapping("/suites/{id}/artifacts")
    public ResponseEntity<?> listArtifacts(@PathVariable String id) {
        return call(() -> artifactService.list(id));
    }

    @GetMapping("/suites/{id}/artifacts/readiness")
    public ResponseEntity<?> artifactReadiness(@PathVariable String id) {
        return call(() -> artifactService.readiness(id));
    }

    @GetMapping("/suites/{id}/artifacts/{type}/download")
    public ResponseEntity<Resource> downloadArtifact(@PathVariable String id,
                                                     @PathVariable BehaviorArtifactType type) {
        Resource resource = artifactService.resource(id, type);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + type.name().toLowerCase() + "\"")
                .body(resource);
    }

    @GetMapping("/suites/{id}/golden-value-keys")
    public ResponseEntity<?> goldenValueKeys(@PathVariable String id) {
        return call(() -> Map.of("keys", artifactService.scanGoldenValueKeys(id)));
    }

    @GetMapping("/suites/{id}/execution-plan")
    public ResponseEntity<?> executionPlan(@PathVariable String id) {
        return call(() -> service.executionPlan(id));
    }

    @GetMapping("/suites/{id}/code-preview")
    public ResponseEntity<?> codePreview(
            @PathVariable String id,
            @RequestParam(value = "scenarioCode", required = false) String scenarioCode) {
        return call(() -> materializer.previewCode(id, scenarioCode));
    }

    @GetMapping("/suites/{id}/determinism")
    public ResponseEntity<?> determinism(@PathVariable String id) {
        return call(() -> materializer.determinismReport(id));
    }

    private void writeTestcaseDefinition(String suiteId, Object changedScenarioCode) throws Exception {
        Map<String, Object> suiteView = service.getSuite(suiteId);
        List<Map<String, Object>> allScenarios = list(suiteView.get("scenarios")).stream()
                .map(BehaviorAuthoringController::map).toList();
        Map<String, Object> testcaseFile = new LinkedHashMap<>();
        testcaseFile.put("schema_version", "1.0");
        testcaseFile.put("scenarios", allScenarios);
        testcaseFile.put("steps", allScenarios.stream()
                .flatMap(item -> list(item.get("steps")).stream())
                .map(BehaviorAuthoringController::map)
                .map(this::automationRow)
                .toList());
        artifactService.writeGenerated(
                suiteId,
                BehaviorArtifactType.TESTCASE_DEFINITION,
                "testcase-definition.json",
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(testcaseFile),
                Map.of("scenario_code", String.valueOf(changedScenarioCode)));
    }

    private ResponseEntity<?> call(ApiCall action) {
        try {
            return ResponseEntity.ok(action.run());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Lỗi máy chủ: " + e.getMessage()));
        }
    }

    @FunctionalInterface
    private interface ApiCall {
        Object run() throws Exception;
    }

    private Map<String, Object> automationRow(Map<String, Object> source) {
        Map<String, Object> target = map(source.get("target"));
        String attribute = text(source, "attribute");
        if (attribute.isBlank()) {
            attribute = target.keySet().stream().findFirst().orElse("none");
        }
        String attributeValue = text(source, "attributeValue");
        if (attributeValue.isBlank() && target.get(attribute) != null) {
            attributeValue = String.valueOf(target.get(attribute));
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", value(source, "stage", "ACTION"));
        row.put("attribute", attribute);
        row.put("attributeValue", attributeValue);
        row.put("valueType", value(source, "valueType", "string"));
        row.put("value", source.getOrDefault("value", ""));
        row.put("action", value(source, "action", "wait_until"));
        row.put("browser", value(source, "browser", "flutter_tester"));
        row.put("target", target);
        if (source.get("id") != null) row.put("id", source.get("id"));
        if (source.get("timeout_ms") != null) row.put("timeout_ms", source.get("timeout_ms"));
        return row;
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

    private static Object value(Map<String, Object> source, String key, Object fallback) {
        Object value = source.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : value;
    }

}
