package com.example.grader.service;

import com.example.grader.entity.BehaviorArtifact;
import com.example.grader.entity.BehaviorArtifactType;
import com.example.grader.entity.BehaviorSuite;
import com.example.grader.entity.Exam;
import com.example.grader.repository.BehaviorArtifactRepository;
import com.example.grader.repository.BehaviorSuiteRepository;
import com.example.grader.repository.ExamRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Khung phát lấy package TỪ GOLDEN, không còn lọc {@code pubspec.base.yaml} theo một danh sách
 * chọn riêng của đề (bỏ 19/9). Hai bên khai y hệt nhau thì bài sinh viên mới chạy trên đúng bộ
 * thư viện của bài giải mẫu.
 */
class ExamStarterFromGoldenTest {

    @TempDir Path temp;
    private final Map<String, Exam> records = new HashMap<>();

    private static final String PUBSPEC_GOLDEN = """
            name: golden_solution
            description: Golden Solution PE_PRM393 - KHONG duoc lo ra khung
            publish_to: none
            version: 1.0.0+1

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
              flutter_lints: ^4.0.0

            flutter:
              uses-material-design: true
              assets:
                - assets/
            """;

    private ExamService service(String pubspecGolden) throws Exception {
        Path base = temp.resolve("grader-base");
        Files.createDirectories(base);
        Files.writeString(base.resolve("Dockerfile.base"), "FROM scratch\n");
        Files.writeString(base.resolve("pubspec.base.yaml"), """
                name: exam_project
                environment:
                  sdk: '>=3.0.0 <4.0.0'
                dependencies:
                  flutter:
                    sdk: flutter
                  sqflite: '>=2.4.2+1 <2.4.3'
                  intl: ^0.20.2
                dev_dependencies:
                  flutter_test:
                    sdk: flutter
                flutter:
                  uses-material-design: true
                """);

        ExamService s = new ExamService();
        ReflectionTestUtils.setField(s, "templateDir", base.toString());
        ReflectionTestUtils.setField(s, "examsDir", temp.resolve("exams").toString());
        // Ảnh không tồn tại -> readLockFromBaseImage() trả null -> không kèm pubspec.lock.
        ReflectionTestUtils.setField(s, "baseImage", "grading-base-khong-ton-tai:test");

        ExamRepository repo = mock(ExamRepository.class);
        Exam exam = new Exam();
        exam.setExamId("PE");
        records.put("PE", exam);
        when(repo.findByExamId(anyString()))
                .thenAnswer(call -> Optional.ofNullable(records.get(call.getArgument(0))));
        when(repo.save(any(Exam.class))).thenAnswer(call -> {
            Exam saved = call.getArgument(0);
            records.put(saved.getExamId(), saved);
            return saved;
        });
        ReflectionTestUtils.setField(s, "examRepository", repo);

        BehaviorSuiteRepository suites = mock(BehaviorSuiteRepository.class);
        BehaviorArtifactRepository artifacts = mock(BehaviorArtifactRepository.class);
        if (pubspecGolden != null) {
            BehaviorSuite suite = new BehaviorSuite();
            suite.setId("suite-1");
            when(suites.findByExamIdOrderByUpdatedAtDesc("PE")).thenReturn(List.of(suite));
            BehaviorArtifact golden = new BehaviorArtifact();
            golden.setStoragePath(goldenZip(pubspecGolden).toAbsolutePath().toString());
            when(artifacts.findFirstBySuiteIdAndArtifactTypeAndActiveTrueOrderByVersionDesc(
                    "suite-1", BehaviorArtifactType.GOLDEN_SOLUTION)).thenReturn(Optional.of(golden));
        } else {
            when(suites.findByExamIdOrderByUpdatedAtDesc("PE")).thenReturn(List.of());
        }
        ReflectionTestUtils.setField(s, "suiteRepository", suites);
        ReflectionTestUtils.setField(s, "artifactRepository", artifacts);
        return s;
    }

