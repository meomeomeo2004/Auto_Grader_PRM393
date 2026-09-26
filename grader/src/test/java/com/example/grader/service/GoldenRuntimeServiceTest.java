package com.example.grader.service;

import com.example.grader.entity.BehaviorArtifact;
import com.example.grader.entity.BehaviorArtifactType;
import com.example.grader.entity.BehaviorSuite;
import com.example.grader.repository.BehaviorSuiteRepository;
import com.example.grader.repository.GoldenAppRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class GoldenRuntimeServiceTest {
    @TempDir
    Path temp;

    private BehaviorArtifactService artifacts;
    private GoldenRuntimeService service;
    private BehaviorSuiteRepository suites;

    @BeforeEach
    void setUp() {
        artifacts = mock(BehaviorArtifactService.class);
        suites = mock(BehaviorSuiteRepository.class);
        BehaviorSuite suite = new BehaviorSuite();
        suite.setDatabaseContractJson("{\"database_name\":\"expenses.db\"}");
        when(suites.findById("suite-1")).thenReturn(java.util.Optional.of(suite));
        service = new GoldenRuntimeService(
                artifacts,
                suites,
                mock(GoldenAppRepository.class));
        ReflectionTestUtils.setField(service, "runtimeDir", temp.toString());
    }

    @Test
    void servesOnlyFilesInsideTheActiveContentAddressedRuntime() throws Exception {
        BehaviorArtifact golden = new BehaviorArtifact();
        golden.setSuiteId("suite-1");
        golden.setArtifactType(BehaviorArtifactType.GOLDEN_SOLUTION);
        golden.setSha256("abc123");
        when(artifacts.active("suite-1", BehaviorArtifactType.GOLDEN_SOLUTION)).thenReturn(golden);

        // Thư mục runtime khoá theo SHA + RECORDER_BRIDGE_VERSION (content-addressed, xem
        // runtimeVersion()), không phải chỉ SHA — gọi qua reflection để không hardcode version.
        String runtimeVersion = ReflectionTestUtils.invokeMethod(service, "runtimeVersion", golden);
        Path root = temp.resolve("suite-1").resolve(runtimeVersion);
        Files.createDirectories(root);
        Files.writeString(root.resolve("index.html"), "<html></html>", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("flutter.js"), "console.log('ok')", StandardCharsets.UTF_8);

        GoldenRuntimeService.RuntimeFile index = service.resource("suite-1", "");
        assertThat(index.index()).isTrue();
        assertThat(index.contentType()).isEqualTo("text/html");
        assertThat(index.resource().exists()).isTrue();

        GoldenRuntimeService.RuntimeFile script = service.resource("suite-1", "/flutter.js");
        assertThat(script.index()).isFalse();
        assertThat(script.contentType()).isIn("application/javascript", "text/javascript");

        assertThatThrownBy(() -> service.resource("suite-1", "../../secret.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("khong an toan");
    }

    @Test
    void injectsSemanticRecorderAndUiSnapshotBridge() throws Exception {
        Path index = temp.resolve("index.html");
        Files.writeString(index, "<html><body>Golden</body></html>", StandardCharsets.UTF_8);

        ReflectionTestUtils.invokeMethod(service, "injectRecorderBridge", index);

        String html = Files.readString(index, StandardCharsets.UTF_8);
        assertThat(html).contains("GOLDEN_RECORDER_EVENT");
        assertThat(html).contains("GOLDEN_RECORDER_COMMAND");
        assertThat(html).contains("GOLDEN_RECORDER_FLUSHED");
        assertThat(html).contains("snapshot_ui");
        // Định danh của widget KHÔNG có chữ (ListView, Stack, Table bọc Semantics) không
        // bao giờ lọt vào bảng tick, mà chính chúng là đích của tiêu chí bố cục Ch.7 —
        // màn soạn đề lấy danh sách đó qua lệnh riêng này.
        assertThat(html).contains("snapshot_identifiers");
        assertThat(html).contains("GOLDEN_RECORDER_IDENTIFIERS");
        assertThat(html).contains("flush_input");
        assertThat(html).contains("GOLDEN_RECORDER_ROUTE");
        assertThat(html).contains("perform_route_action");
        assertThat(html).contains("history.pushState");
        assertThat(html).contains("history.back()");
        assertThat(html).contains("new URL(raw, location.origin)");
        assertThat(html).contains("parsed.search + parsed.hash");
        assertThat(html).contains("compositionend");
        assertThat(html).doesNotContain("setTimeout(chotEnterText, 700)");
        assertThat(html).contains("aria-label");
        assertThat(html).contains("flt-semantics-identifier");
        assertThat(html).contains("INTERACTIVE_ROLES");
        assertThat(html).contains("CONTAINER_ROLES");
        assertThat(html).contains("Khong ghi duoc thao tac nay");
        // Bấm vào KHOẢNG TRỐNG phải im lặng bỏ qua: cuộn màn, bỏ focus khỏi ô nhập, bấm
        // bâng quơ đều là việc bình thường lúc ghi hình. Cảnh báo mọi cú bấm trượt thì
        // người soạn quen luôn cả những cảnh báo thật.
        assertThat(html).contains("function coControlTaiDiem(event)");
        assertThat(html).contains("if (coControlTaiDiem(event)) warnNoTarget();");
        assertThat(html).contains("uniqueLocator('semanticId', semanticId, elements)");
        assertThat(html).contains("function spatialSemanticNode(event, mode, elements)");
        assertThat(html).contains("Nhieu semantic control trung khop tai cung vi tri.");
        assertThat(html).contains("if (ta.label && tb.label) return ta.label === tb.label");
        assertThat(html).doesNotContain("document.elementFromPoint");
    }

    @Test
    void preparesProjectWithNormalizedImportsAndKnownAssetDirectories() throws Exception {
        Path source = temp.resolve("golden");
        Path target = temp.resolve("runtime-project");
        Files.createDirectories(source.resolve("lib/assets"));
        Files.writeString(source.resolve("pubspec.yaml"), "name: golden_answer\n", StandardCharsets.UTF_8);
        Files.writeString(source.resolve("lib/main.dart"),
                "import 'package:golden_answer/models/user.dart';\nvoid main() {}\n",
                StandardCharsets.UTF_8);
        Files.writeString(source.resolve("lib/assets/avatar.txt"), "asset", StandardCharsets.UTF_8);

        ReflectionTestUtils.setField(service, "templateDir", temp.resolve("templates").toString());
        Files.createDirectories(temp.resolve("templates"));
        Files.writeString(temp.resolve("templates/pubspec.base.yaml"),
                "name: exam_project\nflutter:\n  uses-material-design: true\n",
                StandardCharsets.UTF_8);

        ReflectionTestUtils.invokeMethod(service, "prepareProject", "suite-1", source, target);

        assertThat(Files.readString(target.resolve("lib/main.dart"), StandardCharsets.UTF_8))
                .contains("package:exam_project/models/user.dart")
                .doesNotContain("package:golden_answer/");
        assertThat(Files.readString(target.resolve("pubspec.yaml"), StandardCharsets.UTF_8))
                .contains("assets:")
                .contains("- lib/assets/");
        assertThat(Files.readString(target.resolve("lib/_recorder_entry.dart"), StandardCharsets.UTF_8))
                .contains("'expenses.db'")
                .doesNotContain("'app.db'");
    }

    @Test
    void napDatabaseTheoHopDongPathCu() throws Exception {
        var suite = suites.findById("suite-1").orElseThrow();
        suite.setDatabaseContractJson("{\"path\":\"/legacy/expenses.db\"}");
        Path source = temp.resolve("golden-legacy");
        Path target = temp.resolve("runtime-legacy");
        Files.createDirectories(source.resolve("lib"));
        Files.writeString(source.resolve("lib/main.dart"), "void main() {}\n");
        ReflectionTestUtils.setField(service, "templateDir", temp.resolve("templates-legacy").toString());
        Files.createDirectories(temp.resolve("templates-legacy"));
        Files.writeString(temp.resolve("templates-legacy/pubspec.base.yaml"), "name: exam_project\n");

        ReflectionTestUtils.invokeMethod(service, "prepareProject", "suite-1", source, target);

        assertThat(Files.readString(target.resolve("lib/_recorder_entry.dart")))
                .contains("'expenses.db'");
    }

    /**
     * Đề KHÔNG dùng database: bản web vẫn dựng được và entry không đụng tới sqflite.
     *
     * <p>Trước 26/9/2026 prepareProject ném "Chưa xác định được tên database hợp lệ" với mọi Golden
     * không lộ ra tên .db — đề máy tính cộng trừ không mở được khung Golden để ghi thao tác.
     */
    @Test
    void deKhongDungDatabaseThiEntryKhongNapDatabase() throws Exception {
        suites.findById("suite-1").orElseThrow().setDatabaseContractJson("{\"enabled\":false}");
        when(artifacts.khongDungDatabase("suite-1")).thenReturn(true);
        Path source = temp.resolve("golden-calc");
        Path target = temp.resolve("runtime-calc");
        Files.createDirectories(source.resolve("lib"));
        Files.writeString(source.resolve("lib/main.dart"), "void main() {}\n");
        ReflectionTestUtils.setField(service, "templateDir", temp.resolve("templates-calc").toString());
        Files.createDirectories(temp.resolve("templates-calc"));
        Files.writeString(temp.resolve("templates-calc/pubspec.base.yaml"), "name: exam_project\n");

        ReflectionTestUtils.invokeMethod(service, "prepareProject", "suite-1", source, target);

        assertThat(Files.readString(target.resolve("lib/_recorder_entry.dart"), StandardCharsets.UTF_8))
                .contains("golden_app.main();")
                .contains("ensureSemantics()")
                .doesNotContain("sqflite")
                .doesNotContain("rootBundle")
                .doesNotContain("grader_hidden.db")
                .doesNotContain("{{");
        verify(artifacts, never()).activeOptional("suite-1", BehaviorArtifactType.HIDDEN_DATABASE);
    }

    /** Đề CÓ database: entry vẫn nạp đúng tên, và không sót chỗ giữ chỗ nào của mẫu. */
    @Test
    void deCoDatabaseThiEntryVanNapDungTen() throws Exception {
        Path source = temp.resolve("golden-db");
        Path target = temp.resolve("runtime-db");
        Files.createDirectories(source.resolve("lib"));
        Files.writeString(source.resolve("lib/main.dart"), "void main() {}\n");
        ReflectionTestUtils.setField(service, "templateDir", temp.resolve("templates-db").toString());
        Files.createDirectories(temp.resolve("templates-db"));
        Files.writeString(temp.resolve("templates-db/pubspec.base.yaml"), "name: exam_project\n");

        ReflectionTestUtils.invokeMethod(service, "prepareProject", "suite-1", source, target);

        assertThat(Files.readString(target.resolve("lib/_recorder_entry.dart"), StandardCharsets.UTF_8))
                .contains("import 'package:sqflite_common/sqflite.dart' as sqflite_common;")
                .contains("rootBundle.load('assets/grader_hidden.db')")
                .contains("writeDatabaseBytes(")
                .contains("'expenses.db',")
                .contains("golden_app.main();")
                .doesNotContain("{{");
    }

    @Test
    void dockerBuildScriptIsCompatibleWithPosixSh() {
        String script = ReflectionTestUtils.invokeMethod(service, "buildScript", "suite-1");

        assertThat(script).startsWith("set -eu;");
        assertThat(script).doesNotContain("pipefail");
        assertThat(script).contains("flutter build web --release --no-pub");
    }
}
