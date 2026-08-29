package com.example.grader.service;

import com.example.grader.entity.*;
import com.example.grader.repository.GoldenValidationRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Chạy execution plan trên Golden Solution và lưu bằng chứng trước khi publish. */
@Service
public class GoldenValidationService {
    private static final String CHECKPOINT_MARKER = "###RAR_CHECKPOINT###";
    private static final int MAX_ZIP_ENTRIES = 20_000;
    private static final long MAX_EXPANDED_BYTES = 1_500L * 1024 * 1024;

    @Value("${grader.base-image:grading-base:latest}")
    private String baseImage;

    @Value("${grader.golden-validation-timeout-seconds:300}")
    private int timeoutSeconds;

    private final BehaviorAuthoringService authoring;
    private final BehaviorArtifactService artifacts;
    private final BehaviorSuiteMaterializer materializer;
    private final GoldenValidationRunRepository runs;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public GoldenValidationService(BehaviorAuthoringService authoring,
                                   BehaviorArtifactService artifacts,
                                   BehaviorSuiteMaterializer materializer,
                                   GoldenValidationRunRepository runs) {
        this.authoring = authoring;
        this.artifacts = artifacts;
        this.materializer = materializer;
        this.runs = runs;
    }

    public Map<String, Object> validate(String suiteId) {
        artifacts.requireComplete(suiteId);
        BehaviorArtifact golden = artifacts.active(suiteId, BehaviorArtifactType.GOLDEN_SOLUTION);
        String fingerprint = currentPlanSha(suiteId);
        GoldenValidationRun run = new GoldenValidationRun();
        run.setSuiteId(suiteId);
        run.setGoldenSha256(golden.getSha256());
        run.setPlanSha256(fingerprint);
        runs.save(run);

        Path workspace = null;
        String containerName = "golden-preflight-" + suiteId.substring(0, Math.min(8, suiteId.length())).toLowerCase(Locale.ROOT)
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            workspace = Files.createTempDirectory("grader-golden-preflight-");
            Path extracted = workspace.resolve("golden");
            unzipSecure(Path.of(golden.getStoragePath()), extracted);
            Path project = locateFlutterProject(extracted);
            Path lib = project.resolve("lib");
            normalizeInternalPackageImports(project, lib);
            Path test = materializer.createValidationBundle(suiteId, workspace.resolve("bundle"));

            List<String> command = new ArrayList<>(List.of(
                    "docker", "run", "--name", containerName, "--rm",
                    "--memory", "2048m", "--cpus", "2.0",
                    "-e", "GRADER_ANALYZE_LIB=false",
                    "-e", "GRADER_BATCH_TIMEOUT_SECONDS=120",
                    "-e", "GRADER_TOTAL_TIMEOUT_SECONDS=" + Math.max(60, timeoutSeconds - 15),
                    "-v", toDockerPath(lib) + ":/app/lib",
                    "-v", toDockerPath(test) + ":/app/test"));
            Path assets = project.resolve("assets");
            if (Files.isDirectory(assets)) {
                command.add("-v");
                command.add(toDockerPath(assets) + ":/app/assets");
            }
            command.add(baseImage);
            command.add("./run_grader.sh");

            Process process;
            try {
                process = new ProcessBuilder(command).redirectErrorStream(true).start();
            } catch (Exception e) {
                run.setStatus(GoldenValidationStatus.UNAVAILABLE);
                run.setLogText("Không gọi được Docker: " + e.getMessage());
                return finish(run);
            }
            StringBuilder output = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader lines = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) output.append(line).append('\n');
                } catch (Exception ignored) {
                }
            }, "golden-preflight-output");
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                removeContainer(containerName);
                run.setStatus(GoldenValidationStatus.FAILED);
                run.setLogText("Golden preflight timeout sau " + timeoutSeconds + " giây.\n" + output);
                return finish(run);
            }
            reader.join(5_000);
            String rawOutput = output.toString();
            if (process.exitValue() != 0 && isDockerUnavailable(rawOutput)) {
                run.setStatus(GoldenValidationStatus.UNAVAILABLE);
                run.setLogText(limitLog(rawOutput));
                return finish(run);
            }
            List<Boolean> checkpoints = parseCheckpointResults(rawOutput);
            run.setTotalCheckpoints(checkpoints.size());
            run.setPassedCheckpoints((int) checkpoints.stream().filter(Boolean::booleanValue).count());
            run.setLogText(limitLog(rawOutput));
            run.setStatus(process.exitValue() == 0 && !checkpoints.isEmpty()
                    && checkpoints.stream().allMatch(Boolean::booleanValue)
                    ? GoldenValidationStatus.PASSED : GoldenValidationStatus.FAILED);
            return finish(run);
        } catch (Exception e) {
            run.setStatus(GoldenValidationStatus.FAILED);
            run.setLogText(limitLog("Preflight thất bại: " + e.getMessage()));
            return finish(run);
        } finally {
            removeContainer(containerName);
            if (workspace != null) deleteQuietly(workspace);
        }
    }

    public Map<String, Object> latest(String suiteId) {
        return runs.findFirstBySuiteIdOrderByCreatedAtDesc(suiteId)
                .map(row -> view(row, Objects.equals(row.getPlanSha256(), currentPlanSha(suiteId))))
                .orElseGet(() -> Map.of("suite_id", suiteId, "status", "NOT_RUN", "current", false));
    }

    public void requirePassed(String suiteId) {
        GoldenValidationRun latest = runs.findFirstBySuiteIdOrderByCreatedAtDesc(suiteId)
                .orElseThrow(() -> new IllegalStateException("Cần chạy kiểm chứng Golden trước khi publish"));
        if (latest.getStatus() != GoldenValidationStatus.PASSED
                || !Objects.equals(latest.getPlanSha256(), currentPlanSha(suiteId))) {
            throw new IllegalStateException(
                    "Golden preflight chưa pass hoặc execution plan đã thay đổi; hãy chạy kiểm chứng lại");
        }
    }

    private String currentPlanSha(String suiteId) {
        try {
            Map<String, Object> fingerprint = new LinkedHashMap<>();
            fingerprint.put("plan", authoring.previewExecutionPlan(suiteId));
            fingerprint.put("artifacts", artifacts.activeManifest(suiteId));
            return sha256(mapper.writeValueAsBytes(fingerprint));
        } catch (Exception e) {
            throw new IllegalStateException("Không tạo được fingerprint bộ chấm", e);
        }
    }

    private Map<String, Object> finish(GoldenValidationRun run) {
        run.setCompletedAt(Instant.now());
        runs.save(run);
        return view(run, Objects.equals(run.getPlanSha256(), currentPlanSha(run.getSuiteId())));
    }

    private Map<String, Object> view(GoldenValidationRun run, boolean current) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", run.getId());
        result.put("suite_id", run.getSuiteId());
        result.put("status", run.getStatus().name());
        result.put("current", current);
        result.put("golden_sha256", run.getGoldenSha256());
        result.put("plan_sha256", run.getPlanSha256());
        result.put("total_checkpoints", run.getTotalCheckpoints());
        result.put("passed_checkpoints", run.getPassedCheckpoints());
        result.put("log", run.getLogText());
        result.put("created_at", run.getCreatedAt() == null ? null : run.getCreatedAt().toString());
        result.put("completed_at", run.getCompletedAt() == null ? null : run.getCompletedAt().toString());
        return result;
    }

    private void unzipSecure(Path zipPath, Path destination) throws Exception {
        SecureZipExtractor.extract(zipPath, destination, MAX_ZIP_ENTRIES, MAX_EXPANDED_BYTES);
    }

    private Path locateFlutterProject(Path extracted) throws Exception {
        try (Stream<Path> files = Files.walk(extracted, 8)) {
            Path main = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("main.dart"))
                    .filter(path -> path.getParent() != null && path.getParent().getFileName().toString().equals("lib"))
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
        try (Stream<Path> dartFiles = Files.walk(lib)) {
            for (Path file : dartFiles.filter(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".dart")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String normalized = source.replace("package:" + packageName + "/", "package:exam_project/");
                if (!source.equals(normalized)) Files.writeString(file, normalized, StandardCharsets.UTF_8);
            }
        }
    }

    private List<Boolean> parseCheckpointResults(String output) {
        // Từ ảnh chấm có pipeline 4 tầng (2026-08-22), stdout container chỉ còn khối
        // GRADE_RESULT của bộ hợp nhất; marker ###RAR_CHECKPOINT### nằm lại trong file
        // log BÊN TRONG container. Đếm marker khi đó luôn ra 0/0 và preflight trượt
        // bất kể bộ đề đúng sai — lỗi đã gặp thật với PE_PRM393_SP27. Vì vậy ưu tiên
        // đọc GRADE_RESULT (chỉ đếm tiêu chí tầng hành vi: preflight tắt tầng tĩnh,
        // tầng đơn vị không có); không thấy khối này mới rơi về cách đếm marker cũ
        // để vẫn tương thích ảnh đời trước.
        List<Boolean> fromMerge = parseMergedBehaviorResults(output);
        if (!fromMerge.isEmpty()) return fromMerge;
        List<Boolean> result = new ArrayList<>();
        for (String line : output.split("\\R")) {
            String message = line;
            try {
                JsonNode machineEvent = mapper.readTree(line);
                if ("print".equals(machineEvent.path("type").asText())) {
                    message = machineEvent.path("message").asText("");
                }
            } catch (Exception ignored) {
            }
            int marker = message.indexOf(CHECKPOINT_MARKER);
            if (marker < 0) continue;
            String json = message.substring(marker + CHECKPOINT_MARKER.length()).trim();
            try {
                JsonNode node = mapper.readTree(json);
                result.add(node.path("passed").asBoolean(false));
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    /** Đọc test_cases tầng BEHAVIOR_REPLAY từ khối GRADE_RESULT; rỗng nếu không có khối. */
    private List<Boolean> parseMergedBehaviorResults(String output) {
        List<Boolean> result = new ArrayList<>();
        int start = output.indexOf("--- GRADE_RESULT_START ---");
        if (start < 0) return result;
        int end = output.indexOf("--- GRADE_RESULT_END ---", start);
        if (end < 0) return result;
        String json = output.substring(start + "--- GRADE_RESULT_START ---".length(), end).trim();
        try {
            JsonNode root = mapper.readTree(json);
            for (JsonNode testCase : root.path("test_cases")) {
                String runner = testCase.path("runner").asText("BEHAVIOR_REPLAY");
                if (!"BEHAVIOR_REPLAY".equals(runner)) continue;
                result.add("passed".equals(testCase.path("status").asText("")));
            }
        } catch (Exception ignored) {
            // Khối hỏng thì coi như không có, để fallback marker xử lý.
            result.clear();
        }
        return result;
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private String toDockerPath(Path path) {
        String value = path.toAbsolutePath().normalize().toString();
        if (value.length() >= 2 && value.charAt(1) == ':') {
            return "/" + Character.toLowerCase(value.charAt(0)) + value.substring(2).replace('\\', '/');
        }
        return value.replace('\\', '/');
    }

    private String limitLog(String value) {
        int max = 200_000;
        return value.length() <= max ? value : value.substring(value.length() - max);
    }

    private boolean isDockerUnavailable(String output) {
        String normalized = output == null ? "" : output.toLowerCase(Locale.ROOT);
        return normalized.contains("cannot connect to the docker daemon")
                || normalized.contains("error during connect")
                || normalized.contains("docker engine is not running")
                || normalized.contains("the system cannot find the file specified")
                || normalized.contains("open //./pipe/docker_engine")
                || normalized.contains("open \\.\\pipe\\docker_engine");
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
}
