package com.example.grader.service;

import com.example.grader.entity.Exam;
import com.example.grader.repository.BehaviorSuiteRepository;
import com.example.grader.repository.ExamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bàn giao bộ chấm giữa bản giảng viên và bản người chấm.
 *
 * <p>Cái đáng sợ nhất ở khâu này không phải nén hay giải nén, mà là DẤU KIỂM ĐỒNG BỘ KHUNG PHÁT:
 * cổng chặn trước cửa chấm tính dấu đó bằng cách tra bảng Golden trong cơ sở dữ liệu, mà bên
 * người chấm hai bảng ấy rỗng. Không xử lý thì mọi đề giao sang đều bị khóa, kèm câu hướng dẫn
 * trỏ vào màn hình mà bản của họ không có. Nên phần lớn test ở đây nhắm thẳng vào chỗ đó.
 */
class BanGiaoServiceTest {

    @TempDir Path thuMuc;

    private Path testcase;
    private ExamRepository exams;
    private ExamService examService;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void dungBoChamGia() throws Exception {
        testcase = Files.createDirectories(thuMuc.resolve("exams/DE_X/testcase"));
        Files.writeString(testcase.resolve("exam_test.dart"), "void main() {}", StandardCharsets.UTF_8);
        Files.writeString(testcase.resolve("contract.json"), "{\"allowed_packages\":[\"intl\"]}", StandardCharsets.UTF_8);
        Files.createDirectories(testcase.resolve("fixtures"));
        Files.writeString(testcase.resolve("fixtures/hidden.db"), "gia-dinh-la-database", StandardCharsets.UTF_8);

        exams = mock(ExamRepository.class);
        examService = mock(ExamService.class);
    }

    private Exam deDaKiem() {
        Exam e = new Exam();
        e.setExamId("DE_X");
        e.setExamName("Đề X");
        e.setTeacherNote("ghi chú");
        e.setTestcasePath(testcase.toString());
        e.setStarterCheckRequired(true);
        e.setStarterCheckedGoldenSha("sha-cua-golden");
        e.setStarterCheckedAt(Instant.parse("2026-09-13T08:00:00Z"));
        return e;
    }

    private BanGiaoService dichVu(StarterSyncService dongBo) {
        when(exams.findByExamId("DE_X")).thenReturn(Optional.of(deDaKiem()));
        return new BanGiaoService(exams, examService, dongBo, "grading-base:2026-08-22e");
    }

    private static StarterSyncService dongBoGia(String trangThai, boolean chamDuoc) {
        StarterSyncService s = mock(StarterSyncService.class);
        when(s.trangThai(any())).thenReturn(trangThai);
        when(s.chamDuoc(any())).thenReturn(chamDuoc);
        return s;
    }

