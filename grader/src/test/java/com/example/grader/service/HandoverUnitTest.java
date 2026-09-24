package com.example.grader.service;

import com.example.grader.entity.BehaviorArtifact;
import com.example.grader.entity.BehaviorArtifactType;
import com.example.grader.entity.BehaviorSuite;
import com.example.grader.entity.Exam;
import com.example.grader.repository.BehaviorSuiteRepository;
import com.example.grader.repository.ExamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Module HandoverExport + HandoverImport — các sheet UT14…UT18.
 *
 * <p>Kênh liên lạc DUY NHẤT giữa hai bản là gói .zip này. Bên nhận không có Golden nên không tự
 * kiểm lại được gì; mọi thứ họ tin đều nằm trong tờ khai, nên tờ khai sai là bên kia chấm sai mà
 * không có cách nào biết.
 *
 * <p>Sheet UT11–UT13 (StarterSyncService: kiểm đồng bộ khung phát) đã bỏ khỏi bộ này: cả màn
 * Kiểm đồng bộ lẫn service đều bị gỡ ngày 19–20/9. Thứ thay chúng là VÂN TAY KHUNG PHÁT —
 * BanGiaoService đóng dấu vào bản ghi đề mỗi lần xuất gói, và nó KHÔNG đi theo gói vì bên người
 * chấm không có Golden để đối chiếu.
 */
class HandoverUnitTest {

    @TempDir Path thuMuc;

    private static final String ANH_CHAM = "grading-base:2026-08-22e";
    private static final String MA_DE = "PE_PRM393_FA26";
    private static final String SHA_GOLDEN = "sha-golden-v1";

    private final ObjectMapper mapper = new ObjectMapper();

    private ExamRepository exams;
    private ExamService examService;
    private BanGiaoService banGiao;

    private Path testcase;

    @BeforeEach
    void dungBoChamGia() throws Exception {
        testcase = Files.createDirectories(thuMuc.resolve("exams").resolve(MA_DE).resolve("testcase"));
        Files.writeString(testcase.resolve("exam_test.dart"), "void main() {}", StandardCharsets.UTF_8);
        Files.writeString(testcase.resolve("behavior_plan.json"), "{\"cases\":[]}", StandardCharsets.UTF_8);
        Files.writeString(testcase.resolve("contract.json"),
                "{\"allowed_packages\":[\"flutter\",\"intl\",\"sqflite\"]}", StandardCharsets.UTF_8);

        exams = mock(ExamRepository.class);
        examService = mock(ExamService.class);
        when(exams.save(any())).thenAnswer(i -> i.getArgument(0));

        banGiao = new BanGiaoService(exams, examService, ANH_CHAM);
    }

    // ── Đồ dựng sẵn ────────────────────────────────────────────────────

    private Exam de(String examId, String duongTestcase) {
        Exam e = new Exam();
        e.setExamId(examId);
        e.setExamName("PE PRM393 FA26");
        e.setTestcasePath(duongTestcase);
        when(exams.findByExamId(examId)).thenReturn(Optional.of(e));
        return e;
    }

