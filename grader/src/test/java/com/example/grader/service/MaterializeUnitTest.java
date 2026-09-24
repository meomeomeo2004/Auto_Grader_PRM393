package com.example.grader.service;

import com.example.grader.entity.BehaviorArtifact;
import com.example.grader.entity.BehaviorArtifactType;
import com.example.grader.entity.Exam;
import com.example.grader.entity.ExamStatus;
import com.example.grader.repository.ExamRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sheet UT08 — BehaviorSuiteMaterializer.materialize.
 *
 * <p>Tách riêng khỏi {@link TestSuiteAuthoringUnitTest} vì đồ dựng khác hẳn: hàm này GHI RA ĐĨA,
 * nên phải có thư mục exams thật, artifact thật và một Golden ZIP thật (contract.json lấy danh
 * sách package bằng cách đọc pubspec trong chính Golden).
 *
 * <p>Điều đáng kiểm nhất không phải nội dung bundle mà là KỶ LUẬT THAY THẾ: dựng ở thư mục nháp,
 * đổi chỗ, rồi mới xoá bản cũ. Hỏng giữa chừng mà mất bộ đề đang chạy là mất luôn khả năng chấm.
 * Kế đó là ba con dấu bị đặt lại — mỗi lần publish là một bản Golden mới, dấu kiểm đồng bộ cũ
 * hết hiệu lực; quên đặt lại là bộ đề mới đi thẳng qua cổng chặn bằng dấu của bộ cũ.
 */
class MaterializeUnitTest {

    @TempDir Path thuMuc;

    private static final String SUITE_ID = "S1";
    private static final String SUITE_CODE = "PE_PRM393_FA26";

    private BehaviorAuthoringService authoring;
    private BehaviorArtifactService artifacts;
    private StaticRuleService staticRules;
    private ExamRepository exams;
    private BehaviorSuiteMaterializer ketXuat;

    private Path goc;                 // thư mục exams
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void dungMoiTruongGia() throws Exception {
        goc = Files.createDirectories(thuMuc.resolve("exams"));

        authoring = mock(BehaviorAuthoringService.class);
        artifacts = mock(BehaviorArtifactService.class);
        staticRules = mock(StaticRuleService.class);
        exams = mock(ExamRepository.class);

        when(exams.save(any())).thenAnswer(i -> i.getArgument(0));
        when(exams.findByExamId(anyString())).thenReturn(Optional.empty());
        when(staticRules.matrixRows(anyString(), anyString())).thenReturn(Map.of());
        when(artifacts.activeManifest(anyString())).thenReturn(Map.of());
        when(artifacts.goldenScreenshotDir(anyString())).thenReturn(thuMuc.resolve("khong-co-anh"));
        when(artifacts.activeOptional(anyString(), any())).thenReturn(Optional.empty());

        // hidden.db: chỉ cần là file có thật trên đĩa, copyArtifact không đọc nội dung.
        Path hidden = Files.writeString(thuMuc.resolve("hidden.db"), "gia-dinh-la-sqlite");
        when(artifacts.active(SUITE_ID, BehaviorArtifactType.HIDDEN_DATABASE))
                .thenReturn(artifact(hidden));
        // materialize gọi writeBundle với requireOutputDatabase = true nên bản đối chiếu là bắt buộc.
        Path output = Files.writeString(thuMuc.resolve("expected-output.db"), "gia-dinh-la-sqlite");
        when(artifacts.active(SUITE_ID, BehaviorArtifactType.OUTPUT_DATABASE))
                .thenReturn(artifact(output));

        // Golden ZIP thật: publicContract() đọc pubspec trong đây để suy allowed_packages.
        Path golden = thuMuc.resolve("golden.zip");
        Files.write(golden, nenGolden());
        when(artifacts.active(SUITE_ID, BehaviorArtifactType.GOLDEN_SOLUTION))
                .thenReturn(artifact(golden));

        ketXuat = new BehaviorSuiteMaterializer(authoring, artifacts, staticRules, exams);
        datField(ketXuat, "examsDir", goc.toAbsolutePath().toString());
        datField(ketXuat, "templateDir", thuMuc.resolve("grader-base").toString());
    }

    // ── Đồ dựng sẵn ────────────────────────────────────────────────────

    private static void datField(Object o, String ten, Object giaTri) throws Exception {
        Field f = o.getClass().getDeclaredField(ten);
        f.setAccessible(true);
        f.set(o, giaTri);
    }

    private static BehaviorArtifact artifact(Path duong) {
        BehaviorArtifact a = new BehaviorArtifact();
        a.setSuiteId(SUITE_ID);
        a.setStoragePath(duong.toString());
        a.setSha256("sha-golden-v1");
        return a;
    }