    private static Map<String, byte[]> boc(byte[] zip) throws Exception {
        Map<String, byte[]> ra = new java.util.LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (!e.isDirectory()) ra.put(e.getName(), zin.readAllBytes());
            }
        }
        return ra;
    }

    // ==================== XUẤT ====================

    @Test
    void goiMangTronVenThuMucTestcaseVaMotToKhai() throws Exception {
        byte[] zip = dichVu(dongBoGia(StarterSyncService.DAT, true)).xuatGoi("DE_X");

        Set<String> ten = new TreeSet<>(boc(zip).keySet());
        assertTrue(ten.contains("exam_test.dart"), "engine phải nằm ở GỐC gói, đúng hình dạng đường nạp cũ");
        assertTrue(ten.contains("contract.json"));
        assertTrue(ten.contains("fixtures/hidden.db"), "thư mục con phải giữ nguyên đường dẫn");
        assertTrue(ten.contains(BanGiaoService.TEN_META));
    }

    @Test
    void toKhaiChepDayDuDauKiemVaNhanAnhNen() throws Exception {
        byte[] zip = dichVu(dongBoGia(StarterSyncService.DAT, true)).xuatGoi("DE_X");

        Map<?, ?> meta = mapper.readValue(boc(zip).get(BanGiaoService.TEN_META), Map.class);
        assertEquals("DE_X", meta.get("exam_id"));
        assertEquals("Đề X", meta.get("exam_name"));
        assertEquals("grading-base:2026-08-22e", meta.get("base_image"));
        assertNotNull(meta.get("engine_sha256"), "phải có vân tay engine để truy khi điểm lệch");

        Map<?, ?> dau = (Map<?, ?>) meta.get("starter_check");
        assertEquals(StarterSyncService.DAT, dau.get("state"));
        assertEquals("sha-cua-golden", dau.get("golden_sha"));
        assertEquals("2026-09-13T08:00:00Z", dau.get("checked_at"));
    }

    @Test
    void deChuaKiemDongBoThiKhongChoXuat() {
        BanGiaoService dv = dichVu(dongBoGia(StarterSyncService.CHUA_KIEM, false));

        IllegalStateException loi = assertThrows(IllegalStateException.class, () -> dv.xuatGoi("DE_X"));
        assertTrue(loi.getMessage().contains("kiểm đồng bộ khung phát"),
                "lời báo phải nói đúng việc cần làm, và nói với người sửa được: " + loi.getMessage());
    }

    @Test
    void xuatLanHaiKhongKemToKhaiCuCuaLanTruoc() throws Exception {
        // Bộ này từng được nạp từ một gói khác nên trong thư mục đã có sẵn một tờ khai cũ.
        Files.writeString(testcase.resolve(BanGiaoService.TEN_META),
                "{\"exam_id\":\"DE_CU\",\"starter_check\":{\"golden_sha\":\"sha-cu\"}}", StandardCharsets.UTF_8);

        byte[] zip = dichVu(dongBoGia(StarterSyncService.DAT, true)).xuatGoi("DE_X");

        Map<?, ?> meta = mapper.readValue(boc(zip).get(BanGiaoService.TEN_META), Map.class);
        assertEquals("DE_X", meta.get("exam_id"), "phải là tờ khai mới, không phải tờ còn sót lại");
        assertEquals("sha-cua-golden", ((Map<?, ?>) meta.get("starter_check")).get("golden_sha"));
    }

    // ==================== NẠP ====================

    @Test
    void napGoiChepDauKiemSangBanGhiDe() throws Exception {
        byte[] zip = dichVu(dongBoGia(StarterSyncService.DAT, true)).xuatGoi("DE_X");

        // Máy bên nhận: chưa có đề, nạp xong mới có bản ghi (do ExamService dựng).
        Exam moi = new Exam();
        moi.setExamId("DE_X");
        moi.setTestcasePath(testcase.toString());
        ExamRepository exams2 = mock(ExamRepository.class);
        when(exams2.findByExamId("DE_X")).thenReturn(Optional.of(moi));
        when(examService.goiConThieuCuaDe("DE_X")).thenReturn(Map.of("thieu", List.of("intl")));

        StarterSyncService dongBo = dongBoGia(StarterSyncService.DAT, true);
        new BanGiaoService(exams2, examService, dongBo, "grading-base:2026-08-22e").nhapGoi(zip);

        assertTrue(moi.getStarterCheckRequired(), "vẫn phải yêu cầu kiểm, không hạ rào");
        assertEquals("sha-cua-golden", moi.getStarterCheckedGoldenSha(),
                "phải chép vân tay Golden sang, nếu không bên này bị khóa vĩnh viễn");
        assertEquals(Instant.parse("2026-09-13T08:00:00Z"), moi.getStarterCheckedAt());
    }

    @Test
    void goiKhongPhaiBanGiaoThiTuChoiNgay() {
        BanGiaoService dv = new BanGiaoService(exams, examService,
                dongBoGia(StarterSyncService.DAT, true), "grading-base:latest");

        byte[] zipTron = new byte[] {0x50, 0x4b, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        IllegalArgumentException loi = assertThrows(IllegalArgumentException.class, () -> dv.nhapGoi(zipTron));
        assertTrue(loi.getMessage().contains(BanGiaoService.TEN_META), loi.getMessage());
    }

    // ==================== CỔNG CHẶN BÊN NGƯỜI CHẤM ====================

    /**
     * Đây là cái bẫy thật: bên người chấm không có bảng Golden nên phép tra cũ luôn trả "chưa
     * kiểm". Ba ca dưới chạy trên StarterSyncService THẬT, chỉ giả phần kho dữ liệu.
     */
    private StarterSyncService dongBoThatKhongCoGolden(Exam exam) {
        ExamRepository kho = mock(ExamRepository.class);
        when(kho.findByExamId("DE_X")).thenReturn(Optional.of(exam));
        BehaviorSuiteRepository suites = mock(BehaviorSuiteRepository.class);
        when(suites.findByExamIdOrderByUpdatedAtDesc("DE_X")).thenReturn(new ArrayList<>());
        return new StarterSyncService(kho, suites, mock(BehaviorArtifactService.class), mock(ExamService.class));
    }

    private void datToKhai(String goldenSha) throws Exception {
        Files.writeString(testcase.resolve(BanGiaoService.TEN_META),
                "{\"exam_id\":\"DE_X\",\"starter_check\":{\"state\":\"OK\",\"golden_sha\":\"" + goldenSha + "\"}}",
                StandardCharsets.UTF_8);
    }

    @Test
    void benNguoiChamDocDauKiemTuToKhaiNenChamDuoc() throws Exception {
        datToKhai("sha-cua-golden");
        Exam exam = deDaKiem();

        StarterSyncService that = dongBoThatKhongCoGolden(exam);
        assertEquals(StarterSyncService.DAT, that.trangThai("DE_X"));
        assertTrue(that.chamDuoc("DE_X"), "gói đã đóng dấu thì bên người chấm phải chấm được");
    }

    @Test
    void toKhaiGhiVanTayKhacThiVanBiChan() throws Exception {
        datToKhai("sha-khac-hoan-toan");
        Exam exam = deDaKiem();

        StarterSyncService that = dongBoThatKhongCoGolden(exam);
        assertEquals(StarterSyncService.HET_HAN, that.trangThai("DE_X"));
        assertFalse(that.chamDuoc("DE_X"));
    }

    @Test
    void khongCoToKhaiThiVanLaChuaKiem() {
        Exam exam = deDaKiem();   // thư mục testcase không có tờ khai

        StarterSyncService that = dongBoThatKhongCoGolden(exam);
        assertEquals(StarterSyncService.CHUA_KIEM, that.trangThai("DE_X"));
        assertFalse(that.chamDuoc("DE_X"), "không có dấu thì không được nới tay");
    }
}
