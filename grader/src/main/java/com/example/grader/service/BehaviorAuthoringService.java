package com.example.grader.service;

import com.example.grader.entity.*;
import com.example.grader.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Lõi soạn bộ chấm Record–Abstract–Replay. Service chỉ quản lý contract có cấu trúc; việc điều khiển
 * Flutter runtime nằm ở GoldenRuntimeService và GoldenOracleCaptureService để
 * UI/API soạn bộ chấm không phải tự điều khiển Docker.
 */
@Service
public class BehaviorAuthoringService {

    public static final String SCHEMA_VERSION = "1.0";
    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{2,79}");
    private static final Set<String> EVENT_KINDS = Set.of(
            "action", "ui_observation", "database_observation", "checkpoint", "navigation", "exception",
            // Tiêu chí "thành phần giao diện có mặt" — sinh từ bảng tick trên trang soạn đề.
            // Mỗi event là MỘT thành phần; engine có nhánh riêng cho kind này.
            "component_present",
            // Vị trí và màu của MỘT thành phần, mỗi mặt một tiêu chí riêng. Giá trị chuẩn
            // do engine đo trên Golden lúc capture oracle rồi nướng vào đây, không gõ tay.
            "component_position",
            "component_color",
            // Trạng thái thật bên trong widget: công tắc bật hay tắt, dải trượt bao nhiêu,
            // ô nhập có dùng bàn phím số không, nút có đúng loại không. Khác
            // component_present ở chỗ cái kia chỉ hỏi "có trên màn hình không".
            "widget_state",
            // Kiểu chữ của MỘT dòng chữ (cỡ, độ đậm, phông, màu) đọc từ đoạn văn bản
            // thật sự được vẽ — không phải từ style khai trên widget, vì phần thừa kế
            // từ theme không nằm ở đó.
            "text_style",
            // Giá trị trong bảng chủ đề: vai trò màu, useMaterial3, phông toàn app,
            // theme của từng thành phần.
            "theme_value",
            // Cả luồng không vỡ bố cục (RenderFlex overflow). Không có giá trị chuẩn —
            // engine tự bắt lỗi tràn trong lúc replay.
            "no_overflow",
            // Màu chủ đạo của app: đọc thẳng ColorScheme từ cây widget, KHÔNG lấy mẫu
            // pixel. Bù đúng điểm mù của component_color — Material 3 cố ý làm các vai
            // outline/onSurfaceVariant gần như xám trung tính nên thành phần chỉ có
            // viền hoặc chữ hầu như không mang thông tin về bảng màu.
            "theme_color",
            // So bố cục màn hình với ảnh chuẩn chụp từ Golden trong cùng container.
            "screen_match");
    private static final Set<String> ACTIONS = Set.of(
            "boot", "tap", "enter_text", "clear_text", "scroll", "drag", "back", "restart", "wait_until");
    private static final int MAX_EVENTS = 2_000;

    private final GoldenAppRepository goldenApps;
    private final BehaviorSuiteRepository suites;
    private final BehaviorScenarioRepository scenarios;
    private final GoldenRecordingRepository recordings;
    private final OracleSnapshotRepository oracles;
    private final GoldenValidationRunRepository validationRuns;
    private final SkillRepository skills;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public BehaviorAuthoringService(GoldenAppRepository goldenApps,
                                    BehaviorSuiteRepository suites,
                                    BehaviorScenarioRepository scenarios,
                                    GoldenRecordingRepository recordings,
                                    OracleSnapshotRepository oracles,
                                    GoldenValidationRunRepository validationRuns,
                                    SkillRepository skills) {
        this.goldenApps = goldenApps;
        this.suites = suites;
        this.scenarios = scenarios;
        this.recordings = recordings;
        this.oracles = oracles;
        this.validationRuns = validationRuns;
        this.skills = skills;
    }

    @Transactional
    public Map<String, Object> registerGoldenApp(Map<String, Object> body) {
        String name = required(body, "name");
        String runtimeUrl = optional(body, "runtime_url", "runtimeUrl");
        if (runtimeUrl != null && !(runtimeUrl.startsWith("http://") || runtimeUrl.startsWith("https://"))) {
            throw new IllegalArgumentException("runtime_url phải bắt đầu bằng http:// hoặc https://");
        }
        String platform = text(body, "platform", "WEB").toUpperCase(Locale.ROOT);
        if (!Set.of("WEB", "ANDROID", "LINUX_DESKTOP").contains(platform)) {
            throw new IllegalArgumentException("platform chỉ nhận WEB, ANDROID hoặc LINUX_DESKTOP");
        }

        GoldenApp app = new GoldenApp();
        app.setName(name);
        app.setExamId(optional(body, "exam_id", "examId"));
        app.setVersion(text(body, "version", "1"));
        app.setPlatform(platform);
        app.setRuntimeUrl(runtimeUrl);
        app.setArtifactPath(optional(body, "artifact_path", "artifactPath"));
        app.setArtifactSha256(optional(body, "artifact_sha256", "artifactSha256"));
        app.setMetadataJson(json(body.getOrDefault("metadata", Map.of())));
        app.setStatus(bool(body.get("ready"), runtimeUrl != null) ? GoldenAppStatus.READY : GoldenAppStatus.REGISTERED);
        goldenApps.save(app);
        return goldenAppView(app);
    }

    public List<Map<String, Object>> listGoldenApps(String examId) {
        List<GoldenApp> rows = examId == null || examId.isBlank()
                ? goldenApps.findAllByOrderByUpdatedAtDesc()
                : goldenApps.findByExamIdOrderByUpdatedAtDesc(examId.trim());
        return rows.stream().map(this::goldenAppView).toList();
    }

    public Map<String, Object> getGoldenApp(String id) {
        return goldenAppView(golden(id));
    }

    @Transactional
    public Map<String, Object> createSuite(Map<String, Object> body) {
        String code = required(body, "suite_code", "suiteCode").toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("suite_code chỉ gồm A-Z, 0-9, _ hoặc -, dài 3-80 ký tự");
        }
        if (suites.existsBySuiteCode(code)) {
            throw new IllegalStateException("Mã bộ chấm đã tồn tại: " + code);
        }
        GoldenApp app = golden(required(body, "golden_app_id", "goldenAppId"));
        if (app.getStatus() == GoldenAppStatus.DISABLED) {
            throw new IllegalStateException("Golden App không thể dùng để tạo bộ chấm: " + app.getStatus());
        }

