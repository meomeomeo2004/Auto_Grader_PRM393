package com.example.grader.service;

import com.example.grader.entity.BehaviorArtifact;
import com.example.grader.entity.BehaviorArtifactType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Replay một scenario trên Golden Solution với Hidden DB và thu Output DB thật.
 * Đây là cầu nối tự động giữa Record -> Abstract và oracle, thay cho việc giảng
 * viên tự chạy đáp án rồi tải một database sau thao tác lên bằng tay.
 */
@Service
public class GoldenOracleCaptureService {
    private static final int MAX_ZIP_ENTRIES = 20_000;
    private static final long MAX_UNCOMPRESSED_BYTES = 1_500L * 1024 * 1024;

    @Value("${grader.base-image:grading-base:latest}")
    private String baseImage;

    @Value("${grader.golden-capture-timeout-seconds:240}")
    private int timeoutSeconds;

    private final BehaviorAuthoringService authoring;
    private final BehaviorArtifactService artifacts;
    private final BehaviorSuiteMaterializer materializer;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public GoldenOracleCaptureService(BehaviorAuthoringService authoring,
                                      BehaviorArtifactService artifacts,
                                      BehaviorSuiteMaterializer materializer) {
        this.authoring = authoring;
        this.artifacts = artifacts;
        this.materializer = materializer;
    }