    /** Leo ngược lên lớp cha: ExamService dựng tay ở đây là lớp ẩn danh, field nằm ở lớp cha. */
    private static void datField(Object o, String ten, Object giaTri) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(ten);
                f.setAccessible(true);
                f.set(o, giaTri);
                return;
            } catch (NoSuchFieldException tiepTuc) {
                // thử lớp cha
            }
        }
        throw new NoSuchFieldException(ten);
    }

    private byte[] nen(Map<String, String> tep) throws Exception {
        ByteArrayOutputStream ra = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(ra, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> e : tep.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return ra.toByteArray();
    }

    private Map<String, String> giaiNen(byte[] zip) throws Exception {
        Map<String, String> ra = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (!e.isDirectory()) ra.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return ra;
    }

    /** Gói bàn giao hợp lệ: tờ khai đúng khuôn hiện tại + hợp đồng đòi đúng một package. */
    private byte[] goiBanGiao(String examId) throws Exception {
        return goiBanGiao(examId, "{\"allowed_packages\":[\"intl\"]}");
    }

    private byte[] goiBanGiao(String examId, String contract) throws Exception {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schema_version", 1);
        if (examId != null) meta.put("exam_id", examId);
        meta.put("exam_name", "PE PRM393 FA26");
        meta.put("base_image", ANH_CHAM);

        Map<String, String> tep = new LinkedHashMap<>();
        tep.put("exam_test.dart", "void main() {}");
        tep.put("contract.json", contract);
        tep.put(BanGiaoService.TEN_META, mapper.writeValueAsString(meta));
        return nen(tep);
    }

    // ══════════════════ UT14 — BanGiaoService.xuatGoi ══════════════════

    /** Đề có thư mục testcase hợp lệ — đủ điều kiện xuất gói. */
    private Exam deXuatDuoc() {
        return de(MA_DE, testcase.toString());
    }

    @Test
    @DisplayName("UT14 UTCID01/02 — gói mang đủ file testcase + đúng một tờ khai, kèm vân tay engine")
    void ut14_utcid01_02_xuatDuGoi() throws Exception {
        deXuatDuoc();

        byte[] zip = banGiao.xuatGoi(MA_DE);
        Map<String, String> trongGoi = giaiNen(zip);

        assertTrue(trongGoi.containsKey("exam_test.dart"));
        assertTrue(trongGoi.containsKey("behavior_plan.json"));
        assertTrue(trongGoi.containsKey("contract.json"));
        assertEquals(1, trongGoi.keySet().stream().filter(BanGiaoService.TEN_META::equals).count());

        Map<?, ?> meta = mapper.readValue(trongGoi.get(BanGiaoService.TEN_META), Map.class);
        assertEquals(MA_DE, meta.get("exam_id"));
        assertEquals(ANH_CHAM, meta.get("base_image"),
                "hai máy khác ảnh là cùng một bài cho hai kết quả — nhãn phải đi theo gói");

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder mongDoi = new StringBuilder();
        for (byte b : md.digest("void main() {}".getBytes(StandardCharsets.UTF_8))) {
            mongDoi.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        assertEquals(mongDoi.toString(), meta.get("engine_sha256"),
                "vân tay engine để biết điểm lệch là do khác engine hay không, thay vì đoán");
    }

    /**
     * Cổng chặn "chưa qua kiểm đồng bộ khung phát" (UTCID03/04 của sheet cũ) đã gỡ 19/9 cùng
     * màn Kiểm đồng bộ. Thay vào đó lần xuất nào cũng ĐÓNG DẤU vân tay khung phát, để lần sau
     * màn soạn đề đọc ra được là Golden đã đổi ở đúng những chỗ khung lấy về hay chưa.
     */
    @Test
    @DisplayName("UT14 UTCID03 — mỗi lần xuất đóng dấu vân tay khung phát vào bản ghi đề")
    void ut14_utcid03_dongDauVanTayKhungPhat() throws Exception {
        Exam exam = deXuatDuoc();
        when(examService.vanTayKhungPhat(MA_DE)).thenReturn("van-tay-khung-v1");

        byte[] zip = banGiao.xuatGoi(MA_DE);

        assertEquals("van-tay-khung-v1", exam.getKhungVanTay());
        verify(exams).save(exam);

        Map<?, ?> meta = mapper.readValue(giaiNen(zip).get(BanGiaoService.TEN_META), Map.class);
        assertNull(meta.get("khung_van_tay"),
                "vân tay KHÔNG đi theo gói: bên người chấm không có Golden để đối chiếu với nó");
    }

    @Test
    @DisplayName("UT14 UTCID04 — không tính được vân tay thì cả lần xuất hỏng, không giao gói nửa vời")
    void ut14_utcid04_vanTayHongThiHongCaLanXuat() throws Exception {
        deXuatDuoc();
        when(examService.vanTayKhungPhat(MA_DE))
                .thenThrow(new IllegalStateException("Đề chưa có Golden Solution"));

        assertThrows(IllegalStateException.class, () -> banGiao.xuatGoi(MA_DE));
    }

    @Test
    @DisplayName("UT14 UTCID05 — mã đề không tồn tại")
    void ut14_utcid05_deKhongTonTai() {
        when(exams.findByExamId("KHONG_CO")).thenReturn(Optional.empty());

        assertEquals("Không tìm thấy bộ chấm: KHONG_CO",
                assertThrows(IllegalArgumentException.class,
                        () -> banGiao.xuatGoi("KHONG_CO")).getMessage());
    }

    static Stream<Arguments> ut14_chuaXuatBan() {
        return Stream.of(
                Arguments.of("UTCID06", (String) null),
                Arguments.of("UTCID07", "")
        );
    }

    @ParameterizedTest(name = "UT14 {0}: testcasePath = [{1}]")
    @MethodSource("ut14_chuaXuatBan")
    @DisplayName("UT14 — chưa xuất bản testcase thì chưa có gì để giao")
    void ut14_chuaXuatBan(String utcid, String duong) {
        de(MA_DE, duong);

        assertTrue(assertThrows(IllegalStateException.class, () -> banGiao.xuatGoi(MA_DE))
                .getMessage().contains("chưa xuất bản testcase"), utcid);
    }

    @Test
    @DisplayName("UT14 UTCID08 — đường dẫn testcase trỏ vào chỗ không còn tồn tại")
    void ut14_utcid08_thuMucBienMat() {
        de(MA_DE, thuMuc.resolve("da-bi-xoa").toString());

        assertTrue(assertThrows(IllegalStateException.class, () -> banGiao.xuatGoi(MA_DE))
                .getMessage().startsWith("Không tìm thấy thư mục testcase của"));
    }

    @Test
    @DisplayName("UT14 UTCID09 — thiếu exam_test.dart thì vân tay engine là null, KHÔNG chặn lần xuất")
    void ut14_utcid09_thieuEngine() throws Exception {
        Files.delete(testcase.resolve("exam_test.dart"));
        deXuatDuoc();

        Map<String, String> trongGoi = giaiNen(banGiao.xuatGoi(MA_DE));
        Map<?, ?> meta = mapper.readValue(trongGoi.get(BanGiaoService.TEN_META), Map.class);

        assertNull(meta.get("engine_sha256"), "không tính được vân tay thì ghi null, không nổ");
    }

    @Test
    @DisplayName("UT14 UTCID10 — tờ khai của lần NHẬP trước bị loại, gói mới chỉ mang một tờ khai")
    void ut14_utcid10_loaiToKhaiCu() throws Exception {
        Files.writeString(testcase.resolve(BanGiaoService.TEN_META),
                "{\"exam_id\":\"DE_CU\",\"base_image\":\"grading-base:cu\"}", StandardCharsets.UTF_8);
        deXuatDuoc();

        Map<String, String> trongGoi = giaiNen(banGiao.xuatGoi(MA_DE));
        Map<?, ?> meta = mapper.readValue(trongGoi.get(BanGiaoService.TEN_META), Map.class);

        assertEquals(MA_DE, meta.get("exam_id"),
                "hai tờ khai trong một gói là bên kia đọc nhầm tờ cũ");
        assertEquals(ANH_CHAM, meta.get("base_image"));
    }

    // ══════════════════ UT15 — BanGiaoService.tenTepGoi ══════════════════

    static Stream<Arguments> ut15() {
        return Stream.of(
                Arguments.of("UTCID01", MA_DE, "bo-cham-PE_PRM393_FA26.zip"),
                Arguments.of("UTCID02", "", "bo-cham-.zip"),
                Arguments.of("UTCID03", (String) null, "bo-cham-null.zip")
        );
    }

    @ParameterizedTest(name = "UT15 {0}: {1} → {2}")
    @MethodSource("ut15")
    @DisplayName("UT15 — tên tệp tải về")
    void ut15_tenTepGoi(String utcid, String examId, String mongDoi) {
        assertEquals(mongDoi, banGiao.tenTepGoi(examId), utcid);
    }

    // ══════════════════ UT16 — BanGiaoService.nhapGoi ══════════════════

    /** Dựng lại bản ghi đề đúng như examService.setupExamFromZipBytes làm ở bản thật. */
    private void giaLapNapTestcase(String examId) throws Exception {
        when(examService.goiCoTrongAnhCham()).thenReturn(Set.of("flutter", "intl"));
        when(examService.setupExamFromZipBytes(anyString(), any(), any()))
                .thenAnswer(inv -> {
                    de(examId, testcase.toString());
                    return null;
                });
    }

    @Test
    @DisplayName("UT16 UTCID01 — nạp gói hợp lệ: dựng lại đề và báo nhãn ảnh của CẢ HAI bên")
    void ut16_utcid01_napGoiDat() throws Exception {
        giaLapNapTestcase(MA_DE);

        Map<String, Object> ra = banGiao.nhapGoi(goiBanGiao(MA_DE));

        assertEquals(MA_DE, ra.get("exam_id"));
        assertEquals("PE PRM393 FA26", ra.get("exam_name"));
        assertEquals(ANH_CHAM, ra.get("base_image_cua_goi"));
        assertEquals(ANH_CHAM, ra.get("base_image_may_nay"),
                "hai máy khác ảnh là cùng một bài cho hai kết quả — bên nhận phải đối chiếu được");
        verify(examService).setupExamFromZipBytes(eq(MA_DE), eq("PE PRM393 FA26"), any());
    }

    @Test
    @DisplayName("UT16 UTCID02 — ảnh chấm thiếu package thì TỪ CHỐI cả gói, không dựng đề dở dang")
    void ut16_utcid02_thieuGoiThiTuChoi() throws Exception {
        giaLapNapTestcase(MA_DE);
        when(examService.goiCoTrongAnhCham()).thenReturn(Set.of("flutter"));   // thiếu intl

        PackageAvailabilityException loi = assertThrows(PackageAvailabilityException.class,
                () -> banGiao.nhapGoi(goiBanGiao(MA_DE)));

        assertTrue(loi.getMessage().contains("intl"), loi.getMessage());
        assertTrue(loi.getMessage().contains("Thư viện chấm"),
                "phải chỉ đúng chỗ sửa: một bộ không chấm được thì đừng để nó trông như chấm được");
        verify(examService, never()).setupExamFromZipBytes(anyString(), any(), any());
    }

    @Test
    @DisplayName("UT16 UTCID03 — hợp đồng hỏng thì bỏ qua phép kiểm package chứ không chặn cả gói")
    void ut16_utcid03_hopDongHong() throws Exception {
        giaLapNapTestcase(MA_DE);

        assertDoesNotThrow(() -> banGiao.nhapGoi(goiBanGiao(MA_DE, "khong phai json {{{")));
        verify(examService).setupExamFromZipBytes(anyString(), any(), any());
    }

    @Test
    @DisplayName("UT16 UTCID04 — không đọc được ảnh chấm thì CHO QUA: \"không biết\" khác \"không có\"")
    void ut16_utcid04_khongDocDuocAnhCham() throws Exception {
        giaLapNapTestcase(MA_DE);
        when(examService.goiCoTrongAnhCham()).thenReturn(Set.of());   // Docker chưa chạy

        assertDoesNotThrow(() -> banGiao.nhapGoi(goiBanGiao(MA_DE)));
        verify(examService).setupExamFromZipBytes(anyString(), any(), any());
    }

    static Stream<Arguments> ut16_goiRong() {
        return Stream.of(
                Arguments.of("UTCID05", (byte[]) null),
                Arguments.of("UTCID06", new byte[0])
        );
    }

    @ParameterizedTest(name = "UT16 {0}")
    @MethodSource("ut16_goiRong")
    @DisplayName("UT16 — chưa chọn gói")
    void ut16_goiRong(String utcid, byte[] zip) {
        assertEquals("Chưa chọn gói bàn giao (.zip).",
                assertThrows(IllegalArgumentException.class, () -> banGiao.nhapGoi(zip)).getMessage(), utcid);
    }

    @Test
    @DisplayName("UT16 UTCID07 — zip thường (không có tờ khai) không phải gói bàn giao")
    void ut16_utcid07_khongPhaiGoiBanGiao() throws Exception {
        byte[] zip = nen(Map.of("exam_test.dart", "void main() {}"));

        IllegalArgumentException loi = assertThrows(IllegalArgumentException.class,
                () -> banGiao.nhapGoi(zip));

        assertTrue(loi.getMessage().startsWith("Gói này không có " + BanGiaoService.TEN_META));
        assertTrue(loi.getMessage().contains("Xuất bộ chấm"), "phải chỉ ra chỗ lấy gói đúng");
    }

    @Test
    @DisplayName("UT16 UTCID08/09 — biên 200 MB: đúng trần vẫn nhận, quá một byte thì loại")
    void ut16_utcid08_09_bienDungLuong() throws Exception {
        long tran = 200L * 1024 * 1024;

        byte[] qua = new byte[(int) tran + 1];
        assertEquals("Gói bàn giao vượt quá giới hạn 200 MB.",
                assertThrows(IllegalArgumentException.class, () -> banGiao.nhapGoi(qua)).getMessage(),
                "UTCID09");

        // UTCID08: đúng trần thì đi tiếp — dừng ở bước đọc tờ khai, không dừng vì dung lượng.
        byte[] vua = new byte[(int) tran];
        assertTrue(assertThrows(IllegalArgumentException.class, () -> banGiao.nhapGoi(vua))
                        .getMessage().startsWith("Gói này không có"),
                "UTCID08: đúng trần phải qua được cổng dung lượng");
    }

    static Stream<Arguments> ut16_thieuMaDe() {
        return Stream.of(
                Arguments.of("UTCID10", ""),
                Arguments.of("UTCID11", (String) null)
        );
    }

    @ParameterizedTest(name = "UT16 {0}: exam_id = [{1}]")
    @MethodSource("ut16_thieuMaDe")
    @DisplayName("UT16 — tờ khai không ghi mã đề thì không biết dựng lại đề nào")
    void ut16_thieuMaDe(String utcid, String examId) throws Exception {
        byte[] zip = goiBanGiao(examId);

        assertEquals("Tờ khai trong gói không ghi mã đề.",
                assertThrows(IllegalArgumentException.class, () -> banGiao.nhapGoi(zip)).getMessage(), utcid);
        verify(examService, never()).setupExamFromZipBytes(anyString(), any(), any());
    }

    @Test
    @DisplayName("UT16 UTCID12 — nạp xong mà không thấy bản ghi đề thì phải nổ, không báo thành công")
    void ut16_utcid12_napXongKhongThayDe() throws Exception {
        when(examService.goiCoTrongAnhCham()).thenReturn(Set.of("flutter", "intl"));
        when(exams.findByExamId(MA_DE)).thenReturn(Optional.empty());   // setup không ghi được bản ghi

        assertTrue(assertThrows(IllegalStateException.class,
                () -> banGiao.nhapGoi(goiBanGiao(MA_DE)))
                .getMessage().startsWith("Nạp xong nhưng không thấy bản ghi đề"));
    }

    // ══════════════════ UT18 — ExamService.goiConThieuCuaDe ══════════════════

    /** ExamService nhận repo bằng field injection; dựng tay thì phải gán thẳng. */
    private ExamService examServiceThat(Path duongTestcase, Set<String> goiTrongAnh) throws Exception {
        ExamRepository repo = mock(ExamRepository.class);
        Exam e = new Exam();
        e.setExamId(MA_DE);
        e.setTestcasePath(duongTestcase == null ? null : duongTestcase.toString());
        when(repo.findByExamId(MA_DE)).thenReturn(Optional.of(e));

        ExamService svc = new ExamService() {
            @Override
            public Set<String> goiCoTrongAnhCham() {
                return goiTrongAnh;
            }
        };
        datField(svc, "examRepository", repo);
        // testcaseDirOf còn một đường dự phòng là QUÉT ĐĨA exams/<examId>/testcase. Ca "đề chưa
        // có testcase" phải trỏ gốc exams sang chỗ trống, không thì nó tìm thấy bộ dựng sẵn ở trên.
        datField(svc, "examsDir", (duongTestcase == null ? thuMuc.resolve("khong-co-exams")
                : thuMuc.resolve("exams")).toString());
        datField(svc, "templateDir", thuMuc.resolve("grader-base").toString());
        return svc;
    }

    @Test
    @DisplayName("UT18 UTCID01 — ảnh có đủ thì không thiếu gì")
    void ut18_utcid01_anhDuGoi() throws Exception {
        ExamService svc = examServiceThat(testcase,
                Set.of("flutter", "flutter_test", "intl", "sqflite", "path"));

        Map<String, Object> ra = svc.goiConThieuCuaDe(MA_DE);

        assertEquals(true, ra.get("doc_duoc_anh"));
        assertEquals(List.of("flutter", "intl", "sqflite"), ra.get("de_can"));
        assertEquals(List.of(), ra.get("thieu"));
    }

    @Test
    @DisplayName("UT18 UTCID02 — ảnh thiếu gói nào thì kể đúng gói đó")
    void ut18_utcid02_anhThieuGoi() throws Exception {
        ExamService svc = examServiceThat(testcase, Set.of("flutter", "flutter_test", "sqflite"));

        assertEquals(List.of("intl"), svc.goiConThieuCuaDe(MA_DE).get("thieu"));
    }

    @Test
    @DisplayName("UT18 UTCID03 — Docker tắt: không biết thì đừng doạ người dùng là thiếu hết")
    void ut18_utcid03_dockerTat() throws Exception {
        ExamService svc = examServiceThat(testcase, Set.of());

        Map<String, Object> ra = svc.goiConThieuCuaDe(MA_DE);

        assertEquals(false, ra.get("doc_duoc_anh"));
        assertEquals(List.of("flutter", "intl", "sqflite"), ra.get("de_can"));
        assertEquals(List.of(), ra.get("thieu"), "chưa đọc được ảnh thì KHÔNG được liệt kê thiếu");
    }

    static Stream<Arguments> ut18_contractKhongDung() {
        return Stream.of(
                Arguments.of("UTCID05", "{\"allowed_packages\":\"intl\"}"),      // sai kiểu
                Arguments.of("UTCID06", "{\"allowed_packages\": [\"intl\"")      // JSON cụt
        );
    }

    @ParameterizedTest(name = "UT18 {0}")
    @MethodSource("ut18_contractKhongDung")
    @DisplayName("UT18 — contract.json sai kiểu hoặc hỏng thì trả danh sách rỗng, không ném")
    void ut18_contractKhongDung(String utcid, String noiDung) throws Exception {
        Files.writeString(testcase.resolve("contract.json"), noiDung, StandardCharsets.UTF_8);
        ExamService svc = examServiceThat(testcase, Set.of("flutter", "flutter_test"));

        Map<String, Object> ra = assertDoesNotThrow(() -> svc.goiConThieuCuaDe(MA_DE), utcid);

        assertEquals(List.of(), ra.get("de_can"), utcid);
        assertEquals(List.of(), ra.get("thieu"), utcid);
    }

    @Test
    @DisplayName("UT18 UTCID07 — tên trùng lặp trong contract chỉ tính một lần")
    void ut18_utcid07_tenTrungLap() throws Exception {
        Files.writeString(testcase.resolve("contract.json"),
                "{\"allowed_packages\":[\"intl\",\"intl\",\"sqflite\"]}", StandardCharsets.UTF_8);
        ExamService svc = examServiceThat(testcase, Set.of("flutter", "flutter_test"));

        assertEquals(List.of("intl", "sqflite"), svc.goiConThieuCuaDe(MA_DE).get("de_can"));
    }

    @Test
    @DisplayName("UT18 UTCID04 — không có thư mục testcase thì de_can rỗng, không nổ")
    void ut18_utcid04_khongCoTestcase() throws Exception {
        ExamService svc = examServiceThat(null, Set.of("flutter", "flutter_test"));

        Map<String, Object> ra = assertDoesNotThrow(() -> svc.goiConThieuCuaDe(MA_DE));

        assertEquals(List.of(), ra.get("de_can"));
    }

    /** Giữ danh sách trả về ổn định (đã sắp xếp) để giao diện không nhảy thứ tự giữa hai lần gọi. */
    @Test
    @DisplayName("UT18 — danh sách thiếu luôn được sắp xếp")
    void ut18_danhSachThieuLuonSapXep() throws Exception {
        Files.writeString(testcase.resolve("contract.json"),
                "{\"allowed_packages\":[\"sqflite\",\"intl\",\"path\"]}", StandardCharsets.UTF_8);
        ExamService svc = examServiceThat(testcase, Set.of("flutter", "flutter_test"));

        @SuppressWarnings("unchecked")
        List<String> thieu = (List<String>) svc.goiConThieuCuaDe(MA_DE).get("thieu");

        assertEquals(new ArrayList<>(List.of("intl", "path", "sqflite")), thieu);
    }
}