    /** Dựng Golden đúng hình dạng thật: file cho sẵn nằm sâu trong lib/data/, có cả màn lời giải. */
    private Path goldenZip(String pubspec) throws Exception {
        Path zip = temp.resolve("golden.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            them(out, "lib/main.dart", MAIN_GOLDEN);
            them(out, "lib/data/database_helper.dart", HELPER_GOLDEN);
            them(out, "lib/dinh_danh.dart", DINH_DANH_GOLDEN);
            them(out, "lib/screens/home_screen.dart", "class HomeScreen {} // LOI GIAI\n");
            them(out, "lib/data/expense_repository.dart", "class ExpenseRepository {} // LOI GIAI\n");
            them(out, "test/smoke_test.dart", "void main() {} // test cua Golden\n");
            them(out, "pubspec.yaml", pubspec);
        }
        return zip;
    }

    private static final String MAIN_GOLDEN = """
            import 'package:flutter/material.dart';
            import 'screens/home_screen.dart';

            void main() { runApp(const ExpenseApp()); }

            class ExpenseApp extends StatelessWidget {
              const ExpenseApp({super.key});
              @override
              Widget build(BuildContext context) {
                return MaterialApp(
                  title: 'Quan ly chi tieu',
                  theme: ThemeData(colorSchemeSeed: const Color(0xFF0F6A63)),
                  home: const HomeScreen(),
                );
              }
            }
            """;

    private static final String HELPER_GOLDEN =
            "/// CHO SAN — KHONG SUA.\nclass DatabaseHelper { static const ten = 'app.db'; }\n";
    private static final String DINH_DANH_GOLDEN =
            "abstract final class DinhDanh { static const them = 'chi_tieu.them'; }\n";