    private static byte[] nenGolden() throws Exception {
        ByteArrayOutputStream ra = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(ra, StandardCharsets.UTF_8)) {
            z.putNextEntry(new ZipEntry("lib/main.dart"));
            z.write("void main() {}".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("pubspec.yaml"));
            z.write(("name: quan_ly_chi_tieu\ndependencies:\n  flutter:\n    sdk: flutter\n"
                    + "  intl: ^0.19.0\n  sqflite: ^2.3.0\n").getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return ra.toByteArray();
    }

    /** Một scenario với soCheckpoint tiêu chí, mỗi tiêu chí 1 điểm. */
    private Map<String, Object> scenario(String code, int soCheckpoint) {
        List<Map<String, Object>> checkpoints = new ArrayList<>();
        for (int i = 1; i <= soCheckpoint; i++) {
            checkpoints.add(new LinkedHashMap<>(Map.of(
                    "id", "CP_" + i,
                    "kind", "checkpoint",
                    "weight", 1.0,
                    "name", "Tiêu chí " + i,
                    "expect", Map.of("visible_texts", List.of("150.000 đ")))));
        }
        Map<String, Object> sc = new LinkedHashMap<>();
        sc.put("id", "SC_" + code);
        sc.put("scenario_code", code);
        sc.put("name", "Luồng " + code);
        sc.put("description", "Thêm một khoản chi rồi kiểm tổng");
        sc.put("weight", (double) soCheckpoint);
        sc.put("initial_state", Map.of("reset_storage", true));
        sc.put("steps", List.of(
                Map.of("action", "boot"),
                Map.of("action", "tap", "target", Map.of("semantic_id", "chi_tieu.them"))));
        sc.put("viewports", List.of(Map.of("name", "phone", "width", 412, "height", 838,
                "device_pixel_ratio", 1)));
        sc.put("checkpoints", checkpoints);
        sc.put("oracle", Map.of("seed", "seed-abc-123"));
        return sc;
    }

    /** Kế hoạch thực thi đúng hình dạng executionPlan() trả về. */
    private Map<String, Object> plan(String examId, List<Map<String, Object>> scenarios) {
        Map<String, Object> suite = new LinkedHashMap<>();
        suite.put("id", SUITE_ID);
        suite.put("suite_code", SUITE_CODE);
        suite.put("exam_id", examId);
        suite.put("name", "PE Quản lý chi tiêu");
        suite.put("description", "Bộ chấm PE PRM393 FA26");
        suite.put("revision", 1);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("schema_version", "1.0");
        plan.put("suite", suite);
        plan.put("public_contract", Map.of("locator_priority", List.of("semantic_id")));
        plan.put("database_contract", Map.of("database_name", "hidden.db"));
        plan.put("runtime_config", Map.of("driver", "flutter_test"));
        plan.put("scenarios", scenarios);
        return plan;
    }

    private void keHoach(Map<String, Object> plan) {
        when(authoring.executionPlan(SUITE_ID)).thenReturn(plan);
    }

    private Path testcaseCua(String examId) {
        return goc.resolve(examId).resolve("testcase");
    }

    private List<Path> thuMucCon(Path cha) throws Exception {
        try (var ds = Files.list(cha)) {
            return ds.filter(Files::isDirectory).toList();
        }
    }

    // ══════════════════ UT08 ══════════════════

    @Test
    @DisplayName("UT08 UTCID01 — kết xuất đủ bộ testcase và đặt lại ba con dấu của đề")
    void ut08_utcid01_ketXuatDayDu() throws Exception {
        keHoach(plan(SUITE_CODE, List.of(scenario("ADD", 3), scenario("SORT", 2))));

        Map<String, Object> ra = ketXuat.materialize(SUITE_ID);

        assertEquals(SUITE_CODE, ra.get("exam_id"));
        assertEquals(2, ra.get("scenario_count"));
        assertEquals(5, ra.get("criterion_count"), "3 + 2 tiêu chí");
        assertEquals(true, ra.get("ready_for_grading"));

        Path tc = testcaseCua(SUITE_CODE);
        for (String ten : List.of("exam_test.dart", "grader.dart", "behavior_plan.json",
                "skills_matrix.json", "contract.json", "suite_manifest.json")) {
            assertTrue(Files.isRegularFile(tc.resolve(ten)), "thiếu " + ten);
        }
        assertTrue(Files.isRegularFile(tc.resolve("fixtures/hidden.db")));

        // contract.json suy allowed_packages TỪ CHÍNH GOLDEN, không lấy từ runtime_config cũ.
        JsonNode contract = mapper.readTree(Files.readString(tc.resolve("contract.json")));
        List<String> goi = new ArrayList<>();
        contract.get("allowed_packages").forEach(n -> goi.add(n.asText()));
        assertEquals(List.of("flutter", "intl", "sqflite"), goi);

        Exam luu = exams.findByExamId(SUITE_CODE).orElse(null);
        // findByExamId vẫn trả rỗng (mock), nên kiểm trên chính object đã save.
        org.mockito.ArgumentCaptor<Exam> bat = org.mockito.ArgumentCaptor.forClass(Exam.class);
        org.mockito.Mockito.verify(exams).save(bat.capture());
        Exam exam = bat.getValue();
        assertEquals(ExamStatus.READY, exam.getStatus());
        assertEquals("PUBLISHED", exam.getTestcaseStatus());
        assertEquals(1, exam.getTestcaseVersion());
        // Ba cột dấu kiểm đồng bộ khung phát đã gỡ 19/9 cùng màn Kiểm đồng bộ. Thứ thay chúng là
        // vân tay khung phát, và nó KHÔNG đóng ở đây: publish chỉ kết xuất bộ chấm, còn dấu vân
        // tay do BanGiaoService.xuatGoi đóng vào lúc xuất gói bàn giao.
        assertNull(exam.getKhungVanTay(),
                "publish không được tự đóng vân tay khung phát — đó là việc của lần xuất gói");
        assertNull(luu, "findByExamId là mock rỗng — chỉ để chắc test không tự đánh lừa mình");
    }

    @Test
    @DisplayName("UT08 UTCID02 — bộ cũ bị thay hẳn, không để sót thư mục backup, version tăng 1")
    void ut08_utcid02_thayBoCu() throws Exception {
        Path tc = Files.createDirectories(testcaseCua(SUITE_CODE));
        Files.writeString(tc.resolve("file-cua-ban-cu.txt"), "phải biến mất");
        Exam cu = new Exam();
        cu.setExamId(SUITE_CODE);
        cu.setTestcaseVersion(3);
        when(exams.findByExamId(SUITE_CODE)).thenReturn(Optional.of(cu));
        keHoach(plan(SUITE_CODE, List.of(scenario("ADD", 3))));

        ketXuat.materialize(SUITE_ID);

        assertFalse(Files.exists(tc.resolve("file-cua-ban-cu.txt")), "bản cũ phải bị thay hẳn");
        assertTrue(Files.isRegularFile(tc.resolve("behavior_plan.json")));
        assertEquals(4, cu.getTestcaseVersion());

        List<Path> conLai = thuMucCon(goc.resolve(SUITE_CODE));
        assertEquals(List.of(tc), conLai,
                "không được để lại .rar-staging-* hay .testcase-rar-backup-*: " + conLai);
    }

    @Test
    @DisplayName("UT08 UTCID03 — suite không khai exam_id thì lấy chính suite_code làm mã đề")
    void ut08_utcid03_khongKhaiExamId() throws Exception {
        keHoach(plan("", List.of(scenario("ADD", 2))));

        Map<String, Object> ra = ketXuat.materialize(SUITE_ID);

        assertEquals(SUITE_CODE, ra.get("exam_id"));
        assertTrue(Files.isDirectory(testcaseCua(SUITE_CODE)));
    }

    @Test
    @DisplayName("UT08 UTCID04 — mã đề không an toàn bị chặn TRƯỚC khi đụng vào đĩa")
    void ut08_utcid04_maDeKhongAnToan() throws Exception {
        keHoach(plan("PE 01", List.of(scenario("ADD", 2))));

        IllegalArgumentException loi = assertThrows(IllegalArgumentException.class,
                () -> ketXuat.materialize(SUITE_ID));

        assertTrue(loi.getMessage().startsWith("Mã đề không hợp lệ"), loi.getMessage());
        assertTrue(thuMucCon(goc).isEmpty(), "không được tạo thư mục nào khi mã đề đã sai");
    }

    @Test
    @DisplayName("UT08 UTCID05 — bộ chưa publish thì executionPlan chặn từ dòng đầu")
    void ut08_utcid05_boChuaPublish() throws Exception {
        when(authoring.executionPlan(SUITE_ID))
                .thenThrow(new IllegalStateException("Bộ chấm chưa publish"));

        assertEquals("Bộ chấm chưa publish",
                assertThrows(IllegalStateException.class, () -> ketXuat.materialize(SUITE_ID)).getMessage());
        assertTrue(thuMucCon(goc).isEmpty());
    }

    @Test
    @DisplayName("UT08 UTCID06 — không có checkpoint nào thì không có gì để chấm")
    void ut08_utcid06_khongCoCheckpoint() throws Exception {
        keHoach(plan(SUITE_CODE, List.of(scenario("ADD", 0))));

        assertEquals("Bộ chấm không có checkpoint để publish",
                assertThrows(IllegalStateException.class, () -> ketXuat.materialize(SUITE_ID)).getMessage());
        assertTrue(thuMucCon(goc).isEmpty());
    }

    /**
     * Sheet ghi lỗi luật tĩnh bị bọc trong "Không publish được bộ Record–Replay: …" — không
     * đúng: requireGoldenCompliance nằm NGOÀI khối try, nên lỗi bay thẳng ra.
     */
    @Test
    @DisplayName("UT08 UTCID07 — Golden không còn thoả luật tĩnh thì chặn, lỗi KHÔNG bị bọc")
    void ut08_utcid07_luatTinhKhongDat() throws Exception {
        keHoach(plan(SUITE_CODE, List.of(scenario("ADD", 3))));
        org.mockito.Mockito.doThrow(new IllegalStateException("Luật tĩnh LINT_01 không đạt trên Golden"))
                .when(staticRules).requireGoldenCompliance(SUITE_ID);

        assertEquals("Luật tĩnh LINT_01 không đạt trên Golden",
                assertThrows(IllegalStateException.class, () -> ketXuat.materialize(SUITE_ID)).getMessage());
        assertTrue(thuMucCon(goc).isEmpty(), "chặn trước khi tạo thư mục nào");
    }

    /**
     * Kỷ luật thay thế: hỏng ở khâu ghi bundle thì bộ đề ĐANG CHẠY phải còn nguyên. Ép hỏng
     * bằng cách bỏ hidden.db khỏi đĩa — copyArtifact sẽ nổ giữa lúc dựng thư mục nháp.
     */
    @Test
    @DisplayName("UT08 — dựng bundle hỏng giữa chừng thì bộ đề cũ vẫn còn nguyên")
    void ut08_hongGiuaChungKhongMatBoCu() throws Exception {
        Path tc = Files.createDirectories(testcaseCua(SUITE_CODE));
        Files.writeString(tc.resolve("behavior_plan.json"), "{\"ban\":\"dang-cham\"}");
        Files.delete(thuMuc.resolve("hidden.db"));
        keHoach(plan(SUITE_CODE, List.of(scenario("ADD", 3))));

        IllegalStateException loi = assertThrows(IllegalStateException.class,
                () -> ketXuat.materialize(SUITE_ID));

        assertTrue(loi.getMessage().startsWith("Không publish được bộ Record–Replay:"),
                "lỗi trong khối try mới bị bọc: " + loi.getMessage());
        assertEquals("{\"ban\":\"dang-cham\"}", Files.readString(tc.resolve("behavior_plan.json")),
                "mất bộ đề đang chạy là mất luôn khả năng chấm — tuyệt đối không được xảy ra");
        assertEquals(List.of(tc), thuMucCon(goc.resolve(SUITE_CODE)), "phải dọn sạch thư mục nháp");
    }

    /**
     * Sửa engine thì phải publish lại: bản sao engine nằm TRONG thư mục testcase, và
     * BanGiaoService băm chính file đó để ghi vân tay vào tờ khai.
     */
    @Test
    @DisplayName("UT08 — bundle luôn mang bản sao engine của lần publish này")
    void ut08_bundleMangBanSaoEngine() throws Exception {
        keHoach(plan(SUITE_CODE, List.of(scenario("ADD", 1))));

        ketXuat.materialize(SUITE_ID);

        String engine = Files.readString(testcaseCua(SUITE_CODE).resolve("exam_test.dart"),
                StandardCharsets.UTF_8);
        assertTrue(engine.contains("void main("), "phải là engine thật, không phải file rỗng");
        assertTrue(engine.length() > 10_000, "engine thật ~4300 dòng, đang là " + engine.length() + " ký tự");
    }

    /** Trọng số: tổng điểm các case sinh ra phải bằng đúng tổng weight của scenario. */
    @Test
    @DisplayName("UT08 — chia trọng số không làm phình hay hụt tổng điểm của luồng")
    void ut08_chiaTrongSoGiuTong() throws Exception {
        keHoach(plan(SUITE_CODE, List.of(scenario("ADD", 3), scenario("SORT", 2))));

        ketXuat.materialize(SUITE_ID);

        JsonNode plan = mapper.readTree(Files.readString(
                testcaseCua(SUITE_CODE).resolve("behavior_plan.json")));
        // Từ 21/9 plan không còn là danh sách phẳng: case được gom theo LUỒNG (execution_code),
        // phần dùng chung đẩy lên cấp luồng, nên điểm nằm ở luong[*].cases[*].weight.
        double tong = 0;
        for (JsonNode luong : plan.get("luong")) {
            for (JsonNode c : luong.get("cases")) tong += c.get("weight").asDouble();
        }

        assertEquals(5.0, tong, 1e-9, "3 + 2 điểm, không được đổi sau khi chia");
    }
}
