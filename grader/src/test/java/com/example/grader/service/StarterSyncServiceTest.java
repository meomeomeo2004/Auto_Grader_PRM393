package com.example.grader.service;

import com.example.grader.entity.BehaviorArtifact;
import com.example.grader.entity.BehaviorArtifactType;
import com.example.grader.entity.BehaviorSuite;
import com.example.grader.entity.Exam;
import com.example.grader.repository.BehaviorSuiteRepository;
import com.example.grader.repository.ExamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kiểm đồng bộ khung phát: phần lõi là bóc bốn thứ ra khỏi mã nguồn rồi so. Test này chạy
 * thẳng trên hai cây thư mục giả, không cần Spring cũng không cần Docker.
 */
class StarterSyncServiceTest {

    private static final String HELPER = """
            import 'package:sqflite/sqflite.dart';
            class DatabaseHelper {
              static Future<Database> moKho() async {
                final Database db = await openDatabase('app.db');
                await db.execute('''
                  CREATE TABLE IF NOT EXISTS accounts (
                    id       INTEGER PRIMARY KEY AUTOINCREMENT,
                    email    TEXT    NOT NULL,
                    password TEXT    NOT NULL
                  )
                ''');
                await db.execute('''
                  CREATE TABLE IF NOT EXISTS users (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    full_name  TEXT    NOT NULL,
                    phone      TEXT    NOT NULL
                  )
                ''');
                return db;
              }
            }
            """;

    private static final String DINH_DANH = """
            abstract final class DinhDanh {
              static const String dangNhapEmail = 'nguoi_dung.dang_nhap.email';
              static String dong(int id) => 'nguoi_dung.dong.$id';
            }
            """;

    private static final String PUBSPEC = """
            name: exam_project
            environment:
              sdk: '>=3.0.0 <4.0.0'
            dependencies:
              flutter:
                sdk: flutter
              path: ^1.9.1
              sqflite: '>=2.4.2+1 <2.4.3'
            dev_dependencies:
              flutter_test:
                sdk: flutter
            """;

    private StarterSyncService service;
    private ExamRepository checkedExams;

    @BeforeEach
    void setUp() {
        // Bốn hàm bóc dữ liệu chỉ đọc file, không đụng repository nào.
        service = new StarterSyncService(null, null, null, null);
    }

    private Path duAn(Path goc, String ten, String helper, String dinhDanh, String pubspec,
                      List<String> anh) throws Exception {
        Path duAn = goc.resolve(ten);
        Files.createDirectories(duAn.resolve("lib").resolve("data"));
        Files.writeString(duAn.resolve("lib").resolve("main.dart"), "void main() {}", StandardCharsets.UTF_8);
        Files.writeString(duAn.resolve("lib").resolve("data").resolve("database_helper.dart"),
                helper, StandardCharsets.UTF_8);
        Files.writeString(duAn.resolve("lib").resolve("dinh_danh.dart"), dinhDanh, StandardCharsets.UTF_8);
        Files.writeString(duAn.resolve("pubspec.yaml"), pubspec, StandardCharsets.UTF_8);
        if (!anh.isEmpty()) {
            Files.createDirectories(duAn.resolve("assets"));
            for (String a : anh) {
                Files.writeString(duAn.resolve("assets").resolve(a), "anh", StandardCharsets.UTF_8);
            }
        }
        return duAn;
    }

    @Test
    void khungBocDungTuGoldenThiBonPhepKiemDeuKhop(@TempDir Path goc) throws Exception {
        Path golden = duAn(goc, "golden", HELPER, DINH_DANH, PUBSPEC, List.of("avatar_1.jpg", "avatar_2.jpg"));
        Path khung = duAn(goc, "khung", HELPER, DINH_DANH, PUBSPEC, List.of("avatar_1.jpg", "avatar_2.jpg"));

        assertEquals(service.moTaBang(golden), service.moTaBang(khung));
        assertEquals(service.dinhDanh(golden), service.dinhDanh(khung));
        assertEquals(service.tenAsset(golden), service.tenAsset(khung));
        assertEquals(service.goiPhuThuoc(golden), service.goiPhuThuoc(khung));

        assertEquals(List.of("accounts(ID INTEGER PRIMARY KEY AUTOINCREMENT, EMAIL TEXT NOT NULL,"
                        + " PASSWORD TEXT NOT NULL)",
                "users(ID INTEGER PRIMARY KEY AUTOINCREMENT, FULL_NAME TEXT NOT NULL, PHONE TEXT NOT NULL)"),
                service.moTaBang(golden));
    }

