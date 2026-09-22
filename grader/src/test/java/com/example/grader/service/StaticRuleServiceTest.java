package com.example.grader.service;

import com.example.grader.entity.BehaviorArtifact;
import com.example.grader.entity.BehaviorArtifactType;
import com.example.grader.entity.BehaviorSuite;
import com.example.grader.repository.BehaviorSuiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Bản Java của bộ đánh giá source_pattern phải CÙNG ngữ nghĩa với static_checks.dart
 * trong container (glob ** xuyên cấp, chỉ khai max thì min mặc định 0, any_of...).
 * Test này ghim các ngữ nghĩa đó — đổi một bên mà quên bên kia thì test đỏ trước.
 */
class StaticRuleServiceTest {

    @TempDir
    Path tempDir;

    private BehaviorSuiteRepository suites;
    private BehaviorArtifactService artifacts;
    private StaticRuleService service;
    private BehaviorSuite suite;

    @BeforeEach
    void setUp() {
        suites = mock(BehaviorSuiteRepository.class);
        artifacts = mock(BehaviorArtifactService.class);
        service = new StaticRuleService(suites, artifacts);
        suite = new BehaviorSuite();
        suite.setId("suite-1");
        suite.setSuiteCode("PE_TEST");
        when(suites.findById("suite-1")).thenReturn(Optional.of(suite));
        when(suites.existsById("suite-1")).thenReturn(true);
        when(suites.save(any(BehaviorSuite.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** Golden zip bọc thư mục gốc như file thật giáo viên upload. */
    private void goldenZip(Map<String, String> files) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (Map.Entry<String, String> e : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        Path zipPath = tempDir.resolve("golden.zip");
        Files.write(zipPath, buffer.toByteArray());
        BehaviorArtifact artifact = new BehaviorArtifact();
        artifact.setArtifactType(BehaviorArtifactType.GOLDEN_SOLUTION);
        artifact.setStoragePath(zipPath.toString());
        when(artifacts.activeOptional("suite-1", BehaviorArtifactType.GOLDEN_SOLUTION))
                .thenReturn(Optional.of(artifact));
    }

    private Map<String, Object> presetById(String id) {
        return service.presets().stream()
                .filter(p -> id.equals(p.get("id")))
                .findFirst().orElseThrow();
    }

    @Test
    void savesArchitecturePresetThatGoldenSatisfiesAndEmitsMatrixRow() throws Exception {
        goldenZip(Map.of(
                "MyGolden/pubspec.yaml", "name: golden",
                "MyGolden/lib/main.dart", "void main() {}",
                "MyGolden/lib/models/expense.dart", "class Expense {}",
                "MyGolden/lib/screens/home_screen.dart", MA_WIDGET));

        Map<String, Object> saved = service.save("suite-1",
                Map.of("rules", List.of(presetById("ARCH_MODEL"), presetById("ARCH_SCREEN"))));

        assertNotNull(suite.getStaticRulesJson(), "luật phải được lưu vào cột của suite");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) saved.get("rules");
        assertEquals(2, rules.size());
        Map<String, Object> golden = (Map<String, Object>) rules.get(0).get("golden");
        assertEquals(true, golden.get("passed"));
        assertTrue(String.valueOf(golden.get("detail")).contains("lib/models/expense.dart"),
                "bằng chứng phải nêu đúng file khớp: " + golden.get("detail"));

        Map<String, Map<String, Object>> rows = service.matrixRows("suite-1", "PE_TEST");
        Map<String, Object> row = rows.get("PE_TEST_STATIC_ARCH_MODEL");
        assertNotNull(row, "dòng ma trận phải mang id <suite>_STATIC_<rule>");
        assertEquals("STATIC_ANALYSIS", row.get("runner"));
        assertEquals("source_pattern", row.get("static_rule"));
        // Luat tinh mac dinh thuoc nhom "Architecture"; testcase_group/layer da go vi khong
        // ai doc (21/9/2026).
        assertEquals("Architecture", row.get("group_id"));
        assertNull(row.get("testcase_group"));
        assertNull(row.get("group_name"));
        assertNotNull(row.get("static_config"));
    }

    @Test
    void refusesRuleThatGoldenItselfFails() throws Exception {
        // Golden dùng setState — đề đòi Riverpod là đề tự mâu thuẫn, phải chặn ngay.
        goldenZip(Map.of(
                "app/lib/main.dart", "void main() {}",
                "app/lib/screens/home.dart", "class Home { void f() { setState(() {}); } }"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                service.save("suite-1", Map.of("rules", List.of(presetById("STATE_RIVERPOD")))));
        assertTrue(e.getMessage().contains("Dùng Riverpod"), e.getMessage());
        assertTrue(e.getMessage().contains("Golden"), e.getMessage());
        assertNull(suite.getStaticRulesJson(), "lưu thất bại thì không được ghi nửa vời");
    }

    @Test
    void forbidRuleDefaultsMinToZeroLikeTheDartRunner() throws Exception {
        // Chỉ khai max=0 (CẤM print): golden sạch phải ĐẠT — đây đúng bug đã sửa ở bản Dart.
        goldenZip(Map.of("app/lib/main.dart", "void main() { debugPrint('x'); }"));

        Map<String, Object> rule = Map.of(
                "id", "FORBID_PRINT", "name", "Cấm print trong lib", "kind", "source_pattern",
                "weight", 2, "group_id", "G_MA_SACH", "group_name", "Chất lượng mã nguồn",
                "config", Map.of("require", List.of(Map.of(
                        "label", "Cấm print",
                        "paths", List.of("lib/**.dart"),
                        "contains", "(^|[^\\w.])print\\s*\\(",
                        "max", 0))));
        Map<String, Object> saved = service.save("suite-1", Map.of("rules", List.of(rule)));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) saved.get("rules");
        assertEquals(true, ((Map<String, Object>) rules.get(0).get("golden")).get("passed"));

        // Còn khi golden CÓ print thì luật cấm phải bị từ chối (golden không thỏa chính đề).
        goldenZip(Map.of("app/lib/main.dart", "void main() { print('x'); }"));
        assertThrows(IllegalStateException.class,
                () -> service.save("suite-1", Map.of("rules", List.of(rule))));
    }

    @Test
    void viewShowsGoldenVerdictPerPresetSoTeacherOnlyTicksGreenOnes() throws Exception {
        goldenZip(Map.of(
                "app/lib/models/user.dart", "class User {}",
                "app/lib/main.dart", "void main() {}"));

        Map<String, Object> view = service.view("suite-1");

        assertEquals(true, view.get("golden_available"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> presets = (List<Map<String, Object>>) view.get("presets");
        Map<String, Object> model = presets.stream()
                .filter(p -> "ARCH_MODEL".equals(p.get("id"))).findFirst().orElseThrow();
        Map<String, Object> riverpod = presets.stream()
                .filter(p -> "STATE_RIVERPOD".equals(p.get("id"))).findFirst().orElseThrow();
        Map<String, Object> lint = presets.stream()
                .filter(p -> "LINT_EMPTY_CATCHES".equals(p.get("id"))).findFirst().orElseThrow();
        assertEquals(true, ((Map<String, Object>) model.get("golden")).get("passed"));
        assertEquals(false, ((Map<String, Object>) riverpod.get("golden")).get("passed"));
        assertNull(((Map<String, Object>) lint.get("golden")).get("passed"),
                "luật lint không đối chứng được bằng zip — verdict phải là null, không phải đỏ");
    }

    @Test
    void missingGoldenBlocksSourcePatternRulesWithClearMessage() {
        when(artifacts.activeOptional("suite-1", BehaviorArtifactType.GOLDEN_SOLUTION))
                .thenReturn(Optional.empty());
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                service.save("suite-1", Map.of("rules", List.of(presetById("ARCH_MODEL")))));
        assertTrue(e.getMessage().contains("Golden Solution"), e.getMessage());
    }

    // ═══════════════ MỖI TẦNG MỘT THƯ MỤC RIÊNG ═══════════════

    /** Đúng ca thật đã đo trên HE231604: lib/ phẳng, giữ nguyên file đề phát sẵn. */
    private void goldenPhang() throws Exception {
        goldenZip(Map.of(
                "app/lib/main.dart", "void main() {}",
                "app/lib/database_helper.dart", "class DatabaseHelper {}",
                "app/lib/expense.dart", "class Expense {}",
                "app/lib/trang_chinh.dart", "class HomeScreen {}",
                "app/lib/tinh_toan.dart", "int tong() => 0;"));
    }

    @Test
    void fileKhungPhatNamPhangODayLibKhongConDuocTinhLaTangDuLieu() throws Exception {
        // Luật cũ có glob `lib/**_helper.dart` nên `lib/database_helper.dart` — file mà chính
        // khung phát đặt phẳng ở gốc cho MỌI sinh viên — đủ để đạt ARCH_DATA. Đo trên bài thật
        // HE231604: trượt MODEL/SCREEN/LOGIC mà vẫn đạt DATA. Không được tái diễn.
        goldenPhang();

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                service.save("suite-1", Map.of("rules", List.of(presetById("ARCH_DATA")))));
        assertTrue(e.getMessage().contains("Tách tầng dữ liệu"), e.getMessage());
    }

    @Test
    void tenFileDungChuanMaVanNamPhangThiTruotCaBonTang() throws Exception {
        // Cùng bố cục phẳng nhưng đặt tên kiểu Anh: luật cũ cho ĐẠT CẢ BỐN vì khớp hậu tố tên
        // file. Sau khi bỏ hậu tố, không tầng nào đạt — luật chấm kiến trúc chứ không chấm tên.
        goldenZip(Map.of(
                "app/lib/main.dart", "void main() {}",
                "app/lib/database_helper.dart", "class DatabaseHelper {}",
                "app/lib/expense_model.dart", "class Expense {}",
                "app/lib/home_screen.dart", "class HomeScreen {}",
                "app/lib/expense_controller.dart", "class Controller {}"));

        for (String id : List.of("ARCH_DATA", "ARCH_MODEL", "ARCH_SCREEN", "ARCH_LOGIC")) {
            assertThrows(IllegalStateException.class,
                    () -> service.save("suite-1", Map.of("rules", List.of(presetById(id)))),
                    id + " phải trượt khi lib/ phẳng");
        }
    }

    @Test
    void donCaBonTangVaoMotThuMucThiTruotCaBon() throws Exception {
        // "Model mà nằm chung thư mục với data là hỏng": từ vựng bốn tầng rời nhau nên một thư
        // mục `app/` không thuộc tầng nào, dồn hết vào đó là trượt sạch.
        goldenZip(Map.of(
                "g/lib/main.dart", "void main() {}",
                "g/lib/app/database_helper.dart", "class DatabaseHelper {}",
                "g/lib/app/book.dart", "class Book {}",
                "g/lib/app/home_screen.dart", "class HomeScreen {}",
                "g/lib/app/calculator.dart", "int f() => 0;"));

        for (String id : List.of("ARCH_DATA", "ARCH_MODEL", "ARCH_SCREEN", "ARCH_LOGIC")) {
            assertThrows(IllegalStateException.class,
                    () -> service.save("suite-1", Map.of("rules", List.of(presetById(id)))),
                    id + " phải trượt khi bốn tầng dùng chung một thư mục");
        }
    }

    @Test
    void modelNamTrongThuMucDataThiKhongDuocTinhLaModel() throws Exception {
        // Thư mục dữ liệu có đủ, nhưng class dữ liệu để lẫn trong đó: DATA đạt, MODEL trượt.
        goldenZip(Map.of(
                "g/lib/main.dart", "void main() {}",
                "g/lib/data/database_helper.dart", MA_DU_LIEU,
                "g/lib/data/book.dart", MA_MODEL));

        assertDoesNotThrow(() -> service.save("suite-1", Map.of("rules", List.of(presetById("ARCH_DATA")))));
        assertThrows(IllegalStateException.class,
                () -> service.save("suite-1", Map.of("rules", List.of(presetById("ARCH_MODEL")))));
    }

    @Test
    void tenThuMucPhaiTrungKHIT_chuThuong_khongPhaiTienTo() throws Exception {
        // CHỐT 22/9/2026 — user quyết: KHÔNG nới phân biệt hoa/thường, và cũng không cần nhắc
        // trong đề bài ("sinh viên học trên lớp biết rồi"). Dart/Flutter quy ước thư mục
        // snake_case chữ thường, nên `Models/` là sai quy ước và trượt là đúng.
        // Đừng "sửa giúp" thành case-insensitive: làm vậy phải sửa cả static_checks.dart trong
        // ảnh Docker, sửa lệch một bên là Golden và bài nộp chấm khác nhau.
        for (String thuMuc : List.of("Models", "MODELS", "mymodels", "model_layer", "models_v2")) {
            goldenZip(Map.of(
                    "g/lib/main.dart", "void main() {}",
                    "g/lib/" + thuMuc + "/expense.dart", "class Expense {}"));
            assertThrows(IllegalStateException.class,
                    () -> service.save("suite-1", Map.of("rules", List.of(presetById("ARCH_MODEL")))),
                    "lib/" + thuMuc + "/ không được tính là thư mục Model");
        }
        // Chỉ đúng nguyên tên, chữ thường mới đạt.
        goldenZip(Map.of(
                "g/lib/main.dart", "void main() {}",
                "g/lib/models/expense.dart", "class Expense {}"));
        assertDoesNotThrow(() -> service.save("suite-1", Map.of("rules", List.of(presetById("ARCH_MODEL")))));
    }

    // ═══════════════ DẤU HIỆU NỘI DUNG TỪNG TẦNG ═══════════════

    private static final String MA_DU_LIEU =
            "import 'package:sqflite/sqflite.dart';\n"
                    + "class DatabaseHelper { static Future<Database> moKho() async => await openDatabase('a'); }";
    private static final String MA_MODEL =
            "class Book {\n  Book(this.title);\n  final String title;\n"
                    + "  Map<String, Object?> toMap() => <String, Object?>{'title': title};\n}";
    private static final String MA_WIDGET =
            "import 'package:flutter/material.dart';\n"
                    + "class HomeScreen extends StatelessWidget {\n"
                    + "  @override\n  Widget build(BuildContext context) => const Placeholder();\n}";

    @Test
    void datNhamFileVaoDungThuMucThiKhongAnDiemNua() throws Exception {
        // Đo thật 22/9/2026 TRƯỚC khi thêm dấu hiệu nội dung: hoán đổi hai tầng mà CẢ HAI luật
        // vẫn đạt, vì luật chỉ hỏi "thư mục tên đó có file không" chứ không mở file ra đọc.
        goldenZip(Map.of(
                "g/lib/main.dart", "void main() {}",
                "g/lib/data/book.dart", MA_MODEL,
                "g/lib/model/database_helper.dart", MA_DU_LIEU));

        IllegalStateException data = assertThrows(IllegalStateException.class, () ->
                service.save("suite-1", Map.of("rules", List.of(presetById("ARCH_DATA")))));
        assertTrue(data.getMessage().contains("mã đọc/ghi dữ liệu thật"), data.getMessage());

        IllegalStateException model = assertThrows(IllegalStateException.class, () ->
                service.save("suite-1", Map.of("rules", List.of(presetById("ARCH_MODEL")))));
        assertTrue(model.getMessage().contains("không được chứa"), model.getMessage());
    }

    @Test
    void thuMucManHinhRongRuotThiTruot() throws Exception {
        // Có screens/ nhưng bên trong không phải widget: dựng thư mục cho có là không đạt.
        goldenZip(Map.of(
                "g/lib/main.dart", "void main() {}",
                "g/lib/screens/ghi_chu.dart", "const String ten = 'x';"));
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                service.save("suite-1", Map.of("rules", List.of(presetById("ARCH_SCREEN")))));
        assertTrue(e.getMessage().contains("widget màn hình"), e.getMessage());
    }

    @Test
    void thuMucLogicGoiThangDatabaseThiTruot_nhungGoiRepoThiVanDat() throws Exception {
        // Cấm nói chuyện thẳng với DB.
        goldenZip(Map.of(
                "g/lib/main.dart", "void main() {}",
                "g/lib/controllers/expense_controller.dart", MA_DU_LIEU));
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                service.save("suite-1", Map.of("rules", List.of(presetById("ARCH_LOGIC")))));
        assertTrue(e.getMessage().contains("gọi DB/API trực tiếp"), e.getMessage());

        // Nhưng controller gọi repo.insert(...) là ĐÚNG kiến trúc — không được chặn nhầm.
        goldenZip(Map.of(
                "g/lib/main.dart", "void main() {}",
                "g/lib/controllers/expense_controller.dart",
                "class ExpenseController {\n  final dynamic repo;\n  ExpenseController(this.repo);\n"
                        + "  Future<void> them(Object e) => repo.insert(e);\n}"));
        assertDoesNotThrow(() -> service.save("suite-1", Map.of("rules", List.of(presetById("ARCH_LOGIC")))));
    }

    @Test
    void modelQuanLyDanhSachDungListInsertVanDat() throws Exception {
        // `list.insert(0, x)` là API List của Dart, không phải truy cập DB — vế CẤM của Model
        // cố ý chỉ dùng dấu hiệu THÔ để không trượt oan ca này.
        goldenZip(Map.of(
                "g/lib/main.dart", "void main() {}",
                "g/lib/models/gio_hang.dart",
                "class GioHang {\n  final List<String> items = <String>[];\n"
                        + "  void themDau(String x) { items.insert(0, x); }\n}"));
        assertDoesNotThrow(() -> service.save("suite-1", Map.of("rules", List.of(presetById("ARCH_MODEL")))));
    }

    @Test
    void boCucGoldenThatVanXanhSauKhiThemDauHieu() throws Exception {
        // Bố cục + NỘI DUNG đúng như hai Golden thật (QLCT và User Manager): cả bốn phải đạt.
        // Chú ý logic/ của Golden QLCT chỉ có hàm thuần, KHÔNG có class — nên vế "phải có class"
        // chỉ được đặt ở tầng Model, đừng bê sang Logic.
        goldenZip(Map.of(
                "g/lib/main.dart", "void main() {}",
                "g/lib/data/database_helper.dart", MA_DU_LIEU,
                "g/lib/models/expense.dart", MA_MODEL,
                "g/lib/screens/home_screen.dart", MA_WIDGET,
                "g/lib/logic/expense_calculator.dart",
                "int tongTheoThang(List<int> items) => items.fold(0, (a, b) => a + b);"));

        Map<String, Object> saved = service.save("suite-1", Map.of("rules", List.of(
                presetById("ARCH_DATA"), presetById("ARCH_MODEL"),
                presetById("ARCH_SCREEN"), presetById("ARCH_LOGIC"))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) saved.get("rules");
        for (Map<String, Object> r : rules) {
            @SuppressWarnings("unchecked")
            Map<String, Object> golden = (Map<String, Object>) r.get("golden");
            assertEquals(true, golden.get("passed"), r.get("id") + ": " + golden.get("detail"));
        }
    }

    @Test
    void thuMucTangLongTrongModuleVanDuocTinh() throws Exception {
        // `lib/**/<ten>/**` phải bắt được bố cục lồng một cấp, không bắt buộc nằm ngay dưới lib.
        goldenZip(Map.of(
                "g/lib/main.dart", "void main() {}",
                "g/lib/src/repositories/expense_repository.dart", MA_DU_LIEU));
        assertDoesNotThrow(() -> service.save("suite-1", Map.of("rules", List.of(presetById("ARCH_DATA")))));
    }

    @Test
    void luatDaLuuTuNgayXuaVanChamTheoDinhNghiaPresetMoiNhat() throws Exception {
        // Suite soạn từ trước khi siết luật: DB còn giữ mẫu hậu tố `lib/**_helper.dart`.
        // Republish phải phát ra ĐỊNH NGHĨA MỚI, nếu không thì sửa code xong đề cũ vẫn chấm lỏng
        // mà màn hình không có dấu hiệu gì.
        suite.setStaticRulesJson("""
                {"rules":[{"id":"ARCH_DATA","name":"Tach tang du lieu khoi giao dien",
                  "kind":"source_pattern","weight":1.5,"group_id":"Architecture",
                  "skill_code":"PROJ_FOLDER_STRUCTURE",
                  "config":{"require":[{"label":"cu","paths":["lib/**_helper.dart"],"min":1}]}}]}""");

        Map<String, Map<String, Object>> rows = service.matrixRows("suite-1", "PE_TEST");
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) rows.get("PE_TEST_STATIC_ARCH_DATA").get("static_config");
        assertFalse(String.valueOf(config).contains("_helper.dart"),
                "mẫu hậu tố cũ phải biến mất: " + config);
        assertTrue(String.valueOf(config).contains("lib/data/**"), String.valueOf(config));
        // Thứ thuộc về người soạn thì giữ nguyên.
        assertEquals(1.5, ((Number) rows.get("PE_TEST_STATIC_ARCH_DATA").get("weight")).doubleValue());
    }

}
