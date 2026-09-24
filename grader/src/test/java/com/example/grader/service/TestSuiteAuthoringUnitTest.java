package com.example.grader.service;

import com.example.grader.entity.BehaviorScenario;
import com.example.grader.entity.BehaviorSuite;
import com.example.grader.entity.BehaviorSuiteStatus;
import com.example.grader.entity.GoldenApp;
import com.example.grader.entity.GoldenAppStatus;
import com.example.grader.entity.GoldenRecording;
import com.example.grader.entity.GoldenValidationRun;
import com.example.grader.entity.GoldenValidationStatus;
import com.example.grader.entity.OracleSnapshot;
import com.example.grader.entity.OracleStatus;
import com.example.grader.entity.RecordingStatus;
import com.example.grader.repository.BehaviorScenarioRepository;
import com.example.grader.repository.BehaviorSuiteRepository;
import com.example.grader.repository.GoldenAppRepository;
import com.example.grader.repository.GoldenRecordingRepository;
import com.example.grader.repository.GoldenValidationRunRepository;
import com.example.grader.repository.OracleSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Module TestSuiteAuthoring — các sheet UT01…UT10.
 *
 * <p>Toàn bộ khâu giảng viên soạn bộ chấm. Điểm chung của mọi cổng chặn ở đây: nếu để lọt, cái
 * giá phải trả KHÔNG hiện ra ngay — bộ đề vẫn publish, vẫn xuất gói, tới lúc chấm hàng loạt mới
 * sai hàng loạt. Nên phần lớn ca kiểm dưới đây nhắm vào lúc hàm phải TỪ CHỐI, không phải lúc
 * hàm chạy trôi.
 */
class TestSuiteAuthoringUnitTest {

    private GoldenAppRepository goldenApps;
    private BehaviorSuiteRepository suites;
    private BehaviorScenarioRepository scenarios;
    private GoldenRecordingRepository recordings;
    private OracleSnapshotRepository oracles;
    private GoldenValidationRunRepository validationRuns;
    private ExamService exams;
    private BehaviorAuthoringService soan;

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String SHA_GOLDEN = "sha-golden-v1";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void dungKhoGia() {
        goldenApps = mock(GoldenAppRepository.class);
        suites = mock(BehaviorSuiteRepository.class);
        scenarios = mock(BehaviorScenarioRepository.class);
        recordings = mock(GoldenRecordingRepository.class);
        oracles = mock(OracleSnapshotRepository.class);
        validationRuns = mock(GoldenValidationRunRepository.class);
        exams = mock(ExamService.class);

        // Kho giả phải làm thay việc của @PrePersist: id, trạng thái mặc định và mốc thời gian
        // vốn do JPA điền lúc ghi thật. Không làm thì object vừa tạo có status = null và mọi
        // view() đọc .name() sẽ nổ — lỗi của đồ dựng test, không phải của code đang kiểm.
        when(suites.save(any())).thenAnswer(i -> nhuJpa((BehaviorSuite) i.getArgument(0)));
        when(scenarios.save(any())).thenAnswer(i -> nhuJpa((BehaviorScenario) i.getArgument(0)));
        when(recordings.save(any())).thenAnswer(i -> nhuJpa((GoldenRecording) i.getArgument(0)));
        when(oracles.save(any())).thenAnswer(i -> i.getArgument(0));
        when(goldenApps.save(any())).thenAnswer(i -> i.getArgument(0));
        when(scenarios.findBySuiteIdOrderByDisplayOrderAscCreatedAtAsc(anyString())).thenReturn(List.of());
        when(recordings.findBySuiteIdOrderByStartedAtDesc(anyString())).thenReturn(List.of());
        when(oracles.findByScenarioIdOrderByCreatedAtDesc(anyString())).thenReturn(new ArrayList<>());
        when(scenarios.findBySuiteIdAndScenarioCode(anyString(), anyString())).thenReturn(Optional.empty());
        // Ảnh chấm có sẵn 4 gói — dùng cho phép kiểm allowed_packages lúc tạo bộ.
        when(exams.goiCoTrongAnhCham()).thenReturn(Set.of("flutter", "flutter_test", "intl", "sqflite"));

        soan = new BehaviorAuthoringService(goldenApps, suites, scenarios, recordings,
                oracles, validationRuns, exams);
        app("APP_OK", GoldenAppStatus.READY);   // hầu hết ca dùng Golden App sẵn sàng
    }

    // ── Đồ dựng sẵn ────────────────────────────────────────────────────

    private static BehaviorSuite nhuJpa(BehaviorSuite s) {
        if (s.getId() == null) s.setId(java.util.UUID.randomUUID().toString());
        if (s.getStatus() == null) s.setStatus(BehaviorSuiteStatus.DRAFT);
        if (s.getRevision() == null) s.setRevision(1);
        if (s.getCreatedAt() == null) s.setCreatedAt(java.time.Instant.now());
        s.setUpdatedAt(java.time.Instant.now());
        return s;
    }

    private static GoldenRecording nhuJpa(GoldenRecording r) {
        if (r.getId() == null) r.setId(java.util.UUID.randomUUID().toString());
        if (r.getStatus() == null) r.setStatus(RecordingStatus.ACTIVE);
        if (r.getSeed() == null) r.setSeed(java.util.UUID.randomUUID().toString());
        if (r.getRawTraceJson() == null) r.setRawTraceJson("[]");
        if (r.getStartedAt() == null) r.setStartedAt(java.time.Instant.now());
        return r;
    }

    private static BehaviorScenario nhuJpa(BehaviorScenario sc) {
        if (sc.getId() == null) sc.setId(java.util.UUID.randomUUID().toString());
        if (sc.getDisplayOrder() == null) sc.setDisplayOrder(0);
        if (sc.getWeight() == null) sc.setWeight(1.0);
        if (sc.getEnabled() == null) sc.setEnabled(true);
        if (sc.getCreatedAt() == null) sc.setCreatedAt(java.time.Instant.now());
        sc.setUpdatedAt(java.time.Instant.now());
        return sc;
    }

    private GoldenApp app(String id, GoldenAppStatus trangThai) {
        GoldenApp a = new GoldenApp();
        a.setId(id);
        a.setName("Golden " + id);
        a.setVersion("1");
        a.setPlatform("WEB");
        a.setStatus(trangThai);
        a.setArtifactSha256(SHA_GOLDEN);
        when(goldenApps.findById(id)).thenReturn(Optional.of(a));
        return a;
    }

    private BehaviorSuite suite(String id, BehaviorSuiteStatus trangThai, String appId) {
        BehaviorSuite s = new BehaviorSuite();
        s.setId(id);
        s.setSuiteCode("PE_PRM393_FA26");
        s.setName("PE Quản lý chi tiêu");
        s.setGoldenAppId(appId);
        s.setSchemaVersion(BehaviorAuthoringService.SCHEMA_VERSION);
        s.setRevision(1);
        s.setStatus(trangThai);
        s.setRuntimeConfigJson("{}");
        s.setPublicContractJson("{}");
        s.setDatabaseContractJson("{}");
        when(suites.findById(id)).thenReturn(Optional.of(s));
        return s;
    }

