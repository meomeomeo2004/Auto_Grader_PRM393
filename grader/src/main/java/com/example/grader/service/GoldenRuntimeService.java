package com.example.grader.service;

import com.example.grader.entity.BehaviorArtifact;
import com.example.grader.entity.BehaviorArtifactType;
import com.example.grader.entity.BehaviorSuite;
import com.example.grader.entity.GoldenApp;
import com.example.grader.entity.GoldenAppStatus;
import com.example.grader.repository.BehaviorSuiteRepository;
import com.example.grader.repository.GoldenAppRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Builds and serves an instrumented Flutter Web copy of a Golden Solution. */
@Service
public class GoldenRuntimeService {
    private static final long MAX_EXPANDED_BYTES = 1_000L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 20_000;
    // Bump khi sửa logic script recorder (targetOf/action/snapshot...) để build cache cũ
    // (đường dẫn khoá theo SHA golden + version này) không bị dùng nhầm script cũ.
    private static final String RECORDER_BRIDGE_VERSION = "semantic-v3";

    @Value("${grader.base-image:grading-base:latest}")
    private String baseImage;

    @Value("${grader.template-dir:grader-base}")
    private String templateDir;

    @Value("${grader.golden-runtime-dir:golden-runtimes}")
    private String runtimeDir;

    @Value("${grader.golden-runtime-build-timeout-seconds:420}")
    private int buildTimeoutSeconds;

    private final BehaviorArtifactService artifacts;
    private final BehaviorSuiteRepository suites;
    private final GoldenAppRepository goldenApps;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public GoldenRuntimeService(BehaviorArtifactService artifacts,
                                BehaviorSuiteRepository suites,
                                GoldenAppRepository goldenApps) {
        this.artifacts = artifacts;
        this.suites = suites;
        this.goldenApps = goldenApps;
    }

    /**
     * Build is content-addressed by Golden ZIP SHA. Repeating deploy on the same artifact is instant.
     * A failed build never replaces the last valid runtime.
     */
    public Map<String, Object> deploy(String suiteId) {
        BehaviorSuite suite = suite(suiteId);
        GoldenApp app = golden(suite.getGoldenAppId());
        BehaviorArtifact golden = artifacts.active(suiteId, BehaviorArtifactType.GOLDEN_SOLUTION);
        Path finalRoot = runtimeRoot().resolve(safeSegment(suiteId)).resolve(runtimeVersion(golden)).normalize();
        Path index = finalRoot.resolve("index.html");
        String runtimePath = runtimePath(suiteId);

        if (Files.isRegularFile(index)) {
            app.setRuntimeUrl(runtimePath);
            app.setStatus(GoldenAppStatus.READY);
            app.setMetadataJson(metadata(golden, "cache-hit", ""));
            goldenApps.save(app);
            return view(app, runtimePath, true, true, "Golden runtime da san sang tu ban build hien tai.");
        }

        app.setStatus(GoldenAppStatus.BUILDING);
        app.setRuntimeUrl(null);
        goldenApps.save(app);
        Path workspace = null;
        String containerName = "golden-web-" + safeSegment(suiteId).substring(0, Math.min(8, safeSegment(suiteId).length()))
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        StringBuilder output = new StringBuilder();
        try {
            Files.createDirectories(runtimeRoot().resolve(safeSegment(suiteId)));
            workspace = Files.createTempDirectory(runtimeRoot().resolve(safeSegment(suiteId)), ".build-");
            Path extracted = workspace.resolve("extracted");
            unzipSecure(Path.of(golden.getStoragePath()), extracted);
            Path sourceProject = locateFlutterProject(extracted);
            Path project = workspace.resolve("project");
            prepareProject(sourceProject, project);

            List<String> command = List.of(
                    "docker", "run", "--name", containerName, "--rm",
                    "--memory", "2048m", "--cpus", "2.0",
                    "-v", toDockerPath(project) + ":/runtime",
                    baseImage,
                    "bash", "-lc", buildScript(suiteId));
            Process process;
            try {
                process = new ProcessBuilder(command).redirectErrorStream(true).start();
            } catch (Exception e) {
                throw new IllegalStateException("Khong goi duoc Docker: " + e.getMessage(), e);
            }
            Thread reader = new Thread(() -> readOutput(process, output), "golden-web-build-output");
            reader.setDaemon(true);
            reader.start();
            boolean finished = process.waitFor(buildTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                removeContainer(containerName);
                throw new IllegalStateException("Build Golden Web timeout sau " + buildTimeoutSeconds + " giay");
            }
            reader.join(5_000);
            if (process.exitValue() != 0) {
                throw new IllegalStateException(isDockerUnavailable(output.toString())
                        ? "Docker chua san sang. Hay bat Docker Desktop roi thu lai."
                        : "Flutter Web build that bai. Xem build_log de biet chi tiet.");
            }
            Path web = project.resolve("build").resolve("web");
            if (!Files.isRegularFile(web.resolve("index.html"))) {
                throw new IllegalStateException("Docker ket thuc nhung khong tao build/web/index.html");
            }
            injectRecorderBridge(web.resolve("index.html"));
            Files.createDirectories(finalRoot.getParent());
            moveDirectory(web, finalRoot);

            app.setRuntimeUrl(runtimePath);
            app.setStatus(GoldenAppStatus.READY);
            app.setMetadataJson(metadata(golden, "built", limitLog(output.toString())));
            goldenApps.save(app);
            return view(app, runtimePath, false, true, "Build Golden runtime thanh cong.");
        } catch (Exception e) {
            app.setStatus(GoldenAppStatus.FAILED);
            app.setMetadataJson(metadata(golden, "failed", limitLog(output + "\n" + e.getMessage())));
            goldenApps.save(app);
            throw e instanceof IllegalStateException state ? state
                    : new IllegalStateException("Khong build duoc Golden runtime: " + e.getMessage(), e);
        } finally {
            removeContainer(containerName);
            if (workspace != null) deleteQuietly(workspace);
        }
    }

