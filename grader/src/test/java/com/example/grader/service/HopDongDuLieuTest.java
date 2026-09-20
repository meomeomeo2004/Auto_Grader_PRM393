package com.example.grader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chẩn đoán hợp đồng dữ liệu. Hai điều phải giữ bằng mọi giá: (1) không bao giờ ném ra ngoài,
 * (2) không có đường nào chạm vào điểm. Phần còn lại là chuyện báo đúng chỗ lệch và — quan
 * trọng không kém — IM LẶNG khi chỉ khác cách viết.
 */
class HopDongDuLieuTest {

    @TempDir Path thuMuc;

    // ==================== DỰNG SÂN ====================

    private Path duAn(String maDatabaseHelper) throws Exception {
        Path goc = Files.createDirectories(thuMuc.resolve("bai_nop_" + System.nanoTime()));
        Path lib = Files.createDirectories(goc.resolve("lib/data"));
        Files.writeString(lib.resolve("database_helper.dart"), maDatabaseHelper, StandardCharsets.UTF_8);
        return goc;
    }

    private Path db(String... ddl) throws Exception {
        Path file = thuMuc.resolve("hidden_" + System.nanoTime() + ".db");
        try (Connection ket = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement st = ket.createStatement()) {
            for (String sql : ddl) st.execute(sql);
        }
        return file;
    }

    private static final String HELPER_DUNG = """
            import 'package:sqflite/sqflite.dart';

            class DatabaseHelper {
              Future<void> _taoBang(Database db) async {
                await db.execute(
                  "CREATE TABLE IF NOT EXISTS chi_tieu ("
                  "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                  "tieu_de TEXT NOT NULL, "
                  "so_tien REAL NOT NULL, "
                  "danh_muc TEXT NOT NULL, "
                  "ngay_chi TEXT NOT NULL)");
              }
            }
            """;

    private static final String DDL_DUNG =
            "CREATE TABLE chi_tieu (id INTEGER PRIMARY KEY AUTOINCREMENT, tieu_de TEXT NOT NULL, "
                    + "so_tien REAL NOT NULL, danh_muc TEXT NOT NULL, ngay_chi TEXT NOT NULL)";

    // ==================== QUY KIỂU ====================

    @Test
    void kieuKhacCachVietNhungCungLopLuuTruThiCoiLaGiongNhau() {
        assertEquals("TEXT", HopDongDuLieu.affinity("VARCHAR(50)"));
        assertEquals("TEXT", HopDongDuLieu.affinity("TEXT"));
        assertEquals("TEXT", HopDongDuLieu.affinity("nvarchar(10)"));
        assertEquals("INTEGER", HopDongDuLieu.affinity("INT"));
        assertEquals("INTEGER", HopDongDuLieu.affinity("BIGINT"));
        assertEquals("REAL", HopDongDuLieu.affinity("DOUBLE"));
        assertEquals("REAL", HopDongDuLieu.affinity("FLOAT"));
        assertEquals("BLOB", HopDongDuLieu.affinity(""), "cột không khai kiểu là hợp lệ trong SQLite");
        assertEquals("NUMERIC", HopDongDuLieu.affinity("DECIMAL(10,2)"));
    }

    @Test
    void phayTrongNgoacKhongLamVoDinhNghiaCot() {
        List<String> ra = HopDongDuLieu.tachCot("A DECIMAL(10,2), B TEXT, PRIMARY KEY (A, B)");

        assertEquals(3, ra.size(), ra.toString());
        assertEquals("PRIMARY KEY (A, B)", ra.get(2).trim());
    }

    // ==================== ĐỌC HAI PHÍA ====================

    @Test
    void bocDuocHinhDangTuMaDartVaBoRangBuocCapBang() throws Exception {
        Path goc = duAn("""
                const sql = "CREATE TABLE chi_tieu (id INTEGER, ma TEXT, PRIMARY KEY (id), "
                    "FOREIGN KEY (ma) REFERENCES danh_muc(ma))";
                """);

        Map<String, Map<String, String>> ra = HopDongDuLieu.hinhDangTuNguon(goc);

        assertEquals(Map.of("ID", "INTEGER", "MA", "TEXT"), ra.get("chi_tieu"),
                "PRIMARY KEY / FOREIGN KEY là ràng buộc cấp bảng, không phải cột");
    }

    @Test
    void docDuocHinhDangThatCuaDatabaseCham() throws Exception {
        Map<String, Map<String, String>> ra = HopDongDuLieu.hinhDangTuDatabase(db(DDL_DUNG));

        assertEquals(1, ra.size());
        assertEquals(Map.of("ID", "INTEGER", "TIEU_DE", "TEXT", "SO_TIEN", "REAL",
                "DANH_MUC", "TEXT", "NGAY_CHI", "TEXT"), ra.get("chi_tieu"));
    }