    private Map<String, byte[]> giaiNen(byte[] zip) throws Exception {
        Map<String, byte[]> ra = new java.util.LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                ra.put(e.getName(), in.readAllBytes());
            }
        }
        return ra;
    }

    private void them(ZipOutputStream out, String ten, String noiDung) throws Exception {
        out.putNextEntry(new ZipEntry(ten));
        out.write(noiDung.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> tep(ExamService s) throws Exception {
        for (Map<String, String> f : s.starterProjectFiles("PE")) {
            if (f.get("name").equals("pubspec.yaml")) {
                return (Map<String, Object>) new Yaml().load(f.get("content"));
            }
        }
        throw new AssertionError("không sinh ra pubspec.yaml");
    }

    @Test
    void khungLayCaDependenciesVaDevDependenciesTuGolden() throws Exception {
        Map<String, Object> pubspec = tep(service(PUBSPEC_GOLDEN));

        assertEquals(Map.of("flutter", Map.of("sdk", "flutter"),
                        "path", "^1.9.1", "sqflite", ">=2.4.2+1 <2.4.3"),
                pubspec.get("dependencies"));
        assertEquals(Map.of("flutter_test", Map.of("sdk", "flutter"), "flutter_lints", "^4.0.0"),
                pubspec.get("dev_dependencies"),
                "thiếu dev_dependencies thì analysis_options của Golden không dùng được");
    }

    /** Ảnh chấm cache package graph theo tên exam_project, và import nội bộ được viết lại theo tên đó. */
    @Test
    void epTenDuAnVeExamProjectVaKhongLoChuGolden() throws Exception {
        Map<String, Object> pubspec = tep(service(PUBSPEC_GOLDEN));

        assertEquals("exam_project", pubspec.get("name"));
        assertFalse(String.valueOf(pubspec.get("description")).contains("Golden"),
                "mô tả của Golden lộ ra khung là nói cho sinh viên biết đây là bài giải: "
                        + pubspec.get("description"));
    }

    /** Khung không có thư mục assets; giữ khai báo đó thì build báo thiếu thư mục. */
    @SuppressWarnings("unchecked")
    @Test
    void boKhaiBaoAssetsVaGiuPhanFlutterConLai() throws Exception {
        Map<String, Object> pubspec = tep(service(PUBSPEC_GOLDEN));

        Map<String, Object> phanFlutter = (Map<String, Object>) pubspec.get("flutter");
        assertNotNull(phanFlutter);
        assertFalse(phanFlutter.containsKey("assets"), "khung không có thư mục assets: " + phanFlutter);
        assertEquals(Boolean.TRUE, phanFlutter.get("uses-material-design"));
    }

    @Test
    void chuaCoGoldenThiBaoLoiRoChuKhongSinhKhungRong() throws Exception {
        ExamService s = service(null);

        var e = assertThrows(IllegalStateException.class, () -> s.starterProjectFiles("PE"));
        assertTrue(e.getMessage().contains("Golden"), e.getMessage());
    }

    /**
     * Tải bản lời giải mẫu thì KHÔNG được viết lại pubspec của nó: bản đó phải phản ánh đúng
     * Golden thật, nếu không thì phép đối chiếu về sau mất ý nghĩa.
     */
    @Test
    void taiBanLoiGiaiGiuNguyenPubspecCuaGolden() throws Exception {
        ExamService s = service(PUBSPEC_GOLDEN);
        Path solution = temp.resolve("exams/PE/handout/solution");
        Files.createDirectories(solution);
        String goc = "dependencies:\n  unapproved: ^8.0.0\n";
        Files.writeString(solution.resolve("pubspec.yaml"), goc);

        String thay = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(s.zipSolution("PE")))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("pubspec.yaml")) {
                    thay = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertEquals(goc, thay);
    }

    @Test
    void goiKhungPhatChiGomVoDuAn_haiFileChoSan_vaMainDaThayHome() throws Exception {
        Map<String, byte[]> tep = giaiNen(service(PUBSPEC_GOLDEN).zipKhungPhat("PE"));

        assertTrue(tep.containsKey("lib/main.dart"), tep.keySet().toString());
        assertTrue(tep.containsKey("pubspec.yaml"), tep.keySet().toString());
        // Tìm được dù Golden để file ở lib/data/, và ĐẶT PHẲNG về lib/ như khung.
        assertEquals(HELPER_GOLDEN, new String(tep.get("lib/database_helper.dart"), StandardCharsets.UTF_8),
                "file cho sẵn phải chép nguyên byte");
        assertEquals(DINH_DANH_GOLDEN, new String(tep.get("lib/dinh_danh.dart"), StandardCharsets.UTF_8));

        String main = new String(tep.get("lib/main.dart"), StandardCharsets.UTF_8);
        assertTrue(main.contains("Bat dau lam bai tai day"), main);
        assertTrue(main.contains("colorSchemeSeed"), "theme quyết định hình học, phải mang theo:\n" + main);
        assertTrue(main.contains("debugShowCheckedModeBanner: false"), main);
    }

    @Test
    void khungTuyetDoiKhongMangLoiGiaiHayTestCuaGolden() throws Exception {
        Map<String, byte[]> tep = giaiNen(service(PUBSPEC_GOLDEN).zipKhungPhat("PE"));

        for (String ten : tep.keySet()) {
            assertFalse(ten.startsWith("lib/screens/"), "lộ màn lời giải: " + ten);
            assertFalse(ten.equals("lib/expense_repository.dart") || ten.startsWith("lib/data/"),
                    "lộ tầng dữ liệu của Golden: " + ten);
            assertFalse(ten.startsWith("test/"), "khung không phát test: " + ten);
            assertFalse(ten.startsWith("android/.gradle/"), "cache build không được phát: " + ten);
        }
        assertFalse(new String(tep.get("lib/main.dart"), StandardCharsets.UTF_8).contains("HomeScreen"));
    }

    @Test
    void thieuFileChoSanThiBaoLoiChuKhongPhatKhungThieu() throws Exception {
        ExamService s = service(PUBSPEC_GOLDEN);
        // Golden không có dinh_danh.dart -> khung phát ra sẽ không khớp hợp đồng định danh.
        Path zip = temp.resolve("golden.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            them(out, "lib/main.dart", MAIN_GOLDEN);
            them(out, "lib/data/database_helper.dart", HELPER_GOLDEN);
            them(out, "pubspec.yaml", PUBSPEC_GOLDEN);
        }

        var e = assertThrows(IllegalStateException.class, () -> s.zipKhungPhat("PE"));
        assertTrue(e.getMessage().contains("dinh_danh.dart"), e.getMessage());
    }

    /**
     * Đề KHÔNG dùng database (máy tính cộng trừ) thì khung phát không đòi database_helper.dart.
     *
     * <p>Trước 26/9/2026 thiếu file đó là ném lỗi, nên đề không có dữ liệu nào vẫn không xuất nổi
     * khung phát — muốn qua cửa phải bắt Golden viết một DatabaseHelper rỗng không ai gọi.
     */
    @Test
    void deKhongDungDatabaseThiKhungKhongDoiDatabaseHelper() throws Exception {
        ExamService s = service(PUBSPEC_GOLDEN);
        Path zip = temp.resolve("golden.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            them(out, "lib/main.dart", MAIN_GOLDEN);
            them(out, "lib/dinh_danh.dart", DINH_DANH_GOLDEN);
            them(out, "lib/screens/home_screen.dart", "class HomeScreen {} // LOI GIAI\n");
            them(out, "pubspec.yaml", PUBSPEC_GOLDEN);
        }

        Map<String, byte[]> tep = giaiNen(s.zipKhungPhat("PE"));
        assertTrue(tep.containsKey("lib/dinh_danh.dart"), "hợp đồng định danh thì đề nào cũng cần");
        assertFalse(tep.containsKey("lib/database_helper.dart"));
    }

    /** Vế kia: Golden CÓ dùng database mà thiếu database_helper.dart thì vẫn chặn như cũ. */
    @Test
    void deCoDatabaseMaThieuDatabaseHelperVanBaoLoi() throws Exception {
        ExamService s = service(PUBSPEC_GOLDEN);
        Path zip = temp.resolve("golden.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            them(out, "lib/main.dart", MAIN_GOLDEN);
            them(out, "lib/dinh_danh.dart", DINH_DANH_GOLDEN);
            them(out, "lib/data/repo.dart", "import 'package:sqflite/sqflite.dart';\nclass Repo {}\n");
            them(out, "pubspec.yaml", PUBSPEC_GOLDEN);
        }

        var e = assertThrows(IllegalStateException.class, () -> s.zipKhungPhat("PE"));
        assertTrue(e.getMessage().contains("database_helper.dart"), e.getMessage());
    }

    /** Vân tay chỉ đổi khi BỐN THỨ khung đọc đổi — sửa màn lời giải thì khung không lệch. */
    @Test
    void vanTayKhongDoiKhiChiSuaLoiGiai() throws Exception {
        ExamService s = service(PUBSPEC_GOLDEN);
        String truoc = s.vanTayKhungPhat("PE");

        Path zip = temp.resolve("golden.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            them(out, "lib/main.dart", MAIN_GOLDEN);
            them(out, "lib/data/database_helper.dart", HELPER_GOLDEN);
            them(out, "lib/dinh_danh.dart", DINH_DANH_GOLDEN);
            them(out, "lib/screens/home_screen.dart", "class HomeScreen {} // DA SUA KHAC HAN\n");
            them(out, "pubspec.yaml", PUBSPEC_GOLDEN);
        }
        assertEquals(truoc, s.vanTayKhungPhat("PE"), "sửa lời giải mà kêu khung lệch là kêu oan");
    }

    @Test
    void vanTayDoiKhiFileChoSanDoi() throws Exception {
        ExamService s = service(PUBSPEC_GOLDEN);
        String truoc = s.vanTayKhungPhat("PE");

        Path zip = temp.resolve("golden.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            them(out, "lib/main.dart", MAIN_GOLDEN);
            them(out, "lib/data/database_helper.dart", HELPER_GOLDEN.replace("app.db", "chi_tieu.db"));
            them(out, "lib/dinh_danh.dart", DINH_DANH_GOLDEN);
            them(out, "pubspec.yaml", PUBSPEC_GOLDEN);
        }
        assertNotEquals(truoc, s.vanTayKhungPhat("PE"), "đổi tên database mà im lặng là nguy hiểm");
    }

    /** Màn Thư viện chấm vẫn bày theo pubspec.base.yaml — nguồn của ẢNH, không phải của khung. */
    @SuppressWarnings("unchecked")
    @Test
    void manThuVienChamVanBayTheoNguonCuaAnh() throws Exception {
        ExamService s = service(PUBSPEC_GOLDEN);

        List<Map<String, Object>> direct = (List<Map<String, Object>>) s.goiChoManSoanDe().get("direct");
        assertEquals(java.util.Set.of("flutter", "sqflite", "intl"),
                direct.stream().map(p -> String.valueOf(p.get("name")))
                        .collect(java.util.stream.Collectors.toSet()));
    }
}
