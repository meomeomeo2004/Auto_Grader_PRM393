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
                "MyGolden/lib/screens/home_screen.dart", "class HomeScreen {}"));

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
}
