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
import java.nio.file.StandardCopyOption;
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
    private static final String RECORDER_BRIDGE_VERSION = "semantic-v16";   // v16: chỉ nhận semantic control duy nhất, không leo nhầm lên Form/khung cha

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
            prepareProject(suiteId, sourceProject, project);

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

    private void prepareProject(String suiteId, Path source, Path target) throws Exception {
        Files.createDirectories(target);
        copyTree(source.resolve("lib"), target.resolve("lib"));
        normalizeInternalPackageImports(source, target.resolve("lib"));
        writeRecorderEntry(target.resolve("lib"));
        if (Files.isDirectory(source.resolve("assets"))) copyTree(source.resolve("assets"), target.resolve("assets"));
        // hidden.db vào assets của bản web: recorder entry nạp nó vào SQLite web TRƯỚC khi
        // app chạy — đúng cách engine chấm reset database rồi mới boot. Nhờ vậy người soạn
        // đề nhìn thấy CHÍNH dữ liệu chấm, không cần bản mô phỏng chép tay có thể lệch.
        artifacts.activeOptional(suiteId, BehaviorArtifactType.HIDDEN_DATABASE).ifPresent(hidden -> {
            try {
                Files.createDirectories(target.resolve("assets"));
                Files.copy(Path.of(hidden.getStoragePath()),
                        target.resolve("assets").resolve("grader_hidden.db"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
                throw new IllegalStateException("Không chép được hidden.db vào runtime: " + e.getMessage(), e);
            }
        });
        // SQLite thật cho web: gói sqflite_common_ffi_web KHÔNG kèm sqlite3.wasm (bình thường
        // phải tải qua mạng bằng lệnh setup) mà container build web thì offline. Hai file này
        // được vendor sẵn trong grader-base/web-recorder, dựng đúng bản ffi_web 1.1.1 của ảnh
        // chấm. flutter create giữ nguyên file có sẵn trong web/ nên đặt trước là đủ.
        Path webRecorder = resolveTemplateDir().resolve("web-recorder");
        if (Files.isDirectory(webRecorder)) {
            Files.createDirectories(target.resolve("web"));
            try (Stream<Path> files = Files.list(webRecorder)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Files.copy(file, target.resolve("web").resolve(file.getFileName().toString()),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
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
        // Build qua entry bọc ngoài do writeRecorderEntry sinh sẵn (xem ghi chú ở đó).
        return "set -eu; cd /runtime; "
                + "flutter create --platforms=web --project-name=exam_project --no-pub . >/tmp/flutter-create.log; "
                + "rm -rf .dart_tool; cp -a /app/.dart_tool ./.dart_tool; "
                + "if [ -f /app/.flutter-plugins-dependencies ]; then cp /app/.flutter-plugins-dependencies .; fi; "
                + "flutter build web --release --no-pub -t " + RECORDER_ENTRY + " --base-href '" + baseHref + "'";
    }

    /** Tên entry bọc ngoài, tính từ gốc dự án runtime. */
    private static final String RECORDER_ENTRY = "lib/_recorder_entry.dart";

    /**
     * Flutter Web KHÔNG dựng cây ngữ nghĩa cho tới khi trợ năng được bật: {@code flt-semantics-host}
     * rỗng, không widget nào có {@code aria-label}, nên recorder không có gì để bám và buộc phải
     * đoán theo chữ — sinh ra đích rác kiểu "Danh mục Định dạng yyyy-MM-dd Lưu" rồi chết lúc replay.
     *
     * <p>Vì vậy Golden runtime được build qua một entry bọc ngoài gọi {@code ensureSemantics()}
     * trước khi chạy {@code main()} của Golden Solution. Sinh tệp bằng Java thay vì bằng shell
     * để không phụ thuộc cách dash/bash xử lý dấu nháy lồng nhau.
     */
    private void writeRecorderEntry(Path lib) throws Exception {
        Files.createDirectories(lib);
        Files.writeString(lib.resolve("_recorder_entry.dart"), """
                // Tệp do hệ thống sinh cho phiên ghi thao tác — không có trong bài nộp sinh viên.
                import 'package:flutter/foundation.dart' show debugPrint;
                import 'package:flutter/semantics.dart';
                import 'package:flutter/services.dart' show ByteData, rootBundle;
                import 'package:flutter/widgets.dart';
                import 'package:sqflite_common/sqflite.dart' as sqflite_common;
                import 'package:sqflite_common_ffi_web/sqflite_ffi_web.dart' as sqflite_web;

                import 'main.dart' as golden_app;

                Future<void> main() async {
                  WidgetsFlutterBinding.ensureInitialized();
                  // Dấu phiên bản để phân định bản build đang CHẠY với bản bị cache.
                  debugPrint('recorder-entry semantic-v16');
                  // Giữ handle sống suốt phiên để cây ngữ nghĩa luôn được dựng.
                  SemanticsBinding.instance.ensureSemantics();
                  // SQLite THẬT trên web + nạp hidden.db TRƯỚC khi app khởi động — đúng cách
                  // engine chấm reset database rồi mới boot. Nhờ vậy Golden dùng sqflite thuần
                  // (cả ba gói sqflite dùng chung một biến toàn cục databaseFactory), không cần
                  // file web riêng, và màn hình soạn đề là chính dữ liệu chấm.
                  try {
                    // Bản KHÔNG worker: SQLite wasm chạy ngay luồng chính. Bản shared
                    // worker trả null cho getDatabasesPath (mọi lời gọi database chết theo
                    // vì fixPath cần nó); phiên soạn đề một tab nên không cần worker.
                    sqflite_common.databaseFactory = sqflite_web.databaseFactoryFfiWebNoWebWorker;
                    final ByteData bytes = await rootBundle.load('assets/grader_hidden.db');
                    await sqflite_common.databaseFactory.writeDatabaseBytes(
                      'app.db',
                      bytes.buffer.asUint8List(bytes.offsetInBytes, bytes.lengthInBytes),
                    );
                  } catch (e) {
                    // Thiếu hidden.db (chưa upload) hoặc Golden kiểu cũ tự quản dữ liệu —
                    // app vẫn phải lên để ghi thao tác, recorder không được chết theo.
                    debugPrint('Recorder: khong nap duoc hidden.db: $e');
                  }
                  golden_app.main();
                }
                """, StandardCharsets.UTF_8);
    }

    private void injectRecorderBridge(Path index) throws Exception {
        String html = Files.readString(index, StandardCharsets.UTF_8);
        String bridge = """
                <script id="grader-golden-recorder">
                (() => {
                  const TYPE = 'GOLDEN_RECORDER_EVENT';
                  const COMMAND = 'GOLDEN_RECORDER_COMMAND';
                  const FLUSHED = 'GOLDEN_RECORDER_FLUSHED';
                  const timers = new WeakMap();
                  const scrollOffsets = new WeakMap();
                  const send = payload => window.parent.postMessage({type: TYPE, payload}, '*');
                  const textOf = el => ((el && (el.innerText || el.textContent)) || '').replace(/\\s+/g, ' ').trim();
                  // Dem so phan tu la CO CHU RIENG ben trong node. Mot nut that chi co 0 hoac 1.
                  // Neu tu 2 tro len thi node la KHUNG CHUA nhieu nhan khac nhau, khong phai nut.
                  const textLeafCount = el => {
                    let n = 0;
                    const kids = el.querySelectorAll ? el.querySelectorAll('*') : [];
                    for (const kid of kids) {
                      if (kid.children.length === 0 && (kid.textContent || '').trim().length > 0) n++;
                      if (n > 1) break;
                    }
                    return n;
                  };
                  const MAX_TEXT_LOCATOR = 80;
                  let lastReject = '';
                  const INTERACTIVE_ROLES = new Set([
                    'textbox', 'searchbox', 'button', 'checkbox', 'radio', 'switch',
                    'link', 'combobox', 'listbox', 'option', 'slider', 'spinbutton',
                    'menuitem', 'tab'
                  ]);
                  const INPUT_ROLES = new Set(['textbox', 'searchbox', 'combobox', 'spinbutton']);
                  const CONTAINER_ROLES = new Set([
                    'form', 'group', 'generic', 'region', 'main', 'list', 'listitem',
                    'presentation', 'none'
                  ]);
                  const SEMANTIC_ID_ATTRIBUTES = [
                    'flt-semantics-identifier', 'data-semantics-identifier', 'data-semantic-id'
                  ];
                  const semanticRoot = () => document.querySelector('flutter-view') || document.body;
                  function roleOf(node) {
                    const declared = (node.getAttribute('role') || '').trim().toLowerCase();
                    if (declared) return declared;
                    const tag = node.tagName;
                    if (tag === 'TEXTAREA') return 'textbox';
                    if (tag === 'SELECT') return 'combobox';
                    if (tag === 'BUTTON') return 'button';
                    if (tag === 'A' && node.hasAttribute('href')) return 'link';
                    if (tag !== 'INPUT') return '';
                    const type = (node.getAttribute('type') || 'text').toLowerCase();
                    if (type === 'checkbox') return 'checkbox';
                    if (type === 'radio') return 'radio';
                    if (type === 'button' || type === 'submit' || type === 'reset') return 'button';
                    if (type === 'range') return 'slider';
                    if (type === 'number') return 'spinbutton';
                    if (type === 'search') return 'searchbox';
                    return 'textbox';
                  }
                  function checkpointRoleOf(node) {
                    const role = roleOf(node);
                    if (INPUT_ROLES.has(role)) return 'text_field';
                    if (role === 'button' || role === 'checkbox' || role === 'switch'
                        || role === 'radio' || role === 'link') return role;
                    if (role === 'img' || node.tagName === 'IMG') return 'image';
                    if (!role && textOf(node)) return 'text';
                    return 'generic';
                  }
                  function semanticIdOf(node) {
                    for (const name of SEMANTIC_ID_ATTRIBUTES) {
                      const value = (node.getAttribute(name) || '').trim();
                      if (value) return value;
                    }
                    return '';
                  }
                  function allSemanticElements() {
                    const root = semanticRoot();
                    return [root, ...(root.querySelectorAll ? root.querySelectorAll('*') : [])]
                      .filter(node => node instanceof Element);
                  }
                  // DOM Flutter co the co wrapper va node con cung mot nhan. Chi dem node
                  // sau nhat trong moi nhanh de mot control logic khong bi tinh thanh hai.
                  function leafMost(nodes) {
                    return nodes.filter(node => !nodes.some(other => other !== node && node.contains(other)));
                  }
                  function uniqueLocator(kind, value, elements = allSemanticElements()) {
                    if (!value) return false;
                    const matches = [];
                    for (const node of elements) {
                      if (kind === 'semanticId' && semanticIdOf(node) === value) matches.push(node);
                      if (kind === 'label') {
                        const label = node.getAttribute('aria-label') || node.getAttribute('data-semantics-label') || '';
                        if (label === value) matches.push(node);
                      }
                      if (kind === 'hint' && (node.getAttribute('placeholder') || '') === value) matches.push(node);
                      if (kind === 'text' && textOf(node) === value) matches.push(node);
                    }
                    return leafMost(matches).length === 1;
                  }
                  // Flutter Web gop labelText + hintText cua TextFormField vao chung 1
                  // aria-label, ngan cach boi mot ky tu xuong dong. Tach ra de khop
                  // dung decoration.labelText / hintText ma _finder() ben phia replay
                  // (exam_test.dart) so rieng biet.
                  function splitLabelHint(raw) {
                    const parts = raw.split('\\n');
                    return parts.length > 1 ? {label: parts[0], hint: parts.slice(1).join('\\n')} : {label: raw};
                  }
                  function locatorFor(node, mode, elements) {
                    const role = roleOf(node);
                    const semanticId = semanticIdOf(node);
                    if (semanticId) {
                      if (uniqueLocator('semanticId', semanticId, elements)) {
                        return {target: {semanticId}, attribute: 'semanticId', attributeValue: semanticId};
                      }
                      lastReject = 'Semantic identifier "' + semanticId + '" bi trung.';
                    }
                    // Scroll container khong co role tuong tac on dinh. Neu khong co identifier
                    // rieng thi de runner cuon target mac dinh, khong suy doan bang nhan cua cha.
                    if (mode === 'scroll') return null;
                    const rawLabel = node.getAttribute('aria-label') || node.getAttribute('data-semantics-label');
                    if (rawLabel && rawLabel !== 'Enable accessibility') {
                      if (uniqueLocator('label', rawLabel, elements)) {
                        const laONhap = INPUT_ROLES.has(role);
                        return {target: laONhap ? splitLabelHint(rawLabel) : {label: rawLabel},
                                attribute: 'label', attributeValue: rawLabel};
                      }
                      lastReject = 'Nhan semantic "' + rawLabel + '" bi trung.';
                    }
                    const hint = node.getAttribute('placeholder');
                    if (hint) {
                      if (uniqueLocator('hint', hint, elements)) {
                        return {target: {hint}, attribute: 'hint', attributeValue: hint};
                      }
                      lastReject = 'Hint "' + hint + '" bi trung.';
                    }
                    // Text chi la duong du phong cho tap vao mot node la that. Khong bao gio
                    // lay text tong hop cua Form/card/container lam dich thao tac.
                    if (mode === 'tap') {
                      const text = textOf(node);
                      if (text && text.length <= MAX_TEXT_LOCATOR && textLeafCount(node) <= 1) {
                        if (uniqueLocator('text', text, elements)) {
                          return {target: {text}, attribute: 'text', attributeValue: text};
                        }
                        lastReject = 'Noi dung "' + text + '" bi trung.';
                      }
                    }
                    return null;
                  }
                  // ĐÃ GỠ khối roleOf/targetOf/boolAttr + bản semanticState(el) đi kèm: merge
                  // 67fb085 kéo về hai bản semanticState trong cùng một scope (hai nhánh làm
                  // song song cùng một việc). JS lấy bản khai sau, tức bản semanticState(node)
                  // bên dưới — nên khối trên là code chết, và ai đảo thứ tự là inventory() vỡ
                  // câm bằng ReferenceError. Giữ lại splitLabelHint vì semanticNode() đang dùng.
                  function semanticNode(event, mode = 'tap') {
                    lastReject = '';
                    const path = (event.composedPath ? event.composedPath() : [])
                      .filter(node => node instanceof Element);
                    const elements = allSemanticElements();
                    // Luot 1: chi xet control tuong tac. Form/group/generic khong bao gio la
                    // dich cua tap/enter_text, du chung co aria-label tong hop.
                    for (const node of path) {
                      const role = roleOf(node);
                      const isInput = node instanceof HTMLInputElement || node instanceof HTMLTextAreaElement
                        || INPUT_ROLES.has(role);
                      if (mode === 'input' && !isInput) continue;
                      if (mode === 'tap' && !INTERACTIVE_ROLES.has(role)) continue;
                      const found = locatorFor(node, mode, elements);
                      if (found) return found;
                    }
                    if (!lastReject) lastReject = mode === 'input'
                      ? 'O nhap khong co semantic locator rieng.'
                      : 'Khong tim thay semantic control tuong tac duy nhat tai vi tri bam.';
                    return null;
                  }
                  function warnNoTarget() {
                    const message = 'Khong ghi thao tac de tranh chon nham element cha. '
                      + (lastReject || 'Khong suy ra duoc semantic locator duy nhat.')
                      + ' Hay gan Semantics(identifier: ...) duy nhat cho control neu cac nhan bi trung.';
                    window.parent.postMessage({type: 'GOLDEN_RECORDER_WARNING', payload: {message}}, '*');
                  }
                  function action(event, name, value = '') {
                    const found = semanticNode(event, 'tap');
                    if (!found) {
                      warnNoTarget();
                      return;
                    }
                    // Thao tác logic khác là ranh giới chắc chắn của phiên nhập. Chốt
                    // giá trị đầy đủ TRƯỚC tap để replay giữ đúng thứ tự. Bấm lại chính
                    // ô đang nhập thì chưa phải đổi thao tác nên chưa cần chốt.
                    const el = event.target;
                    const laONhap = el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement;
                    if (goDangCho && !(laONhap && cungLocator(goDangCho.found, found))) chotEnterText();
                    send({kind: 'action', stage: 'ACTION', action: name, ...found, valueType: 'string', value, browser: 'flutter_tester'});
                  }
                  document.addEventListener('click', event => {
                    const target = event.target;
                    if (target instanceof Element && target.getAttribute('aria-label') === 'Enable accessibility') return;
                    action(event, 'tap');
                  }, true);
                  // Một phiên nhập LOGIC đang chờ ghi. Không dùng debounce theo thời gian:
                  // người dùng có thể dừng suy nghĩ bao lâu tùy ý rồi gõ tiếp. Flutter Web
                  // còn thay DOM input giữa các ký tự, nên locator của lần focus/nhập đầu
                  // phải được giữ độc lập với vòng đời phần tử DOM.
                  let goDangCho = null; // {found, el, giaTri}
                  let oDangFocus = null;
                  let dangGhepBoGo = false;
                  const cungLocator = (a, b) => Boolean(a && b
                    && a.attribute === b.attribute && a.attributeValue === b.attributeValue);
                  // Nguon gia tri BEN nhat: node semantics (aria-label) — Flutter dong bo
                  // FULL noi dung o vao day moi khung hinh va KHONG trao node nay khi go
                  // (chi trao phan tu editing). Doc tu day thi ky tu cuoi cung khong mat.
                  function docTuSemantics(found) {
                    if (!found || found.attribute !== 'label' && found.attribute !== 'hint'
                        && found.attribute !== 'semanticId') return null;
                    const root = document.querySelector('flutter-view') || document.body;
                    for (const el of root.querySelectorAll('input, textarea')) {
                      if (found.attribute === 'semanticId') {
                        let owner = el;
                        while (owner && owner !== root && !semanticIdOf(owner)) owner = owner.parentElement;
                        if (owner && semanticIdOf(owner) === found.attributeValue
                            && typeof el.value === 'string') return el.value;
                        continue;
                      }
                      const nhan = el.getAttribute('aria-label') || el.getAttribute('placeholder') || '';
                      if (nhan === found.attributeValue || nhan.split(String.fromCharCode(10))[0] === found.attributeValue) {
                        // Chuỗi rỗng cũng là giá trị hợp lệ sau thao tác xóa hết.
                        if (typeof el.value === 'string') return el.value;
                      }
                    }
                    return null;
                  }
                  function chotEnterText() {
                    if (!goDangCho) return;
                    const {found, el, giaTri} = goDangCho;
                    goDangCho = null;
                    // Nguồn ưu tiên: input còn sống -> node semantics hiện tại -> snapshot
                    // đầy đủ cuối cùng từ event input. Không ghép delta ký tự nên paste,
                    // cut, undo/redo và bộ gõ tiếng Việt đều giữ đúng giá trị cuối.
                    let value = (el && el.isConnected && typeof el.value === 'string') ? el.value : null;
                    if (value === null) value = docTuSemantics(found);
                    if (value === null) value = giaTri;
                    send({kind: 'action', stage: 'ACTION', action: 'enter_text', ...found, valueType: 'string', value, browser: 'flutter_tester'});
                  }
                  function capNhatONhap(event, canhBao = true) {
                    const target = event.target;
                    if (!(target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement)) return;
                    const found = semanticNode(event, 'input') || oDangFocus || (goDangCho && goDangCho.found);
                    if (!found) {
                      if (canhBao) warnNoTarget();
                      return;
                    }
                    if (found.attribute !== 'label' && found.attribute !== 'hint' && found.attribute !== 'semanticId') {
                      if (canhBao) window.parent.postMessage({type: 'GOLDEN_RECORDER_WARNING', payload: {message:
                        'O nhap nay khong co nhan ngu nghia rieng; recorder chi doc duoc chu tinh "'
                        + String(found.attributeValue).slice(0, 40)
                        + '". Hay them Semantics(label: ...) hoac hintText cho o nhap trong Golden Solution.'}}, '*');
                      return;
                    }
                    if (goDangCho && !cungLocator(goDangCho.found, found)) chotEnterText();
                    oDangFocus = found;
                    goDangCho = {found, el: target, giaTri: target.value};
                  }
                  document.addEventListener('focusin', event => {
                    const target = event.target;
                    if (!(target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement)) return;
                    const found = semanticNode(event, 'input');
                    if (!found) return;
                    if (goDangCho && !cungLocator(goDangCho.found, found)) chotEnterText();
                    oDangFocus = found;
                  }, true);
                  document.addEventListener('focusout', event => {
                    const el = event.target;
                    if (!(el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement)) return;
                    if (!goDangCho) return;
                    // Không chốt theo blur DOM: Flutter tự thay input trong lúc gõ và phát
                    // blur giả. Chỉ giữ snapshot; focusin/action/flush mới là ranh giới logic.
                    goDangCho.el = el; goDangCho.giaTri = el.value;
                  }, true);
                  document.addEventListener('compositionstart', () => { dangGhepBoGo = true; }, true);
                  document.addEventListener('compositionend', event => {
                    dangGhepBoGo = false;
                    capNhatONhap(event, false);
                  }, true);
                  document.addEventListener('input', event => capNhatONhap(event), true);
                  document.addEventListener('keydown', event => {
                    const el = event.target;
                    if (!(el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) || dangGhepBoGo) return;
                    // Tab luôn chuyển control; Enter chỉ là ranh giới ở input một dòng,
                    // không cắt nội dung xuống dòng hợp lệ của textarea.
                    if (event.key === 'Tab' || (event.key === 'Enter' && el instanceof HTMLInputElement)) {
                      capNhatONhap(event, false);
                      chotEnterText();
                    }
                  }, true);
                  document.addEventListener('scroll', event => {
                    const target = event.target instanceof Element ? event.target : document.scrollingElement;
                    if (!target) return;
                    const found = semanticNode(event, 'scroll') || {target: {}, attribute: 'none', attributeValue: ''};
                    const previous = scrollOffsets.get(target) || {x: target.scrollLeft || 0, y: target.scrollTop || 0};
                    const current = {x: target.scrollLeft || 0, y: target.scrollTop || 0};
                    scrollOffsets.set(target, current);
                    const old = timers.get(target); if (old) clearTimeout(old);
                    timers.set(target, setTimeout(() => {
                      chotEnterText();
                      send({kind: 'action', stage: 'ACTION', action: 'scroll', ...found, delta: {x: previous.x - current.x, y: previous.y - current.y}, valueType: 'json', value: '', browser: 'flutter_tester'});
                    }, 250));
                  }, true);
                  // Phan loai MOT node thanh locator ma engine cham tim lai duoc, DUNG thu tu
                  // uu tien cua semanticNode (semanticId -> label -> hint -> text). Dong bo hai ham nay la
                  // bat buoc: item duoc tick phai tro vao dung widget ma luc replay tim thay.
                  function semanticState(node, elements) {
                    const semanticId = semanticIdOf(node);
                    if (semanticId && uniqueLocator('semanticId', semanticId, elements)) {
                      return {target: {semanticId}, attribute: 'semanticId', attributeValue: semanticId, role: checkpointRoleOf(node)};
                    }
                    const role = roleOf(node);
                    const label = node.getAttribute('aria-label') || node.getAttribute('data-semantics-label');
                    if (label && label !== 'Enable accessibility') {
                      // Chi identifier duoc phep dai dien cho container co chu dich. Nhan tong
                      // hop cua Form/group/generic khong duoc dua vao inventory de tick nham.
                      if (CONTAINER_ROLES.has(role) || textLeafCount(node) > 1 || !uniqueLocator('label', label, elements)) return null;
                      return {target: {label}, attribute: 'label', attributeValue: label, role: checkpointRoleOf(node)};
                    }
                    const hint = node.getAttribute('placeholder');
                    if (hint && uniqueLocator('hint', hint, elements)) {
                      return {target: {hint}, attribute: 'hint', attributeValue: hint, role: 'text_field'};
                    }
                    const text = textOf(node);
                    if (!text || textLeafCount(node) > 1 || text.length > MAX_TEXT_LOCATOR
                        || !uniqueLocator('text', text, elements)) return null;
                    return {target: {text}, attribute: 'text', attributeValue: text, role: checkpointRoleOf(node)};
                  }
                  // LIET KE thanh phan man hinh hien tai cho bang tick ben trang soan de.
                  // Truoc day lenh nay goi ham semanticState CHUA TON TAI — ReferenceError,
                  // chet im lang, giao vien bam nut ma khong thay gi.
                  function inventory() {
                    const items = [];
                    const seen = new Set();
                    // CHI quet ben trong flutter-view. Quet ca document se vo nham DOM cua
                    // extension trinh duyet (tu dien, dich thuat...) — nhung thanh phan do
                    // khong ton tai trong app, tick vao la capture oracle chet vi tim khong thay.
                    const elements = allSemanticElements();
                    // 'flt-semantics' bat buoc phai co: node CHU TRAN (tieu de man hinh,
                    // dong "Tong thang: ...") khong mang aria-label/role nao — thieu selector
                    // nay thi quet UI bo sot toan bo text tren man hinh.
                    elements.forEach(node => {
                      if (!node.matches('flt-semantics, [flt-semantics-identifier], [data-semantics-identifier], [data-semantic-id], [aria-label], [data-semantics-label], input, textarea, [role]')) return;
                      const state = semanticState(node, elements);
                      if (!state) return;
                      const key = state.attribute + '=' + state.attributeValue;
                      if (seen.has(key)) return;
                      seen.add(key);
                      items.push(state);
                    });
                    window.parent.postMessage({type: 'GOLDEN_RECORDER_INVENTORY', payload: {items}}, '*');
                  }
                  window.addEventListener('message', event => {
                    if (!event.data || event.data.type !== COMMAND) return;
                    if (event.data.action === 'snapshot_ui') {
                      chotEnterText();
                      inventory();
                    }
                    if (event.data.action === 'flush_input') {
                      const requestId = event.data.request_id || '';
                      let done = false;
                      const finish = () => {
                        if (done) return;
                        done = true;
                        chotEnterText();
                        window.parent.postMessage({type: FLUSHED, payload: {request_id: requestId}}, '*');
                      };
                      // Cho Flutter tối đa hai frame để đồng bộ value semantics. Tab nền
                      // có thể không chạy rAF nên vẫn có fallback 250ms.
                      const fallback = setTimeout(finish, 250);
                      requestAnimationFrame(() => requestAnimationFrame(() => {
                        clearTimeout(fallback);
                        finish();
                      }));
                    }
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
