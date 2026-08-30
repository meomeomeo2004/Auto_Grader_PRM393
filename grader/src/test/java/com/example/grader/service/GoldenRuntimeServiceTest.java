package com.example.grader.service;

import com.example.grader.entity.BehaviorArtifact;
import com.example.grader.entity.BehaviorArtifactType;
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

class GoldenRuntimeServiceTest {
    @TempDir
    Path temp;

    private BehaviorArtifactService artifacts;
    private GoldenRuntimeService service;

    @BeforeEach
    void setUp() {
        artifacts = mock(BehaviorArtifactService.class);
        service = new GoldenRuntimeService(
                artifacts,
                mock(BehaviorSuiteRepository.class),
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
        assertThat(html).contains("snapshot_ui");
        assertThat(html).contains("aria-label");
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
    }

    @Test
    void dockerBuildScriptIsCompatibleWithPosixSh() {
        String script = ReflectionTestUtils.invokeMethod(service, "buildScript", "suite-1");

        assertThat(script).startsWith("set -eu;");
        assertThat(script).doesNotContain("pipefail");
        assertThat(script).contains("flutter build web --release --no-pub");
    }
}
