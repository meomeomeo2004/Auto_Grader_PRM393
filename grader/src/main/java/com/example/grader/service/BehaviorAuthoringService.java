package com.example.grader.service;

import com.example.grader.entity.*;
import com.example.grader.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.net.URI;
import java.net.URISyntaxException;
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
    /**
     * Khoá trong `expect` là GIÁ TRỊ CHUẨN do engine đo trên Golden, không phải do người soạn
     * gõ. Dùng khi so hai checkpoint để quyết định có giữ điểm cũ không — giữ đồng bộ với
     * {@link #applyCapturedLayout}, thêm kênh nướng mới thì phải thêm khoá vào đây.
     */
    private static final Set<String> KHOA_GIA_TRI_MAY_DO = Set.of(
            "value", "repeat", "relation", "color", "center_x", "center_y", "width", "height");
    /** Loại tiêu chí có nhận giá trị chuẩn từ capture — cũng lấy từ applyCapturedLayout. */
    private static final Set<String> LOAI_NHAN_GIA_TRI_MAY_DO = Set.of(
            "component_position", "component_color", "theme_color", "component_present",
            "layout_relation", "preferences_observation", "widget_state", "text_style", "theme_value");
    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{2,79}");
    private static final Set<String> EVENT_KINDS = Set.of(
            "action", "ui_observation", "database_observation", "checkpoint", "navigation", "exception",
            // Tiêu chí "thành phần giao diện có mặt" — sinh từ bảng tick trên trang soạn đề.
            // Mỗi event là MỘT thành phần; engine có nhánh riêng cho kind này.
            "component_present",
            // Vị trí và màu của MỘT thành phần, mỗi mặt một tiêu chí riêng. Giá trị chuẩn
            // do engine đo trên Golden lúc capture oracle rồi nướng vào đây, không gõ tay.
            "component_position",
            // Quan hệ bố cục giữa HAI thành phần. Không so pixel/screenshot: engine chỉ
            // đo hình chữ nhật logic để xác nhận trên/dưới, cùng hàng/cột, chứa nhau...
            "layout_relation",
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
            // Giá trị app ghi vào bộ nhớ nhanh (SharedPreferences). Khác widget_state ở chỗ
            // nó đọc KHO LƯU chứ không đọc màn hình: phân biệt được bài lưu thật với bài chỉ
            // đổi giao diện bằng setState.
            "preferences_observation",
            // Cả luồng không vỡ bố cục (RenderFlex overflow). Không có giá trị chuẩn —
            // engine tự bắt lỗi tràn trong lúc replay.
            "no_overflow",
            // Màu chủ đạo của app: đọc thẳng ColorScheme từ cây widget, KHÔNG lấy mẫu
            // pixel. Bù đúng điểm mù của component_color — Material 3 cố ý làm các vai
            // outline/onSurfaceVariant gần như xám trung tính nên thành phần chỉ có
            // viền hoặc chữ hầu như không mang thông tin về bảng màu.
            "theme_color",
            // So bố cục màn hình với ảnh chuẩn chụp từ Golden trong cùng container.
            "screen_match",
            // Ch.7 — widget bố cục và hiển thị nâng cao. Không quét được từ semantics
            // (Stack/IndexedStack/Table/Sliver đều lộ ra là container "list"/"generic"
            // giống nhau) nên giáo viên tự gõ định danh thay vì tick từ bảng quét. Mỗi
            // kind ứng với đúng 1 runner COMMON_V1 cùng tên khái niệm ở
            // common-testcase-engine/exam_test.dart — xem CH7_KIND_TO_EXPECT_FIELDS.
            "component_scroll_direction", "component_scroll_to_end", "component_stack_order",
            "component_indexed_switch", "component_bottom_sheet", "component_table",
            "component_sliver_collapse", "component_expanded",
            // Trạng thái Router/URL do ứng dụng phản ánh ra SystemNavigator.
            "route_state");
    /** Ch.7 — field bắt buộc trong {@code expect} theo từng kind, xem {@link #EVENT_KINDS}. */
    private static final Map<String, List<String>> CH7_REQUIRED_EXPECT_FIELDS = Map.of(
            "component_scroll_direction", List.of("direction"),
            "component_scroll_to_end", List.of("target_key"),
            "component_stack_order", List.of("bottom_key", "top_key"),
            "component_indexed_switch", List.of("tabs"),
            "component_bottom_sheet", List.of("sheet_key"),
            "component_table", List.of("row_count"),
            "component_sliver_collapse", List.of("appbar_key"),
            "component_expanded", List.of("flex"));
    private static final Set<String> ACTIONS = Set.of(
            "boot", "boot_with_uri", "tap", "enter_text", "clear_text", "scroll", "drag", "back",
            "open_uri", "browser_back", "browser_forward", "reload", "restart",
            "wait_until", "wait_for_route");
    private static final int MAX_EVENTS = 2_000;

    private final GoldenAppRepository goldenApps;
    private final BehaviorSuiteRepository suites;
    private final BehaviorScenarioRepository scenarios;
    private final GoldenRecordingRepository recordings;
    private final OracleSnapshotRepository oracles;
    private final GoldenValidationRunRepository validationRuns;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private final ExamService exams;

    public BehaviorAuthoringService(GoldenAppRepository goldenApps,
                                    BehaviorSuiteRepository suites,
                                    BehaviorScenarioRepository scenarios,
                                    GoldenRecordingRepository recordings,
                                    OracleSnapshotRepository oracles,
                                    GoldenValidationRunRepository validationRuns,
                                    ExamService exams) {
        this.exams = exams;
        this.goldenApps = goldenApps;
        this.suites = suites;
        this.scenarios = scenarios;
        this.recordings = recordings;
        this.oracles = oracles;
        this.validationRuns = validationRuns;
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
        // KHUNG của tiêu chí: để trống là khung điện thoại, "desktop" là khung responsive
        // 1280×800. Chặn ngay ở đây thay vì để nó trôi xuống materializer, vì gõ sai một chữ
        // thì tiêu chí âm thầm rơi về khung điện thoại và kĩ năng responsive vẫn báo đạt.
        String khung = text(event, "khung", "").toLowerCase(Locale.ROOT);
        if (!khung.isBlank() && !BehaviorSuiteMaterializer.KHUNG_DESKTOP_CO.equals(khung)) {
            throw new IllegalArgumentException("Khung của tiêu chí chỉ nhận rỗng (điện thoại) hoặc \"desktop\": " + khung);
        }
        if (!khung.isBlank()) event.put("khung", khung);
        if ("action".equals(kind)) {
            String action = required(event, "action").toLowerCase(Locale.ROOT);
            if (!ACTIONS.contains(action)) throw new IllegalArgumentException("Action không hỗ trợ: " + action);
            if ("boot_with_uri".equals(action) && !trace.isEmpty()) {
                throw new IllegalArgumentException("boot_with_uri phải là event đầu tiên của scenario");
            }
            if (Set.of("tap", "enter_text", "clear_text", "scroll", "drag", "wait_until").contains(action)
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
            if (Set.of("boot_with_uri", "open_uri", "wait_for_route").contains(action)) {
                String uri = optional(event, "uri", "value");
                if (uri == null || uri.isBlank()) {
                    throw new IllegalArgumentException("Action " + action + " phải có URI/path");
                }
                validateRouteUri(uri);
                event.put("uri", uri.trim());
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
        } else if ("layout_relation".equals(kind)) {
            validateLayoutRelation(event);
        } else if ("route_state".equals(kind)) {
            validateRouteState(event);
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
        } else if (CH7_REQUIRED_EXPECT_FIELDS.containsKey(kind)) {
            // Ch.7 nhắm vào widget cấu trúc (Stack/Table/Sliver...), không có label/hint/
            // text như thành phần tương tác — chỉ nhận ĐỊNH DANH làm locator, gõ sai tên là
            // trượt rõ ràng chứ không âm thầm rơi về finder khác.
            //
            // Vì sao không phải ValueKey như bản đầu: đề chỉ được có MỘT hệ định danh, bắt
            // sinh viên học hai cách là nhầm hai lần. Đo 7/9/2026: cả tám loại widget của
            // mục này đều tìm được bằng Semantics(identifier:), sliver thì dùng
            // SliverSemantics(identifier:) có sẵn trong SDK.
            Map<String, Object> target = map(event.get("target"));
            if (text(target, "semantic_id", "").isBlank() && text(target, "semanticId", "").isBlank()) {
                throw new IllegalArgumentException(
                        "Tiêu chí Ch.7 phải có target.semantic_id (định danh Semantics của widget cần chấm)");
            }
            Map<String, Object> expect = map(event.get("expect"));
            for (String field : CH7_REQUIRED_EXPECT_FIELDS.get(kind)) {
                if (text(expect, field, "").isBlank()) {
                    throw new IllegalArgumentException(
                            "Tiêu chí " + kind + " thiếu expect." + field);
                }
            }
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
        } else if ("preferences_observation".equals(kind)) {
            // Thiếu khoá thì lúc chấm engine không biết đọc gì. Chặn ngay lúc ghi.
            if (text(event, "key", "").isBlank()) {
                throw new IllegalArgumentException(
                        "Tiêu chí giá trị đã lưu phải khai khoá cần đọc trong bộ nhớ của app");
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
        // Flutter Web có thể rebuild editing DOM giữa hai ký tự và phát một bản chốt
        // trung gian dù người dùng chưa rời ô. Hai enter_text LIÊN TIẾP trỏ cùng control
        // vì thế là cùng một thao tác logic: giữ locator giàu thông tin nhất và chỉ cập
        // nhật value cuối. Nếu giáo viên thật sự rời rồi quay lại ô sẽ có tap/action ở
        // giữa, nên hai lần nhập đó vẫn được giữ tách biệt.
        if ("action".equals(kind) && "enter_text".equals(text(event, "action", "")) && !trace.isEmpty()) {
            Map<String, Object> previous = trace.get(trace.size() - 1);
            if ("action".equals(text(previous, "kind", ""))
                    && "enter_text".equals(text(previous, "action", ""))
                    && sameLogicalInputTarget(map(previous.get("target")), map(event.get("target")))) {
                Map<String, Object> richerTarget = new LinkedHashMap<>(map(previous.get("target")));
                richerTarget.putAll(map(event.get("target")));
                event.put("target", richerTarget);
                event.put("sequence", previous.getOrDefault("sequence", trace.size()));
                event.put("recorded_at", Instant.now().toString());
                trace.set(trace.size() - 1, event);
                recording.setRawTraceJson(json(trace));
                recordings.save(recording);
                return Map.of(
                        "recording_id", recordingId,
                        "event_count", trace.size(),
                        "event", event,
                        "compacted", true);
            }
        }
        event.put("sequence", trace.size() + 1);
        event.putIfAbsent("recorded_at", Instant.now().toString());
        trace.add(event);
        recording.setRawTraceJson(json(trace));
        recordings.save(recording);
        return Map.of("recording_id", recordingId, "event_count", trace.size(), "event", event);
    }

    private boolean sameLogicalInputTarget(Map<String, Object> first, Map<String, Object> second) {
        if (first.isEmpty() || second.isEmpty()) return false;
        for (String key : List.of("semanticId", "semantic_id")) {
            String left = optional(first, key);
            String right = optional(second, key);
            if (left != null && right != null) return left.equals(right);
        }
        String firstLabel = optional(first, "label");
        String secondLabel = optional(second, "label");
        if (firstLabel != null && secondLabel != null) return firstLabel.equals(secondLabel);
        String firstHint = optional(first, "hint");
        String secondHint = optional(second, "hint");
        return firstLabel == null && secondLabel == null
                && firstHint != null && firstHint.equals(secondHint);
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

    // ĐÃ GỠ updateEventValue (20/9/2026): sửa tay GIÁ TRỊ NHẬP của một bước gõ chữ.
    //
    // Nó sinh ra thời recorder chưa đọc được chữ từ DOM Flutter Web. Nay đường đọc có ba nguồn
    // (input còn sống -> node semantics -> snapshot cuối của event input, xem chotEnterText
    // trong GoldenRuntimeService) và đo trên Golden thật ngày 20/9 thì vào đủ, không mất dấu
    // tiếng Việt.
    //
    // Vì sao gỡ hẳn chứ không để đó: bộ chấm chỉ được phép mô tả THỨ NGƯỜI SOẠN THẬT SỰ LÀM
    // trên app. Một ô cho gõ đè nội dung là đường duy nhất để plan nói một đằng còn thao tác
    // trên Golden một nẻo, mà lệch kiểu đó thì Kiểm Golden cũng không bắt được — nó replay
    // đúng cái plan đã lệch. Ghi sai thì xóa bước rồi gõ lại trên Golden.

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
                for (String field : List.of("stage", "attribute", "attributeValue", "valueType", "browser",
                        "uri", "visible")) {
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
                    || "layout_relation".equals(kind)
                    || "component_color".equals(kind)
                    || "widget_state".equals(kind)
                    || "text_style".equals(kind)
                    || "theme_value".equals(kind)
                    || "preferences_observation".equals(kind)
                    || "no_overflow".equals(kind)
                    || "theme_color".equals(kind)
                    || "screen_match".equals(kind)
                    || "route_state".equals(kind)
                    // Ch.7 — thiếu nhánh này thì abstractRecording ÂM THẦM LOẠI BỎ cả 8 event
                    // component_* Chương 7 (rơi qua vòng lặp không action cũng không
                    // checkpoint), scenario sinh ra không hề có tiêu chí nào dù appendEvent đã
                    // lưu đúng vào raw_trace — bug thật phát hiện khi chấm thử 1 bài thật.
                    || CH7_REQUIRED_EXPECT_FIELDS.containsKey(kind)
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

        // Mã nhóm + TÊN luồng là hai thứ duy nhất người soạn gõ (chốt 21/9/2026). Mã luồng —
        // khoá sinh ra test_id và execution_code — máy tự dựng từ chúng. Để người gõ thì luôn có
        // cửa hai luồng trùng mã, mà trùng mã là engine gom chung MỘT lượt replay.
        String maNhom = maNhom(body.containsKey("group_code") || body.containsKey("groupCode")
                ? text(body, "group_code", text(body, "groupCode", ""))
                : (revision == null || revision.getGroupCode() == null ? "" : revision.getGroupCode()));
        String tenLuong = text(body, "name",
                revision == null ? recording.getName() : revision.getName());
        String requestedCode = text(body, "scenario_code", "");
        String code = requestedCode.isBlank()
                ? uniqueScenarioCode(suite.getId(),
                        slug(maNhom.isBlank() ? tenLuong : maNhom + "_" + tenLuong),
                        revision == null ? null : revision.getId())
                : requestedCode.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_-]", "_");
        Optional<BehaviorScenario> codeOwner = scenarios.findBySuiteIdAndScenarioCode(suite.getId(), code);
        if (codeOwner.isPresent() && (revision == null || !codeOwner.get().getId().equals(revision.getId()))) {
            throw new IllegalStateException("scenario_code đã tồn tại trong bộ chấm: " + code);
        }

        BehaviorScenario scenario = revision == null ? new BehaviorScenario() : revision;
        scenario.setSuiteId(suite.getId());
        scenario.setSourceRecordingId(recording.getId());
        scenario.setScenarioCode(code);
        scenario.setGroupCode(maNhom.isBlank() ? null : maNhom);
        scenario.setName(tenLuong);
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
        // ── Giữ điểm và ràng buộc khi sinh lại testcase mà checkpoint KHÔNG đổi ────────────
        // Sửa một dòng giao diện trong Golden là phải upload lại rồi sinh lại testcase cho
        // từng luồng. Trước đây lượt sinh lại ghi đè trắng checkpointsJson, nên công chia điểm
        // và các ràng buộc `requires` mất sạch dù nội dung y hệt — bộ đề nhiều luồng thì chia
        // lại điểm còn lâu hơn ghi hình lại. Cơ chế thừa kế viết 30/8 không cứu được ca này:
        // applyDerivedDatabaseCheckpoints đi tìm "cái cũ" trong chính ô vừa bị ghi đè ở đây,
        // nên lúc nó tìm thì cái cũ đã chết rồi.
        //
        // Luật (chốt 20/9): ĐƯỢC ĂN CẢ NGÃ VỀ KHÔNG và so CHẶT. Danh sách checkpoint mới giống
        // hệt danh sách cũ — cùng số lượng, cùng thứ tự, cùng nội dung tới từng giá trị mong
        // đợi — thì giữ nguyên toàn bộ điểm + requires + id. Lệch một chỗ là reset sạch, vì
        // lúc đó không còn cách nào biết điểm cũ thuộc về tiêu chí nào.
        List<Map<String, Object>> checkpointCu = revision == null
                ? List.<Map<String, Object>>of()
                : readObjectList(revision.getCheckpointsJson());
        double diemMacDinh = Math.max(1.0, checkpoints.size());
        boolean giuNguyenDiem = thuaKeDiemNeuKhongDoi(checkpointCu, checkpoints);
        if (giuNguyenDiem) {
            // Giữ lại checkpoint CSDL cũ để lượt capture ngay sau đây còn chỗ mà thừa kế:
            // applyDerivedDatabaseCheckpoints tra "cái cũ" trong đúng ô checkpointsJson này.
            checkpointCu.stream().filter(this::laCheckpointCsdlTuSinh).forEach(checkpoints::add);
        }
        scenario.setWeight(number(body.get("weight"),
                giuNguyenDiem && revision.getWeight() != null ? revision.getWeight() : diemMacDinh));
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
     * Chép điểm + ràng buộc + id từ danh sách checkpoint cũ sang danh sách vừa dựng lại, CHỈ KHI
     * hai danh sách không khác gì nhau. Trả về true nếu đã chép.
     *
     * So theo THỨ TỰ, không theo tập hợp: đảo chỗ hai checkpoint cũng là một thay đổi thật của
     * bộ đề, và nếu so theo tập hợp thì hai checkpoint trùng nội dung sẽ tranh nhau một điểm.
     *
     * Checkpoint CSDL tự sinh phải loại khỏi phép so: ở thời điểm này chúng chưa có trong danh
     * sách mới (chỉ xuất hiện sau lượt replay Docker), để nguyên thì lần nào cũng ra "khác".
     */
    private boolean thuaKeDiemNeuKhongDoi(List<Map<String, Object>> cu, List<Map<String, Object>> moi) {
        List<Map<String, Object>> cuUi = cu.stream().filter(item -> !laCheckpointCsdlTuSinh(item)).toList();
        if (cuUi.isEmpty() || cuUi.size() != moi.size()) return false;
        for (int i = 0; i < moi.size(); i++) {
            if (!vanTayCheckpoint(cuUi.get(i)).equals(vanTayCheckpoint(moi.get(i)))) return false;
        }
        for (int i = 0; i < moi.size(); i++) {
            Map<String, Object> truoc = cuUi.get(i);
            Map<String, Object> sau = moi.get(i);
            if (truoc.get("weight") != null) sau.put("weight", truoc.get("weight"));
            if (truoc.get("requires") != null) sau.put("requires", truoc.get("requires"));
            // Giữ luôn id cũ: `requires` của checkpoint khác đang trỏ vào đúng chuỗi id này,
            // đổi id mà giữ requires là tự tay làm đứt liên kết cha–con.
            if (!text(truoc, "id", "").isBlank()) sau.put("id", truoc.get("id"));
        }
        return true;
    }

    /**
     * Vân tay nội dung một checkpoint. Loại ba khoá thay vì liệt kê khoá cần so: checkpoint có
     * hàng chục dạng (Ch.7, entity_consistency, database_diff…), liệt kê tay là chắc chắn bỏ sót
     * dạng mới rồi âm thầm coi hai thứ khác nhau là một.
     *
     * `weight` và `requires` là của người soạn — đúng thứ đang đi thừa kế nên không được so.
     * `id` do phép tách đánh số theo thứ tự, không phải nội dung.
     */
    private String vanTayCheckpoint(Map<String, Object> checkpoint) {
        Map<String, Object> rut = new LinkedHashMap<>(checkpoint);
        rut.keySet().removeAll(Set.of("weight", "requires", "id"));
        // Bỏ nốt GIÁ TRỊ CHUẨN do máy đo. Toạ độ, màu, luật lặp, giá trị widget đều do engine
        // đo trên Golden lúc capture rồi nướng vào (xem applyCapturedLayout) — chúng KHÔNG nằm
        // trong raw_trace, nên bản dựng lại từ trace không đời nào có. Để nguyên thì mọi
        // scenario có lấy một tiêu chí vị trí/màu/trạng thái đều "khác" ở mọi lượt sinh lại,
        // và cơ chế thừa kế chết cứng — đo thật 20/9: bên cũ có expect{center_x…}, bên mới
        // không có khoá expect nào.
        //
        // Không phải nới luật so chặt: người soạn chưa từng gõ mấy con số này, và vài giây sau
        // capture sẽ đo lại rồi nướng vào đúng chỗ cũ.
        if (LOAI_NHAN_GIA_TRI_MAY_DO.contains(text(checkpoint, "kind", ""))) {
            Map<String, Object> mongDoi = new LinkedHashMap<>(map(rut.get("expect")));
            mongDoi.keySet().removeAll(KHOA_GIA_TRI_MAY_DO);
            // Rỗng thì phải XOÁ HẲN khoá: bên mới không có `expect` chứ không phải có mà rỗng.
            if (mongDoi.isEmpty()) rut.remove("expect");
            else rut.put("expect", mongDoi);
        }
        return json(chuanHoaSoSanh(rut));
    }

    /**
     * Sắp xếp khoá và quy số về một kiểu để so bằng chuỗi. Bên cũ đọc từ JSON đã lưu, bên mới
     * vừa dựng trong bộ nhớ: cùng một con số có thể là Integer 1 bên này và Double 1.0 bên kia,
     * so thô thì báo "khác" dù giá trị y hệt.
     */
    private Object chuanHoaSoSanh(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> ra = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                ra.put(String.valueOf(entry.getKey()), chuanHoaSoSanh(entry.getValue()));
            }
            return ra;
        }
        if (value instanceof List<?> list) {
            List<Object> ra = new ArrayList<>();
            for (Object item : list) ra.add(chuanHoaSoSanh(item));
            return ra;
        }
        if (value instanceof Number number) return number.doubleValue();
        return value;
    }

    private boolean laCheckpointCsdlTuSinh(Map<String, Object> checkpoint) {
        return Set.of("hidden_output_diff", "hidden_output_consistency")
                .contains(text(checkpoint, "generated_from", ""));
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
        return applyDerivedDatabaseCheckpoints(scenarioId, derived, outputSha256, null);
    }

    /**
     * @param goldenSha256 sha của Golden Solution VỪA DÙNG để replay ra outputSha256 — PHẢI
     *                     ghi lại vào oracle, nếu không publish() sẽ mãi mãi coi oracle này là
     *                     "chưa khớp phiên bản Golden hiện tại" sau khi Golden đổi (bug thật:
     *                     recapture qua updateScenario chỉ cập nhật nội dung oracle chứ không
     *                     đụng golden_sha256, nên oracle cũ "READY" mãi mãi trỏ sha Golden ĐÃ
     *                     THAY vì Golden ĐANG DÙNG). null = giữ nguyên sha cũ (dùng khi gọi
     *                     ngay sau abstractRecording, lúc đó sha vừa được set đúng rồi).
     */
    @Transactional
    public Map<String, Object> applyDerivedDatabaseCheckpoints(String scenarioId,
                                                                List<Map<String, Object>> derived,
                                                                String outputSha256,
                                                                String goldenSha256) {
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
        // bao giờ đạt được: "KHAC" lọt vì nó là một đoạn của "29.300 VND · KHAC ·
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
        if (goldenSha256 != null && !goldenSha256.isBlank()) oracle.setGoldenSha256(goldenSha256);
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
            boolean laQuanHe = "layout_relation".equals(kind);
            boolean laMau = "component_color".equals(kind) || "theme_color".equals(kind);
            // Tiêu chí "có mặt" trước đây không nhận gì từ capture. Nay nó nhận phần LẶP:
            // Golden có sáu nút Xóa thì "có mặt" nghĩa là có ở mọi dòng, không phải có một cái.
            boolean laCoMat = "component_present".equals(kind);
            // Ba loại tiêu chí "đọc một giá trị rồi so": trạng thái widget, kiểu chữ, chủ đề.
            // Chúng dùng CHUNG một kênh giá trị chuẩn (`observed`) nên chỉ có một luật nướng.
            boolean laTrangThai = "preferences_observation".equals(kind)
                    || "widget_state".equals(kind)
                    || "text_style".equals(kind)
                    || "theme_value".equals(kind);
            // Quan he bo cuc (Ch.7 cua main) cung nhan gia tri chuan tu capture.
            if (!laViTri && !laMau && !laTrangThai && !laCoMat && !laQuanHe) continue;
            Map<String, Object> doDuoc = map(components.get(text(checkpoint, "id", "")));
            if (doDuoc.isEmpty()) continue;
            Map<String, Object> mongDoi = new LinkedHashMap<>(map(checkpoint.get("expect")));
            boolean daDoi = false;
            if (laViTri || laMau || laCoMat) {
                Object lap = doDuoc.get("repeat");
                // Bỏ luật lặp cũ khi lượt đo mới chỉ thấy một thể hiện: dữ liệu mẫu đổi mà giữ
                // luật cũ thì bài đúng trượt oan vì "thiếu dòng".
                if (lap == null) {
                    if (mongDoi.remove("repeat") != null) daDoi = true;
                } else {
                    mongDoi.put("repeat", lap);
                    daDoi = true;
                }
            }
            if (laTrangThai) {
                Object giaTri = doDuoc.get("observed");
                // Không đo được (tiêu chí khai sai, widget không có trên màn) thì KHÔNG nướng:
                // để lúc chấm báo "chưa có giá trị chuẩn" còn hơn nướng rỗng rồi cái gì cũng đạt.
                if (giaTri == null) continue;
                mongDoi.put("value", giaTri);
                daDoi = true;
            } else if (laViTri) {
                if (doDuoc.get("center_x") != null && doDuoc.get("center_y") != null) {
                    mongDoi.put("center_x", doDuoc.get("center_x"));
                    mongDoi.put("center_y", doDuoc.get("center_y"));
                    mongDoi.put("width", doDuoc.get("width"));
                    mongDoi.put("height", doDuoc.get("height"));
                    daDoi = true;
                }
            } else if (laQuanHe) {
                String quanHe = text(doDuoc, "relation", "");
                if (!quanHe.isBlank()) {
                    mongDoi.put("relation", quanHe);
                    daDoi = true;
                }
            } else if (laMau) {
                String mau = text(doDuoc, "color", "");
                // Thành phần trong suốt hoàn toàn thì không có màu để so — bỏ qua, để tiêu chí
                // báo "chưa có giá trị chuẩn" còn hơn nướng bừa một màu sai.
                if (!mau.isBlank()) {
                    mongDoi.put("color", mau);
                    daDoi = true;
                }
            }
            if (!daDoi) continue;
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
    public int applyCapturedTargets(String scenarioId, Map<String, Object> nhan,
                                    Map<String, Object> theoDinhDanh) {
        if ((nhan == null || nhan.isEmpty()) && (theoDinhDanh == null || theoDinhDanh.isEmpty())) return 0;
        Map<String, Object> banNhan = nhan == null ? Map.of() : nhan;
        Map<String, Object> banId = theoDinhDanh == null ? Map.of() : theoDinhDanh;
        BehaviorScenario scenario = scenario(scenarioId);
        ensureEditable(suite(scenario.getSuiteId()));
        List<Map<String, Object>> steps = new ArrayList<>(readObjectList(scenario.getStepsJson()));
        int daNuong = 0;
        for (Map<String, Object> step : steps) {
            Map<String, Object> target = new LinkedHashMap<>(map(step.get("target")));
            String maDinhDanh = text(target, "semantic_id", "");
            if (maDinhDanh.isBlank()) maDinhDanh = text(target, "semanticId", "");
            if (!maDinhDanh.isBlank()) {
                // BƯỚC ĐI BẰNG ĐỊNH DANH. Recorder chốt đúng một khoá rồi dừng, nên target
                // không mang nhãn nào — bài quên gắn định danh là bước hỏng và cả lượt chấm
                // sập theo. Nướng đường lui đo trên Golden vào đây: nhãn cho nút có chữ, hình
                // dạng (kiểu widget + mã icon) cho nút chỉ có icon.
                Map<String, Object> lui = map(banId.get(maDinhDanh));
                boolean doi = false;
                // `tooltip` cho nút chỉ có icon nhưng khai tooltip (IconButton, FAB): Flutter không
                // biến tooltip thành nhãn nên engine thu riêng khoá này, tìm bằng find.byTooltip.
                //
                // Số đo MỚI thắng số đo cũ. Bước đi bằng định danh không bao giờ mang nhãn/tooltip
                // gõ tay — recorder chỉ ghi semanticId, khung "Thêm action" chỉ dựng target một
                // khoá. Giá trị đang có chỉ có thể là lần nướng trước, hoặc nhãn ghi hình đời cũ
                // được applyCapturedIdentifiers nâng định danh; cả hai đều đo từ Golden, nên Golden
                // HIỆN TẠI nói gì thì theo đó. Giữ bản cũ thì Golden đổi chữ nút ("Lưu" → "Lưu lại")
                // rồi "Sinh lại toàn bộ" vẫn để đường lui trỏ vào chữ đã chết — recapture không
                // dựng lại bước từ raw_trace nên không có dịp nào khác để sửa.
                // Lần đo KHÔNG báo giá trị (chữ thành trùng, nút hết chữ) thì để nguyên: xoá đi là
                // mất nhãn ghi hình đời cũ của các bước lặp có `index`; để lại thì vô hại, vì bài
                // theo Golden mới không còn chữ đó, còn khớp nhiều widget thì engine đã từ chối.
                for (String khoa : List.of("label", "tooltip")) {
                    String moi = text(lui, khoa, "");
                    if (!moi.isBlank()) {
                        target.put(khoa, moi);
                        doi = true;
                    }
                }
                Map<String, Object> hinh = map(lui.get("shape"));
                if (!hinh.isEmpty()) {
                    target.put("fallback", List.of(hinh));
                    doi = true;
                }
                if (doi) {
                    step.put("target", target);
                    daNuong++;
                    continue;
                }
                // KHÔNG `continue` khi bảng theo định danh không có mục nào: bước mang CẢ hai
                // khoá (nhãn ghi hình trước, định danh nướng vào sau qua applyCapturedIdentifiers)
                // vẫn phải nhận được đường lui theo nhãn như trước. Chặn ở đây là lặng lẽ làm
                // mất đường lui của mọi bộ đề đã nâng cấp định danh kiểu đó.
            }
            String label = text(target, "label", "");
            if (label.isBlank()) continue;
            Map<String, Object> moTa = map(banNhan.get(label));
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

    /**
     * Khoá định vị cũ của một bước, đúng THỨ TỰ engine chọn lúc thu định danh
     * ({@code _thuDinhDanh} trong exam_test.dart). Hai bên phải cùng thứ tự, kẻo bước có cả
     * label lẫn text được engine ghi theo label mà backend lại tra theo text.
     */
    private static final List<String> KHOA_DINH_VI_CU =
            List.of("label", "text", "hint", "tooltip", "text_prefix");

    /**
     * Nướng ĐỊNH DANH (Semantics identifier) đo trên Golden lúc thu oracle vào target của các
     * bước — Gói 2 kế hoạch "Định danh Semantics".
     *
     * Engine ghi mỗi phần tử dạng {label|text|hint|tooltip|text_prefix: giá trị, semantic_id: ...}.
     * Bước nào có cùng khoá và giá trị thì nhận {@code semantic_id}; nhãn cũ GIỮ NGUYÊN cạnh
     * bên làm đường lui, nên bài nộp quên định danh vẫn được tìm bằng nhãn như trước.
     * Nhờ vậy bộ đề đã ghi hình từ trước nhận định danh sau một lần "Sinh lại testcase".
     *
     * KHÔNG ghi đè định danh người soạn đã gõ tay ở khung "Thêm action" (đường 2): tay là
     * ý người, máy chỉ điền chỗ trống. CHỈ vá steps, KHÔNG vá checkpoint (quyết định Q2,
     * 4/9/2026): tiêu chí "màn hình có dòng X" là kiểm NỘI DUNG, đổi sang định danh thì
     * dòng đúng định danh mà sai chữ vẫn đạt.
     */
    @Transactional
    public int applyCapturedIdentifiers(String scenarioId, List<Object> dinhDanh) {
        if (dinhDanh == null || dinhDanh.isEmpty()) return 0;
        BehaviorScenario scenario = scenario(scenarioId);
        ensureEditable(suite(scenario.getSuiteId()));
        Map<String, String> tra = new LinkedHashMap<>();
        for (Object raw : dinhDanh) {
            Map<String, Object> muc = map(raw);
            String id = text(muc, "semantic_id", "");
            if (id.isBlank()) continue;
            for (String khoa : KHOA_DINH_VI_CU) {
                String giaTri = text(muc, khoa, "");
                if (giaTri.isBlank()) continue;
                tra.putIfAbsent(khoa + "=" + giaTri, id);
                break;
            }
        }
        if (tra.isEmpty()) return 0;
        List<Map<String, Object>> steps = new ArrayList<>(readObjectList(scenario.getStepsJson()));
        int daNuong = 0;
        for (Map<String, Object> step : steps) {
            Map<String, Object> target = new LinkedHashMap<>(map(step.get("target")));
            if (target.isEmpty()) continue;
            if (!text(target, "semantic_id", "").isBlank() || !text(target, "semanticId", "").isBlank()) {
                continue;
            }
            String id = null;
            for (String khoa : KHOA_DINH_VI_CU) {
                String giaTri = text(target, khoa, "");
                if (giaTri.isBlank()) continue;
                id = tra.get(khoa + "=" + giaTri);
                break;
            }
            if (id == null) continue;
            // semantic_id đứng đầu để ai đọc JSON cũng thấy nó là khoá chính; nhãn cũ theo sau.
            Map<String, Object> moi = new LinkedHashMap<>();
            moi.put("semantic_id", id);
            moi.putAll(target);
            step.put("target", moi);
            daNuong++;
        }
        if (daNuong == 0) return 0;
        scenario.setStepsJson(json(steps));
        scenarios.save(scenario);
        return daNuong;
    }

    /** Số bước có định danh / tổng bước có target — để màn soạn đề và lúc publish thấy độ phủ. */
    public Map<String, Object> identifierCoverage(List<BehaviorScenario> danhSach) {
        int co = 0;
        int tong = 0;
        for (BehaviorScenario scenario : danhSach) {
            for (Map<String, Object> step : readObjectList(scenario.getStepsJson())) {
                Map<String, Object> target = map(step.get("target"));
                if (target.isEmpty()) continue;
                tong++;
                if (!text(target, "semantic_id", "").isBlank() || !text(target, "semanticId", "").isBlank()) {
                    co++;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("steps_with_identifier", co);
        out.put("steps_total", tong);
        return out;
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
        // Đổi mã nhóm hoặc đổi tên luồng là đổi luôn mã luồng, vì mã luồng dựng từ đúng hai thứ
        // đó. Kéo theo test_id đổi — nên sau khi sửa phải publish lại, và kết quả đã chấm bằng
        // bản cũ không còn đối chiếu được theo id. Người soạn chỉ sửa ở bước soạn nên đổi ở đây
        // rẻ hơn nhiều so với việc để một mã vô nghĩa đi theo bộ đề suốt đời.
        if (body.containsKey("group_code") || body.containsKey("name")) {
            String maNhom = body.containsKey("group_code")
                    ? maNhom(text(body, "group_code", ""))
                    : (scenario.getGroupCode() == null ? "" : scenario.getGroupCode());
            scenario.setGroupCode(maNhom.isBlank() ? null : maNhom);
            scenario.setScenarioCode(uniqueScenarioCode(scenario.getSuiteId(),
                    slug(maNhom.isBlank() ? scenario.getName() : maNhom + "_" + scenario.getName()),
                    scenario.getId()));
        }
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

    /**
     * Duyệt MỌI scenario đang bật để biết cái nào không còn khớp Golden hiện tại.
     *
     * Vì sao cần: sửa một dòng giao diện trong Golden là phải upload lại, và mọi oracle cũ lập
     * tức lệch sha. publish() dừng ngay ở scenario ĐẦU TIÊN lệch rồi ném lỗi, nên bộ chấm mười
     * hai luồng thì người soạn phải bấm publish mười hai lần mới biết hết danh sách phải sửa.
     * Hàm này gom cả danh sách trong một lượt, cùng bộ điều kiện y hệt publish().
     *
     * KHÔNG chạy Docker: thứ chặn publish ở đây là điều kiện tĩnh (thiếu bước, action không hỗ
     * trợ, oracle lệch phiên bản Golden). Phần replay thật đã có nút "Chạy thử trên Golden" lo,
     * và kết quả của nó hiện ở bảng "Tiêu chí chưa đạt".
     */
    public Map<String, Object> scenarioReadiness(String suiteId) {
        BehaviorSuite suite = suite(suiteId);
        GoldenApp app = golden(suite.getGoldenAppId());
        List<BehaviorScenario> enabled = scenarios.findBySuiteIdOrderByDisplayOrderAscCreatedAtAsc(suiteId)
                .stream().filter(row -> Boolean.TRUE.equals(row.getEnabled())).toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> hong = new ArrayList<>();
        for (BehaviorScenario scenario : enabled) {
            List<String> lyDo = new ArrayList<>();
            try {
                validateScenario(scenario, true);
            } catch (RuntimeException e) {
                lyDo.add(e.getMessage());
            }
            boolean oracleKhop = oracles.findByScenarioIdOrderByCreatedAtDesc(scenario.getId()).stream()
                    .anyMatch(row -> row.getStatus() == OracleStatus.READY
                            && Objects.equals(row.getGoldenSha256(), app.getArtifactSha256()));
            if (!oracleKhop) {
                lyDo.add("Oracle chưa khớp bản Golden đang dùng — mở “Sửa thao tác” rồi “Sinh lại testcase”.");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", scenario.getId());
            row.put("scenario_code", scenario.getScenarioCode());
            row.put("name", scenario.getName());
            row.put("ok", lyDo.isEmpty());
            row.put("reasons", lyDo);
            rows.add(row);
            if (!lyDo.isEmpty()) hong.add(scenario.getScenarioCode());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("suite_id", suiteId);
        out.put("golden_sha256", app.getArtifactSha256());
        out.put("golden_ready", app.getStatus() == GoldenAppStatus.READY);
        out.put("total", rows.size());
        out.put("failed", hong);
        out.put("scenarios", rows);
        return out;
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
        // Độ phủ định danh: thông tin, KHÔNG chặn (Q3: không có điểm riêng cho định danh).
        // Bước chưa có định danh vẫn chấm được bằng nhãn; con số này chỉ để người soạn biết
        // bộ đề đã hưởng định danh tới đâu và cần "Sinh lại testcase" kịch bản nào.
        out.put("identifier_coverage", identifierCoverage(enabled));
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
        for (int index = 0; index < steps.size(); index++) {
            Map<String, Object> step = steps.get(index);
            String action = text(step, "action", "");
            if (!ACTIONS.contains(action)) {
                throw new IllegalArgumentException("Scenario " + scenario.getScenarioCode() + " có action không hỗ trợ: " + action);
            }
            if ("boot_with_uri".equals(action) && index != 0) {
                throw new IllegalArgumentException(
                        "Scenario " + scenario.getScenarioCode() + " phải đặt boot_with_uri ở bước đầu tiên");
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

    private void validateRouteState(Map<String, Object> event) {
        Map<String, Object> expect = map(event.get("expect"));
        boolean hasExpectation = List.of(
                        "uri", "path", "fragment", "can_pop", "can_forward", "history_length", "history_index")
                .stream().anyMatch(key -> event.containsKey(key) || expect.containsKey(key));
        hasExpectation = hasExpectation || event.containsKey("query") || expect.containsKey("query");
        if (!hasExpectation) {
            throw new IllegalArgumentException(
                    "Checkpoint route phải khai uri/path/query/fragment/can_pop hoặc history");
        }
        String uri = optional(expect, "uri");
        if (uri == null) uri = optional(event, "uri");
        if (uri != null) validateRouteUri(uri);
        event.putIfAbsent("checkpoint", true);
        event.putIfAbsent("scope", "navigation");
        event.putIfAbsent("stage", "ASSERT");
        event.putIfAbsent("action", "observe_route");
        event.putIfAbsent("browser", "flutter_tester");
    }

    private void validateLayoutRelation(Map<String, Object> event) {
        if (map(event.get("target")).isEmpty() || map(event.get("relative_to")).isEmpty()) {
            throw new IllegalArgumentException(
                    "Tiêu chí quan hệ bố cục phải có target và relative_to");
        }
        String relation = text(event, "relation", "auto").toLowerCase(Locale.ROOT);
        if (!Set.of("auto", "above", "below", "left_of", "right_of", "same_row",
                "same_column", "inside", "contains", "overlap", "not_overlap",
                "wider_than", "taller_than").contains(relation)) {
            throw new IllegalArgumentException("Quan hệ bố cục không hỗ trợ: " + relation);
        }
        double tolerance = number(event.get("tolerance_pct"), 5);
        if (tolerance < 0 || tolerance > 50) {
            throw new IllegalArgumentException("Sai số quan hệ bố cục phải từ 0 đến 50%");
        }
        event.put("relation", relation);
        event.putIfAbsent("tolerance_pct", 5);
        event.putIfAbsent("checkpoint", true);
        event.putIfAbsent("scope", "ui");
        event.putIfAbsent("stage", "ASSERT");
        event.putIfAbsent("action", "observe_ui");
        event.putIfAbsent("browser", "flutter_tester");
    }

    /**
     * Route dùng trong bộ chấm có thể là path nội bộ hoặc URI http(s). Không cho scheme
     * khác vì widget-test không được phép mở intent/file bên ngoài sandbox.
     */
    private void validateRouteUri(String raw) {
        if (raw.chars().anyMatch(Character::isISOControl) || raw.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("URI/path không được chứa khoảng trắng hoặc ký tự điều khiển");
        }
        try {
            URI uri = new URI(raw);
            String scheme = uri.getScheme();
            if (scheme != null && !scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
                throw new IllegalArgumentException("URI chỉ nhận path nội bộ, http hoặc https");
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("URI/path không hợp lệ: " + raw, e);
        }
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
        out.put("group_code", scenario.getGroupCode() == null ? "" : scenario.getGroupCode());
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
     * Đổi cấu hình runtime (driver, timeout, api_base_url…) thì oracle đã capture không còn tin
     * được, phải record/capture lại.
     *
     * <p>Trước đây runtime_config còn giữ một danh sách package riêng và field đó được loại khỏi
     * phép so vì nó không đổi hành vi Golden. Danh sách ấy đã bỏ (19/9) — cổng chặn bài sinh viên
     * đọc contract.json, mà contract.json sinh thẳng từ dependencies của Golden.
     */
    private boolean runtimeConfigChangeInvalidatesReplay(BehaviorSuite suite, Map<String, Object> body) {
        if (!body.containsKey("runtime_config")) return false;
        Object raw = body.get("runtime_config");
        Map<String, Object> incoming = raw instanceof String s ? readObject(s) : map(raw);
        Map<String, Object> current = readObject(suite.getRuntimeConfigJson());
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
                "locator_priority", List.of("semantic_id", "accessibility_label", "role_text", "structure"),
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
                "api_base_url", "http://mock-api:8080");
    }

    private Map<String, Object> defaultViewport() {
        return Map.of("id", "desktop", "width", 1280, "height", 800, "device_pixel_ratio", 1.0);
    }



    private String locatorAttribute(Map<String, Object> target) {
        for (String key : List.of("semanticId", "semantic_id",
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
        return uniqueScenarioCode(suiteId, base, null);
    }

    /**
     * @param boQuaScenarioId luồng được phép giữ mã đó mà không tính là đụng độ — chính nó.
     *                        Thiếu tham số này thì mỗi lượt "Sinh lại testcase" của một luồng
     *                        không đổi tên lại mọc thêm hậu tố "_2", và test_id trôi theo.
     */
    private String uniqueScenarioCode(String suiteId, String base, String boQuaScenarioId) {
        String value = base.isBlank() ? "SCENARIO" : base;
        String candidate = value;
        int suffix = 2;
        while (true) {
            Optional<BehaviorScenario> chu = scenarios.findBySuiteIdAndScenarioCode(suiteId, candidate);
            if (chu.isEmpty() || chu.get().getId().equals(boQuaScenarioId)) return candidate;
            candidate = value + "_" + suffix++;
        }
    }

    /** Chuẩn hoá mã nhóm. Chuỗi rỗng nghĩa là luồng KHÔNG thuộc nhóm nào — đó là trạng thái hợp lệ. */
    private String maNhom(String value) {
        String s = boDau(value == null ? "" : value).toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_-]+", "_").replaceAll("^[_-]+|[_-]+$", "");
        return s.length() > 50 ? s.substring(0, 50) : s;
    }

    private String slug(String value) {
        String slug = value == null ? "" : boDau(value).toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        return slug.length() > 90 ? slug.substring(0, 90) : slug;
    }

    /**
     * Bỏ dấu tiếng Việt trước khi dựng mã. Không bỏ thì "lọc học tập" ra "L_C_H_C_T_P" — mã rác,
     * mà mã này đi thẳng vào test_id, thứ người chấm đọc trên bảng điểm.
     */
    private static String boDau(String value) {
        String tach = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return tach.replace('đ', 'd').replace('Đ', 'D');
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