    public Map<String, Object> capture(String suiteId, String scenarioId) {
        BehaviorArtifact golden = artifacts.active(suiteId, BehaviorArtifactType.GOLDEN_SOLUTION);
        Path workspace = null;
        String containerName = "golden-capture-"
                + suiteId.substring(0, Math.min(8, suiteId.length())).toLowerCase(Locale.ROOT)
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            workspace = Files.createTempDirectory("grader-golden-capture-");
            Path extracted = workspace.resolve("golden");
            unzipSecure(Path.of(golden.getStoragePath()), extracted);
            Path project = locateFlutterProject(extracted);
            Path lib = project.resolve("lib");
            normalizeInternalPackageImports(project, lib);
            Path test = materializer.createCaptureBundle(suiteId, workspace.resolve("bundle"));

            Map<String, Object> plan = mapper.readValue(
                    test.resolve("behavior_plan.json").toFile(), new TypeReference<>() {});
            Map<String, Object> executionCase = list(plan.get("cases")).stream()
                    .map(GoldenOracleCaptureService::map)
                    .filter(item -> scenarioId.equals(text(item, "scenario_id")))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Không tìm thấy execution case cho scenario " + scenarioId));
            String executionCode = text(executionCase, "execution_code");
            if (executionCode.isBlank()) throw new IllegalStateException("Execution code của scenario bị trống");

            Path captured = test.resolve("fixtures").resolve("captured-output.db");
            Path metadata = test.resolve("fixtures").resolve("captured-output.json");
            List<String> command = new ArrayList<>(List.of(
                    "docker", "run", "--name", containerName, "--rm",
                    "--memory", "2048m", "--cpus", "2.0",
                    "-e", "GRADER_SCENARIO_CODE=" + executionCode,
                    "-e", "GRADER_CAPTURE_OUTPUT_PATH=/app/test/fixtures/captured-output.db",
                    "-e", "GRADER_CAPTURE_METADATA_PATH=/app/test/fixtures/captured-output.json",
                    "-v", toDockerPath(lib) + ":/app/lib",
                    "-v", toDockerPath(test) + ":/app/test"));
            Path assets = project.resolve("assets");
            if (Files.isDirectory(assets)) {
                command.add("-v");
                command.add(toDockerPath(assets) + ":/app/assets");
            }
            command.add(baseImage);
            command.add("flutter");
            command.add("test");
            command.add("--no-pub");
            command.add("--machine");
            command.add("--concurrency=1");
            command.add("test/exam_test.dart");

            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> readOutput(process, output), "golden-capture-output");
            reader.setDaemon(true);
            reader.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                removeContainer(containerName);
                throw new IllegalStateException("Golden replay timeout sau " + timeoutSeconds + " giây");
            }
            reader.join(5_000);
            if (process.exitValue() != 0) {
                // Bóc đúng CHECKPOINT NÀO trượt ra đầu thông báo. Không có nó, giáo viên chỉ
                // thấy "replay thất bại" kèm log thô — không biết phải sửa tiêu chí nào
                // (đã gặp thật: tick nhầm thành phần của extension trình duyệt, capture chết
                // mà thông báo không nói lý do).
                String hong = failedCheckpointSummary(output.toString());
                throw new IllegalStateException(hong.isBlank()
                        ? "Golden replay thất bại: " + limitLog(output.toString())
                        : "Golden replay thất bại — checkpoint không đạt trên chính Golden App:\n" + hong
                                + "\nKiểm lại các tiêu chí vừa thêm (thành phần có thật trên màn hình đó không?).");
            }
            if (!Files.isRegularFile(captured) || Files.size(captured) == 0) {
                throw new IllegalStateException("Golden replay kết thúc nhưng không sinh captured-output.db");
            }

            Map<String, Object> captureMetadata = Files.isRegularFile(metadata)
                    ? mapper.readValue(metadata.toFile(), new TypeReference<>() {})
                    : Map.of();
            Map<String, Object> outputArtifact = artifacts.writeGeneratedFile(
                    suiteId,
                    BehaviorArtifactType.OUTPUT_DATABASE,
                    "output-database.db",
                    captured,
                    Map.of(
                            "generated_from", "golden_hidden_replay",
                            "scenario_id", scenarioId,
                            "execution_code", executionCode,
                            "golden_sha256", golden.getSha256()));
            // Ảnh chuẩn cho tiêu chí screen_match — engine ghi cạnh captured-output.db.
            // Bản engine cũ không chụp: vắng file thì bỏ qua, không phải lỗi.
            Path screenshot = captured.resolveSibling("captured-screen.png");
            if (Files.isRegularFile(screenshot) && Files.size(screenshot) > 0) {
                Path screens = artifacts.goldenScreenshotDir(suiteId);
                Files.createDirectories(screens);
                Files.copy(screenshot, screens.resolve(executionCode + ".png"),
                        StandardCopyOption.REPLACE_EXISTING);
            }

            // Vị trí + màu chuẩn của thành phần giao diện — engine ghi cạnh captured-output.db
            // trong CÙNG khoảnh khắc với ảnh chuẩn. Bản engine cũ không đo: vắng file thì bỏ
            // qua, không phải lỗi. Nướng TRƯỚC applyDerivedDatabaseCheckpoints vì hàm đó cũng
            // ghi checkpointsJson; nó đọc lại bản vừa lưu nên số chuẩn không bị mất.
            int daNuongBoCuc = 0;
            int daNuongDuPhong = 0;
            Path layoutFile = captured.resolveSibling("captured-layout.json");
            if (Files.isRegularFile(layoutFile) && Files.size(layoutFile) > 0) {
                Map<String, Object> layout = mapper.readValue(layoutFile.toFile(), new TypeReference<>() {});
                daNuongBoCuc = authoring.applyCapturedLayout(scenarioId, map(layout.get("components")));
                // Đường dự phòng: cách tìm lại widget khi bài nộp quên gắn nhãn. Engine chỉ
                // ghi mô tả nào DUY NHẤT trên cây Golden nên ở đây nhận sao dùng vậy.
                daNuongDuPhong = authoring.applyCapturedTargets(scenarioId, map(layout.get("targets")));
            }

            List<Map<String, Object>> checkpoints = artifacts.databaseDiffCheckpoints(suiteId);
            Map<String, Object> completedScenario = authoring.applyDerivedDatabaseCheckpoints(
                    scenarioId, checkpoints, String.valueOf(outputArtifact.get("sha256")));

            Map<String, Object> result = new LinkedHashMap<>();
            // Luồng có nhập liệu mà không đẻ nổi một checkpoint database nào là dấu hiệu
            // gần như chắc chắn của hỏng-im-lặng: điểm hẹn database lệch, hoặc giá trị
            // nhập bị hỏng nên app không lưu (cả hai đều đã xảy ra thật 29-30/8, đều
            // "thành công" xanh mượt trong khi kịch bản Thêm không kiểm database gì cả).
            List<?> cacBuoc = completedScenario.get("steps") instanceof List<?> l1 ? l1 : List.of();
            List<?> cacKiem = completedScenario.get("checkpoints") instanceof List<?> l2 ? l2 : List.of();
            boolean coNhapLieu = cacBuoc.stream()
                    .anyMatch(s -> "enter_text".equals(String.valueOf(map(s).get("action"))));
            boolean coKiemDb = cacKiem.stream()
                    .anyMatch(c -> Set.of("database_observation", "entity_consistency")
                            .contains(String.valueOf(map(c).get("kind"))));
            if (coNhapLieu && !coKiemDb) {
                result.put("capture_warning",
                        "Luồng có nhập liệu nhưng replay KHÔNG làm database thay đổi — không sinh được"
                        + " checkpoint database nào. Kiểm lại: giá trị nhập có qua được validate không,"
                        + " app có thật sự lưu không. Nếu bỏ qua, kịch bản này sẽ không kiểm database.");
            }
            result.put("scenario", completedScenario);
            result.put("output_database", outputArtifact);
            result.put("database_checkpoint_count", checkpoints.size());
            result.put("layout_checkpoint_count", daNuongBoCuc);
            result.put("fallback_target_count", daNuongDuPhong);
            result.put("execution_code", executionCode);
            result.put("log", limitLog(output.toString()));
            return result;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Không capture được oracle từ Golden Solution: " + e.getMessage(), e);
        } finally {
            removeContainer(containerName);
            if (workspace != null) deleteQuietly(workspace);
        }
    }

    private void unzipSecure(Path zipPath, Path destination) throws Exception {
        SecureZipExtractor.extract(zipPath, destination, MAX_ZIP_ENTRIES, MAX_UNCOMPRESSED_BYTES);
    }

    private Path locateFlutterProject(Path extracted) throws Exception {
        try (Stream<Path> files = Files.walk(extracted, 8)) {
            Path main = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("main.dart"))
                    .filter(path -> path.getParent() != null
                            && path.getParent().getFileName().toString().equals("lib"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Golden ZIP không có lib/main.dart"));
            return main.getParent().getParent();
        }
    }

    private void normalizeInternalPackageImports(Path project, Path lib) throws Exception {
        Path pubspec = project.resolve("pubspec.yaml");
        if (!Files.isRegularFile(pubspec)) return;
        String packageName = null;
        for (String line : Files.readAllLines(pubspec, StandardCharsets.UTF_8)) {
            if (line.startsWith("name:")) {
                packageName = line.substring("name:".length()).trim();
                break;
            }
        }
        if (packageName == null || packageName.isBlank() || "exam_project".equals(packageName)) return;
        try (Stream<Path> files = Files.walk(lib)) {
            for (Path file : files.filter(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".dart")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String normalized = source.replace("package:" + packageName + "/", "package:exam_project/");
                if (!source.equals(normalized)) Files.writeString(file, normalized, StandardCharsets.UTF_8);
            }
        }
    }

    private void readOutput(Process process, StringBuilder output) {
        try (BufferedReader lines = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) output.append(line).append('\n');
        } catch (Exception ignored) {
        }
    }

    private String toDockerPath(Path path) {
        String value = path.toAbsolutePath().normalize().toString();
        if (value.length() >= 2 && value.charAt(1) == ':') {
            return "/" + Character.toLowerCase(value.charAt(0)) + value.substring(2).replace('\\', '/');
        }
        return value.replace('\\', '/');
    }

    private void removeContainer(String name) {
        try {
            Process process = new ProcessBuilder("docker", "rm", "-f", name).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (Exception ignored) {
        }
    }

    private void deleteQuietly(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    /** Nhặt các dòng ###RAR_CHECKPOINT### có passed=false: "tên — lý do", mỗi checkpoint một dòng. */
    private String failedCheckpointSummary(String output) {
        StringBuilder sb = new StringBuilder();
        for (String line : output.split("\n")) {
            int at = line.indexOf("###RAR_CHECKPOINT###");
            if (at < 0) continue;
            try {
                Map<String, Object> item = mapper.readValue(
                        line.substring(at + "###RAR_CHECKPOINT###".length()).trim(),
                        new TypeReference<Map<String, Object>>() {});
                if (Boolean.TRUE.equals(item.get("passed"))) continue;
                String message = String.valueOf(item.getOrDefault("message", ""));
                sb.append("  · ").append(item.getOrDefault("test_id", "?"))
                  .append(": ").append(message.length() > 200 ? message.substring(0, 200) + "…" : message)
                  .append('\n');
            } catch (Exception ignored) {
            }
        }
        return sb.toString().trim();
    }

    private String limitLog(String value) {
        int max = 40_000;
        return value.length() <= max ? value : value.substring(value.length() - max);
    }

    private static List<Object> list(Object value) {
        return value instanceof List<?> source ? new ArrayList<>(source) : new ArrayList<>();
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return new LinkedHashMap<>();
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((key, item) -> out.put(String.valueOf(key), item));
        return out;
    }

    private static Map<String, String> stringMap(Object value) {
        Map<String, String> out = new LinkedHashMap<>();
        map(value).forEach((key, item) -> out.put(key, item == null ? "" : String.valueOf(item)));
        return out;
    }

    private static String text(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