    public Map<String, Object> status(String suiteId) {
        BehaviorSuite suite = suite(suiteId);
        GoldenApp app = golden(suite.getGoldenAppId());
        String runtimePath = runtimePath(suiteId);
        boolean available = false;
        try {
            BehaviorArtifact golden = artifacts.active(suiteId, BehaviorArtifactType.GOLDEN_SOLUTION);
            available = Files.isRegularFile(runtimeRoot().resolve(safeSegment(suiteId))
                    .resolve(runtimeVersion(golden)).resolve("index.html"));
        } catch (Exception ignored) {
        }
        return view(app, runtimePath, available, available,
                available ? "Golden runtime da san sang." : "Chua build runtime.");
    }

    /** Dọn toàn bộ bản build web của đúng suite đã xóa. */
    public void deleteSuiteRuntime(String suiteId) {
        Path root = runtimeRoot();
        Path target = root.resolve(safeSegment(suiteId)).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalStateException("Duong dan xoa Golden runtime khong an toan");
        }
        deleteQuietly(target);
    }

    public RuntimeFile resource(String suiteId, String assetPath) {
        BehaviorArtifact golden = artifacts.active(suiteId, BehaviorArtifactType.GOLDEN_SOLUTION);
        Path root = runtimeRoot().resolve(safeSegment(suiteId)).resolve(runtimeVersion(golden)).normalize();
        String clean = assetPath == null || assetPath.isBlank() ? "index.html" : assetPath.replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);
        Path target = root.resolve(clean).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("Duong dan runtime khong an toan");
        if (Files.isDirectory(target)) target = target.resolve("index.html");
        if (!Files.isRegularFile(target) && !clean.contains(".")) target = root.resolve("index.html");
        if (!Files.isRegularFile(target)) throw new IllegalArgumentException("Khong tim thay runtime asset: " + clean);
        return new RuntimeFile(new FileSystemResource(target), contentType(target), target.getFileName().toString().equals("index.html"));
    }

    private void prepareProject(Path source, Path target) throws Exception {
        Files.createDirectories(target);
        copyTree(source.resolve("lib"), target.resolve("lib"));
        normalizeInternalPackageImports(source, target.resolve("lib"));
        if (Files.isDirectory(source.resolve("assets"))) copyTree(source.resolve("assets"), target.resolve("assets"));
        Path basePubspec = resolveTemplateDir().resolve("pubspec.base.yaml");
        if (!Files.isRegularFile(basePubspec)) throw new IllegalStateException("Khong tim thay pubspec.base.yaml");
        String pubspec = Files.readString(basePubspec, StandardCharsets.UTF_8);
        List<String> assetDirectories = new ArrayList<>();
        if (Files.isDirectory(target.resolve("assets"))) assetDirectories.add("assets/");
        if (Files.isDirectory(target.resolve("lib/assets"))) assetDirectories.add("lib/assets/");
        if (!assetDirectories.isEmpty()) {
            StringBuilder assetsYaml = new StringBuilder("\n  assets:\n");
            assetDirectories.forEach(path -> assetsYaml.append("    - ").append(path).append('\n'));
            pubspec = pubspec.stripTrailing() + assetsYaml;
        }
        Files.writeString(target.resolve("pubspec.yaml"), pubspec, StandardCharsets.UTF_8);
    }

    /**
     * The runtime deliberately uses the same fixed package name as the grading image. Golden
     * projects may have any pubspec name, so internal package imports must be rewritten exactly
     * as they are in GoldenValidationService before the web build starts.
     */
    private void normalizeInternalPackageImports(Path sourceProject, Path copiedLib) throws Exception {
        Path sourcePubspec = sourceProject.resolve("pubspec.yaml");
        if (!Files.isRegularFile(sourcePubspec)) return;
        String packageName = null;
        for (String line : Files.readAllLines(sourcePubspec, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("name:")) {
                packageName = trimmed.substring("name:".length()).trim();
                break;
            }
        }
        if (packageName == null || packageName.isBlank() || "exam_project".equals(packageName)) return;
        try (Stream<Path> files = Files.walk(copiedLib)) {
            for (Path file : files.filter(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".dart")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String normalized = source.replace("package:" + packageName + "/", "package:exam_project/");
                if (!source.equals(normalized)) {
                    Files.writeString(file, normalized, StandardCharsets.UTF_8);
                }
            }
        }
    }

    private String buildScript(String suiteId) {
        String baseHref = runtimePath(suiteId);
        // Docker invokes /bin/sh; the grading image uses dash, which does not support
        // `set -o pipefail`. Keep this script POSIX so runtime builds do not fail before Flutter starts.
        return "set -eu; cd /runtime; "
                + "flutter create --platforms=web --project-name=exam_project --no-pub . >/tmp/flutter-create.log; "
                + "rm -rf .dart_tool; cp -a /app/.dart_tool ./.dart_tool; "
                + "if [ -f /app/.flutter-plugins-dependencies ]; then cp /app/.flutter-plugins-dependencies .; fi; "
                + "flutter build web --release --no-pub --base-href '" + baseHref + "'";
    }

    private void injectRecorderBridge(Path index) throws Exception {
        String html = Files.readString(index, StandardCharsets.UTF_8);
        String bridge = """
                <script id="grader-golden-recorder">
                (() => {
                  const TYPE = 'GOLDEN_RECORDER_EVENT';
                  const COMMAND = 'GOLDEN_RECORDER_COMMAND';
                  const timers = new WeakMap();
                  const scrollOffsets = new WeakMap();
                  const send = payload => window.parent.postMessage({type: TYPE, payload}, '*');
                  const textOf = el => ((el && (el.innerText || el.textContent)) || '').replace(/\\s+/g, ' ').trim();
                  const attr = (el, ...names) => {
                    for (const name of names) {
                      const value = el.getAttribute(name);
                      if (value != null && value.trim()) return value.trim();
                    }
                    return '';
                  };
                  const boolAttr = (el, name, fallback) => {
                    const value = el.getAttribute(name);
                    return value == null ? fallback : value !== 'false';
                  };
                  function roleOf(el) {
                    const declared = attr(el, 'role').toLowerCase();
                    const type = attr(el, 'type').toLowerCase();
                    if (type === 'checkbox' || declared === 'checkbox') return 'checkbox';
                    if (type === 'radio' || declared === 'radio') return 'radio';
                    if (declared === 'switch') return 'switch';
                    if (declared === 'textbox' || el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) return 'text_field';
                    if (declared === 'button' || el instanceof HTMLButtonElement) return 'button';
                    if (declared === 'img') return 'image';
                    if (declared === 'link') return 'link';
                    return 'text';
                  }
                  function targetOf(el) {
                    const semanticId = attr(el, 'data-semantic-id', 'data-semantics-id');
                    if (semanticId) return {semanticId};
                    const rawLabel = attr(el, 'aria-label', 'data-semantics-label');
                    if (rawLabel && rawLabel !== 'Enable accessibility') {
                      // Flutter Web gop labelText + hintText vao chung aria-label,
                      // ngan cach boi \\n. Tach ra de khop dung decoration.labelText/hintText
                      // ma _finder() ben phia replay (exam_test.dart) so rieng biet.
                      const parts = rawLabel.split('\\n');
                      return parts.length > 1 ? {label: parts[0], hint: parts.slice(1).join('\\n')} : {label: rawLabel};
                    }
                    const hint = attr(el, 'placeholder');
                    if (hint) return {hint};
                    const text = textOf(el);
                    return text && text.length <= 120 ? {text} : {};
                  }
                  function semanticState(el) {
                    const target = targetOf(el);
                    if (!Object.keys(target).length) return null;
                    const role = roleOf(el);
                    const rect = el.getBoundingClientRect();
                    const style = getComputedStyle(el);
                    const state = {target, role, visible: rect.width > 0 && rect.height > 0 && style.display !== 'none' && style.visibility !== 'hidden'};
                    const value = el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement
                      ? el.value : attr(el, 'aria-valuetext', 'aria-value-now');
                    if (value !== '') state.value = value;
                    if (['text_field', 'button', 'checkbox', 'switch', 'radio'].includes(role)) {
                      state.enabled = !('disabled' in el && el.disabled) && !boolAttr(el, 'aria-disabled', false);
                    }
                    if (['checkbox', 'switch', 'radio'].includes(role)) {
                      state.checked = el instanceof HTMLInputElement ? el.checked : boolAttr(el, 'aria-checked', false);
                    }
                    return state;
                  }
                  function semanticNode(event) {
                    const path = event.composedPath ? event.composedPath() : [];
                    for (const node of path) {
                      if (!(node instanceof Element)) continue;
                      const target = targetOf(node);
                      const key = Object.keys(target)[0];
                      if (key) return {target, attribute: key, attributeValue: target[key]};
                    }
                    return null;
                  }
                  function action(event, name, value = '') {
                    const found = semanticNode(event);
                    if (!found) {
                      window.parent.postMessage({type: 'GOLDEN_RECORDER_WARNING', payload: {message: 'Khong suy ra duoc semantic locator cho thao tac nay.'}}, '*');
                      return;
                    }
                    send({kind: 'action', stage: 'ACTION', action: name, ...found, valueType: 'string', value, browser: 'flutter_tester'});
                  }
                  document.addEventListener('click', event => {
                    const target = event.target;
                    if (target instanceof Element && target.getAttribute('aria-label') === 'Enable accessibility') return;
                    action(event, 'tap');
                  }, true);
                  document.addEventListener('input', event => {
                    const target = event.target;
                    if (!(target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement)) return;
                    const found = semanticNode(event);
                    if (!found) {
                      window.parent.postMessage({type: 'GOLDEN_RECORDER_WARNING', payload: {message: 'Khong suy ra duoc semantic locator cho o nhap nay.'}}, '*');
                      return;
                    }
                    const old = timers.get(target); if (old) clearTimeout(old);
                    timers.set(target, setTimeout(() => {
                      send({kind: 'action', stage: 'ACTION', action: 'enter_text', ...found, valueType: 'string', value: target.value, browser: 'flutter_tester'});
                    }, 250));
                  }, true);
                  document.addEventListener('scroll', event => {
                    const target = event.target instanceof Element ? event.target : document.scrollingElement;
                    if (!target) return;
                    const found = semanticNode(event) || {target: {}, attribute: 'none', attributeValue: ''};
                    const previous = scrollOffsets.get(target) || {x: target.scrollLeft || 0, y: target.scrollTop || 0};
                    const current = {x: target.scrollLeft || 0, y: target.scrollTop || 0};
                    scrollOffsets.set(target, current);
                    const old = timers.get(target); if (old) clearTimeout(old);
                    timers.set(target, setTimeout(() => {
                      send({kind: 'action', stage: 'ACTION', action: 'scroll', ...found, delta: {x: previous.x - current.x, y: previous.y - current.y}, valueType: 'json', value: '', browser: 'flutter_tester'});
                    }, 250));
                  }, true);
                  function snapshot() {
                    const nodes = [];
                    const seen = new Set();
                    document.querySelectorAll('[aria-label], [data-semantics-label], [data-semantic-id], [data-semantics-id], input, textarea, button, [role]').forEach(node => {
                      if (!(node instanceof Element)) return;
                      const state = semanticState(node);
                      if (!state || state.target.label === 'Enable accessibility') return;
                      const signature = JSON.stringify([state.target, state.role, state.value, state.enabled, state.checked]);
                      if (seen.has(signature)) return;
                      seen.add(signature);
                      nodes.push(state);
                    });
                    send({kind: 'checkpoint', checkpoint: true, scope: 'ui', stage: 'ASSERT', attribute: 'semantic_nodes', attributeValue: '', valueType: 'json', value: nodes, action: 'observe_ui', browser: 'flutter_tester', expect: {semantic_nodes: nodes, no_exception: true}});
                  }
                  window.addEventListener('message', event => {
                    if (event.data && event.data.type === COMMAND && event.data.action === 'snapshot_ui') snapshot();
                  });
                  const enable = () => {
                    const placeholder = document.querySelector('flt-semantics-placeholder[aria-label="Enable accessibility"]');
                    if (placeholder) placeholder.click();
                  };
                  window.addEventListener('flutter-first-frame', () => setTimeout(enable, 100));
                  setTimeout(enable, 1000);
                  window.parent.postMessage({type: 'GOLDEN_RECORDER_READY'}, '*');
                })();
                </script>
                """;
        if (html.contains("</body>")) html = html.replace("</body>", bridge + "\n</body>");
        else html += bridge;
        Files.writeString(index, html, StandardCharsets.UTF_8);
    }

    private void unzipSecure(Path zipPath, Path destination) throws Exception {
        SecureZipExtractor.extract(zipPath, destination, MAX_ZIP_ENTRIES, MAX_EXPANDED_BYTES);
    }

    private Path locateFlutterProject(Path extracted) throws Exception {
        try (Stream<Path> files = Files.walk(extracted, 8)) {
            Path main = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("main.dart"))
                    .filter(path -> path.getParent() != null && path.getParent().getFileName().toString().equals("lib"))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Golden ZIP khong co lib/main.dart"));
            return main.getParent().getParent();
        }
    }

    private void copyTree(Path source, Path destination) throws Exception {
        if (!Files.isDirectory(source)) throw new IllegalArgumentException("Golden Solution thieu " + source.getFileName());
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("Golden ZIP khong duoc chua symlink");
                Path target = destination.resolve(source.relativize(path)).normalize();
                if (!target.startsWith(destination)) throw new IllegalArgumentException("Duong dan Golden khong an toan");
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void moveDirectory(Path source, Path target) throws Exception {
        Path incoming = target.resolveSibling(target.getFileName() + ".incoming-" + UUID.randomUUID());
        if (Files.exists(incoming)) deleteQuietly(incoming);
        Files.move(source, incoming, StandardCopyOption.REPLACE_EXISTING);
        if (Files.exists(target)) deleteQuietly(target);
        Files.move(incoming, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void readOutput(Process process, StringBuilder output) {
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) output.append(line).append('\n');
        } catch (Exception ignored) {
        }
    }

    private String metadata(BehaviorArtifact artifact, String buildState, String buildLog) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "golden_sha256", artifact.getSha256(),
                    "build_state", buildState,
                    "build_log", buildLog,
                    "built_at", Instant.now().toString()));
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> view(GoldenApp app,
                                     String runtimePath,
                                     boolean cached,
                                     boolean available,
                                     String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("suite_id", suiteByGolden(app.getId()).map(BehaviorSuite::getId).orElse(null));
        result.put("golden_app_id", app.getId());
        result.put("status", app.getStatus().name());
        result.put("runtime_url", available ? app.getRuntimeUrl() : null);
        result.put("runtime_path", available ? runtimePath : null);
        result.put("available", available);
        result.put("cached", cached);
        result.put("message", message);
        try { result.put("metadata", mapper.readValue(app.getMetadataJson(), Map.class)); }
        catch (Exception ignored) { result.put("metadata", Map.of()); }
        return result;
    }

    private Optional<BehaviorSuite> suiteByGolden(String goldenAppId) {
        return suites.findAll().stream().filter(row -> Objects.equals(row.getGoldenAppId(), goldenAppId)).findFirst();
    }

    private BehaviorSuite suite(String id) {
        return suites.findById(id).orElseThrow(() -> new IllegalArgumentException("Khong tim thay behavior suite: " + id));
    }

    private GoldenApp golden(String id) {
        return goldenApps.findById(id).orElseThrow(() -> new IllegalArgumentException("Khong tim thay Golden App: " + id));
    }

    private Path runtimeRoot() {
        return Path.of(runtimeDir).toAbsolutePath().normalize();
    }

    private Path resolveTemplateDir() {
        Path configured = Path.of(templateDir);
        if (configured.isAbsolute() && Files.isDirectory(configured)) return configured.normalize();
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(cwd.resolve(configured))) return cwd.resolve(configured).normalize();
        if (cwd.getParent() != null && Files.isDirectory(cwd.getParent().resolve(configured))) {
            return cwd.getParent().resolve(configured).normalize();
        }
        return cwd.resolve(configured).normalize();
    }

    private String runtimePath(String suiteId) {
        return "/api/behavior-authoring/runtime/" + safeSegment(suiteId) + "/";
    }

    private String runtimeVersion(BehaviorArtifact golden) {
        return golden.getSha256() + "-" + RECORDER_BRIDGE_VERSION;
    }

    private String safeSegment(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("ID runtime khong hop le");
        return value;
    }

    private String toDockerPath(Path path) {
        String value = path.toAbsolutePath().normalize().toString();
        if (value.length() >= 2 && value.charAt(1) == ':') {
            return "/" + Character.toLowerCase(value.charAt(0)) + value.substring(2).replace('\\', '/');
        }
        return value.replace('\\', '/');
    }

    private String contentType(Path path) {
        try {
            String detected = Files.probeContentType(path);
            if (detected != null) return detected;
        } catch (Exception ignored) {
        }
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".wasm")) return "application/wasm";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".html")) return "text/html";
        return "application/octet-stream";
    }

    private boolean isDockerUnavailable(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return normalized.contains("cannot connect to the docker daemon")
                || normalized.contains("error during connect")
                || normalized.contains("docker engine is not running")
                || normalized.contains("open //./pipe/docker_engine")
                || normalized.contains("open \\.\\pipe\\docker_engine");
    }

    private String limitLog(String value) {
        if (value == null) return "";
        int max = 200_000;
        return value.length() <= max ? value : value.substring(value.length() - max);
    }

    private void removeContainer(String name) {
        try {
            Process process = new ProcessBuilder("docker", "rm", "-f", name).start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (Exception ignored) {
        }
    }

    private void deleteQuietly(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    public record RuntimeFile(Resource resource, String contentType, boolean index) {}
}
