package com.example.grader.service;

import com.example.grader.entity.BehaviorArtifact;
import com.example.grader.entity.BehaviorArtifactType;
import com.example.grader.entity.BehaviorSuite;
import com.example.grader.entity.Exam;
import com.example.grader.repository.BehaviorSuiteRepository;
import com.example.grader.repository.ExamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sheet UT11 — StarterSyncService.kiem (phần đối chiếu thật).
 *
 * <p>Phép kiểm này là chỗ DUY NHẤT phát hiện được Golden và khung phát cho sinh viên đã trôi xa
 * nhau. Trôi rồi thì không có triệu chứng nào ở bên ngoài: bộ đề vẫn publish, vẫn xuất gói, sinh
 * viên vẫn làm bài — chỉ tới lúc chấm hàng loạt mới trượt hàng loạt vì máy chấm tra một cấu trúc
 * bảng khác, hoặc tìm một định danh không tồn tại.
 *
 * <p>Nên bộ kiểm dưới đây dựng hai dự án Flutter thật (Golden và khung phát) rồi làm lệch từng
 * mặt một, và soi hai thứ ở mỗi ca: phán đúng hay sai, và CON DẤU có bị đóng nhầm không.
 */
class StarterCheckUnitTest {

    @TempDir Path thuMuc;

    private static final String MA_DE = "PE_PRM393_FA26";
    private static final String SHA_GOLDEN = "sha-golden-v1";

    private ExamRepository exams;
    private BehaviorSuiteRepository suites;
    private BehaviorArtifactService artifacts;
    private ExamService examFiles;
    private StarterSyncService dongBo;

    private Exam de;

    @BeforeEach
    void dungDeVaGolden() throws Exception {
        exams = mock(ExamRepository.class);
        suites = mock(BehaviorSuiteRepository.class);
        artifacts = mock(BehaviorArtifactService.class);
        examFiles = mock(ExamService.class);
        when(exams.save(any())).thenAnswer(i -> i.getArgument(0));

        de = new Exam();
        de.setExamId(MA_DE);
        de.setStarterCheckRequired(true);
        // Đề đã từng kiểm đạt — mọi ca trượt phải XOÁ được con dấu này.
        de.setStarterCheckedGoldenSha("dau-cua-lan-truoc");
        de.setStarterCheckedAt(java.time.Instant.parse("2026-09-01T00:00:00Z"));
        when(exams.findByExamId(MA_DE)).thenReturn(Optional.of(de));

        BehaviorSuite s = new BehaviorSuite();
        s.setId("S1");
        s.setExamId(MA_DE);
        when(suites.findByExamIdOrderByUpdatedAtDesc(MA_DE)).thenReturn(List.of(s));

        // Khai báo package mà hệ thống đang giữ cho khung phát của đề.
        when(examFiles.starterDependencies(MA_DE)).thenReturn(new LinkedHashMap<>(Map.of(
                "flutter", Map.of("sdk", "flutter"),
                "intl", "^0.19.0",
                "sqflite", "^2.3.0")));

        dongBo = new StarterSyncService(exams, suites, artifacts, examFiles);
    }

    // ── Hai dự án Flutter dựng sẵn ─────────────────────────────────────

    private static final String PUBSPEC = """
            name: quan_ly_chi_tieu
            environment:
              sdk: ">=3.0.0 <4.0.0"
            dependencies:
              flutter:
                sdk: flutter
              intl: ^0.19.0
              sqflite: ^2.3.0
            dev_dependencies:
              flutter_test:
                sdk: flutter
            """;

    private static final String DB_HELPER = """
            import 'package:sqflite/sqflite.dart';

            class DbHelper {
              Future<void> onCreate(Database db) async {
                await db.execute('''
                  CREATE TABLE expenses (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    title TEXT NOT NULL,
                    amount REAL NOT NULL,
                    created_at TEXT NOT NULL
                  )
                ''');
              }
            }
            """;