    @Test
    void chiKhacKhoangTrangVaChuThichThiVanCoiLaKhop(@TempDir Path goc) throws Exception {
        String helperKhac = HELPER
                .replace("CREATE TABLE IF NOT EXISTS accounts (", "create table if not exists accounts(")
                .replace("id       INTEGER PRIMARY KEY AUTOINCREMENT,",
                        "  id INTEGER PRIMARY KEY AUTOINCREMENT ,");
        // Chú thích có CHUỖI trong dấu nháy: đây mới là ca thật, vì file định danh luôn có
        // những dòng mô tả kiểu /// Nút "Cancel". Nhặt cả chuỗi trong chú thích thì sửa một
        // câu mô tả cũng thành lệch, mà người ra đề không hiểu vì sao.
        String dinhDanhKhac = DINH_DANH.replace(
                "abstract final class DinhDanh {",
                "/// Nut \"Cancel\", chi hien khi dang sua. Xem them 'Add User'.\nabstract final class DinhDanh {");
        Path golden = duAn(goc, "golden", HELPER, DINH_DANH, PUBSPEC, List.of());
        Path khung = duAn(goc, "khung", helperKhac, dinhDanhKhac, PUBSPEC, List.of());

        assertEquals(service.moTaBang(golden), service.moTaBang(khung),
                "một dấu cách hay chữ hoa chữ thường không được thành lỗi");
        assertEquals(service.dinhDanh(golden), service.dinhDanh(khung),
                "sửa chú thích không đụng tới định danh");
    }

    @Test
    void doiTenCotThiBatDuoc(@TempDir Path goc) throws Exception {
        Path golden = duAn(goc, "golden", HELPER, DINH_DANH, PUBSPEC, List.of());
        Path khung = duAn(goc, "khung", HELPER.replace("full_name", "fullname"), DINH_DANH, PUBSPEC, List.of());

        List<String> bangGolden = service.moTaBang(golden);
        List<String> bangKhung = service.moTaBang(khung);
        assertNotEquals(bangGolden, bangKhung, "đổi tên cột là lỗi chết cả lớp, phải bắt được");
        assertTrue(bangKhung.toString().contains("FULLNAME"));
    }

    @Test
    void doiChuoiDinhDanhVaThieuAnhVaThemGoiDeuBatDuoc(@TempDir Path goc) throws Exception {
        Path golden = duAn(goc, "golden", HELPER, DINH_DANH, PUBSPEC, List.of("avatar_1.jpg", "avatar_2.jpg"));
        Path khung = duAn(goc, "khung", HELPER,
                DINH_DANH.replace("nguoi_dung.dang_nhap.email", "nguoi_dung.dangnhap.email"),
                PUBSPEC.replace("  path: ^1.9.1", "  path: ^1.9.1\n  intl: ^0.20.2"),
                List.of("avatar_1.jpg"));

        assertNotEquals(service.dinhDanh(golden), service.dinhDanh(khung));
        assertEquals(List.of("avatar_1.jpg"), service.tenAsset(khung));
        assertNotEquals(service.tenAsset(golden), service.tenAsset(khung));
        assertTrue(service.goiPhuThuoc(khung).stream().anyMatch(p -> p.startsWith("intl: ")));
        assertNotEquals(service.goiPhuThuoc(golden), service.goiPhuThuoc(khung));
        // dev_dependencies KHÔNG tính: chúng không đi vào bài nộp.
        assertEquals(List.of("flutter: {\"sdk\":\"flutter\"}", "path: \"^1.9.1\"",
                "sqflite: \">=2.4.2+1 <2.4.3\""), service.goiPhuThuoc(golden));
    }

    @Test
    void cungTenGoiNhungDoiRangBuocPhienBanThiTruot(@TempDir Path goc) throws Exception {
        Exam exam = deDaKiem();
        StarterSyncService checker = checker(goc, PUBSPEC, exam);

        Map<String, Object> result = checker.kiem("PE", khungZip(PUBSPEC.replace("^1.9.1", "^1.9.0")));

        assertFalse((Boolean) result.get("passed"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) result.get("checks");
        Map<String, Object> packages = checks.stream().filter(c -> "Danh sách package".equals(c.get("name")))
                .findFirst().orElseThrow();
        assertFalse((Boolean) packages.get("passed"));
        assertTrue(packages.get("detail").toString().contains("^1.9.1"));
        assertTrue(packages.get("detail").toString().contains("^1.9.0"));
        assertNull(exam.getStarterCheckedGoldenSha());
        assertNull(exam.getStarterCheckedAt());
    }

    @Test
    void chiDoiDevDependenciesThiVanPass(@TempDir Path goc) throws Exception {
        Exam exam = deDaKiem();
        StarterSyncService checker = checker(goc, PUBSPEC, exam);

        Map<String, Object> result = checker.kiem("PE", khungZip(PUBSPEC + "  flutter_lints: ^4.0.0\n"));

        assertTrue((Boolean) result.get("passed"));
        assertEquals("golden-sha", exam.getStarterCheckedGoldenSha());
    }

    @Test
    void doiThuTuVaCachVietYamlKhongDoiDependencies(@TempDir Path goc) throws Exception {
        Path golden = duAn(goc, "golden", HELPER, DINH_DANH, PUBSPEC, List.of());
        Path khung = duAn(goc, "khung", HELPER, DINH_DANH, """
                dependencies: {sqflite: ">=2.4.2+1 <2.4.3", path: '^1.9.1', flutter: {sdk: flutter}}
                """, List.of());

        assertEquals(service.goiPhuThuoc(golden), service.goiPhuThuoc(khung));
    }

    @Test
    void khongCoPubspecPhaiBaoLoi(@TempDir Path goc) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.goiPhuThuoc(goc));