    private GoldenRecording phien(String id, RecordingStatus trangThai, String suiteId, String traceJson) {
        GoldenRecording r = new GoldenRecording();
        r.setId(id);
        r.setSuiteId(suiteId);
        r.setGoldenAppId("APP_OK");
        r.setName("Thêm khoản chi");
        r.setSeed("seed-abc-123");
        r.setStatus(trangThai);
        r.setViewportJson("{\"width\":1280,\"height\":800}");
        r.setInitialStateJson("{\"reset_storage\":true}");
        r.setRawTraceJson(traceJson);
        when(recordings.findById(id)).thenReturn(Optional.of(r));
        when(recordings.findByIdForUpdate(id)).thenReturn(Optional.of(r));
        return r;
    }

    private BehaviorScenario kichBan(String id, String suiteId, double weight, boolean bat) {
        BehaviorScenario sc = new BehaviorScenario();
        sc.setId(id);
        sc.setSuiteId(suiteId);
        sc.setScenarioCode(id);
        sc.setName("Luồng " + id);
        sc.setSkillCode("ADD");
        sc.setDisplayOrder(1);
        sc.setWeight(weight);
        sc.setEnabled(bat);
        sc.setVariablesJson("{}");
        sc.setInitialStateJson("{}");
        sc.setStepsJson("[{\"action\":\"boot\"},{\"action\":\"tap\"}]");
        sc.setCheckpointsJson("[{\"kind\":\"checkpoint\",\"weight\":1.0}]");
        sc.setViewportsJson("[{\"width\":1280,\"height\":800}]");
        when(scenarios.findById(id)).thenReturn(Optional.of(sc));
        return sc;
    }

    private OracleSnapshot oracle(String scenarioId, OracleStatus trangThai, String sha) {
        OracleSnapshot o = new OracleSnapshot();
        o.setId("OR_" + scenarioId);
        o.setScenarioId(scenarioId);
        o.setGoldenAppId("APP_OK");
        o.setGoldenSha256(sha);
        o.setSeed("seed-abc-123");
        o.setStatus(trangThai);
        o.setInputJson("{}");
        o.setUiObservationJson("{}");
        o.setDatabaseObservationJson("{}");
        return o;
    }

    private Map<String, Object> than(Object... k) {
        Map<String, Object> b = new LinkedHashMap<>();
        for (int i = 0; i < k.length; i += 2) if (k[i + 1] != null) b.put(String.valueOf(k[i]), k[i + 1]);
        return b;
    }