    private static final String DINH_DANH = """
            /// Nút "Cancel" ở hộp thoại — chú thích này KHÔNG phải định danh.
            class DinhDanh {
              static const themChiTieu = 'chi_tieu.them';
              static const oSoTien = 'chi_tieu.so_tien';
              static const tongChi = 'chi_tieu.tong';
            }
            """;

    /** Bộ file gốc của một dự án đầy đủ; các ca lệch chỉ thay đúng một phần tử. */
    private Map<String, String> duAnChuan() {
        Map<String, String> tep = new LinkedHashMap<>();
        tep.put("lib/main.dart", "void main() {}");
        tep.put("lib/db_helper.dart", DB_HELPER);
        tep.put("lib/dinh_danh.dart", DINH_DANH);
        tep.put("pubspec.yaml", PUBSPEC);
        tep.put("assets/icon_food.png", "PNG-GIA-DINH");
        tep.put("assets/icon_bus.png", "PNG-GIA-DINH");
        return tep;
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

    /** Nạp Golden vào kho artifact giả (là một file .zip thật trên đĩa). */
    private void golden(Map<String, String> tep) throws Exception {
        Path zip = Files.write(thuMuc.resolve("golden-" + System.nanoTime() + ".zip"), nen(tep));
        BehaviorArtifact a = new BehaviorArtifact();
        a.setId("A1");
        a.setSuiteId("S1");
        a.setArtifactType(BehaviorArtifactType.GOLDEN_SOLUTION);
        a.setSha256(SHA_GOLDEN);
        a.setStoragePath(zip.toString());
        when(artifacts.activeOptional("S1", BehaviorArtifactType.GOLDEN_SOLUTION))
                .thenReturn(Optional.of(a));
    }

    private MockMultipartFile khung(Map<String, String> tep) throws Exception {
        return new MockMultipartFile("khung", "khung.zip", "application/zip", nen(tep));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> phep(Map<String, Object> ketQua, String ten) {
        for (Object o : (List<Object>) ketQua.get("checks")) {
            Map<String, Object> p = (Map<String, Object>) o;
            if (ten.equals(p.get("name"))) return p;
        }
        throw new AssertionError("Không có phép kiểm tên: " + ten);
    }

    private void khangDinhDaDongDau(Map<String, Object> ra) {
        assertEquals(true, ra.get("passed"));
        assertEquals(SHA_GOLDEN, de.getStarterCheckedGoldenSha(), "phải đóng dấu bằng SHA Golden hiện tại");
        assertNotNull(de.getStarterCheckedAt());
    }

    private void khangDinhDaXoaDau(Map<String, Object> ra) {
        assertEquals(false, ra.get("passed"));
        assertNull(de.getStarterCheckedGoldenSha(),
                "trượt thì đề phải quay về CHƯA KIỂM, không được giữ con dấu của lần trước");
        assertNull(de.getStarterCheckedAt());
    }

    // ══════════════════ UT11 UTCID01 — mọi mặt trùng khớp ══════════════════

    @Test
    @DisplayName("UT11 UTCID01 — bốn phép kiểm đều đạt thì đóng dấu bằng SHA Golden hiện tại")
    void ut11_utcid01_trungKhopHoanToan() throws Exception {
        golden(duAnChuan());

        Map<String, Object> ra = dongBo.kiem(MA_DE, khung(duAnChuan()));

        khangDinhDaDongDau(ra);
        assertEquals(SHA_GOLDEN, ra.get("golden_sha256"));
        assertEquals(StarterSyncService.DAT, ra.get("state"));
        for (String ten : List.of("Cấu trúc bảng database", "Danh sách định danh",
                "Tên file trong assets", "Danh sách package")) {
            assertEquals(true, phep(ra, ten).get("passed"), ten);
        }
    }

    @Test
    @DisplayName("UT11 — khác thứ tự khai trong file KHÔNG phải là lệch (cả hai đã chuẩn hoá)")
    void ut11_khacThuTuKhaiKhongPhaiLech() throws Exception {
        golden(duAnChuan());
        Map<String, String> khung = duAnChuan();
        // Cùng ba định danh, đảo thứ tự dòng — bóc xong sắp xếp nên phải ra cùng một danh sách.
        khung.put("lib/dinh_danh.dart", """
                class DinhDanh {
                  static const tongChi = 'chi_tieu.tong';
                  static const themChiTieu = 'chi_tieu.them';
                  static const oSoTien = 'chi_tieu.so_tien';
                }
                """);

        khangDinhDaDongDau(dongBo.kiem(MA_DE, khung(khung)));
    }

    @Test
    @DisplayName("UT11 — sửa chú thích trong file định danh không được tính là lệch")
    void ut11_suaChuThichKhongPhaiLech() throws Exception {
        golden(duAnChuan());
        Map<String, String> khung = duAnChuan();
        khung.put("lib/dinh_danh.dart", DINH_DANH
                .replace("/// Nút \"Cancel\" ở hộp thoại — chú thích này KHÔNG phải định danh.",
                        "/// Đổi hẳn câu chú thích: 'chuoi_trong_chu_thich' và \"mot_chuoi_khac\""));

        khangDinhDaDongDau(dongBo.kiem(MA_DE, khung(khung)));
    }

    // ══════════════════ UT11 UTCID07 — lệch cấu trúc bảng ══════════════════

    @Test
    @DisplayName("UT11 UTCID07 — khung đổi tên cột thì trượt, và nói rõ bên nào thiếu gì")
    void ut11_utcid07_lechCauTrucBang() throws Exception {
        golden(duAnChuan());
        Map<String, String> khung = duAnChuan();
        khung.put("lib/db_helper.dart", DB_HELPER.replace("created_at TEXT NOT NULL", "createdAt TEXT NOT NULL"));

        Map<String, Object> ra = dongBo.kiem(MA_DE, khung(khung));

        khangDinhDaXoaDau(ra);
        Map<String, Object> bang = phep(ra, "Cấu trúc bảng database");
        assertEquals(false, bang.get("passed"));
        String chiTiet = String.valueOf(bang.get("detail"));
        assertTrue(chiTiet.contains("CREATED_AT"), "phải kể ra cột chỉ Golden có: " + chiTiet);
        assertTrue(chiTiet.contains("CREATEDAT"), "và cột chỉ khung có: " + chiTiet);
        assertTrue(chiTiet.contains("Sinh viên sẽ xây trên một cấu trúc"), "phải nói hậu quả");
    }

    @Test
    @DisplayName("UT11 — khác khoảng trắng / hoa thường trong CREATE TABLE không phải là lệch")
    void ut11_khacKhoangTrangKhongPhaiLech() throws Exception {
        golden(duAnChuan());
        Map<String, String> khung = duAnChuan();
        khung.put("lib/db_helper.dart", DB_HELPER
                .replace("CREATE TABLE expenses (", "create table   EXPENSES  (")
                .replace("title TEXT NOT NULL", "title    text   not null"));

        khangDinhDaDongDau(dongBo.kiem(MA_DE, khung(khung)));
    }

    // ══════════════════ UT11 UTCID08 — lệch định danh ══════════════════

    @Test
    @DisplayName("UT11 UTCID08 — khung thiếu một định danh thì trượt")
    void ut11_utcid08_thieuDinhDanh() throws Exception {
        golden(duAnChuan());
        Map<String, String> khung = duAnChuan();
        khung.put("lib/dinh_danh.dart", DINH_DANH
                .replace("  static const tongChi = 'chi_tieu.tong';\n", ""));

        Map<String, Object> ra = dongBo.kiem(MA_DE, khung(khung));

        khangDinhDaXoaDau(ra);
        Map<String, Object> dd = phep(ra, "Danh sách định danh");
        assertEquals(false, dd.get("passed"));
        assertTrue(String.valueOf(dd.get("detail")).contains("chi_tieu.tong"),
                "phải nêu đúng chuỗi bị thiếu: " + dd.get("detail"));
        assertTrue(String.valueOf(dd.get("detail")).contains("Lệch một chuỗi"));
    }

    @Test
    @DisplayName("UT11 — gõ sai một ký tự trong định danh vẫn phải bắt được")
    void ut11_goSaiMotKyTu() throws Exception {
        golden(duAnChuan());
        Map<String, String> khung = duAnChuan();
        khung.put("lib/dinh_danh.dart", DINH_DANH.replace("chi_tieu.tong", "chi_tieu.tOng"));

        khangDinhDaXoaDau(dongBo.kiem(MA_DE, khung(khung)));
    }

    // ══════════════════ UT11 — lệch asset ══════════════════

    @Test
    @DisplayName("UT11 — khung đổi tên file asset thì trượt (tiêu chí ảnh so đúng tên)")
    void ut11_lechTenAsset() throws Exception {
        golden(duAnChuan());
        Map<String, String> khung = duAnChuan();
        khung.remove("assets/icon_bus.png");
        khung.put("assets/icon_xe_buyt.png", "PNG-GIA-DINH");

        Map<String, Object> ra = dongBo.kiem(MA_DE, khung(khung));

        khangDinhDaXoaDau(ra);
        Map<String, Object> asset = phep(ra, "Tên file trong assets");
        assertTrue(String.valueOf(asset.get("detail")).contains("icon_bus.png"));
        assertTrue(String.valueOf(asset.get("detail")).contains("icon_xe_buyt.png"));
    }

    @Test
    @DisplayName("UT11 — hai bên đều không có thư mục assets thì phép kiểm vẫn đạt")
    void ut11_haiBenDeuKhongCoAsset() throws Exception {
        Map<String, String> khongAsset = duAnChuan();
        khongAsset.remove("assets/icon_food.png");
        khongAsset.remove("assets/icon_bus.png");
        golden(khongAsset);

        Map<String, Object> ra = dongBo.kiem(MA_DE, khung(khongAsset));

        khangDinhDaDongDau(ra);
        assertEquals("Cả hai bên đều không khai gì.", phep(ra, "Tên file trong assets").get("detail"));
    }

    // ══════════════════ UT11 UTCID09 — lệch gói phụ thuộc ══════════════════

    @Test
    @DisplayName("UT11 UTCID09 — Golden thiếu package so với khung thì trượt")
    void ut11_utcid09_goldenThieuPackage() throws Exception {
        Map<String, String> goldenThieu = duAnChuan();
        goldenThieu.put("pubspec.yaml", PUBSPEC.replace("  intl: ^0.19.0\n", ""));
        golden(goldenThieu);

        Map<String, Object> ra = dongBo.kiem(MA_DE, khung(duAnChuan()));

        khangDinhDaXoaDau(ra);
        Map<String, Object> goi = phep(ra, "Danh sách package");
        assertTrue(String.valueOf(goi.get("detail")).contains("intl"), goi.get("detail").toString());
    }

    @Test
    @DisplayName("UT11 — khác ràng buộc phiên bản cũng là lệch, không chỉ khác tên package")
    void ut11_khacRangBuocPhienBan() throws Exception {
        golden(duAnChuan());
        Map<String, String> khung = duAnChuan();
        khung.put("pubspec.yaml", PUBSPEC.replace("intl: ^0.19.0", "intl: ^0.18.0"));

        khangDinhDaXoaDau(dongBo.kiem(MA_DE, khung(khung)));
    }

    /**
     * Cạm bẫy riêng: khung phát và Golden khớp NHAU nhưng cả hai đều lệch với lựa chọn package
     * đang lưu của đề. Người dùng đang cầm một bản khung tải về từ lâu — phải bắt được, và lời
     * báo phải bảo họ tải lại khung chứ không bảo sửa Golden.
     */
    @Test
    @DisplayName("UT11 — khung khớp Golden nhưng lệch lựa chọn package hiện tại của đề")
    void ut11_khungCuKhongKhopLuaChonHienTai() throws Exception {
        golden(duAnChuan());
        when(examFiles.starterDependencies(MA_DE)).thenReturn(new LinkedHashMap<>(Map.of(
                "flutter", Map.of("sdk", "flutter"),
                "intl", "^0.19.0")));            // đề đã bỏ sqflite khỏi lựa chọn

        Map<String, Object> ra = dongBo.kiem(MA_DE, khung(duAnChuan()));

        khangDinhDaXoaDau(ra);
        Map<String, Object> goi = phep(ra, "Danh sách package");
        assertEquals(false, goi.get("passed"));
        assertTrue(String.valueOf(goi.get("detail")).startsWith("Khung tải lên không khớp lựa chọn"),
                "phải chỉ đúng việc cần làm — tải lại khung, không phải sửa Golden: " + goi.get("detail"));
    }

    // ══════════════════ UT11 — các lối thoát lỗi ══════════════════

    @Test
    @DisplayName("UT11 — khung phát không phải dự án Flutter thì báo rõ và xoá dấu")
    void ut11_khungKhongPhaiDuAnFlutter() throws Exception {
        golden(duAnChuan());

        IllegalArgumentException loi = assertThrows(IllegalArgumentException.class,
                () -> dongBo.kiem(MA_DE, khung(Map.of("doc/huong_dan.txt", "khong co lib/main.dart"))));

        assertEquals("Khung phát không phải dự án Flutter: không thấy lib/main.dart", loi.getMessage());
        assertNull(de.getStarterCheckedGoldenSha(), "không kiểm được thì cũng phải xoá dấu cũ");
        assertNull(de.getStarterCheckedAt());
    }

    @Test
    @DisplayName("UT11 — khung thiếu pubspec.yaml: không kiểm được thì KHÔNG giữ dấu đạt cũ")
    void ut11_khungThieuPubspec() throws Exception {
        golden(duAnChuan());
        Map<String, String> khung = duAnChuan();
        khung.remove("pubspec.yaml");

        assertThrows(IllegalArgumentException.class, () -> dongBo.kiem(MA_DE, khung(khung)));

        assertNull(de.getStarterCheckedGoldenSha());
        assertNull(de.getStarterCheckedAt());
    }

    @Test
    @DisplayName("UT11 — hai mặt cùng lệch thì cả hai phép kiểm đều phải đỏ, không dừng ở cái đầu")
    void ut11_nhieuMatCungLech() throws Exception {
        golden(duAnChuan());
        Map<String, String> khung = duAnChuan();
        khung.put("lib/db_helper.dart", DB_HELPER.replace("expenses", "chi_tieu"));
        khung.put("lib/dinh_danh.dart", DINH_DANH.replace("chi_tieu.them", "chi_tieu.them_moi"));

        Map<String, Object> ra = dongBo.kiem(MA_DE, khung(khung));

        khangDinhDaXoaDau(ra);
        assertEquals(false, phep(ra, "Cấu trúc bảng database").get("passed"));
        assertEquals(false, phep(ra, "Danh sách định danh").get("passed"));
        assertEquals(4, ((List<?>) ra.get("checks")).size(), "luôn chạy đủ bốn phép kiểm");
    }

    /** Giải nén cả Golden lẫn khung vào thư mục tạm — chạy hàng loạt mà không dọn là đầy đĩa. */
    @Test
    @DisplayName("UT11 — mỗi lần kiểm phải dọn sạch thư mục làm việc tạm, kể cả khi trượt")
    void ut11_donSachThuMucTam() throws Exception {
        golden(duAnChuan());
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        long truoc = demThuMucKiem(tmp);

        dongBo.kiem(MA_DE, khung(duAnChuan()));                       // lượt đạt
        Map<String, String> lech = duAnChuan();
        lech.put("lib/dinh_danh.dart", DINH_DANH.replace("chi_tieu.tong", "khac_han"));
        dongBo.kiem(MA_DE, khung(lech));                              // lượt trượt

        assertEquals(truoc, demThuMucKiem(tmp), "không được để sót thư mục kiem-dong-bo-*");
    }

    private long demThuMucKiem(Path tmp) throws Exception {
        try (var ds = Files.list(tmp)) {
            return ds.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("kiem-dong-bo-"))
                    .count();
        }
    }
}