        assertTrue(error.getMessage().contains("pubspec.yaml"));
    }

    @Test
    void thieuPubspecGoldenKhongDuocGiuDauPassCu(@TempDir Path goc) throws Exception {
        kiemLoiXoaDauCu(goc, null, PUBSPEC);
    }

    @Test
    void thieuPubspecKhungKhongDuocGiuDauPassCu(@TempDir Path goc) throws Exception {
        kiemLoiXoaDauCu(goc, PUBSPEC, null);
    }

    @Test
    void haiBenCungThieuPubspecKhongDuocPass(@TempDir Path goc) throws Exception {
        kiemLoiXoaDauCu(goc, null, null);
    }

    @Test
    void pubspecSaiYamlKhongDuocGiuDauPassCu(@TempDir Path goc) throws Exception {
        kiemLoiXoaDauCu(goc, PUBSPEC, "dependencies: [\n");
    }

    private void kiemLoiXoaDauCu(Path goc, String goldenPubspec, String khungPubspec) throws Exception {
        Exam exam = deDaKiem();
        StarterSyncService checker = checker(goc, goldenPubspec, exam);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> checker.kiem("PE", khungZip(khungPubspec)));

        assertTrue(error.getMessage().contains("pubspec"));
        assertNull(exam.getStarterCheckedGoldenSha());
        assertNull(exam.getStarterCheckedAt());
        verify(checkedExams).save(exam);
        assertEquals(StarterSyncService.CHUA_KIEM, checker.trangThai("PE"));
    }

    private Exam deDaKiem() {
        Exam exam = new Exam();
        exam.setExamId("PE");
        exam.setStarterCheckRequired(true);
        exam.setStarterCheckedGoldenSha("golden-sha");
        exam.setStarterCheckedAt(Instant.parse("2026-09-17T00:00:00Z"));
        return exam;
    }

    private ExamService generatedFrames;

    @Test
    void khungCuKhongKhoiPhucDauDatSauKhiDoiLuaChon(@TempDir Path goc) throws Exception {
        Exam exam = deDaKiem();
        StarterSyncService checker = checker(goc, PUBSPEC, exam);
        when(generatedFrames.starterDependencies("PE")).thenReturn(Map.of("flutter", Map.of("sdk", "flutter")));
        Map<String, Object> result = checker.kiem("PE", khungZip(PUBSPEC));
        assertFalse((Boolean) result.get("passed"));
        assertNull(exam.getStarterCheckedGoldenSha());
        assertNull(exam.getStarterCheckedAt());
    }

    @Test
    void doiPhienBanTrongAnhLamConDauCuHetHan(@TempDir Path goc) throws Exception {
        Exam exam = deDaKiem();
        StarterSyncService checker = checker(goc, PUBSPEC, exam);
        assertEquals(StarterSyncService.DAT, checker.trangThai("PE"));
        when(generatedFrames.starterDependencies("PE")).thenReturn(PubspecDependencies.parse(PUBSPEC.replace("^1.9.1", "^1.9.0")));
        assertEquals(StarterSyncService.HET_HAN, checker.trangThai("PE"));
        assertFalse(checker.chamDuoc("PE"));
    }

    private StarterSyncService checker(Path goc, String goldenPubspec, Exam exam) throws Exception {
        Path goldenZip = goc.resolve("golden.zip");
        Files.write(goldenZip, zip(goldenPubspec));
        BehaviorSuite suite = new BehaviorSuite();
        suite.setId("suite");
        BehaviorArtifact golden = new BehaviorArtifact();
        golden.setStoragePath(goldenZip.toString());
        golden.setSha256("golden-sha");
        ExamRepository exams = mock(ExamRepository.class);
        checkedExams = exams;
        BehaviorSuiteRepository suites = mock(BehaviorSuiteRepository.class);
        BehaviorArtifactService artifacts = mock(BehaviorArtifactService.class);
        when(exams.findByExamId("PE")).thenReturn(Optional.of(exam));
        when(suites.findByExamIdOrderByUpdatedAtDesc("PE")).thenReturn(List.of(suite));
        when(artifacts.activeOptional("suite", BehaviorArtifactType.GOLDEN_SOLUTION))
                .thenReturn(Optional.of(golden));
        when(exams.save(exam)).thenReturn(exam);
        generatedFrames = mock(ExamService.class);
        when(generatedFrames.starterDependencies("PE")).thenReturn(PubspecDependencies.parse(PUBSPEC));
        return new StarterSyncService(exams, suites, artifacts, generatedFrames);
    }

    private MockMultipartFile khungZip(String pubspec) throws Exception {
        return new MockMultipartFile("file", "khung.zip", "application/zip", zip(pubspec));
    }

    private byte[] zip(String pubspec) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("project/lib/main.dart"));
            zip.write("void main() {}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (pubspec != null) {
                zip.putNextEntry(new ZipEntry("project/pubspec.yaml"));
                zip.write(pubspec.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