    private String json(Object v) {
        try { return mapper.writeValueAsString(v); } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ══════════════════ UT01 — createSuite ══════════════════

    private Map<String, Object> thanTaoBo(String code) {
        return than("suite_code", code, "golden_app_id", "APP_OK", "name", "PE Quản lý chi tiêu");
    }

    static Stream<Arguments> ut01_maHopLe() {
        return Stream.of(
                Arguments.of("UTCID01", "PE_PRM393_FA26", "PE_PRM393_FA26"),
                Arguments.of("UTCID02", "AB1", "AB1"),                        // biên dưới: 3 ký tự
                Arguments.of("UTCID04", "A".repeat(80), "A".repeat(80)),      // biên trên: 80 ký tự
                Arguments.of("UTCID07", "pe_prm393", "PE_PRM393")             // tự viết hoa
        );
    }

    @ParameterizedTest(name = "UT01 {0}: suite_code={1}")
    @MethodSource("ut01_maHopLe")
    @DisplayName("UT01 — mã hợp lệ tạo được bộ ở trạng thái DRAFT, mã luôn được viết hoa")
    void ut01_maHopLe(String utcid, String code, String mongDoi) {
        app("APP_OK", GoldenAppStatus.READY);
        when(suites.existsBySuiteCode(anyString())).thenReturn(false);

        Map<String, Object> ra = soan.createSuite(thanTaoBo(code));

        assertEquals(mongDoi, ra.get("suite_code"), utcid);
        assertEquals("DRAFT", ra.get("status"), utcid + ": bộ mới luôn ở DRAFT");
        assertEquals(BehaviorAuthoringService.SCHEMA_VERSION, ra.get("schema_version"), utcid);
    }

    static Stream<Arguments> ut01_maSai() {
        return Stream.of(
                Arguments.of("UTCID03", "AB"),                 // 2 ký tự — dưới biên
                Arguments.of("UTCID05", "A".repeat(81)),       // 81 ký tự — trên biên
                Arguments.of("UTCID06", "_PE01")               // ký tự đầu không phải A-Z0-9
        );
    }

    @ParameterizedTest(name = "UT01 {0}: suite_code={1} bị loại")
    @MethodSource("ut01_maSai")
    @DisplayName("UT01 — mã sai định dạng bị loại, lời báo nêu đúng luật")
    void ut01_maSaiDinhDang(String utcid, String code) {
        app("APP_OK", GoldenAppStatus.READY);
        when(suites.existsBySuiteCode(anyString())).thenReturn(false);

        assertEquals("suite_code chỉ gồm A-Z, 0-9, _ hoặc -, dài 3-80 ký tự",
                assertThrows(IllegalArgumentException.class,
                        () -> soan.createSuite(thanTaoBo(code))).getMessage(), utcid);
        verify(suites, never()).save(any());
    }

    @Test
    @DisplayName("UT01 UTCID08 — mã trùng bị chặn trước khi ghi")
    void ut01_utcid08_maTrung() {
        app("APP_OK", GoldenAppStatus.READY);
        when(suites.existsBySuiteCode("DA_TON_TAI")).thenReturn(true);

        assertEquals("Mã bộ chấm đã tồn tại: DA_TON_TAI",
                assertThrows(IllegalStateException.class,
                        () -> soan.createSuite(thanTaoBo("DA_TON_TAI"))).getMessage());
        verify(suites, never()).save(any());
    }

    @Test
    @DisplayName("UT01 UTCID09/12 — thiếu suite_code hoặc thiếu name đều bị chặn")
    void ut01_utcid09_12_thieuTruong() {
        app("APP_OK", GoldenAppStatus.READY);
        when(suites.existsBySuiteCode(anyString())).thenReturn(false);

        assertEquals("Thiếu trường suite_code",
                assertThrows(IllegalArgumentException.class, () -> soan.createSuite(
                        than("golden_app_id", "APP_OK", "name", "x"))).getMessage(), "UTCID09");

        assertEquals("Thiếu trường name",
                assertThrows(IllegalArgumentException.class, () -> soan.createSuite(
                        than("suite_code", "PE_01", "golden_app_id", "APP_OK"))).getMessage(), "UTCID12");
    }

    @Test
    @DisplayName("UT01 UTCID10 — Golden App DISABLED không dùng để tạo bộ được")
    void ut01_utcid10_goldenAppDisabled() {
        app("APP_OFF", GoldenAppStatus.DISABLED);
        when(suites.existsBySuiteCode(anyString())).thenReturn(false);

        assertEquals("Golden App không thể dùng để tạo bộ chấm: DISABLED",
                assertThrows(IllegalStateException.class, () -> soan.createSuite(
                        than("suite_code", "PE_01", "golden_app_id", "APP_OFF", "name", "x"))).getMessage());
    }

    @Test
    @DisplayName("UT01 UTCID11 — golden_app_id không tồn tại bị chặn")
    void ut01_utcid11_goldenAppKhongTonTai() {
        when(suites.existsBySuiteCode(anyString())).thenReturn(false);
        when(goldenApps.findById("KHONG_CO")).thenReturn(Optional.empty());

        assertEquals("Không tìm thấy Golden App: KHONG_CO",
                assertThrows(IllegalArgumentException.class, () -> soan.createSuite(
                        than("suite_code", "PE_01", "golden_app_id", "KHONG_CO", "name", "x"))).getMessage());
    }

    @Test
    @DisplayName("UT01 UTCID13 — allowed_packages nằm trong ảnh chấm thì cho qua")
    void ut01_utcid13_goiCoTrongAnh() {
        app("APP_OK", GoldenAppStatus.READY);
        when(suites.existsBySuiteCode(anyString())).thenReturn(false);
        Map<String, Object> than = thanTaoBo("PE_01");
        than.put("runtime_config", Map.of("allowed_packages", List.of("intl")));

        assertDoesNotThrow(() -> soan.createSuite(than));
    }

    /**
     * Sheet ghi ca này là "khai package ảnh chấm không có thì chặn". Cổng đó đã bỏ ngày 19/9:
     * runtime_config không còn giữ danh sách package riêng nữa, nên khai gì ở đây cũng chỉ là
     * cấu hình chết. Phép kiểm package thật đã dời sang contract.json — mà contract.json sinh
     * thẳng từ dependencies của Golden — và chạy ở khâu NẠP GÓI bên người chấm
     * (BanGiaoService.nhapGoi → ExamService.goiCoTrongAnhCham).
     */
    @Test
    @DisplayName("UT01 UTCID14 — allowed_packages không còn là cổng chặn lúc tạo bộ")
    void ut01_utcid14_goiKhongCoTrongAnh() {
        app("APP_OK", GoldenAppStatus.READY);
        when(suites.existsBySuiteCode(anyString())).thenReturn(false);
        Map<String, Object> than = thanTaoBo("PE_01");
        than.put("runtime_config", Map.of("allowed_packages", List.of("dio")));

        assertDoesNotThrow(() -> soan.createSuite(than),
                "gói lạ trong runtime_config không được chặn ở đây nữa — cổng thật nằm ở khâu nạp gói");
    }

    /** Không đọc được ảnh (Docker tắt) thì KHÔNG được chặn — "không biết" khác "không có". */
    @Test
    @DisplayName("UT01 — Docker tắt thì phép kiểm package phải nhường, không chặn oan")
    void ut01_dockerTatThiKhongChan() {
        app("APP_OK", GoldenAppStatus.READY);
        when(suites.existsBySuiteCode(anyString())).thenReturn(false);
        when(exams.goiCoTrongAnhCham()).thenReturn(Set.of());
        Map<String, Object> than = thanTaoBo("PE_01");
        than.put("runtime_config", Map.of("allowed_packages", List.of("dio")));

        assertDoesNotThrow(() -> soan.createSuite(than));
    }

    // ══════════════════ UT02 — startRecording ══════════════════

    @Test
    @DisplayName("UT02 UTCID01/02 — mở phiên trên bộ DRAFT hoặc REVIEW, suite chuyển sang RECORDING")
    void ut02_utcid01_02_moPhien() {
        app("APP_OK", GoldenAppStatus.READY);
        for (BehaviorSuiteStatus tt : List.of(BehaviorSuiteStatus.DRAFT, BehaviorSuiteStatus.REVIEW)) {
            BehaviorSuite s = suite("S_" + tt, tt, "APP_OK");
            when(recordings.countBySuiteIdAndStatus("S_" + tt, RecordingStatus.ACTIVE)).thenReturn(0L);
            when(scenarios.countBySuiteIdAndEnabledTrue("S_" + tt)).thenReturn(0L);

            Map<String, Object> ra = soan.startRecording("S_" + tt, than("name", "Thêm khoản chi"));

            assertEquals("ACTIVE", ra.get("status"), tt.name());
            assertEquals(List.of(), ra.get("raw_trace"), tt + ": phiên mới phải có trace rỗng");
            assertNotNull(ra.get("seed"), tt + ": seed phải được sinh");
            assertEquals(BehaviorSuiteStatus.RECORDING, s.getStatus(), tt.name());
        }
    }

    @Test
    @DisplayName("UT02 UTCID04 — suiteId không tồn tại bị chặn")
    void ut02_utcid04_suiteKhongTonTai() {
        when(suites.findById("KHONG_CO")).thenReturn(Optional.empty());

        assertEquals("Không tìm thấy bộ chấm hành vi: KHONG_CO",
                assertThrows(IllegalArgumentException.class,
                        () -> soan.startRecording("KHONG_CO", than())).getMessage());
    }

    @Test
    @DisplayName("UT02 UTCID05/06 — Golden App không READY thì không mở phiên được")
    void ut02_utcid05_06_goldenAppChuaSanSang() {
        for (GoldenAppStatus tt : List.of(GoldenAppStatus.REGISTERED, GoldenAppStatus.DISABLED)) {
            app("APP_" + tt, tt);
            suite("S_" + tt, BehaviorSuiteStatus.DRAFT, "APP_" + tt);

            assertEquals("Golden App không còn READY",
                    assertThrows(IllegalStateException.class,
                            () -> soan.startRecording("S_" + tt, than())).getMessage(), tt.name());
        }
    }

    @Test
    @DisplayName("UT02 UTCID07 — đang có phiên ACTIVE thì không mở thêm phiên thứ hai")
    void ut02_utcid07_daCoPhienActive() {
        app("APP_OK", GoldenAppStatus.READY);
        suite("S1", BehaviorSuiteStatus.RECORDING, "APP_OK");
        when(recordings.countBySuiteIdAndStatus("S1", RecordingStatus.ACTIVE)).thenReturn(1L);

        assertEquals("Bộ chấm đang có một phiên record chưa kết thúc",
                assertThrows(IllegalStateException.class,
                        () -> soan.startRecording("S1", than())).getMessage());
    }

    @Test
    @DisplayName("UT02 UTCID08/09 — thiếu name thì tự đặt \"Luồng N\"; viewport mặc định 1280×800")
    void ut02_utcid08_09_macDinh() {
        app("APP_OK", GoldenAppStatus.READY);
        suite("S1", BehaviorSuiteStatus.DRAFT, "APP_OK");
        when(recordings.countBySuiteIdAndStatus("S1", RecordingStatus.ACTIVE)).thenReturn(0L);
        when(scenarios.countBySuiteIdAndEnabledTrue("S1")).thenReturn(2L);

        Map<String, Object> ra = soan.startRecording("S1", than());

        assertEquals("Luồng 3", ra.get("name"), "UTCID08");
        assertEquals(Map.of("id", "desktop", "width", 1280, "height", 800, "device_pixel_ratio", 1.0),
                ra.get("viewport"), "UTCID09");

        Map<String, Object> raRieng = soan.startRecording("S1",
                than("viewport", Map.of("width", 390, "height", 844)));
        assertEquals(Map.of("width", 390, "height", 844), raRieng.get("viewport"),
                "UTCID09: khai viewport riêng thì phải dùng đúng cái đó");
    }

    /**
     * Sheet UT02 UTCID03 ghi bộ PUBLISHED sẽ bị chặn — code thật KHÔNG chặn: ensureEditable chỉ
     * khoá DISABLED. Ghim đúng hành vi thật ở đây, còn ô trong sheet cần sửa lại.
     */
    @Test
    @DisplayName("UT02 UTCID03 — chỉ bộ DISABLED mới bị khoá (bộ PUBLISHED vẫn record lại được)")
    void ut02_utcid03_chiDisabledMoiBiKhoa() {
        app("APP_OK", GoldenAppStatus.READY);
        suite("S_OFF", BehaviorSuiteStatus.DISABLED, "APP_OK");

        assertEquals("Bộ chấm đã bị vô hiệu hoá",
                assertThrows(IllegalStateException.class,
                        () -> soan.startRecording("S_OFF", than())).getMessage());

        suite("S_PUB", BehaviorSuiteStatus.PUBLISHED, "APP_OK");
        when(recordings.countBySuiteIdAndStatus("S_PUB", RecordingStatus.ACTIVE)).thenReturn(0L);
        assertDoesNotThrow(() -> soan.startRecording("S_PUB", than()),
                "bộ đã publish vẫn mở phiên record lại được — đây là đường sửa bộ đề");
    }

    // ══════════════════ UT03 — appendEvent ══════════════════

    private Map<String, Object> thaoTacHopLe() {
        return than("kind", "action", "action", "tap",
                "target", Map.of("semantic_id", "chi_tieu.them"));
    }

    @Test
    @DisplayName("UT03 UTCID01 — thao tác hợp lệ được ghi kèm các trường suy ra")
    void ut03_utcid01_thaoTacHopLe() {
        suite("S1", BehaviorSuiteStatus.RECORDING, "APP_OK");
        phien("R1", RecordingStatus.ACTIVE, "S1", "[]");

        Map<String, Object> ra = soan.appendEvent("R1", thaoTacHopLe());

        assertEquals(1, ra.get("event_count"));
        Map<?, ?> ev = (Map<?, ?>) ra.get("event");
        assertEquals(1, ev.get("sequence"));
        assertEquals("ACTION", ev.get("stage"));
        assertEquals("flutter_tester", ev.get("browser"));
        assertEquals("semantic_id", ev.get("attribute"));
        assertEquals("chi_tieu.them", ev.get("attributeValue"));
    }

    static Stream<Arguments> ut03_tieuChiKhongCanTarget() {
        return Stream.of(
                Arguments.of("UTCID03", "theme_color"),
                Arguments.of("UTCID04", "screen_match")
        );
    }

    @ParameterizedTest(name = "UT03 {0}: kind={1} không cần target")
    @MethodSource("ut03_tieuChiKhongCanTarget")
    @DisplayName("UT03 — tiêu chí của cả app không gắn với thành phần nào nên không đòi target")
    void ut03_tieuChiKhongCanTarget(String utcid, String kind) {
        suite("S1", BehaviorSuiteStatus.RECORDING, "APP_OK");
        phien("R1", RecordingStatus.ACTIVE, "S1", "[]");

        assertDoesNotThrow(() -> soan.appendEvent("R1", than("kind", kind)), utcid);
    }

    @Test
    @DisplayName("UT03 UTCID02 — component_color phải có target và được gán sai số mặc định 5%")
    void ut03_utcid02_componentColor() {
        suite("S1", BehaviorSuiteStatus.RECORDING, "APP_OK");
        phien("R1", RecordingStatus.ACTIVE, "S1", "[]");

        Map<String, Object> ra = soan.appendEvent("R1", than("kind", "component_color",
                "target", Map.of("semantic_id", "chi_tieu.tong"), "khung", "desktop"));

        Map<?, ?> ev = (Map<?, ?>) ra.get("event");
        assertEquals(5, ev.get("tolerance_pct"),
                "5% ≈ ±13/255 mỗi kênh — đủ chặt để lệch một nấc Material shade vẫn bị bắt");
        assertEquals("desktop", ev.get("khung"));
        assertEquals(true, ev.get("checkpoint"));
    }

    static Stream<Arguments> ut03_phienKhongGhiDuoc() {
        return Stream.of(
                Arguments.of("UTCID05", RecordingStatus.STOPPED),
                Arguments.of("UTCID06", RecordingStatus.FAILED)
        );
    }

    @ParameterizedTest(name = "UT03 {0}: phiên {1}")
    @MethodSource("ut03_phienKhongGhiDuoc")
    @DisplayName("UT03 — chỉ phiên ACTIVE mới nhận event")
    void ut03_phienKhongGhiDuoc(String utcid, RecordingStatus tt) {
        suite("S1", BehaviorSuiteStatus.RECORDING, "APP_OK");
        phien("R1", tt, "S1", "[]");

        assertEquals("Chỉ có thể ghi event vào phiên ACTIVE",
                assertThrows(IllegalStateException.class,
                        () -> soan.appendEvent("R1", thaoTacHopLe())).getMessage(), utcid);
    }

    @Test
    @DisplayName("UT03 UTCID07/08 — biên 2000 event: 1999 còn ghi được, 2000 thì dừng")
    void ut03_utcid07_08_bienSoEvent() {
        suite("S1", BehaviorSuiteStatus.RECORDING, "APP_OK");
        List<Map<String, Object>> trace = new ArrayList<>();
        for (int i = 0; i < 1999; i++) trace.add(Map.of("kind", "action", "action", "boot"));

        phien("R_1999", RecordingStatus.ACTIVE, "S1", json(trace));
        assertDoesNotThrow(() -> soan.appendEvent("R_1999", thaoTacHopLe()), "UTCID07");

        trace.add(Map.of("kind", "action", "action", "boot"));
        phien("R_2000", RecordingStatus.ACTIVE, "S1", json(trace));
        assertEquals("Phiên record vượt quá 2000 event",
                assertThrows(IllegalStateException.class,
                        () -> soan.appendEvent("R_2000", thaoTacHopLe())).getMessage(), "UTCID08");
    }

    @Test
    @DisplayName("UT03 UTCID09/10 — kind hoặc action không hỗ trợ đều bị chặn ngay tại cửa")
    void ut03_utcid09_10_kindVaActionKhongHoTro() {
        suite("S1", BehaviorSuiteStatus.RECORDING, "APP_OK");
        phien("R1", RecordingStatus.ACTIVE, "S1", "[]");

        assertEquals("Loại event không hỗ trợ: khong_ton_tai",
                assertThrows(IllegalArgumentException.class,
                        () -> soan.appendEvent("R1", than("kind", "khong_ton_tai"))).getMessage(), "UTCID09");

        assertEquals("Action không hỗ trợ: nhay_mua",
                assertThrows(IllegalArgumentException.class, () -> soan.appendEvent("R1",
                        than("kind", "action", "action", "nhay_mua",
                                "target", Map.of("semantic_id", "x")))).getMessage(), "UTCID10");
    }

    @Test
    @DisplayName("UT03 UTCID11 — thao tác cần target mà thiếu target thì replay không làm gì được")
    void ut03_utcid11_thieuTarget() {
        suite("S1", BehaviorSuiteStatus.RECORDING, "APP_OK");
        phien("R1", RecordingStatus.ACTIVE, "S1", "[]");

        assertEquals("Action tap phải có target ngữ nghĩa",
                assertThrows(IllegalArgumentException.class,
                        () -> soan.appendEvent("R1", than("kind", "action", "action", "tap"))).getMessage());
    }

    @Test
    @DisplayName("UT03 UTCID12/13 — khung nhận rỗng hoặc \"desktop\" (không phân biệt hoa thường)")
    void ut03_utcid12_13_khung() {
        suite("S1", BehaviorSuiteStatus.RECORDING, "APP_OK");
        phien("R1", RecordingStatus.ACTIVE, "S1", "[]");

        Map<String, Object> hoa = thaoTacHopLe();
        hoa.put("khung", "DESKTOP");
        Map<String, Object> ra = soan.appendEvent("R1", hoa);
        assertEquals("desktop", ((Map<?, ?>) ra.get("event")).get("khung"),
                "UTCID12: phải hạ về chữ thường");

        Map<String, Object> sai = thaoTacHopLe();
        sai.put("khung", "tablet");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> soan.appendEvent("R1", sai))
                        .getMessage().startsWith("Khung của tiêu chí chỉ nhận rỗng"),
                "UTCID13: gõ sai một chữ là tiêu chí âm thầm rơi về khung điện thoại");
    }

    @Test
    @DisplayName("UT03 UTCID14 — drag không khai độ dời thì engine không làm gì được")
    void ut03_utcid14_dragKhongDoDoi() {
        suite("S1", BehaviorSuiteStatus.RECORDING, "APP_OK");
        phien("R1", RecordingStatus.ACTIVE, "S1", "[]");

        assertEquals("Action drag phải khai độ dời (kéo ngang hoặc kéo dọc khác 0)",
                assertThrows(IllegalArgumentException.class, () -> soan.appendEvent("R1",
                        than("kind", "action", "action", "drag",
                                "target", Map.of("semantic_id", "x"),
                                "delta", Map.of("x", 0, "y", 0)))).getMessage());

        assertDoesNotThrow(() -> soan.appendEvent("R1",
                than("kind", "action", "action", "drag",
                        "target", Map.of("semantic_id", "x"),
                        "delta", Map.of("x", 0, "y", -300))), "kéo dọc khác 0 thì hợp lệ");
    }

    @Test
    @DisplayName("UT03 UTCID15 — boot_with_uri phải là event đầu tiên của scenario")
    void ut03_utcid15_bootWithUriPhaiDauTien() {
        suite("S1", BehaviorSuiteStatus.RECORDING, "APP_OK");
        phien("R1", RecordingStatus.ACTIVE, "S1", json(List.of(Map.of("kind", "action", "action", "boot"))));

        assertEquals("boot_with_uri phải là event đầu tiên của scenario",
                assertThrows(IllegalArgumentException.class, () -> soan.appendEvent("R1",
                        than("kind", "action", "action", "boot_with_uri", "uri", "/chi-tieu/3"))).getMessage());
    }

    @Test
    @DisplayName("UT03 — open_uri thiếu URI bị chặn; URI có khoảng trắng cũng bị chặn")
    void ut03_uriKhongHopLe() {
        suite("S1", BehaviorSuiteStatus.RECORDING, "APP_OK");
        phien("R1", RecordingStatus.ACTIVE, "S1", "[]");

        assertEquals("Action open_uri phải có URI/path",
                assertThrows(IllegalArgumentException.class, () -> soan.appendEvent("R1",
                        than("kind", "action", "action", "open_uri"))).getMessage());

        assertEquals("URI/path không được chứa khoảng trắng hoặc ký tự điều khiển",
                assertThrows(IllegalArgumentException.class, () -> soan.appendEvent("R1",
                        than("kind", "action", "action", "open_uri", "uri", "/chi tieu/3"))).getMessage());
    }

    // ══════════════════ UT04 — stopRecording ══════════════════

    @Test
    @DisplayName("UT04 UTCID01/02/03 — dừng phiên ACTIVE, suite sang REVIEW, body nào cũng nhận")
    void ut04_utcid01_02_03_dungPhien() {
        BehaviorSuite s = suite("S1", BehaviorSuiteStatus.RECORDING, "APP_OK");
        GoldenRecording r = phien("R1", RecordingStatus.ACTIVE, "S1", "[]");

        Map<String, Object> ra = soan.stopRecording("R1", than("final_observation", Map.of("tong_chi", 150000)));

        assertEquals("STOPPED", ra.get("status"));
        assertNotNull(ra.get("stopped_at"));
        assertEquals(Map.of("tong_chi", 150000), ra.get("final_observation"));
        assertEquals(BehaviorSuiteStatus.REVIEW, s.getStatus());
        assertEquals(RecordingStatus.STOPPED, r.getStatus());

        // UTCID02/03: body null hoặc không khai final_observation vẫn dừng được.
        phien("R2", RecordingStatus.ACTIVE, "S1", "[]");
        assertEquals(Map.of(), soan.stopRecording("R2", null).get("final_observation"));
    }

    @Test
    @DisplayName("UT04 UTCID04/05 — gọi lại trên phiên đã dừng là bất biến, không đổi gì")
    void ut04_utcid04_05_batBien() {
        suite("S1", BehaviorSuiteStatus.REVIEW, "APP_OK");
        for (RecordingStatus tt : List.of(RecordingStatus.STOPPED, RecordingStatus.ABSTRACTED)) {
            GoldenRecording r = phien("R_" + tt, tt, "S1", "[]");

            Map<String, Object> ra = soan.stopRecording("R_" + tt, than());

            assertEquals(tt.name(), ra.get("status"), tt.name());
            assertEquals(tt, r.getStatus(), tt + ": không được đổi trạng thái");
            assertEquals(null, r.getStoppedAt(), tt + ": không được đóng dấu thời gian lần hai");
        }
    }

    @Test
    @DisplayName("UT04 UTCID06 — phiên hỏng (FAILED) thì không dừng được")
    void ut04_utcid06_phienDaHuy() {
        suite("S1", BehaviorSuiteStatus.RECORDING, "APP_OK");
        phien("R1", RecordingStatus.FAILED, "S1", "[]");

        assertEquals("Phiên record không thể dừng ở trạng thái FAILED",
                assertThrows(IllegalStateException.class,
                        () -> soan.stopRecording("R1", than())).getMessage());
    }

    // ══════════════════ UT05 — abstractRecording ══════════════════

    private static final String TRACE_DU = """
            [{"kind":"action","action":"boot"},
             {"kind":"action","action":"tap","target":{"semantic_id":"chi_tieu.them"}},
             {"kind":"action","action":"enter_text","target":{"semantic_id":"chi_tieu.so_tien"},"value":"50000"},
             {"kind":"checkpoint","target":{"semantic_id":"chi_tieu.tong"},"weight":1.0}]
            """;

    @Test
    @DisplayName("UT05 UTCID01 — trace đủ được tách thành steps + checkpoints")
    void ut05_utcid01_traceDu() {
        suite("S1", BehaviorSuiteStatus.REVIEW, "APP_OK");
        phien("R1", RecordingStatus.STOPPED, "S1", TRACE_DU);
        when(scenarios.findFirstBySourceRecordingId("R1")).thenReturn(Optional.empty());

        Map<String, Object> ra = soan.abstractRecording("R1", than());

        assertEquals(3, ((List<?>) ra.get("steps")).size(), "3 thao tác");
        assertEquals(1, ((List<?>) ra.get("checkpoints")).size(), "1 tiêu chí");
        assertNotNull(ra.get("scenario_code"));
    }

    @Test
    @DisplayName("UT05 UTCID02 — chưa dừng record thì chưa abstract được")
    void ut05_utcid02_chuaDung() {
        suite("S1", BehaviorSuiteStatus.RECORDING, "APP_OK");
        phien("R1", RecordingStatus.ACTIVE, "S1", TRACE_DU);

        assertEquals("Cần dừng record trước khi abstract",
                assertThrows(IllegalStateException.class,
                        () -> soan.abstractRecording("R1", than())).getMessage());
    }

    @Test
    @DisplayName("UT05 UTCID03 — gọi lại trên phiên đã abstract thì trả lại scenario cũ")
    void ut05_utcid03_traLaiScenarioCu() {
        suite("S1", BehaviorSuiteStatus.REVIEW, "APP_OK");
        phien("R1", RecordingStatus.ABSTRACTED, "S1", TRACE_DU);
        BehaviorScenario cu = kichBan("SC_01", "S1", 1.0, true);
        when(scenarios.findFirstBySourceRecordingId("R1")).thenReturn(Optional.of(cu));
        when(oracles.findFirstByScenarioIdOrderByCreatedAtDesc("SC_01"))
                .thenReturn(Optional.of(oracle("SC_01", OracleStatus.READY, SHA_GOLDEN)));

        Map<String, Object> ra = soan.abstractRecording("R1", than());

        assertEquals("SC_01", ra.get("id"));
        assertNotNull(ra.get("oracle"), "phải kèm oracle mới nhất để màn soạn đề hiện lại được");
    }

    @Test
    @DisplayName("UT05 UTCID04 — phiên ABSTRACTED mà mất scenario là dữ liệu hỏng, phải nói rõ")
    void ut05_utcid04_matScenario() {
        suite("S1", BehaviorSuiteStatus.REVIEW, "APP_OK");
        phien("R1", RecordingStatus.ABSTRACTED, "S1", TRACE_DU);
        when(scenarios.findFirstBySourceRecordingId("R1")).thenReturn(Optional.empty());

        assertEquals("Record đã ABSTRACTED nhưng không còn scenario tương ứng",
                assertThrows(IllegalStateException.class,
                        () -> soan.abstractRecording("R1", than())).getMessage());
    }

    @Test
    @DisplayName("UT05 UTCID05 — trace rỗng thì không có gì để trừu tượng hoá")
    void ut05_utcid05_traceRong() {
        suite("S1", BehaviorSuiteStatus.REVIEW, "APP_OK");
        phien("R1", RecordingStatus.STOPPED, "S1", "[]");

        assertEquals("Phiên record chưa có thao tác nào",
                assertThrows(IllegalStateException.class,
                        () -> soan.abstractRecording("R1", than())).getMessage());
    }

    /**
     * "Thiếu giá trị" ở đây là THIẾU HẲN trường {@code value} — tức recorder gửi hụt dữ liệu.
     * Chuỗi rỗng KHÔNG phải thiếu: từ 20/9 giá trị ghi được giữ nguyên khi replay, nên "" và
     * null là hai lựa chọn có chủ đích của người soạn, runner đều replay thành chuỗi rỗng.
     */
    @Test
    @DisplayName("UT05 UTCID06/07 — enter_text mất hẳn trường value bị chặn TẠI CỬA, liệt kê đủ ô")
    void ut05_utcid06_07_enterTextThieuGiaTri() {
        suite("S1", BehaviorSuiteStatus.REVIEW, "APP_OK");
        phien("R1", RecordingStatus.STOPPED, "S1", """
                [{"kind":"action","action":"boot"},
                 {"kind":"action","action":"enter_text","target":{"semantic_id":"chi_tieu.so_tien"}}]
                """);

        IllegalArgumentException loi = assertThrows(IllegalArgumentException.class,
                () -> soan.abstractRecording("R1", than()));
        assertTrue(loi.getMessage().startsWith("Recorder chưa gửi trường value cho ô: chi_tieu.so_tien"),
                "UTCID06: " + loi.getMessage());

        // UTCID07: hai ô thiếu thì phải kể ra cả hai, không dừng ở ô đầu.
        phien("R2", RecordingStatus.STOPPED, "S1", """
                [{"kind":"action","action":"enter_text","target":{"semantic_id":"o_mot"}},
                 {"kind":"action","action":"enter_text","target":{"semantic_id":"o_hai"}}]
                """);
        String tinNhan = assertThrows(IllegalArgumentException.class,
                () -> soan.abstractRecording("R2", than())).getMessage();
        assertTrue(tinNhan.contains("o_mot") && tinNhan.contains("o_hai"), "UTCID07: " + tinNhan);
    }

    @Test
    @DisplayName("UT05 UTCID06b — enter_text khai chuỗi rỗng là hợp lệ, KHÔNG bị coi là thiếu")
    void ut05_utcid06b_chuoiRongVanHopLe() {
        suite("S1", BehaviorSuiteStatus.REVIEW, "APP_OK");
        phien("R1", RecordingStatus.STOPPED, "S1", """
                [{"kind":"action","action":"boot"},
                 {"kind":"action","action":"enter_text","target":{"semantic_id":"chi_tieu.so_tien"},"value":""}]
                """);

        assertDoesNotThrow(() -> soan.abstractRecording("R1", than()),
                "xoá trắng một ô là thao tác thật của sinh viên, không phải dữ liệu hụt");
    }

    /** Như UT02: sheet ghi PUBLISHED bị chặn, code thật chỉ chặn DISABLED. */
    @Test
    @DisplayName("UT05 UTCID08 — chỉ bộ DISABLED mới chặn abstract")
    void ut05_utcid08_chiDisabledMoiChan() {
        suite("S_OFF", BehaviorSuiteStatus.DISABLED, "APP_OK");
        phien("R1", RecordingStatus.STOPPED, "S_OFF", TRACE_DU);

        assertEquals("Bộ chấm đã bị vô hiệu hoá",
                assertThrows(IllegalStateException.class,
                        () -> soan.abstractRecording("R1", than())).getMessage());
    }

    // ══════════════════ UT06 — saveOracle ══════════════════

    @Test
    @DisplayName("UT06 UTCID01/02 — oracle READY luôn mang SHA của Golden hiện tại")
    void ut06_utcid01_02_luuOracle() {
        app("APP_OK", GoldenAppStatus.READY);
        suite("S1", BehaviorSuiteStatus.REVIEW, "APP_OK");
        kichBan("SC_01", "S1", 1.0, true);

        Map<String, Object> ra = soan.saveOracle("SC_01", than("seed", "seed-abc-123",
                "ui_observation", Map.of("tong_chi", "150.000 đ"),
                "database_observation", Map.of("expenses", Map.of("count", 3))));

        assertEquals("READY", ra.get("status"));
        assertEquals(SHA_GOLDEN, ra.get("golden_sha256"),
                "publish đối chiếu đúng trường này — lấy sai là bộ đề không bao giờ publish được");
        assertEquals("seed-abc-123", ra.get("seed"));
        assertEquals(Map.of("tong_chi", "150.000 đ"), ra.get("ui_observation"));

        // UTCID02: không khai quan sát nào thì lưu object rỗng, không nổ.
        Map<String, Object> raRong = soan.saveOracle("SC_01", than("seed", "seed-abc-123"));
        assertEquals(Map.of(), raRong.get("ui_observation"));
        assertEquals(Map.of(), raRong.get("database_observation"));
    }

    @Test
    @DisplayName("UT06 UTCID03/04 — thiếu seed thì không lưu được (seed là cái làm lần chạy lặp lại được)")
    void ut06_utcid03_04_thieuSeed() {
        app("APP_OK", GoldenAppStatus.READY);
        suite("S1", BehaviorSuiteStatus.REVIEW, "APP_OK");
        kichBan("SC_01", "S1", 1.0, true);

        assertEquals("Thiếu trường seed",
                assertThrows(IllegalArgumentException.class,
                        () -> soan.saveOracle("SC_01", than("seed", ""))).getMessage(), "UTCID03");
        assertEquals("Thiếu trường seed",
                assertThrows(IllegalArgumentException.class,
                        () -> soan.saveOracle("SC_01", than())).getMessage(), "UTCID04");
    }

    @Test
    @DisplayName("UT06 UTCID05 — scenarioId không tồn tại bị chặn")
    void ut06_utcid05_scenarioKhongTonTai() {
        when(scenarios.findById("KHONG_CO")).thenReturn(Optional.empty());

        assertEquals("Không tìm thấy scenario: KHONG_CO",
                assertThrows(IllegalArgumentException.class,
                        () -> soan.saveOracle("KHONG_CO", than("seed", "s"))).getMessage());
    }

    // ══════════════════ UT07 — publish ══════════════════

    private void dungBoSanSangPublish(String suiteId, String sha) {
        app("APP_OK", GoldenAppStatus.READY);
        suite(suiteId, BehaviorSuiteStatus.REVIEW, "APP_OK");
        BehaviorScenario sc1 = kichBan("SC_01", suiteId, 1.0, true);
        BehaviorScenario sc2 = kichBan("SC_02", suiteId, 2.0, true);
        when(scenarios.findBySuiteIdOrderByDisplayOrderAscCreatedAtAsc(suiteId))
                .thenReturn(List.of(sc1, sc2));
        when(oracles.findByScenarioIdOrderByCreatedAtDesc("SC_01"))
                .thenReturn(new ArrayList<>(List.of(oracle("SC_01", OracleStatus.READY, sha))));
        when(oracles.findByScenarioIdOrderByCreatedAtDesc("SC_02"))
                .thenReturn(new ArrayList<>(List.of(oracle("SC_02", OracleStatus.READY, sha))));
    }

    @Test
    @DisplayName("UT07 UTCID01 — đủ điều kiện thì publish, tổng trọng số đúng")
    void ut07_utcid01_publishDuDieuKien() {
        dungBoSanSangPublish("S1", SHA_GOLDEN);

        Map<String, Object> ra = soan.publish("S1");

        assertEquals("PUBLISHED", ra.get("status"));
        assertEquals(3.0, (Double) ra.get("total_weight"));
        assertEquals(true, ra.get("ready_for_replay"));
        assertNotNull(ra.get("published_at"));
        assertNotNull(ra.get("identifier_coverage"), "độ phủ định danh là thông tin, phải luôn có");
    }

    @Test
    @DisplayName("UT07 UTCID02 — publish lại bộ đã publish thì tăng revision")
    void ut07_utcid02_publishLaiTangRevision() {
        dungBoSanSangPublish("S1", SHA_GOLDEN);
        BehaviorSuite s = suites.findById("S1").orElseThrow();
        s.setStatus(BehaviorSuiteStatus.PUBLISHED);
        s.setRevision(3);

        Map<String, Object> ra = soan.publish("S1");

        assertEquals(4, ra.get("revision"));
    }

    @Test
    @DisplayName("UT07 UTCID03 — Golden App chưa READY thì không publish")
    void ut07_utcid03_goldenAppChuaReady() {
        app("APP_OK", GoldenAppStatus.REGISTERED);
        suite("S1", BehaviorSuiteStatus.REVIEW, "APP_OK");

        assertEquals("Không thể publish vì Golden App chưa READY",
                assertThrows(IllegalStateException.class, () -> soan.publish("S1")).getMessage());
    }

    @Test
    @DisplayName("UT07 UTCID04 — không có scenario nào đang bật thì không publish")
    void ut07_utcid04_khongCoScenarioBat() {
        app("APP_OK", GoldenAppStatus.READY);
        suite("S1", BehaviorSuiteStatus.REVIEW, "APP_OK");
        // Dựng scenario TRƯỚC: kichBan() tự stub bên trong, gọi lồng trong when() sẽ vỡ stubbing.
        BehaviorScenario tat = kichBan("SC_01", "S1", 1.0, false);
        when(scenarios.findBySuiteIdOrderByDisplayOrderAscCreatedAtAsc("S1")).thenReturn(List.of(tat));

        assertEquals("Bộ chấm chưa có scenario đang bật",
                assertThrows(IllegalStateException.class, () -> soan.publish("S1")).getMessage());
    }

    @Test
    @DisplayName("UT07 UTCID05 — oracle đo trên bản Golden CŨ không được tính là có oracle")
    void ut07_utcid05_oracleLechBanGolden() {
        dungBoSanSangPublish("S1", "sha-golden-v0");

        IllegalStateException loi = assertThrows(IllegalStateException.class, () -> soan.publish("S1"));

        assertTrue(loi.getMessage().contains("chưa có oracle READY khớp phiên bản Golden Solution hiện tại"),
                loi.getMessage());
    }

    @Test
    @DisplayName("UT07 UTCID06 — scenario chưa có oracle thì không publish")
    void ut07_utcid06_chuaCoOracle() {
        dungBoSanSangPublish("S1", SHA_GOLDEN);
        when(oracles.findByScenarioIdOrderByCreatedAtDesc("SC_01")).thenReturn(new ArrayList<>());

        assertTrue(assertThrows(IllegalStateException.class, () -> soan.publish("S1"))
                .getMessage().startsWith("Scenario SC_01 chưa có oracle READY"));
    }

    @Test
    @DisplayName("UT07 UTCID07 — weight <= 0 bị chặn ngay ở validateScenario")
    void ut07_utcid07_weightKhongDuong() {
        dungBoSanSangPublish("S1", SHA_GOLDEN);
        BehaviorScenario sc1 = scenarios.findById("SC_01").orElseThrow();
        sc1.setWeight(0.0);

        assertEquals("Scenario SC_01 phải có weight > 0",
                assertThrows(IllegalArgumentException.class, () -> soan.publish("S1")).getMessage());
    }

    @Test
    @DisplayName("UT07 — scenario chưa có checkpoint thì không phát hành được (không có gì để chấm)")
    void ut07_scenarioChuaCoCheckpoint() {
        dungBoSanSangPublish("S1", SHA_GOLDEN);
        scenarios.findById("SC_01").orElseThrow().setCheckpointsJson("[]");

        assertEquals("Scenario SC_01 chưa có checkpoint chấm",
                assertThrows(IllegalArgumentException.class, () -> soan.publish("S1")).getMessage());
    }

    // ══════════════════ UT09 — GoldenValidationService.requirePassed ══════════════════

    private GoldenValidationService kiemChung(GoldenValidationRun lanChay, String planShaHienTai) {
        BehaviorAuthoringService authoringGia = mock(BehaviorAuthoringService.class);
        BehaviorArtifactService artifacts = mock(BehaviorArtifactService.class);
        BehaviorSuiteMaterializer materializer = mock(BehaviorSuiteMaterializer.class);
        GoldenValidationRunRepository runs = mock(GoldenValidationRunRepository.class);

        // currentPlanSha băm từ preview plan — trả plan cố định thì SHA cũng cố định.
        when(authoringGia.previewExecutionPlan(anyString()))
                .thenReturn(new LinkedHashMap<>(Map.of("suite", Map.of("suite_code", planShaHienTai))));
        when(runs.findFirstBySuiteIdOrderByCreatedAtDesc(anyString()))
                .thenReturn(Optional.ofNullable(lanChay));
        return new GoldenValidationService(authoringGia, artifacts, materializer, runs);
    }

    /**
     * Vân tay plan do chính service băm ra. Không có đường công khai nào đọc được nó (latest()
     * trả vân tay của LẦN CHẠY, không phải của plan hiện tại), nên ca "khớp vân tay" phải hỏi
     * thẳng hàm riêng — đó cũng đúng là đại lượng requirePassed đem ra so.
     */
    private String shaHienTai(GoldenValidationService svc, String suiteId) throws Exception {
        java.lang.reflect.Method m =
                GoldenValidationService.class.getDeclaredMethod("currentPlanSha", String.class);
        m.setAccessible(true);
        return (String) m.invoke(svc, suiteId);
    }

    @Test
    @DisplayName("UT09 UTCID02 — chưa chạy kiểm chứng lần nào thì chặn với lời báo riêng")
    void ut09_utcid02_chuaChayLanNao() {
        GoldenValidationService svc = kiemChung(null, "plan-v2");

        assertEquals("Cần chạy kiểm chứng Golden trước khi publish",
                assertThrows(IllegalStateException.class, () -> svc.requirePassed("S1")).getMessage());
    }

    static Stream<Arguments> ut09_khongDat() {
        return Stream.of(
                Arguments.of("UTCID03", GoldenValidationStatus.FAILED, 79, 79),
                Arguments.of("UTCID04", GoldenValidationStatus.UNAVAILABLE, 79, 79),
                Arguments.of("UTCID05", GoldenValidationStatus.PASSED, 0, 0),
                Arguments.of("UTCID06", GoldenValidationStatus.PASSED, null, null),
                Arguments.of("UTCID07", GoldenValidationStatus.PASSED, 79, 78)
        );
    }

    @ParameterizedTest(name = "UT09 {0}: {1} {3}/{2}")
    @MethodSource("ut09_khongDat")
    @DisplayName("UT09 — mọi kiểu \"chưa đạt\" đều bị chặn bằng cùng một lời báo")
    void ut09_khongDat(String utcid, GoldenValidationStatus tt, Integer tong, Integer dat) {
        GoldenValidationRun run = new GoldenValidationRun();
        run.setStatus(tt);
        run.setTotalCheckpoints(tong);
        run.setPassedCheckpoints(dat);
        run.setPlanSha256("bat-ky");
        GoldenValidationService svc = kiemChung(run, "plan-v2");

        assertTrue(assertThrows(IllegalStateException.class, () -> svc.requirePassed("S1"))
                .getMessage().startsWith("Golden preflight chưa pass hoặc execution plan đã thay đổi"), utcid);
    }

    @Test
    @DisplayName("UT09 UTCID01/08 — pass đủ và đúng vân tay plan mới qua; plan đổi là phải kiểm lại")
    void ut09_utcid01_08_vanTayPlan() throws Exception {
        // UTCID08: plan hiện tại băm ra một SHA khác hẳn chuỗi dưới đây → phải chặn.
        GoldenValidationRun lech = new GoldenValidationRun();
        lech.setStatus(GoldenValidationStatus.PASSED);
        lech.setTotalCheckpoints(79);
        lech.setPassedCheckpoints(79);
        lech.setPlanSha256("plan-v1-cu");
        GoldenValidationService svc = kiemChung(lech, "plan-v2");
        assertThrows(IllegalStateException.class, () -> svc.requirePassed("S1"), "UTCID08");

        // UTCID01: lấy đúng vân tay service tự tính (qua latest) rồi gán vào lần chạy.
        GoldenValidationRun dat = new GoldenValidationRun();
        dat.setStatus(GoldenValidationStatus.PASSED);
        dat.setTotalCheckpoints(79);
        dat.setPassedCheckpoints(79);
        GoldenValidationService svc2 = kiemChung(dat, "plan-v2");
        dat.setPlanSha256(shaHienTai(svc2, "S1"));

        assertDoesNotThrow(() -> svc2.requirePassed("S1"), "UTCID01");
    }

    // ══════════════════ UT10 — StaticRuleService.globPattern ══════════════════

    static Stream<Arguments> ut10() {
        return Stream.of(
                Arguments.of("UTCID01", "lib/*.dart", "lib/main.dart", true),
                // * chỉ ăn một cấp — đây là khác biệt dễ bỏ sót nhất so với **
                Arguments.of("UTCID02", "lib/*.dart", "lib/models/chi_tieu.dart", false),
                Arguments.of("UTCID03", "lib/**/*.dart", "lib/models/chi_tieu.dart", true),
                Arguments.of("UTCID04", "lib/**/*.dart", "lib/main.dart", false),
                Arguments.of("UTCID05", "lib\\models\\chi_tieu.dart", "lib/models/chi_tieu.dart", true),
                Arguments.of("UTCID06", "lib/a?.dart", "lib/ab.dart", true),
                Arguments.of("UTCID07", "lib/a?.dart", "lib/main.dart", false),
                Arguments.of("UTCID08", "lib/(main).dart", "lib/(main).dart", true),
                Arguments.of("UTCID09", "lib/a+b.dart", "lib/a+b.dart", true),
                Arguments.of("UTCID10", "", "", true),
                Arguments.of("UTCID11", "**", "lib/models/chi_tieu.dart", true)
        );
    }

    @ParameterizedTest(name = "UT10 {0}: {1} vs {2} → {3}")
    @MethodSource("ut10")
    @DisplayName("UT10 — phép đổi glob→regex phải trùng ngữ nghĩa với static_checks.dart trong container")
    void ut10_globPattern(String utcid, String glob, String duong, boolean khop) {
        Pattern p = StaticRuleService.globPattern(glob);

        assertEquals(khop, p.matcher(duong).matches(),
                utcid + ": glob=" + glob + " regex=" + p.pattern() + " vs " + duong);
    }

    @Test
    @DisplayName("UT10 UTCID09 — ký tự regex trong glob phải được escape, không được ăn nghĩa regex")
    void ut10_utcid09_escapeKyTuRegex() {
        // "a+b" mà không escape thì regex hiểu là "một hoặc nhiều a" → khớp nhầm "aab.dart".
        assertFalse(StaticRuleService.globPattern("lib/a+b.dart").matcher("lib/aab.dart").matches());
        assertFalse(StaticRuleService.globPattern("lib/(main).dart").matcher("lib/main.dart").matches());
    }

    /** Dùng để chắc chắn helper trong test này không vô tình so sánh nhầm bằng find(). */
    @Test
    @DisplayName("UT10 — pattern luôn neo hai đầu, không khớp chuỗi con")
    void ut10_neoHaiDau() {
        Pattern p = StaticRuleService.globPattern("lib/main.dart");
        assertTrue(p.pattern().startsWith("^") && p.pattern().endsWith("$"));
        assertFalse(p.matcher("a/lib/main.dart").matches());
        assertFalse(p.matcher("lib/main.dart.bak").matches());
    }

}
