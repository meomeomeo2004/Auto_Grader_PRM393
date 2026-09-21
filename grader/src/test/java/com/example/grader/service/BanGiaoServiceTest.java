package com.example.grader.service;

import com.example.grader.entity.Exam;
import com.example.grader.repository.ExamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bàn giao bộ chấm giữa bản giảng viên và bản người chấm.
 *
 * <p>Trước 19/9, phần lớn test ở đây nhắm vào DẤU KIỂM ĐỒNG BỘ KHUNG PHÁT: cổng chặn trước cửa
 * chấm tra bảng Golden, mà bên người chấm hai bảng ấy rỗng, nên phải truyền dấu qua tờ khai.
 * Cả cơ chế đó đã bỏ — khung phát nay do máy sinh từ chính Golden đang xuất, nên "khung khớp
 * Golden" đúng theo cấu tạo chứ không phải thứ phải đi kiểm rồi mang dấu đi theo.
 *
 * <p>Còn lại ở đây là hình dạng gói và tờ khai: hai thứ bên kia thật sự đọc.
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
        when(examService.vanTayKhungPhat("DE_X")).thenReturn("van-tay-khung-abc");
    }

    private Exam de() {
        Exam e = new Exam();
        e.setExamId("DE_X");
        e.setExamName("Đề X");
        e.setTestcasePath(testcase.toString());
        return e;
    }

    private BanGiaoService dichVu() {
        when(exams.findByExamId("DE_X")).thenReturn(Optional.of(de()));
        return new BanGiaoService(exams, examService, "grading-base:2026-08-22e");
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
        byte[] zip = dichVu().xuatGoi("DE_X");

        Set<String> ten = new TreeSet<>(boc(zip).keySet());
        assertTrue(ten.contains("exam_test.dart"), "engine phải nằm ở GỐC gói, đúng hình dạng đường nạp cũ");
        assertTrue(ten.contains("contract.json"));
        assertTrue(ten.contains("fixtures/hidden.db"), "thư mục con phải giữ nguyên đường dẫn");
        assertTrue(ten.contains(BanGiaoService.TEN_META));
    }

    @Test
    void toKhaiGhiNhanAnhNen_vanTayEngine_vaVanTayKhungPhat() throws Exception {
        byte[] zip = dichVu().xuatGoi("DE_X");

        Map<?, ?> meta = mapper.readValue(boc(zip).get(BanGiaoService.TEN_META), Map.class);
        assertEquals("DE_X", meta.get("exam_id"));
        assertEquals("Đề X", meta.get("exam_name"));
        assertEquals("grading-base:2026-08-22e", meta.get("base_image"));
        assertNotNull(meta.get("engine_sha256"), "phải có vân tay engine để truy khi điểm lệch");
        // Hai trường đã gỡ khỏi tờ khai (21/9/2026): `khung_van_tay` chỉ có nghĩa bên giảng viên
        // (so với bản ghi đề để biết Golden đã đổi chưa), còn `teacher_note` thì bên người chấm
        // lưu vào DB mà không màn nào in ra. Mang đi là để người đọc tưởng bên kia có dùng.
        assertNull(meta.get("khung_van_tay"));
        assertNull(meta.get("teacher_note"));
    }

    /** Vân tay được đóng vào bản ghi đề để LẦN XUẤT SAU biết Golden đã đổi ở chỗ khung lấy về chưa. */
    @Test
    void xuatGoiDongDauVanTayKhungVaoBanGhiDe() throws Exception {
        Exam e = de();
        when(exams.findByExamId("DE_X")).thenReturn(Optional.of(e));

        new BanGiaoService(exams, examService, "grading-base:2026-08-22e").xuatGoi("DE_X");

        assertEquals("van-tay-khung-abc", e.getKhungVanTay());
    }

    @Test
    void xuatLanHaiKhongKemToKhaiCuCuaLanTruoc() throws Exception {
        // Bộ này từng được nạp từ một gói khác nên trong thư mục đã có sẵn một tờ khai cũ.
        Files.writeString(testcase.resolve(BanGiaoService.TEN_META),
                "{\"exam_id\":\"DE_CU\"}", StandardCharsets.UTF_8);

        byte[] zip = dichVu().xuatGoi("DE_X");

        Map<?, ?> meta = mapper.readValue(boc(zip).get(BanGiaoService.TEN_META), Map.class);
        assertEquals("DE_X", meta.get("exam_id"), "phải là tờ khai mới, không phải tờ còn sót lại");
    }

    // ==================== NẠP ====================

    /** Bên nhận: mock để ảnh chấm coi như đủ gói, phép kiểm package có đường riêng bên dưới. */
    private BanGiaoService benNhan(String anhNen) {
        Exam moi = new Exam();
        moi.setExamId("DE_X");
        moi.setTestcasePath(testcase.toString());
        ExamRepository exams2 = mock(ExamRepository.class);
        when(exams2.findByExamId("DE_X")).thenReturn(Optional.of(moi));
        return new BanGiaoService(exams2, examService, anhNen);
    }

    @Test
    void napGoiTraVeThongTinDoiChieuChoBenNhan() throws Exception {
        byte[] zip = dichVu().xuatGoi("DE_X");
        when(examService.goiCoTrongAnhCham()).thenReturn(Set.of("intl"));

        Map<String, Object> ra = benNhan("grading-base:khac").nhapGoi(zip);

        assertEquals("DE_X", ra.get("exam_id"));
        assertEquals("grading-base:2026-08-22e", ra.get("base_image_cua_goi"));
        assertEquals("grading-base:khac", ra.get("base_image_may_nay"),
                "hai máy khác ảnh là cùng một bài cho hai kết quả — bên nhận phải thấy được");
        assertNull(ra.get("khung_van_tay"), "bên nhận không có gì để đối chiếu vân tay khung");
    }

    // ==================== CỬA CHẶN THIẾU THƯ VIỆN ====================

    @Test
    void anhChamThieuGoiThiTuChoiCaGoi_khongDungBoChamNaoLen() throws Exception {
        byte[] zip = dichVu().xuatGoi("DE_X");
        when(examService.goiCoTrongAnhCham()).thenReturn(Set.of("flutter"));   // thiếu intl

        PackageAvailabilityException loi = assertThrows(PackageAvailabilityException.class,
                () -> benNhan("grading-base:khac").nhapGoi(zip));

        assertTrue(loi.getMessage().contains("intl"), loi.getMessage());
        assertTrue(loi.getMessage().contains("CHƯA được nhận"),
                "phải nói rõ gói không vào máy, nếu không người dùng đi tìm nó trong danh sách: "
                        + loi.getMessage());
        assertEquals(List.of("intl"), loi.response().get("missing_packages"));
    }

    @Test
    void khongDocDuocAnhChamThiVanCHoNap_khongBietKhacVoiAnhRong() throws Exception {
        byte[] zip = dichVu().xuatGoi("DE_X");
        // Docker chưa chạy → goiCoTrongAnhCham trả tập rỗng. Chặn ở đây là chặn oan mọi gói.
        when(examService.goiCoTrongAnhCham()).thenReturn(Set.of());

        assertEquals("DE_X", benNhan("grading-base:khac").nhapGoi(zip).get("exam_id"));
    }

    @Test
    void goiKhongPhaiBanGiaoThiTuChoiNgay() {
        BanGiaoService dv = new BanGiaoService(exams, examService, "grading-base:latest");

        byte[] zipTron = new byte[] {0x50, 0x4b, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        IllegalArgumentException loi = assertThrows(IllegalArgumentException.class, () -> dv.nhapGoi(zipTron));
        assertTrue(loi.getMessage().contains(BanGiaoService.TEN_META), loi.getMessage());
    }
}