        BehaviorSuite suite = new BehaviorSuite();
        suite.setSuiteCode(code);
        suite.setExamId(optional(body, "exam_id", "examId"));
        suite.setGoldenAppId(app.getId());
        suite.setName(required(body, "name"));
        suite.setDescription(optional(body, "description"));
        suite.setSchemaVersion(SCHEMA_VERSION);
        suite.setPublicContractJson(normalizeObject(body.get("public_contract"), defaultPublicContract()));
        suite.setDatabaseContractJson(normalizeObject(body.get("database_contract"), defaultDatabaseContract()));
        suite.setRuntimeConfigJson(normalizeObject(body.get("runtime_config"), defaultRuntimeConfig()));
        suites.save(suite);
        return suiteView(suite, true);
    }

    /** Golden Solution đã qua kiểm tra ZIP thì Golden App mới được phép record/replay. */
    @Transactional
    public Map<String, Object> markGoldenSolutionReady(String suiteId, Map<String, Object> artifact) {
        BehaviorSuite suite = suite(suiteId);
        GoldenApp app = golden(suite.getGoldenAppId());
        if (app.getStatus() == GoldenAppStatus.DISABLED) {
            throw new IllegalStateException("Golden App đang ở trạng thái không thể kích hoạt: " + app.getStatus());
        }
        app.setArtifactPath("behavior-artifact:" + String.valueOf(artifact.get("id")));
        app.setArtifactSha256(String.valueOf(artifact.get("sha256")));
        // Upload thành công mới chỉ xác nhận artifact hợp lệ. Golden App chỉ READY
        // sau khi GoldenRuntimeService build xong và runtime thực sự truy cập được.
        app.setRuntimeUrl(null);
        app.setStatus(GoldenAppStatus.REGISTERED);
        goldenApps.save(app);

        // Một Golden Solution mới làm các oracle cũ mất tính xác thực. Không được âm thầm
        // publish testcase với output của phiên bản đáp án trước.
        staleSuiteOracles(suite);
        suites.save(suite);
        return goldenAppView(app);
    }

    public List<Map<String, Object>> listSuites(String examId) {
        List<BehaviorSuite> rows = examId == null || examId.isBlank()
                ? suites.findAllByOrderByUpdatedAtDesc()
                : suites.findByExamIdOrderByUpdatedAtDesc(examId.trim());
        return rows.stream().map(row -> suiteView(row, false)).toList();
    }

    public Map<String, Object> getSuite(String id) {
        return suiteView(suite(id), true);
    }

    /** Xóa dữ liệu nghiệp vụ của suite sau khi controller đã dọn file runtime/artifact. */
    @Transactional
    public Map<String, Object> deleteSuite(String id) {
        BehaviorSuite suite = suite(id);
        String goldenAppId = suite.getGoldenAppId();
        List<BehaviorScenario> suiteScenarios =
                scenarios.findBySuiteIdOrderByDisplayOrderAscCreatedAtAsc(id);
        suiteScenarios.forEach(row -> oracles.deleteByScenarioId(row.getId()));
        validationRuns.deleteBySuiteId(id);
        scenarios.deleteBySuiteId(id);
        recordings.deleteBySuiteId(id);
        suites.delete(suite);
        if (suites.countByGoldenAppId(goldenAppId) == 0) {
            goldenApps.deleteById(goldenAppId);
        }
        return Map.of(
                "deleted", true,
                "suite_id", id,
                "suite_code", suite.getSuiteCode());
    }

    public Map<String, Object> getRecording(String id) {
        return recordingView(recording(id));
    }

    @Transactional
    public Map<String, Object> updateSuite(String id, Map<String, Object> body) {
        BehaviorSuite suite = suite(id);
        ensureEditable(suite);
        boolean invalidatesReplay = body.containsKey("public_contract")
                || body.containsKey("database_contract")
                || runtimeConfigChangeInvalidatesReplay(suite, body);
        if (body.containsKey("name")) suite.setName(required(body, "name"));
        if (body.containsKey("description")) suite.setDescription(optional(body, "description"));
        if (body.containsKey("public_contract")) {
            suite.setPublicContractJson(normalizeObject(body.get("public_contract"), Map.of()));
        }
        if (body.containsKey("database_contract")) {
            suite.setDatabaseContractJson(normalizeObject(body.get("database_contract"), Map.of()));
        }
        if (body.containsKey("runtime_config")) {
            suite.setRuntimeConfigJson(normalizeObject(body.get("runtime_config"), Map.of()));
        }
        if (invalidatesReplay) {
            staleSuiteOracles(suite);
        }
        suites.save(suite);
        return suiteView(suite, true);
    }

    @Transactional
    public Map<String, Object> startRecording(String suiteId, Map<String, Object> body) {
        BehaviorSuite suite = suite(suiteId);
        ensureEditable(suite);
        GoldenApp app = golden(suite.getGoldenAppId());
        if (app.getStatus() != GoldenAppStatus.READY) {
            throw new IllegalStateException("Golden App không còn READY");
        }
        if (recordings.countBySuiteIdAndStatus(suiteId, RecordingStatus.ACTIVE) > 0) {
            throw new IllegalStateException("Bộ chấm đang có một phiên record chưa kết thúc");
        }

        GoldenRecording recording = new GoldenRecording();
        recording.setSuiteId(suiteId);
        recording.setGoldenAppId(app.getId());
        recording.setName(text(body, "name", "Luồng " + (scenarios.countBySuiteIdAndEnabledTrue(suiteId) + 1)));
        recording.setSeed(text(body, "seed", UUID.randomUUID().toString()));
        recording.setViewportJson(normalizeObject(body.get("viewport"), defaultViewport()));
        recording.setInitialStateJson(normalizeObject(body.get("initial_state"), Map.of("reset_storage", true)));
        recording.setRawTraceJson("[]");
        recordings.save(recording);
        suite.setStatus(BehaviorSuiteStatus.RECORDING);
        suites.save(suite);
        return recordingView(recording);
    }

    /**
     * Opens an existing scenario as a new ACTIVE recording. The original raw trace is
     * copied so the teacher can use the normal authoring controls to append or remove
     * actions/checkpoints without editing generated JSON.
     */
    @Transactional
    public Map<String, Object> startScenarioRevision(String scenarioId) {
        BehaviorScenario scenario = scenario(scenarioId);
        BehaviorSuite suite = suite(scenario.getSuiteId());
        ensureEditable(suite);
        GoldenApp app = golden(suite.getGoldenAppId());
        if (app.getStatus() != GoldenAppStatus.READY) {
            throw new IllegalStateException("Golden App không còn READY");
        }
        if (recordings.countBySuiteIdAndStatus(suite.getId(), RecordingStatus.ACTIVE) > 0) {
            throw new IllegalStateException("Bộ chấm đang có một phiên record chưa kết thúc");
        }

        List<Map<String, Object>> trace = scenario.getSourceRecordingId() == null
                ? scenarioTrace(scenario)
                : recordings.findById(scenario.getSourceRecordingId())
                .map(source -> readObjectList(source.getRawTraceJson()))
                .orElseGet(() -> scenarioTrace(scenario));
        List<Map<String, Object>> copiedTrace = new ArrayList<>();
        for (int index = 0; index < trace.size(); index++) {
            Map<String, Object> event = new LinkedHashMap<>(trace.get(index));
            event.put("sequence", index + 1);
            event.remove("recorded_at");
            copiedTrace.add(event);
        }

        List<Object> viewports = readArray(scenario.getViewportsJson());
        GoldenRecording revision = new GoldenRecording();
        revision.setSuiteId(suite.getId());
        revision.setGoldenAppId(app.getId());
        revision.setRevisionScenarioId(scenario.getId());
        revision.setName(scenario.getName());
        revision.setSeed(UUID.randomUUID().toString());
        revision.setViewportJson(json(viewports.isEmpty() ? defaultViewport() : viewports.get(0)));
        revision.setInitialStateJson(scenario.getInitialStateJson());
        revision.setRawTraceJson(json(copiedTrace));
        recordings.save(revision);
        suite.setStatus(BehaviorSuiteStatus.RECORDING);
        suites.save(suite);
        return recordingView(revision);
    }

    @Transactional
    public Map<String, Object> cancelRecording(String recordingId) {
        GoldenRecording recording = recordingForUpdate(recordingId);
        if (recording.getStatus() != RecordingStatus.ACTIVE
                && recording.getStatus() != RecordingStatus.STOPPED) {
            throw new IllegalStateException("Chỉ có thể hủy phiên record chưa abstract");
        }
        BehaviorSuite suite = suite(recording.getSuiteId());
        recordings.delete(recording);
        suite.setStatus(BehaviorSuiteStatus.REVIEW);
        suites.save(suite);
        return Map.of("cancelled", true, "recording_id", recordingId, "suite_id", suite.getId());
    }

    @Transactional
    public Map<String, Object> appendEvent(String recordingId, Map<String, Object> body) {
        GoldenRecording recording = recordingForUpdate(recordingId);
        if (recording.getStatus() != RecordingStatus.ACTIVE) {
            throw new IllegalStateException("Chỉ có thể ghi event vào phiên ACTIVE");
        }
        List<Map<String, Object>> trace = readObjectList(recording.getRawTraceJson());
        if (trace.size() >= MAX_EVENTS) throw new IllegalStateException("Phiên record vượt quá 2000 event");

        Map<String, Object> event = new LinkedHashMap<>(body == null ? Map.of() : body);
        String kind = text(event, "kind", "action").toLowerCase(Locale.ROOT);
        if (!EVENT_KINDS.contains(kind)) throw new IllegalArgumentException("Loại event không hỗ trợ: " + kind);
        if ("action".equals(kind)) {
            String action = required(event, "action").toLowerCase(Locale.ROOT);
            if (!ACTIONS.contains(action)) throw new IllegalArgumentException("Action không hỗ trợ: " + action);
            if (Set.of("tap", "enter_text", "clear_text", "scroll", "drag").contains(action)
                    && map(event.get("target")).isEmpty()) {
                throw new IllegalArgumentException("Action " + action + " phải có target ngữ nghĩa");
            }
            // Kéo mà không nói kéo bao xa thì engine không làm gì được — chặn ngay lúc
            // ghi, đừng để tới lượt capture mới nổ.
            if ("drag".equals(action)) {
                Map<String, Object> delta = map(event.get("delta"));
                if (number(delta.get("x"), 0) == 0 && number(delta.get("y"), 0) == 0) {
                    throw new IllegalArgumentException("Action drag phải khai độ dời (kéo ngang hoặc kéo dọc khác 0)");
                }
            }
            Map<String, Object> target = map(event.get("target"));
            event.putIfAbsent("stage", "ACTION");
            event.putIfAbsent("attribute", locatorAttribute(target));
            event.putIfAbsent("attributeValue", locatorValue(target));
            event.putIfAbsent("valueType", valueType(event.get("value")));
            event.putIfAbsent("value", event.getOrDefault("value", ""));
            event.putIfAbsent("browser", "flutter_tester");
        } else if ("database_observation".equals(kind)) {
            validateDatabaseObservation(event);
        } else if ("checkpoint".equals(kind) || "ui_observation".equals(kind)) {
            validateUiObservation(event);
        } else if ("screen_match".equals(kind)) {
            event.putIfAbsent("checkpoint", true);
            event.putIfAbsent("threshold", 0.85);
            event.putIfAbsent("stage", "ASSERT");
            event.putIfAbsent("action", "observe_ui");
            event.putIfAbsent("browser", "flutter_tester");
        } else if ("theme_color".equals(kind)) {
            // KHÔNG cần target: màu chủ đạo là của cả app, không gắn với thành phần nào.
            event.putIfAbsent("checkpoint", true);
            // 20% mặc định — rộng hơn hẳn màu thành phần. Vì phép đo này CHÍNH XÁC
            // tuyệt đối (không nhiễu khử răng cưa, không lẫn nền) nên sai số không dùng
            // để chống nhiễu mà để tha sắc độ lân cận trong cùng họ màu. Đo thật:
            // xanh-vs-tím lệch 40,4% nên 20% vẫn bắt chắc.
            event.putIfAbsent("tolerance_pct", 20);
            event.putIfAbsent("stage", "ASSERT");
            event.putIfAbsent("action", "observe_ui");
            event.putIfAbsent("browser", "flutter_tester");
        } else if ("component_position".equals(kind) || "component_color".equals(kind)) {
            // Cùng ràng buộc với component_present: không có target thì không biết đo cái gì.
            if (map(event.get("target")).isEmpty()) {
                throw new IllegalArgumentException("Tiêu chí vị trí/màu phải có target (label/hint/text)");
            }
            event.putIfAbsent("checkpoint", true);
            // 5% mặc định. VỊ TRÍ tính theo % chiều rộng/cao màn hình nên đổi viewport không
            // làm lệch chính sách; MÀU tính theo % của 255 trên từng kênh R/G/B (5% ~ ±13,
            // đủ chặt để lệch một nấc Material shade vẫn bị bắt).
            event.putIfAbsent("tolerance_pct", 5);
            event.putIfAbsent("stage", "ASSERT");
            event.putIfAbsent("action", "observe_ui");
            event.putIfAbsent("browser", "flutter_tester");
        } else if ("text_style".equals(kind)) {
            if (map(event.get("target")).isEmpty() || text(event, "property", "").isBlank()) {
                throw new IllegalArgumentException(
                        "Tiêu chí kiểu chữ phải có target (dòng chữ cần đo) và thuộc tính cần đọc");
            }
            event.putIfAbsent("checkpoint", true);
            // Cỡ chữ so tuyệt đối theo mặc định: sinh viên đặt 22 thì phải là 22, đây là
            // con số người ra đề quy định chứ không phải phép đo có nhiễu. Riêng MÀU chữ
            // engine tự dùng phép so màu với sai số 20% như mọi tiêu chí màu khác.
            event.putIfAbsent("stage", "ASSERT");
            event.putIfAbsent("action", "observe_ui");
            event.putIfAbsent("browser", "flutter_tester");
        } else if ("no_overflow".equals(kind)) {
            event.putIfAbsent("checkpoint", true);
            event.putIfAbsent("stage", "ASSERT");
            event.putIfAbsent("action", "observe_ui");
            event.putIfAbsent("browser", "flutter_tester");
        } else if ("theme_value".equals(kind)) {
            if (text(event, "property", "").isBlank()) {
                throw new IllegalArgumentException("Tiêu chí chủ đề phải khai thuộc tính cần đọc");
            }
            event.putIfAbsent("checkpoint", true);
            event.putIfAbsent("stage", "ASSERT");
            event.putIfAbsent("action", "observe_ui");
            event.putIfAbsent("browser", "flutter_tester");
        } else if ("widget_state".equals(kind)) {
            // Thiếu một trong hai thì lúc chấm engine không biết đọc gì của ai. Chặn ngay
            // lúc ghi để người soạn đề sửa liền, đừng để lỗi trôi tới lượt capture.
            if (text(event, "widget", "").isBlank() || text(event, "property", "").isBlank()) {
                throw new IllegalArgumentException(
                        "Tiêu chí trạng thái phải khai cả loại widget lẫn thuộc tính cần đọc");
            }
            event.putIfAbsent("checkpoint", true);
            event.putIfAbsent("stage", "ASSERT");
            event.putIfAbsent("action", "observe_ui");
            event.putIfAbsent("browser", "flutter_tester");
        } else if ("component_present".equals(kind)) {
            // Không có target thì lúc chấm không biết tìm widget nào — chặn ngay lúc ghi,
            // đừng để lỗi trôi tới preflight.
            if (map(event.get("target")).isEmpty()) {
                throw new IllegalArgumentException("Tiêu chí giao diện phải có target (label/hint/text)");
            }
            event.putIfAbsent("checkpoint", true);
            event.putIfAbsent("visible", true);
            event.putIfAbsent("stage", "ASSERT");
            event.putIfAbsent("action", "observe_ui");
            event.putIfAbsent("browser", "flutter_tester");
        }
        event.put("kind", kind);
        event.put("sequence", trace.size() + 1);
        event.putIfAbsent("recorded_at", Instant.now().toString());
        trace.add(event);
        recording.setRawTraceJson(json(trace));
        recordings.save(recording);
        return Map.of("recording_id", recordingId, "event_count", trace.size(), "event", event);
    }

    @Transactional
    /** Sửa điểm của một event checkpoint trong phiên record — ô nhập inline trên danh sách. */
    public Map<String, Object> updateEventWeight(String recordingId, int sequence, double weight) {
        if (sequence < 1) throw new IllegalArgumentException("sequence event phải lớn hơn hoặc bằng 1");
        if (weight < 0.25) throw new IllegalArgumentException("Điểm checkpoint tối thiểu 0.25");
        GoldenRecording recording = recordingForUpdate(recordingId);
        if (recording.getStatus() != RecordingStatus.ACTIVE) {
            throw new IllegalStateException("Chỉ sửa được điểm trong phiên ACTIVE");
        }
        List<Map<String, Object>> trace = readObjectList(recording.getRawTraceJson());
        for (Map<String, Object> event : trace) {
            Object value = event.get("sequence");
            if (value instanceof Number number && number.intValue() == sequence) {
                if ("action".equals(text(event, "kind", ""))) {
                    throw new IllegalArgumentException("Action không mang điểm — chỉ checkpoint mới có.");
                }
                event.put("weight", weight);
                recording.setRawTraceJson(json(trace));
                recordings.save(recording);
                return event;
            }
        }
        throw new IllegalArgumentException("Không tìm thấy event sequence " + sequence);
    }

    @Transactional
    /**
     * Sửa GIÁ TRỊ NHẬP của một bước gõ chữ trong phiên record.
     *
     * Đây là nguồn sự thật duy nhất cho nội dung gõ. Recorder chỉ ghi được cú chạm vào ô;
     * chữ thì người soạn tự khai ở đây, vì đọc chữ từ DOM của Flutter Web đã hỏng đủ ba
     * kiểu: cắt cụt, mất trắng, và gán nhầm ô.
     */
    public Map<String, Object> updateEventValue(String recordingId, int sequence, String value) {
        if (sequence < 1) throw new IllegalArgumentException("sequence event phải lớn hơn hoặc bằng 1");
        GoldenRecording recording = recordingForUpdate(recordingId);
        if (recording.getStatus() != RecordingStatus.ACTIVE) {
            throw new IllegalStateException("Chỉ sửa được giá trị trong phiên ACTIVE");
        }
        List<Map<String, Object>> trace = readObjectList(recording.getRawTraceJson());
        for (Map<String, Object> event : trace) {
            Object raw = event.get("sequence");
            if (raw instanceof Number number && number.intValue() == sequence) {
                if (!"enter_text".equals(text(event, "action", ""))) {
                    throw new IllegalArgumentException("Chỉ bước gõ chữ mới có giá trị nhập.");
                }
                event.put("value", value);
                event.put("valueType", "string");
                recording.setRawTraceJson(json(trace));
                recordings.save(recording);
                return event;
            }
        }
        throw new IllegalArgumentException("Không tìm thấy event sequence " + sequence);
    }

    // BẮT BUỘC @Transactional: recordingForUpdate() khóa bi quan PESSIMISTIC_WRITE, mà khóa
    // này đòi phải nằm trong transaction — thiếu là ném "No active transaction" ngay khi bấm
    // xóa action (đã xảy ra 31/8). Bốn hàm sửa phiên record còn lại đều đã có.
    @Transactional
    public Map<String, Object> deleteEvent(String recordingId, int sequence) {
        if (sequence < 1) throw new IllegalArgumentException("sequence event phải lớn hơn hoặc bằng 1");
        GoldenRecording recording = recordingForUpdate(recordingId);
        if (recording.getStatus() != RecordingStatus.ACTIVE) {
            throw new IllegalStateException("Chỉ có thể xóa event trong phiên ACTIVE");
        }
        List<Map<String, Object>> trace = readObjectList(recording.getRawTraceJson());
        int index = -1;
        for (int i = 0; i < trace.size(); i++) {
            Object value = trace.get(i).get("sequence");
            if (value instanceof Number number && number.intValue() == sequence) {
                index = i;
                break;
            }
        }
        if (index < 0) throw new IllegalArgumentException("Không tìm thấy event sequence " + sequence);

        Map<String, Object> removed = new LinkedHashMap<>(trace.remove(index));
        for (int i = 0; i < trace.size(); i++) trace.get(i).put("sequence", i + 1);
        recording.setRawTraceJson(json(trace));
        recordings.save(recording);
        return Map.of(
                "recording_id", recordingId,
                "event_count", trace.size(),
                "removed", removed);
    }

    @Transactional
    public Map<String, Object> stopRecording(String recordingId, Map<String, Object> body) {
        GoldenRecording recording = recordingForUpdate(recordingId);
        if (recording.getStatus() == RecordingStatus.STOPPED
                || recording.getStatus() == RecordingStatus.ABSTRACTED) {
            return recordingView(recording);
        }
        if (recording.getStatus() != RecordingStatus.ACTIVE) {
            throw new IllegalStateException("Phiên record không thể dừng ở trạng thái " + recording.getStatus());
        }
        recording.setFinalObservationJson(normalizeObject(
                body == null ? null : body.get("final_observation"), Map.of()));
        recording.setStoppedAt(Instant.now());
        recording.setStatus(RecordingStatus.STOPPED);
        recordings.save(recording);
        BehaviorSuite suite = suite(recording.getSuiteId());
        suite.setStatus(BehaviorSuiteStatus.REVIEW);
        suites.save(suite);
        return recordingView(recording);
    }

    @Transactional
    public Map<String, Object> abstractRecording(String recordingId, Map<String, Object> body) {
        GoldenRecording recording = recording(recordingId);
        if (recording.getStatus() == RecordingStatus.ABSTRACTED) {
            BehaviorScenario existing = scenarios.findFirstBySourceRecordingId(recordingId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Record đã ABSTRACTED nhưng không còn scenario tương ứng"));
            Map<String, Object> existingView = new LinkedHashMap<>(scenarioView(existing, true));
            oracles.findFirstByScenarioIdOrderByCreatedAtDesc(existing.getId())
                    .ifPresent(oracle -> existingView.put("oracle", oracleView(oracle)));
            return existingView;
        }
        if (recording.getStatus() != RecordingStatus.STOPPED) {
            throw new IllegalStateException("Cần dừng record trước khi abstract");
        }
        BehaviorSuite suite = suite(recording.getSuiteId());
        ensureEditable(suite);
        List<Map<String, Object>> trace = readObjectList(recording.getRawTraceJson());
        if (trace.isEmpty()) throw new IllegalStateException("Phiên record chưa có thao tác nào");

        // CHẶN TẠI CỬA: bước gõ chữ mà chưa khai giá trị thì replay sẽ gõ chuỗi rỗng và
        // mọi tiêu chí phía sau trượt theo — không được để lỗi đó trôi tới lượt capture.
        List<String> thieuGiaTri = new ArrayList<>();
        for (Map<String, Object> event : trace) {
            if (!"enter_text".equals(text(event, "action", ""))) continue;
            if (text(event, "value", "").isEmpty()) {
                thieuGiaTri.add(locatorValue(map(event.get("target"))));
            }
        }
        if (!thieuGiaTri.isEmpty()) {
            throw new IllegalArgumentException(
                    "Chưa khai giá trị cho ô: " + String.join(", ", thieuGiaTri)
                    + ". Gõ nội dung vào ô nhập trên từng dòng gõ chữ rồi sinh testcase lại.");
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        List<Map<String, Object>> checkpoints = new ArrayList<>();
        int actionNo = 0;
        for (Map<String, Object> event : trace) {
            String kind = text(event, "kind", "");
            if ("action".equals(kind)) {
                String action = text(event, "action", "");
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("id", "step_" + (++actionNo));
                step.put("action", action);
                for (String field : List.of("stage", "attribute", "attributeValue", "valueType", "browser")) {
                    if (event.containsKey(field)) step.put(field, event.get(field));
                }
                if (event.containsKey("target")) step.put("target", event.get("target"));
                if (event.containsKey("delta")) step.put("delta", event.get("delta"));
                if (event.containsKey("direction")) step.put("direction", event.get("direction"));
                // GIÁ TRỊ NGƯỜI RA ĐỀ GÕ LÀ GIÁ TRỊ CHẠY THẬT — không biến hoá.
                // Trước đây mọi enter_text bị thay bằng biến sinh tự động để chống cắm cứng.
                // Lớp đó thừa: bài chấm chạy trên hidden.db mà sinh viên không bao giờ thấy,
                // dữ liệu khó đoán là do người ra đề tự chọn. Đổi lại nó làm mọi tiêu chí phụ
                // thuộc giá trị nhập (ví dụ tổng tháng sau khi thêm) không khai nổi số chuẩn,
                // và tệ hơn là sửa đầu vào của người ra đề mà giao diện không hề báo.
                if (event.containsKey("value")) step.put("value", event.get("value"));
                step.put("timeout_ms", number(event.get("timeout_ms"), 5_000));
                steps.add(step);
            } else if ("checkpoint".equals(kind)
                    || "component_present".equals(kind)
                    || "component_position".equals(kind)
                    || "component_color".equals(kind)
                    || "widget_state".equals(kind)
                    || "text_style".equals(kind)
                    || "theme_value".equals(kind)
                    || "no_overflow".equals(kind)
                    || "theme_color".equals(kind)
                    || "screen_match".equals(kind)
                    || (bool(event.get("checkpoint"), false)
                    && Set.of("ui_observation", "database_observation", "navigation").contains(kind))) {
                Map<String, Object> checkpoint = new LinkedHashMap<>(event);
                checkpoint.remove("recorded_at");
                checkpoint.remove("sequence");
                checkpoint.putIfAbsent("id", "checkpoint_" + (checkpoints.size() + 1));
                checkpoint.putIfAbsent("weight", 1.0);
                checkpoints.add(checkpoint);
            }
        }
        // Dịch ràng buộc tiên quyết: người soạn chọn theo SỐ THỨ TỰ checkpoint trong
        // danh sách record (id thật chỉ sinh ra ở đây). Chỉ cho trỏ về checkpoint ĐỨNG
        // TRƯỚC — vòng phụ thuộc bị loại ngay từ cách khai, khỏi cần dò chu trình.
        for (int index = 0; index < checkpoints.size(); index++) {
            Map<String, Object> checkpoint = checkpoints.get(index);
            Object raw = checkpoint.remove("requires_index");
            if (raw == null) continue;
            int viTri = (int) number(raw, 0);
            if (viTri < 1 || viTri > checkpoints.size()) {
                throw new IllegalArgumentException(
                        "Điều kiện tiên quyết của checkpoint thứ " + (index + 1)
                        + " trỏ tới vị trí không tồn tại: " + viTri);
            }
            if (viTri - 1 >= index) {
                throw new IllegalArgumentException(
                        "Checkpoint thứ " + (index + 1) + " chỉ được ràng buộc vào checkpoint ĐỨNG TRƯỚC nó"
                        + " (đang trỏ tới vị trí " + viTri + ").");
            }
            checkpoint.put("requires", text(checkpoints.get(viTri - 1), "id", ""));
        }

        if (checkpoints.stream().noneMatch(item -> "database_observation".equals(text(item, "kind", "")))) {
            for (Object raw : objectList(body.get("database_checkpoints"))) {
                Map<String, Object> checkpoint = map(raw);
                validateDatabaseObservation(checkpoint);
                checkpoint.putIfAbsent("id", "checkpoint_" + (checkpoints.size() + 1));
                checkpoints.add(checkpoint);
            }
        }
        if (actionNo == 0) {
            // Scenario chỉ mô tả contract màn hình có thể hoàn toàn không cần tương tác.
            // Runner luôn boot ứng dụng trước khi chạy steps, vì vậy thêm một boot no-op
            // giúp record chỉ chứa checkpoint vẫn là một testcase replay hợp lệ.
            Map<String, Object> boot = new LinkedHashMap<>();
            boot.put("id", "step_1");
            boot.put("action", "boot");
            boot.put("timeout_ms", 5_000);
            steps.add(boot);
        }

        Map<String, Object> finalObservation = readObject(recording.getFinalObservationJson());
        if (!finalObservation.isEmpty()) {
            checkpoints.add(new LinkedHashMap<>(Map.of(
                    "id", "checkpoint_final",
                    "kind", "checkpoint",
                    "scope", "ui",
                    "weight", 1.0,
                    "expect", finalObservation)));
        }
        if (checkpoints.isEmpty()) {
            // Một record chỉ gồm thao tác vẫn cần một criterion tối thiểu để runner có
            // execution group và capture Output DB. Criterion này chỉ kiểm tra luồng
            // Golden/student không ném exception; DB diff sẽ được bổ sung sau capture.
            Map<String, Object> checkpoint = new LinkedHashMap<>();
            checkpoint.put("id", "checkpoint_no_exception");
            checkpoint.put("kind", "checkpoint");
            checkpoint.put("scope", "ui");
            checkpoint.put("stage", "ASSERT");
            checkpoint.put("attribute", "runtime");
            checkpoint.put("attributeValue", "no_exception");
            checkpoint.put("valueType", "boolean");
            checkpoint.put("value", true);
            checkpoint.put("action", "observe_ui");
            checkpoint.put("browser", "flutter_tester");
            checkpoint.put("weight", 1.0);
            checkpoint.put("expect", Map.of("no_exception", true));
            checkpoints.add(checkpoint);
        }

        String revisionScenarioId = optional(body, "replace_scenario_id", "replaceScenarioId");
        if ((revisionScenarioId == null || revisionScenarioId.isBlank())
                && recording.getRevisionScenarioId() != null) {
            revisionScenarioId = recording.getRevisionScenarioId();
        }
        BehaviorScenario revision = revisionScenarioId == null || revisionScenarioId.isBlank()
                ? null : scenario(revisionScenarioId);
        if (revision != null && !Objects.equals(revision.getSuiteId(), suite.getId())) {
            throw new IllegalArgumentException("Scenario sửa không thuộc bộ chấm của phiên record");
        }

        String requestedCode = text(body, "scenario_code", "");
        String code = requestedCode.isBlank()
                ? (revision == null
                    ? uniqueScenarioCode(suite.getId(), slug(recording.getName()))
                    : revision.getScenarioCode())
                : requestedCode.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_-]", "_");
        Optional<BehaviorScenario> codeOwner = scenarios.findBySuiteIdAndScenarioCode(suite.getId(), code);
        if (codeOwner.isPresent() && (revision == null || !codeOwner.get().getId().equals(revision.getId()))) {
            throw new IllegalStateException("scenario_code đã tồn tại trong bộ chấm: " + code);
        }

        BehaviorScenario scenario = revision == null ? new BehaviorScenario() : revision;
        scenario.setSuiteId(suite.getId());
        scenario.setSourceRecordingId(recording.getId());
        scenario.setScenarioCode(code);
        scenario.setName(text(body, "name", recording.getName()));
        scenario.setSkillCode(text(body, "skill_code",
                revision == null
                        ? (checkpoints.stream().anyMatch(item -> "database_observation".equals(text(item, "kind", "")))
                            ? "STORAGE_SQLITE_CRUD" : "UI_BUTTONS_SELECTION")
                        : revision.getSkillCode()));
        scenario.setDescription(body.containsKey("description")
                ? optional(body, "description")
                : revision == null ? null : revision.getDescription());
        if (revision == null) {
            scenario.setDisplayOrder((int) scenarios.countBySuiteIdAndEnabledTrue(suite.getId()) + 1);
        }
        scenario.setWeight(number(body.get("weight"), Math.max(1.0, checkpoints.size())));
        scenario.setInitialStateJson(recording.getInitialStateJson());
        scenario.setStepsJson(json(steps));
        scenario.setCheckpointsJson(json(checkpoints));
        List<Object> requestedViewports = objectList(body.get("viewports"));
        scenario.setViewportsJson(requestedViewports.isEmpty()
                ? json(List.of(readObject(recording.getViewportJson())))
                : json(requestedViewports));
        if (revision != null) staleScenarioOracles(revision.getId());
        scenarios.save(scenario);

        // Record trên Golden App chính là nguồn oracle đầu tiên. Giá trị nhập đã được tách
        // thành biến, còn snapshot UI/DB vẫn giữ nguyên để replay cùng seed đối chiếu.
        Map<String, Object> input = new LinkedHashMap<>();
        List<Map<String, Object>> databaseObservations = checkpoints.stream()
                .filter(item -> "database_observation".equals(text(item, "kind", "")))
                .toList();
        OracleSnapshot oracle = new OracleSnapshot();
        oracle.setScenarioId(scenario.getId());
        oracle.setGoldenAppId(suite.getGoldenAppId());
        oracle.setGoldenSha256(golden(suite.getGoldenAppId()).getArtifactSha256());
        String requestedSeed = optional(body, "seed");
        oracle.setSeed(requestedSeed == null
                ? deterministicSeed(steps, checkpoints, recording.getInitialStateJson(), scenario.getViewportsJson())
                : requestedSeed);
        oracle.setInputJson(json(input));
        oracle.setUiObservationJson(recording.getFinalObservationJson());
        Map<String, Object> databaseObservation = new LinkedHashMap<>();
        databaseObservation.put("checkpoints", databaseObservations);
        databaseObservation.put("output_database_sha256", text(body, "output_database_sha256", ""));
        oracle.setDatabaseObservationJson(json(databaseObservation));
        oracle.setStatus(text(body, "output_database_sha256", "").isBlank()
                ? OracleStatus.PENDING : OracleStatus.READY);
        oracles.save(oracle);

        recording.setStatus(RecordingStatus.ABSTRACTED);
        recordings.save(recording);
        Map<String, Object> out = new LinkedHashMap<>(scenarioView(scenario, true));
        out.put("oracle", oracleView(oracle));
        return out;
    }

    /**
     * Hoàn thiện oracle sau khi Docker đã replay Golden trên Hidden DB. Các thay đổi
     * SQLite được tách thành checkpoint độc lập và giá trị sinh ngẫu nhiên được trả
     * về placeholder để replay mỗi bài bằng dữ liệu khác, tránh hard-code.
     */
    @Transactional
    public Map<String, Object> applyDerivedDatabaseCheckpoints(String scenarioId,
                                                                List<Map<String, Object>> derived,
                                                                String outputSha256) {
        BehaviorScenario scenario = scenario(scenarioId);
        ensureEditable(suite(scenario.getSuiteId()));
        // Checkpoint tự sinh bị xoá đi tách lại mỗi lần capture — nhưng ĐIỂM và RÀNG BUỘC
        // là của người soạn, không phải của phép tách. Không thừa kế thì mỗi lần capture
        // lại nuốt mất phần chia điểm (ca thật 30/8: đặt entity 8đ làm cha, capture xong
        // tụt về 1đ). Khoá nhận diện: bảng + operation — ổn định qua các lần tách.
        Map<String, Map<String, Object>> cuTheoKhoa = new LinkedHashMap<>();
        for (Map<String, Object> item : readObjectList(scenario.getCheckpointsJson())) {
            if (Set.of("hidden_output_diff", "hidden_output_consistency")
                    .contains(text(item, "generated_from", ""))) {
                cuTheoKhoa.putIfAbsent(
                        text(item, "table", "") + "|" + text(item, "operation", "").toUpperCase(Locale.ROOT),
                        item);
            }
        }
        List<Map<String, Object>> checkpoints = readObjectList(scenario.getCheckpointsJson()).stream()
                .filter(item -> !Set.of("hidden_output_diff", "hidden_output_consistency")
                        .contains(text(item, "generated_from", "")))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        // Tập chữ mà các tiêu chí giao diện đang khẳng định NGUYÊN VĂN.
        //
        // Trước đây chỗ này gom JSON của mọi tiêu chí thành một chuỗi rồi lọc bằng
        // `contains` — khớp CON. Nhưng lúc chấm, engine dùng `find.text(value)` khớp
        // TUYỆT ĐỐI cả widget Text. Hai khái niệm khác nhau nên sinh ra tiêu chí không
        // bao giờ đạt được: "KHAC" lọt vì nó là một đoạn của "29.300 ₫ · KHAC ·
        // 2026-09-09", còn "6" lọt vì bất kỳ chuỗi nào có chữ số 6 cũng chứa nó.
        Set<String> chuUiKhangDinh = new LinkedHashSet<>();
        for (Map<String, Object> item : checkpoints) {
            if ("database_observation".equals(text(item, "kind", ""))) continue;
            themChuKhangDinh(chuUiKhangDinh, map(item.get("target")));
            Map<String, Object> mongDoi = map(item.get("expect"));
            for (Object raw : objectList(mongDoi.get("visible_texts"))) {
                themChuKhangDinh(chuUiKhangDinh, raw);
            }
            for (Object raw : objectList(mongDoi.get("semantic_nodes"))) {
                themChuKhangDinh(chuUiKhangDinh, map(map(raw).get("target")));
            }
        }
        int next = checkpoints.size() + 1;
        for (Map<String, Object> raw : derived == null ? List.<Map<String, Object>>of() : derived) {
            Map<String, Object> checkpoint = map(raw);
            validateDatabaseObservation(checkpoint);
            // Giữ giá trị nào có THẬT trong một dòng chữ mà tiêu chí giao diện khẳng định
            // — khớp CON, đúng cách engine sẽ so (một dòng danh sách gộp nhiều trường vào
            // một Text). Bỏ các cột ghi sổ của CSDL: id và khoá tự sinh không bao giờ hiện
            // lên màn hình, mà lại khớp bừa với bất kỳ chuỗi nào chứa chữ số đó ("6" khớp
            // "62.600") — sinh ra tiêu chí vô nghĩa rồi trượt oan.
            List<String> uiValues = map(checkpoint.get("row")).entrySet().stream()
                    .filter(o -> o.getValue() != null)
                    .filter(o -> !COT_GHI_SO.contains(o.getKey().toLowerCase(Locale.ROOT)))
                    .map(o -> String.valueOf(o.getValue()))
                    .filter(value -> !value.isBlank())
                    .filter(value -> chuUiKhangDinh.stream().anyMatch(chu -> chu.contains(value)))
                    .distinct()
                    .toList();
            if (!uiValues.isEmpty()
                    && !"DELETE".equals(text(checkpoint, "operation", "").toUpperCase(Locale.ROOT))) {
                checkpoint.put("kind", "entity_consistency");
                checkpoint.put("scope", "cross_layer");
                checkpoint.put("action", "observe_entity_consistency");
                checkpoint.put("browser", "flutter_tester+sqlite");
                checkpoint.put("ui_values", uiValues);
                checkpoint.put("generated_from", "hidden_output_consistency");
                checkpoint.put("id", "entity_consistency_" + next++);
                checkpoint.put("name", text(checkpoint, "operation", "READ")
                        + " nhat quan giua input, UI va SQLite tren bang " + text(checkpoint, "table", ""));
            } else {
                checkpoint.put("generated_from", "hidden_output_diff");
                checkpoint.put("id", "database_diff_" + next++);
            }
            Map<String, Object> cu = cuTheoKhoa.get(
                    text(checkpoint, "table", "") + "|" + text(checkpoint, "operation", "").toUpperCase(Locale.ROOT));
            if (cu != null) {
                checkpoint.put("weight", cu.getOrDefault("weight", 1.0));
                // Giữ nguyên id cũ: các checkpoint con đang trỏ `requires` vào id này.
                checkpoint.put("id", text(cu, "id", text(checkpoint, "id", "")));
                if (cu.get("requires") != null) checkpoint.put("requires", cu.get("requires"));
            }
            checkpoint.putIfAbsent("weight", 1.0);
            checkpoints.add(checkpoint);
        }
        if (derived != null && !derived.isEmpty()
                && "UI_BUTTONS_SELECTION".equals(scenario.getSkillCode())) {
            scenario.setSkillCode("STORAGE_SQLITE_CRUD");
        }
        scenario.setCheckpointsJson(json(checkpoints));
        scenarios.save(scenario);

        OracleSnapshot oracle = oracles.findFirstByScenarioIdOrderByCreatedAtDesc(scenarioId)
                .orElseThrow(() -> new IllegalStateException("Scenario chưa có oracle để hoàn thiện"));
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("checkpoints", checkpoints.stream()
                .filter(item -> "database_observation".equals(text(item, "kind", ""))
                        || "entity_consistency".equals(text(item, "kind", "")))
                .toList());
        observation.put("output_database_sha256", outputSha256);
        oracle.setDatabaseObservationJson(json(observation));
        oracle.setStatus(OracleStatus.READY);
        oracles.save(oracle);

        Map<String, Object> out = new LinkedHashMap<>(scenarioView(scenario, true));
        out.put("oracle", oracleView(oracle));
        return out;
    }

    /**
     * Nướng VỊ TRÍ và MÀU chuẩn — do engine đo trên chính Golden lúc capture oracle — vào
     * các tiêu chí giao diện của scenario.
     *
     * Vì sao không để người ra đề gõ tay toạ độ: toạ độ phụ thuộc viewport, phông chữ và
     * bản Flutter; gõ tay thì sai ngay lần đầu và không ai kiểm được. Đo tự động thì số
     * chuẩn luôn sinh ra từ cùng một lượt chạy với ảnh mẫu nên hai thứ không thể lệch nhau.
     *
     * Khoá nối là `id` của checkpoint, KHÔNG phải test_id: test_id do materializer sinh lúc
     * bung ma trận nên không tồn tại ở tầng soạn đề.
     */
    @Transactional
    public int applyCapturedLayout(String scenarioId, Map<String, Object> components) {
        if (components == null || components.isEmpty()) return 0;
        BehaviorScenario scenario = scenario(scenarioId);
        ensureEditable(suite(scenario.getSuiteId()));
        List<Map<String, Object>> checkpoints = new ArrayList<>(readObjectList(scenario.getCheckpointsJson()));
        int daNuong = 0;
        for (Map<String, Object> checkpoint : checkpoints) {
            String kind = text(checkpoint, "kind", "");
            boolean laViTri = "component_position".equals(kind);
            boolean laMau = "component_color".equals(kind) || "theme_color".equals(kind);
            // Ba loại tiêu chí "đọc một giá trị rồi so": trạng thái widget, kiểu chữ, chủ đề.
            // Chúng dùng CHUNG một kênh giá trị chuẩn (`observed`) nên chỉ có một luật nướng.
            boolean laTrangThai = "widget_state".equals(kind)
                    || "text_style".equals(kind)
                    || "theme_value".equals(kind);
            if (!laViTri && !laMau && !laTrangThai) continue;
            Map<String, Object> doDuoc = map(components.get(text(checkpoint, "id", "")));
            if (doDuoc.isEmpty()) continue;
            Map<String, Object> mongDoi = new LinkedHashMap<>(map(checkpoint.get("expect")));
            if (laTrangThai) {
                Object giaTri = doDuoc.get("observed");
                // Không đo được (tiêu chí khai sai, widget không có trên màn) thì KHÔNG nướng:
                // để lúc chấm báo "chưa có giá trị chuẩn" còn hơn nướng rỗng rồi cái gì cũng đạt.
                if (giaTri == null) continue;
                mongDoi.put("value", giaTri);
            } else if (laViTri) {
                if (doDuoc.get("center_x") == null || doDuoc.get("center_y") == null) continue;
                mongDoi.put("center_x", doDuoc.get("center_x"));
                mongDoi.put("center_y", doDuoc.get("center_y"));
                mongDoi.put("width", doDuoc.get("width"));
                mongDoi.put("height", doDuoc.get("height"));
            } else {
                String mau = text(doDuoc, "color", "");
                // Thành phần trong suốt hoàn toàn thì không có màu để so — bỏ qua, để tiêu chí
                // báo "chưa có giá trị chuẩn" còn hơn nướng bừa một màu sai.
                if (mau.isBlank()) continue;
                mongDoi.put("color", mau);
            }
            checkpoint.put("expect", mongDoi);
            daNuong++;
        }
        if (daNuong == 0) return 0;
        scenario.setCheckpointsJson(json(checkpoints));
        scenarios.save(scenario);
        return daNuong;
    }

    /**
     * Nướng mô tả dự phòng (đo trên Golden lúc thu oracle) vào target của các bước bấm/gõ.
     *
     * CHỈ vá steps, KHÔNG vá checkpoint: đường lui dùng để ĐI TỚI màn kế, còn tiêu chí
     * "có nhãn đúng" phải trượt thật khi bài nộp quên nhãn. Nhờ vậy quên một cái nút chỉ
     * mất đúng tiêu chí nhãn thay vì kéo sập cả màn phía sau.
     */
    @Transactional
    public int applyCapturedTargets(String scenarioId, Map<String, Object> nhan) {
        if (nhan == null || nhan.isEmpty()) return 0;
        BehaviorScenario scenario = scenario(scenarioId);
        ensureEditable(suite(scenario.getSuiteId()));
        List<Map<String, Object>> steps = new ArrayList<>(readObjectList(scenario.getStepsJson()));
        int daNuong = 0;
        for (Map<String, Object> step : steps) {
            Map<String, Object> target = new LinkedHashMap<>(map(step.get("target")));
            String label = text(target, "label", "");
            if (label.isBlank()) continue;
            Map<String, Object> moTa = map(nhan.get(label));
            if (moTa.isEmpty()) continue;
            target.put("fallback", List.of(moTa));
            step.put("target", target);
            daNuong++;
        }
        if (daNuong == 0) return 0;
        scenario.setStepsJson(json(steps));
        scenarios.save(scenario);
        return daNuong;
    }

    @Transactional
    public Map<String, Object> updateScenario(String scenarioId, Map<String, Object> body) {
        BehaviorScenario scenario = scenario(scenarioId);
        BehaviorSuite suite = suite(scenario.getSuiteId());
        ensureEditable(suite);
        boolean invalidatesReplay = body.containsKey("initial_state")
                || body.containsKey("steps")
                || body.containsKey("checkpoints")
                || body.containsKey("viewports");
        if (body.containsKey("name")) scenario.setName(required(body, "name"));
        if (body.containsKey("skill_code")) scenario.setSkillCode(required(body, "skill_code"));
        if (body.containsKey("description")) scenario.setDescription(optional(body, "description"));
        if (body.containsKey("weight")) scenario.setWeight(number(body.get("weight"), 1.0));
        if (body.containsKey("enabled")) scenario.setEnabled(bool(body.get("enabled"), true));
        if (body.containsKey("display_order")) {
            scenario.setDisplayOrder((int) number(body.get("display_order"), scenario.getDisplayOrder()));
        }
        if (body.containsKey("initial_state")) scenario.setInitialStateJson(normalizeObject(body.get("initial_state"), Map.of()));
        if (body.containsKey("steps")) scenario.setStepsJson(normalizeArray(body.get("steps")));
        if (body.containsKey("checkpoints")) scenario.setCheckpointsJson(normalizeArray(body.get("checkpoints")));
        if (body.containsKey("viewports")) scenario.setViewportsJson(normalizeArray(body.get("viewports")));
        validateScenario(scenario, false);
        scenarios.save(scenario);
        if (invalidatesReplay) {
            staleScenarioOracles(scenario.getId());
        }
        if (suite.getStatus() == BehaviorSuiteStatus.PUBLISHED) {
            suite.setStatus(BehaviorSuiteStatus.REVIEW);
            suites.save(suite);
        }
        return scenarioView(scenario, true);
    }

    @Transactional
    public Map<String, Object> deleteScenario(String scenarioId) {
        BehaviorScenario scenario = scenario(scenarioId);
        BehaviorSuite suite = suite(scenario.getSuiteId());
        ensureEditable(suite);
        String recordingId = scenario.getSourceRecordingId();
        oracles.deleteByScenarioId(scenarioId);
        scenarios.delete(scenario);
        if (recordingId != null && !recordingId.isBlank()) {
            recordings.deleteById(recordingId);
        }
        if (suite.getStatus() == BehaviorSuiteStatus.PUBLISHED) {
            suite.setStatus(BehaviorSuiteStatus.REVIEW);
            suites.save(suite);
        }
        return Map.of(
                "deleted", true,
                "id", scenarioId,
                "suite_id", suite.getId(),
                "scenario_code", scenario.getScenarioCode());
    }

    /**
     * Artifact đầu vào của replay (đặc biệt Hidden DB) đã đổi thì mọi oracle cũ
     * không còn chứng minh được kết quả của Golden Solution trên đầu vào hiện tại.
     */
    @Transactional
    public void invalidateSuiteOracles(String suiteId) {
        BehaviorSuite suite = suite(suiteId);
        ensureEditable(suite);
        staleSuiteOracles(suite);
        suites.save(suite);
    }

    @Transactional
    public Map<String, Object> saveOracle(String scenarioId, Map<String, Object> body) {
        BehaviorScenario scenario = scenario(scenarioId);
        BehaviorSuite suite = suite(scenario.getSuiteId());
        GoldenApp app = golden(suite.getGoldenAppId());
        String seed = required(body, "seed");
        OracleSnapshot oracle = new OracleSnapshot();
        oracle.setScenarioId(scenarioId);
        oracle.setGoldenAppId(app.getId());
        oracle.setGoldenSha256(app.getArtifactSha256());
        oracle.setSeed(seed);
        oracle.setInputJson(normalizeObject(body.get("input"), Map.of()));
        oracle.setUiObservationJson(normalizeObject(body.get("ui_observation"), Map.of()));
        oracle.setDatabaseObservationJson(normalizeObject(body.get("database_observation"), Map.of()));
        oracle.setStatus(OracleStatus.READY);
        oracles.save(oracle);
        return oracleView(oracle);
    }

    @Transactional
    public Map<String, Object> publish(String suiteId) {
        BehaviorSuite suite = suite(suiteId);
        GoldenApp app = golden(suite.getGoldenAppId());
        if (app.getStatus() != GoldenAppStatus.READY) {
            throw new IllegalStateException("Không thể publish vì Golden App chưa READY");
        }
        List<BehaviorScenario> enabled = scenarios.findBySuiteIdOrderByDisplayOrderAscCreatedAtAsc(suiteId)
                .stream().filter(row -> Boolean.TRUE.equals(row.getEnabled())).toList();
        if (enabled.isEmpty()) throw new IllegalStateException("Bộ chấm chưa có scenario đang bật");
        double totalWeight = 0;
        for (BehaviorScenario scenario : enabled) {
            validateScenario(scenario, true);
            if (!skills.existsById(scenario.getSkillCode())) {
                throw new IllegalStateException(
                        "Scenario " + scenario.getScenarioCode() + " dùng skill_code không có trong syllabus: "
                                + scenario.getSkillCode());
            }
            boolean hasOracle = oracles.findByScenarioIdOrderByCreatedAtDesc(scenario.getId()).stream()
                    .anyMatch(row -> row.getStatus() == OracleStatus.READY
                            && Objects.equals(row.getGoldenSha256(), app.getArtifactSha256()));
            if (!hasOracle) {
                throw new IllegalStateException(
                        "Scenario " + scenario.getScenarioCode()
                                + " chưa có oracle READY khớp phiên bản Golden Solution hiện tại");
            }
            totalWeight += scenario.getWeight();
        }
        if (totalWeight <= 0) throw new IllegalStateException("Tổng trọng số phải lớn hơn 0");
        suite.setRevision(suite.getStatus() == BehaviorSuiteStatus.PUBLISHED
                ? suite.getRevision() + 1 : suite.getRevision());
        suite.setStatus(BehaviorSuiteStatus.PUBLISHED);
        suite.setPublishedAt(Instant.now());
        suites.save(suite);
        Map<String, Object> out = new LinkedHashMap<>(suiteView(suite, true));
        out.put("total_weight", totalWeight);
        out.put("ready_for_replay", true);
        return out;
    }

    public Map<String, Object> executionPlan(String suiteId) {
        BehaviorSuite suite = suite(suiteId);
        if (suite.getStatus() != BehaviorSuiteStatus.PUBLISHED) {
            throw new IllegalStateException("Bộ chấm chưa publish");
        }
        return buildExecutionPlan(suite);
    }

    /** Kế hoạch nháp chỉ dùng để chạy preflight trên Golden Solution trước khi publish. */
    public Map<String, Object> previewExecutionPlan(String suiteId) {
        return buildExecutionPlan(suite(suiteId));
    }

    private Map<String, Object> buildExecutionPlan(BehaviorSuite suite) {
        String suiteId = suite.getId();
        GoldenApp app = golden(suite.getGoldenAppId());
        List<Map<String, Object>> scenarioRows = scenarios
                .findBySuiteIdOrderByDisplayOrderAscCreatedAtAsc(suiteId).stream()
                .filter(row -> Boolean.TRUE.equals(row.getEnabled()))
                .map(row -> {
                    Map<String, Object> view = new LinkedHashMap<>(scenarioView(row, true));
                    oracles.findByScenarioIdOrderByCreatedAtDesc(row.getId()).stream()
                            .filter(oracle -> oracle.getStatus() == OracleStatus.READY)
                            .findFirst()
                            .ifPresent(oracle -> view.put("oracle", oracleView(oracle)));
                    return view;
                }).toList();
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("schema_version", SCHEMA_VERSION);
        plan.put("suite", suiteView(suite, false));
        plan.put("golden_app", goldenAppView(app));
        plan.put("public_contract", readObject(suite.getPublicContractJson()));
        plan.put("database_contract", readObject(suite.getDatabaseContractJson()));
        plan.put("runtime_config", readObject(suite.getRuntimeConfigJson()));
        plan.put("scenarios", scenarioRows);
        return plan;
    }

    private void validateScenario(BehaviorScenario scenario, boolean publish) {
        if (scenario.getWeight() == null || scenario.getWeight() <= 0) {
            throw new IllegalArgumentException("Scenario " + scenario.getScenarioCode() + " phải có weight > 0");
        }
        List<Map<String, Object>> steps = readObjectList(scenario.getStepsJson());
        if (steps.isEmpty()) throw new IllegalArgumentException("Scenario " + scenario.getScenarioCode() + " chưa có bước");
        for (Map<String, Object> step : steps) {
            String action = text(step, "action", "");
            if (!ACTIONS.contains(action)) {
                throw new IllegalArgumentException("Scenario " + scenario.getScenarioCode() + " có action không hỗ trợ: " + action);
            }
        }
        List<Map<String, Object>> viewports = readObjectList(scenario.getViewportsJson());
        if (viewports.isEmpty()) {
            throw new IllegalArgumentException("Scenario " + scenario.getScenarioCode() + " chưa có viewport");
        }
        for (Map<String, Object> viewport : viewports) {
            if (number(viewport.get("width"), 0) <= 0 || number(viewport.get("height"), 0) <= 0) {
                throw new IllegalArgumentException(
                        "Scenario " + scenario.getScenarioCode() + " có viewport không hợp lệ: " + viewport);
            }
        }
        if (publish && readObjectList(scenario.getCheckpointsJson()).isEmpty()) {
            throw new IllegalArgumentException("Scenario " + scenario.getScenarioCode() + " chưa có checkpoint chấm");
        }
    }

    private void validateDatabaseObservation(Map<String, Object> event) {
        String table = required(event, "table");
        if (!table.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Tên bảng SQLite không hợp lệ: " + table);
        }
        String operation = text(event, "operation", "READ").toUpperCase(Locale.ROOT);
        if (!Set.of("READ", "INSERT", "UPDATE", "DELETE").contains(operation)) {
            throw new IllegalArgumentException("operation DB chỉ nhận READ, INSERT, UPDATE hoặc DELETE");
        }
        if (map(event.get("row")).isEmpty() && event.get("count") == null) {
            throw new IllegalArgumentException("Checkpoint DB phải có row hoặc count cần đối chiếu");
        }
        event.put("operation", operation);
        event.putIfAbsent("checkpoint", true);
        event.putIfAbsent("scope", "database");
        event.putIfAbsent("stage", "ASSERT");
        event.putIfAbsent("attribute", "table");
        event.putIfAbsent("attributeValue", table);
        event.putIfAbsent("valueType", "json");
        event.putIfAbsent("value", event.getOrDefault("row", Map.of()));
        event.putIfAbsent("action", "observe_database");
        event.putIfAbsent("browser", "sqlite");
    }

    private void validateUiObservation(Map<String, Object> event) {
        Map<String, Object> expect = map(event.get("expect"));
        List<Object> semanticNodes = objectList(expect.get("semantic_nodes"));
        for (Object raw : semanticNodes) {
            Map<String, Object> node = map(raw);
            if (map(node.get("target")).isEmpty()) {
                throw new IllegalArgumentException("Semantic node phải có target nhận diện");
            }
            String role = text(node, "role", "").toLowerCase(Locale.ROOT);
            if (!role.isEmpty() && !Set.of(
                    "text_field", "button", "checkbox", "switch", "radio",
                    "text", "image", "link", "generic").contains(role)) {
                throw new IllegalArgumentException("Loại semantic node không được hỗ trợ: " + role);
            }
        }
        // TIỀN TỐ cũng là một nội dung hợp lệ. Thiếu hai dòng này thì tiêu chí chỉ khai
        // tiền tố bị chặn ngay tại cửa — engine hiểu, form gửi đúng, mà backend từ chối.
        if (map(event.get("target")).isEmpty()
                && objectList(expect.get("visible_texts")).isEmpty()
                && objectList(expect.get("visible_text_prefixes")).isEmpty()
                && objectList(expect.get("hidden_texts")).isEmpty()
                && objectList(expect.get("hidden_text_prefixes")).isEmpty()
                && semanticNodes.isEmpty()
                && event.get("text") == null
                && !bool(event.get("no_exception"), false)) {
            throw new IllegalArgumentException(
                    "Checkpoint UI phải có target, semantic_nodes, visible_texts, hidden_texts, "
                    + "tiền tố (bắt đầu bằng…), text hoặc no_exception");
        }
        event.putIfAbsent("checkpoint", true);
        event.putIfAbsent("scope", "ui");
        event.putIfAbsent("stage", "ASSERT");
        event.putIfAbsent("action", "observe_ui");
        event.putIfAbsent("browser", "flutter_tester");
    }

    private Map<String, Object> goldenAppView(GoldenApp app) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", app.getId());
        out.put("exam_id", app.getExamId());
        out.put("name", app.getName());
        out.put("version", app.getVersion());
        out.put("platform", app.getPlatform());
        out.put("runtime_url", app.getRuntimeUrl());
        out.put("artifact_path", app.getArtifactPath());
        out.put("artifact_sha256", app.getArtifactSha256());
        out.put("status", app.getStatus().name());
        out.put("metadata", readObject(app.getMetadataJson()));
        out.put("created_at", timestamp(app.getCreatedAt()));
        out.put("updated_at", timestamp(app.getUpdatedAt()));
        return out;
    }

    private Map<String, Object> suiteView(BehaviorSuite suite, boolean includeScenarios) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", suite.getId());
        out.put("suite_code", suite.getSuiteCode());
        out.put("exam_id", suite.getExamId());
        out.put("golden_app_id", suite.getGoldenAppId());
        out.put("name", suite.getName());
        out.put("description", suite.getDescription());
        out.put("schema_version", suite.getSchemaVersion());
        out.put("revision", suite.getRevision());
        out.put("status", suite.getStatus().name());
        out.put("public_contract", readObject(suite.getPublicContractJson()));
        out.put("database_contract", readObject(suite.getDatabaseContractJson()));
        out.put("runtime_config", readObject(suite.getRuntimeConfigJson()));
        out.put("created_at", timestamp(suite.getCreatedAt()));
        out.put("updated_at", timestamp(suite.getUpdatedAt()));
        out.put("published_at", timestamp(suite.getPublishedAt()));
        if (includeScenarios) {
            out.put("scenarios", scenarios.findBySuiteIdOrderByDisplayOrderAscCreatedAtAsc(suite.getId())
                    .stream().map(row -> scenarioView(row, true)).toList());
            out.put("recordings", recordings.findBySuiteIdOrderByStartedAtDesc(suite.getId())
                    .stream().map(this::recordingView).toList());
        }
        return out;
    }

    private Map<String, Object> scenarioView(BehaviorScenario scenario, boolean full) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", scenario.getId());
        out.put("suite_id", scenario.getSuiteId());
        out.put("source_recording_id", scenario.getSourceRecordingId());
        out.put("scenario_code", scenario.getScenarioCode());
        out.put("name", scenario.getName());
        out.put("skill_code", scenario.getSkillCode());
        out.put("description", scenario.getDescription());
        out.put("display_order", scenario.getDisplayOrder());
        out.put("weight", scenario.getWeight());
        out.put("enabled", scenario.getEnabled());
        if (full) {
            out.put("initial_state", readObject(scenario.getInitialStateJson()));
            out.put("steps", readArray(scenario.getStepsJson()));
            out.put("checkpoints", readArray(scenario.getCheckpointsJson()));
            out.put("viewports", readArray(scenario.getViewportsJson()));
        }
        return out;
    }

    private Map<String, Object> recordingView(GoldenRecording recording) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", recording.getId());
        out.put("suite_id", recording.getSuiteId());
        out.put("golden_app_id", recording.getGoldenAppId());
        out.put("revision_scenario_id", recording.getRevisionScenarioId());
        out.put("name", recording.getName());
        out.put("seed", recording.getSeed());
        out.put("status", recording.getStatus().name());
        out.put("viewport", readObject(recording.getViewportJson()));
        out.put("initial_state", readObject(recording.getInitialStateJson()));
        out.put("raw_trace", readArray(recording.getRawTraceJson()));
        out.put("final_observation", readObject(recording.getFinalObservationJson()));
        out.put("started_at", timestamp(recording.getStartedAt()));
        out.put("stopped_at", timestamp(recording.getStoppedAt()));
        return out;
    }

    private List<Map<String, Object>> scenarioTrace(BehaviorScenario scenario) {
        List<Map<String, Object>> trace = new ArrayList<>();
        for (Object raw : readArray(scenario.getStepsJson())) {
            Map<String, Object> event = map(raw);
            event.put("kind", "action");
            trace.add(event);
        }
        for (Object raw : readArray(scenario.getCheckpointsJson())) {
            Map<String, Object> event = map(raw);
            event.putIfAbsent("kind", "checkpoint");
            event.put("checkpoint", true);
            trace.add(event);
        }
        for (int index = 0; index < trace.size(); index++) trace.get(index).put("sequence", index + 1);
        return trace;
    }

    private Map<String, Object> oracleView(OracleSnapshot oracle) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", oracle.getId());
        out.put("scenario_id", oracle.getScenarioId());
        out.put("golden_app_id", oracle.getGoldenAppId());
        out.put("golden_sha256", oracle.getGoldenSha256());
        out.put("seed", oracle.getSeed());
        out.put("status", oracle.getStatus().name());
        out.put("input", readObject(oracle.getInputJson()));
        out.put("ui_observation", readObject(oracle.getUiObservationJson()));
        out.put("database_observation", readObject(oracle.getDatabaseObservationJson()));
        out.put("created_at", timestamp(oracle.getCreatedAt()));
        return out;
    }

    private String timestamp(Instant value) {
        return value == null ? null : value.toString();
    }

    /** Seed dựa trên nội dung hành vi, không phụ thuộc UUID hay thời điểm record. */
    private String deterministicSeed(List<Map<String, Object>> steps,
                                     List<Map<String, Object>> checkpoints,
                                     String initialStateJson,
                                     String viewportsJson) {
        Map<String, Object> source = new TreeMap<>();
        source.put("steps", steps);
        source.put("checkpoints", checkpoints);
        source.put("initial_state", readObject(initialStateJson));
        source.put("viewports", readArray(viewportsJson));
        try {
            ObjectMapper canonical = mapper.copy()
                    .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
            byte[] bytes = canonical.writeValueAsString(source).getBytes(StandardCharsets.UTF_8);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return "rar-v1-" + hash.substring(0, 24);
        } catch (Exception e) {
            throw new IllegalStateException("Không sinh được seed tất định", e);
        }
    }

    private GoldenApp golden(String id) {
        return goldenApps.findById(id).orElseThrow(() -> new IllegalArgumentException("Không tìm thấy Golden App: " + id));
    }

    private BehaviorSuite suite(String id) {
        return suites.findById(id).orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bộ chấm hành vi: " + id));
    }

    private BehaviorScenario scenario(String id) {
        return scenarios.findById(id).orElseThrow(() -> new IllegalArgumentException("Không tìm thấy scenario: " + id));
    }

    private GoldenRecording recording(String id) {
        return recordings.findById(id).orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiên record: " + id));
    }

    private GoldenRecording recordingForUpdate(String id) {
        return recordings.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiên record: " + id));
    }

    /**
     * allowed_packages chỉ gate dependency phía bài sinh viên trước khi compile (SubmissionPackagePolicy),
     * không ảnh hưởng hành vi Golden Solution hay oracle đã capture. Đổi riêng field này không cần
     * record/capture lại; chỉ các key runtime khác (driver, timeout, api_base_url...) mới invalidate.
     */
    private boolean runtimeConfigChangeInvalidatesReplay(BehaviorSuite suite, Map<String, Object> body) {
        if (!body.containsKey("runtime_config")) return false;
        Object raw = body.get("runtime_config");
        Map<String, Object> incoming = raw instanceof String s ? readObject(s) : map(raw);
        Map<String, Object> current = readObject(suite.getRuntimeConfigJson());
        current.remove("allowed_packages");
        incoming.remove("allowed_packages");
        return !current.equals(incoming);
    }

    private void ensureEditable(BehaviorSuite suite) {
        if (suite.getStatus() == BehaviorSuiteStatus.DISABLED) {
            throw new IllegalStateException("Bộ chấm đã bị vô hiệu hoá");
        }
    }

    private void staleSuiteOracles(BehaviorSuite suite) {
        for (BehaviorScenario scenario : scenarios.findBySuiteIdOrderByDisplayOrderAscCreatedAtAsc(suite.getId())) {
            staleScenarioOracles(scenario.getId());
        }
        if (suite.getStatus() == BehaviorSuiteStatus.PUBLISHED) {
            suite.setStatus(BehaviorSuiteStatus.REVIEW);
        }
    }

    private void staleScenarioOracles(String scenarioId) {
        List<OracleSnapshot> snapshots = oracles.findByScenarioIdOrderByCreatedAtDesc(scenarioId);
        snapshots.stream()
                .filter(snapshot -> snapshot.getStatus() != OracleStatus.STALE)
                .forEach(snapshot -> snapshot.setStatus(OracleStatus.STALE));
        oracles.saveAll(snapshots);
    }

    private Map<String, Object> defaultPublicContract() {
        return Map.of(
                "locator_priority", List.of("semantic_id", "value_key", "accessibility_label", "role_text", "structure"),
                "required_semantics", List.of(),
                "allow_coordinate_fallback", false);
    }

    private Map<String, Object> defaultDatabaseContract() {
        return Map.of(
                "enabled", false,
                "driver", "sqlite",
                "tables", List.of(),
                "ignore_columns", List.of("created_at", "updated_at"));
    }

    private Map<String, Object> defaultRuntimeConfig() {
        return Map.of(
                "reset_between_scenarios", true,
                "default_timeout_ms", 5_000,
                "oracle_mode", "golden_per_seed",
                "screenshot_evidence", true,
                "automation_driver", "flutter_test",
                "browser", "flutter_tester",
                "api_base_url", "http://mock-api:8080",
                "allowed_packages", List.of(
                        "flutter", "flutter_test", "path", "sqflite", "sqflite_common_ffi"));
    }

    private Map<String, Object> defaultViewport() {
        return Map.of("id", "desktop", "width", 1280, "height", 800, "device_pixel_ratio", 1.0);
    }



    private String locatorAttribute(Map<String, Object> target) {
        for (String key : List.of("semanticId", "semantic_id", "valueKey", "value_key", "key",
                "label", "hint", "text", "text_prefix", "tooltip", "role")) {
            if (target.get(key) != null && !String.valueOf(target.get(key)).isBlank()) return key;
        }
        return "none";
    }

    private String locatorValue(Map<String, Object> target) {
        String attribute = locatorAttribute(target);
        return "none".equals(attribute) ? "" : String.valueOf(target.get(attribute));
    }

    private String valueType(Object value) {
        if (value == null) return "none";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Number) return "number";
        if (value instanceof Map<?, ?>) return "object";
        if (value instanceof List<?>) return "array";
        return "string";
    }

    /**
     * Gom các chuỗi mà một target/giá trị đang khẳng định, dưới dạng `find.text()` sẽ tìm.
     *
     * Nhãn hai dòng của ListTile ("tiêu đề\nphụ đề") được tách thêm DÒNG ĐẦU: title của
     * ListTile là một widget Text riêng nên `find.text` khớp được nó, còn cả cụm hai dòng
     * thì chỉ tồn tại trong cây ngữ nghĩa.
     */
    private void themChuKhangDinh(Set<String> dich, Object nguon) {
        if (nguon == null) return;
        if (nguon instanceof Map<?, ?> m) {
            Map<String, Object> target = map(m);
            for (String khoa : List.of("text", "label", "hint", "text_prefix")) {
                themChuKhangDinh(dich, target.get(khoa));
            }
            return;
        }
        String chu = String.valueOf(nguon).trim();
        if (chu.isBlank()) return;
        dich.add(chu);
        int xuong = chu.indexOf('\n');
        if (xuong > 0) dich.add(chu.substring(0, xuong).trim());
    }

    /**
     * Cột ghi sổ của CSDL — không phải dữ liệu người dùng nhìn thấy, nên không đưa vào
     * phép đối chiếu UI. Khoá tự sinh còn nguy hiểm ở chỗ nó khớp bừa: id 6 nằm trong
     * "62.600", trong "2026" — tiêu chí sẽ vừa vô nghĩa vừa trượt thất thường.
     */
    private static final Set<String> COT_GHI_SO =
            Set.of("id", "_id", "rowid", "uuid", "created_at", "updated_at");

    private List<Object> objectList(Object value) {
        return value instanceof List<?> source ? new ArrayList<>(source) : new ArrayList<>();
    }




    private String uniqueScenarioCode(String suiteId, String base) {
        String value = base.isBlank() ? "SCENARIO" : base;
        String candidate = value;
        int suffix = 2;
        while (scenarios.findBySuiteIdAndScenarioCode(suiteId, candidate).isPresent()) {
            candidate = value + "_" + suffix++;
        }
        return candidate;
    }

    private String slug(String value) {
        String slug = value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return slug.length() > 90 ? slug.substring(0, 90) : slug;
    }

    private String normalizeObject(Object value, Map<String, Object> fallback) {
        if (value == null) return json(fallback);
        Map<String, Object> parsed = value instanceof String s ? readObject(s) : map(value);
        if (parsed.isEmpty() && !(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Giá trị phải là JSON object");
        }
        return json(parsed);
    }

    private String normalizeArray(Object value) {
        if (value == null) return "[]";
        if (value instanceof String s) return json(readArray(s));
        if (!(value instanceof List<?>)) throw new IllegalArgumentException("Giá trị phải là JSON array");
        return json(value);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Không thể mã hoá JSON", e);
        }
    }

    private Map<String, Object> readObject(String value) {
        if (value == null || value.isBlank()) return new LinkedHashMap<>();
        try {
            return mapper.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON object không hợp lệ", e);
        }
    }

    private List<Object> readArray(String value) {
        if (value == null || value.isBlank()) return new ArrayList<>();
        try {
            return mapper.readValue(value, new TypeReference<ArrayList<Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON array không hợp lệ", e);
        }
    }

    private List<Map<String, Object>> readObjectList(String value) {
        if (value == null || value.isBlank()) return new ArrayList<>();
        try {
            return mapper.readValue(value, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Danh sách JSON không hợp lệ", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((key, item) -> out.put(String.valueOf(key), item));
        return out;
    }

    private String required(Map<String, Object> body, String... keys) {
        String value = optional(body, keys);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Thiếu trường " + keys[0]);
        return value;
    }

    private String optional(Map<String, Object> body, String... keys) {
        if (body == null) return null;
        for (String key : keys) {
            Object value = body.get(key);
            if (value != null && !String.valueOf(value).trim().isEmpty()) return String.valueOf(value).trim();
        }
        return null;
    }

    private String text(Map<String, Object> body, String key, String fallback) {
        String value = optional(body, key);
        return value == null ? fallback : value;
    }

    private boolean bool(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private double number(Object value, double fallback) {
        if (value == null) return fallback;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Giá trị số không hợp lệ: " + value);
        }
    }
}