    // ==================== SO ====================

    @Test
    void baiKhongSuaFileCapSanThiImLang() throws Exception {
        assertEquals(List.of(), HopDongDuLieu.kiem(duAn(HELPER_DUNG), db(DDL_DUNG)));
    }

    @Test
    void doiCachKhaiKieuNhungCungLopLuuTruVanImLang() throws Exception {
        // VARCHAR(100) thay cho TEXT: SQLite coi là một, báo động ở đây là báo oan.
        Path goc = duAn(HELPER_DUNG.replace("tieu_de TEXT NOT NULL", "tieu_de VARCHAR(100) NOT NULL"));

        assertEquals(List.of(), HopDongDuLieu.kiem(goc, db(DDL_DUNG)));
    }

    @Test
    void doiTenCotTrongMaNguonThiChiRoCauTruyVanSeGay() throws Exception {
        Path goc = duAn(HELPER_DUNG.replace("so_tien REAL", "amount REAL"));

        List<String> ra = HopDongDuLieu.kiem(goc, db(DDL_DUNG));

        assertEquals(2, ra.size(), ra.toString());
        assertTrue(ra.stream().anyMatch(s -> s.contains("AMOUNT") && s.contains("no such column")),
                "cột chỉ có bên mã nguồn là hướng nguy hiểm, phải nói rõ hậu quả: " + ra);
        assertTrue(ra.stream().anyMatch(s -> s.contains("SO_TIEN") && s.contains("không khai")), ra.toString());
    }

    @Test
    void doiKieuCotThiBaoDungCaHaiBen() throws Exception {
        Path goc = duAn(HELPER_DUNG.replace("so_tien REAL", "so_tien INTEGER"));

        List<String> ra = HopDongDuLieu.kiem(goc, db(DDL_DUNG));

        assertEquals(1, ra.size(), ra.toString());
        assertTrue(ra.get(0).contains("SO_TIEN") && ra.get(0).contains("INTEGER")
                && ra.get(0).contains("REAL"), ra.get(0));
    }

    @Test
    void thayHanFileCapSanThiBaoMotSuViec_khongPhaiNBang() throws Exception {
        // Không còn CREATE TABLE nào: liệt kê từng bảng thiếu là mô tả sai bản chất.
        List<String> ra = HopDongDuLieu.kiem(duAn("class DatabaseHelper {}"), db(DDL_DUNG));

        assertEquals(1, ra.size(), ra.toString());
        assertTrue(ra.get(0).contains("không có câu CREATE TABLE nào"), ra.get(0));
        assertTrue(ra.get(0).contains("chi_tieu"), "vẫn phải nêu bảng nào đang chờ: " + ra.get(0));
    }

    @Test
    void lechQuaNhieuThiCatBotChoNguoiCoTheDoc() {
        Map<String, String> cot = new java.util.TreeMap<>();
        for (int i = 0; i < 40; i++) cot.put("C" + i, "TEXT");

        List<String> ra = HopDongDuLieu.soSanh(Map.of("t", cot), Map.of("t", Map.of("C0", "TEXT")));

        assertEquals(21, ra.size(), "20 dòng cộng một dòng tổng kết");
        assertTrue(ra.get(20).startsWith("… và "), ra.get(20));
    }

    // ==================== KHÔNG BAO GIỜ LÀM HỎNG LƯỢT CHẤM ====================

    @Test
    void thieuHiddenDbThiTraVeRong_khongNem() throws Exception {
        assertEquals(List.of(), HopDongDuLieu.kiem(duAn(HELPER_DUNG), thuMuc.resolve("khong-co.db")));
        assertEquals(List.of(), HopDongDuLieu.kiem(duAn(HELPER_DUNG), null));
    }

    @Test
    void fileKhongPhaiSqliteThiTraVeRong_khongNem() throws Exception {
        Path rac = thuMuc.resolve("rac.db");
        Files.writeString(rac, "day khong phai database", StandardCharsets.UTF_8);

        assertEquals(List.of(), HopDongDuLieu.kiem(duAn(HELPER_DUNG), rac));
    }

    @Test
    void deChuaXuatBanTestcaseThiKhongCoDuongDanHiddenDb() {
        org.junit.jupiter.api.Assertions.assertNull(HopDongDuLieu.hiddenDbCuaTestcase(null));
        org.junit.jupiter.api.Assertions.assertNull(HopDongDuLieu.hiddenDbCuaTestcase("  "));
        assertTrue(HopDongDuLieu.hiddenDbCuaTestcase("/x/testcase").toString()
                .replace('\\', '/').endsWith("/x/testcase/fixtures/hidden.db"));
    }

    // ==================== ĐƯA RA NGOÀI MÀ KHÔNG ĐỔI ĐIỂM ====================

    @Test
    void ganKetLuanVaoResultJsonMaKhongDungVaoTestCases() throws Exception {
        String goc = "{\"grading_result\":{\"score\":7.5},\"test_cases\":[{\"test_id\":\"A\",\"status\":\"failed\"}]}";

        String ra = HopDongDuLieu.ganVaoKetQua(goc, List.of("Bảng \"chi_tieu\": cột X thiếu."));

        JsonNode cay = new ObjectMapper().readTree(ra);
        assertEquals(7.5, cay.path("grading_result").path("score").asDouble(), 0.0001);
        assertEquals(1, cay.path("test_cases").size());
        assertEquals("failed", cay.path("test_cases").get(0).path("status").asText());
        assertFalse(cay.path(HopDongDuLieu.KHOA).path("khop").asBoolean(true));
        assertEquals(1, cay.path(HopDongDuLieu.KHOA).path("khac_biet").size());
    }

    @Test
    void khongLechThiKhongThemKhoaNaoVaoKetQua() {
        String goc = "{\"grading_result\":{\"score\":10}}";

        assertSame(goc, HopDongDuLieu.ganVaoKetQua(goc, List.of()));
    }

    @Test
    void ketQuaVoThiTraNguyenChuoiCu_thaMatLoiGiaiThichConHonVoJson() {
        String vo = "khong phai json";

        assertEquals(vo, HopDongDuLieu.ganVaoKetQua(vo, List.of("co lech")));
    }

    @Test
    void docNguocDuocKetLuanDeNoiVaoBangKetQua() {
        List<String> lech = List.of("Bảng \"chi_tieu\": cột X thiếu.", "Bảng \"chi_tieu\": cột Y thừa.");

        assertEquals(lech, HopDongDuLieu.docTuKetQua(
                HopDongDuLieu.ganVaoKetQua("{\"grading_result\":{\"score\":0}}", lech)));
    }

    @Test
    void ketQuaKhongCoKhoaThiDocRaRong() {
        assertEquals(List.of(), HopDongDuLieu.docTuKetQua("{\"grading_result\":{\"score\":10}}"));
        assertEquals(List.of(), HopDongDuLieu.docTuKetQua("khong phai json"));
        assertEquals(List.of(), HopDongDuLieu.docTuKetQua(null));
    }

    @Test
    void baiKhongCoChanDoanNaoThiKhongTuDungMotCaiMoi() {
        // null vào → null ra: bài chạy sạch mà lệch schema thì ghi chú nằm trong result.json,
        // KHÔNG được biến thành một chẩn đoán cấp lượt chấm (đổi cách hệ thống xử lý bài).
        org.junit.jupiter.api.Assertions.assertNull(
                HopDongDuLieu.themVaoChanDoan(null, List.of("co lech")));
    }

    @Test
    void noiVaoChanDoanMaGiuNguyenPhanLoai() {
        GradingDiagnosticException goc = new GradingDiagnosticException(
                "STUDENT_COMPILE_ERROR", GradingDiagnosticException.Origin.STUDENT,
                "SOURCE_COMPILE", false, "Mã nguồn không biên dịch được.");

        GradingDiagnosticException ra = HopDongDuLieu.themVaoChanDoan(goc, List.of("Cột X thiếu."));

        assertEquals("STUDENT_COMPILE_ERROR", ra.code());
        assertEquals(GradingDiagnosticException.Origin.STUDENT, ra.origin());
        assertEquals("SOURCE_COMPILE", ra.stage());
        assertFalse(ra.manualReview(), "chẩn đoán này KHÔNG được đẩy bài sang chấm tay");
        assertTrue(ra.getMessage().startsWith("Mã nguồn không biên dịch được."), ra.getMessage());
        assertTrue(ra.getMessage().contains("Cột X thiếu."), ra.getMessage());
    }

    @Test
    void khongLechThiChanDoanGiuNguyenKhongDungToi() {
        GradingDiagnosticException goc = new GradingDiagnosticException(
                "X", GradingDiagnosticException.Origin.TESTCASE, "S", true, "y");

        assertSame(goc, HopDongDuLieu.themVaoChanDoan(goc, List.of()));
    }
}
