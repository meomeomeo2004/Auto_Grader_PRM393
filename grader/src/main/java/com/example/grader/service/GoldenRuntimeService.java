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
    private static final String RECORDER_BRIDGE_VERSION = "semantic-v15";   // v15: ghi định danh flt-semantics-identifier cạnh nhãn · v4: quét thành phần CHỈ trong flutter-view (v3 vớ nhầm DOM của extension)

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
                  debugPrint('recorder-entry semantic-v15');
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
                  // Flutter Web gop labelText + hintText cua TextFormField vao chung 1
                  // aria-label, ngan cach boi mot ky tu xuong dong. Tach ra de khop
                  // dung decoration.labelText / hintText ma _finder() ben phia replay
                  // (exam_test.dart) so rieng biet.
                  function splitLabelHint(raw) {
                    const parts = raw.split('\\n');
                    return parts.length > 1 ? {label: parts[0], hint: parts.slice(1).join('\\n')} : {label: raw};
                  }
                  // ĐÃ GỠ khối roleOf/targetOf/boolAttr + bản semanticState(el) đi kèm: merge
                  // 67fb085 kéo về hai bản semanticState trong cùng một scope (hai nhánh làm
                  // song song cùng một việc). JS lấy bản khai sau, tức bản semanticState(node)
                  // bên dưới — nên khối trên là code chết, và ai đảo thứ tự là inventory() vỡ
                  // câm bằng ReferenceError. Giữ lại splitLabelHint vì semanticNode() đang dùng.
                  // DINH DANH (Semantics identifier) cua chinh node, hoac cua to tien "wrapper tran"
                  // gan nhat: cha CHUA CO nhan, hoac mang DUNG nhan cua node (Semantics(label) boc
                  // ngoai chip lap lai nhan cua chip). Gap cha co nhan KHAC la thuc the khac (nut
                  // Xoa nam trong dong danh sach) -> dung, khong nhan nham dinh danh cua dong.
                  // Do GD0 4/9/2026 tren ban web dung bang chinh script nay: dong ListTile, nut
                  // Xoa, FAB mang id ngay tren phan tu co aria-label; o nhap va nut "Luu" mang id
                  // o phan tu CHA cua INPUT / cua nut. Cung luat voi _docDinhDanhTaiDich ben engine
                  // de dinh danh ghi luc soan va dinh danh engine tim luc cham la MOT.
                  function identifierOf(node, nhanCuaNode) {
                    let cur = node;
                    for (let buoc = 0; cur instanceof Element && buoc < 5; buoc++) {
                      const id = cur.getAttribute('flt-semantics-identifier');
                      if (id) return id;
                      const cha = cur.parentElement;
                      if (!cha) break;
                      const nhanCha = cha.getAttribute('aria-label') || '';
                      if (nhanCha && nhanCha !== nhanCuaNode) break;
                      cur = cha;
                    }
                    return '';
                  }
                  // Target ghi CA HAI: dinh danh (neu co) dung dau, nhan/chu cu giu nguyen lam
                  // duong lui. attribute/attributeValue KHONG doi de moi cho khac (docTuSemantics,
                  // bang tick, chong doi enter_text) van hoat dong y het ban v14.
                  function ketQua(node, target, attribute, attributeValue) {
                    const id = identifierOf(node, attribute === 'label' ? attributeValue : '');
                    return {target: id ? {semantic_id: id, ...target} : target, attribute, attributeValue};
                  }
                  function semanticNode(event) {
                    lastReject = '';
                    const path = event.composedPath ? event.composedPath() : [];
                    for (const node of path) {
                      if (!(node instanceof Element)) continue;
                      const rawLabel = node.getAttribute('aria-label') || node.getAttribute('data-semantics-label');
                      if (rawLabel && rawLabel !== 'Enable accessibility') {
                        // Chi tach label/hint cho O NHAP that: Flutter chi gop labelText+hintText
                        // o input. Dong danh sach cung co nhan hai dong (title+subtitle) nhung
                        // tach ra la sai — replay so nhan TUYET DOI se khong bao gio khop.
                        const laONhap = node.tagName === 'INPUT' || node.tagName === 'TEXTAREA'
                          || node.getAttribute('role') === 'textbox';
                        return ketQua(node, laONhap ? splitLabelHint(rawLabel) : {label: rawLabel},
                                'label', rawLabel);
                      }
                      const hint = node.getAttribute('placeholder');
                      if (hint) return ketQua(node, {hint}, 'hint', hint);
                      const text = textOf(node);
                      if (!text) continue;
                      // Chi nhan chu cua node khi no la mot nhan/nut THAT SU.
                      // Truoc day chi kiem do dai <= 120 nen chu cua ca khung bi dinh lien
                      // ("Danh muc Dinh dang yyyy-MM-dd Luu") van duoc ghi lai, roi khong bao gio
                      // tim thay luc replay vi khong widget nao mang noi dung do.
                      if (textLeafCount(node) > 1) {
                        lastReject = text;
                        return null;
                      }
                      if (text.length <= MAX_TEXT_LOCATOR) return ketQua(node, {text}, 'text', text);
                      lastReject = text;
                      return null;
                    }
                    return null;
                  }
                  function warnNoTarget() {
                    const message = lastReject
                      ? 'Cham truot ra vung trong. Recorder chi doc duoc chu cua ca khung: "'
                        + (lastReject.length > 60 ? lastReject.slice(0, 60) + '...' : lastReject)
                        + '". Hay bam DUNG vao nut hoac o nhap, dung bam vao le hay nen.'
                      : 'Khong suy ra duoc semantic locator cho thao tac nay. Hay bam dung vao nut hoac o nhap.';
                    window.parent.postMessage({type: 'GOLDEN_RECORDER_WARNING', payload: {message}}, '*');
                  }
                  function action(event, name, value = '') {
                    const found = semanticNode(event);
                    if (!found) {
                      warnNoTarget();
                      return;
                    }
                    send({kind: 'action', stage: 'ACTION', action: name, ...found, valueType: 'string', value, browser: 'flutter_tester'});
                  }
                  document.addEventListener('click', event => {
                    const target = event.target;
                    if (target instanceof Element && target.getAttribute('aria-label') === 'Enable accessibility') return;
                    action(event, 'tap');
                  }, true);
                  // Chong doi theo LOCATOR chu khong theo phan tu DOM: Flutter Web tao lai the
                  // input sau moi lan go, nen khoa theo phan tu thi moi ky tu thanh mot su kien
                  // rieng (da gap: 8 su kien cho mot o). 700ms de go het roi moi ghi mot lan.
                  const textTimers = new Map();
                  // MOT phien go dang cho ghi. Flutter web thay the phan tu input sau moi
                  // ky tu va phan tu moi thuong MAT dinh danh (khong aria-label) — nen cac
                  // su kien go sau ky tu dau khong nhan dien duoc muc tieu, va moi cach doc
                  // gia tri theo timer deu ra chuoi cut ("abyu", roi te hon: mot ky tu "a").
                  // Chot dung: GHI KHI O MAT FOCUS (blur) — thoi diem duy nhat phan tu con
                  // song va mang DU chu; timer 700ms chi la duong lui khi nguoi go dung tay
                  // lau ma chua roi o (doc tu document.activeElement dang giu chu day du).
                  let goDangCho = null; // {found, el, giaTri}
                  const textValues = new Map();
                  // Nguon gia tri BEN nhat: node semantics (aria-label) — Flutter dong bo
                  // FULL noi dung o vao day moi khung hinh va KHONG trao node nay khi go
                  // (chi trao phan tu editing). Doc tu day thi ky tu cuoi cung khong mat.
                  function docTuSemantics(found) {
                    if (!found || found.attribute !== 'label' && found.attribute !== 'hint') return null;
                    const root = document.querySelector('flutter-view') || document.body;
                    for (const el of root.querySelectorAll('input, textarea')) {
                      const nhan = el.getAttribute('aria-label') || el.getAttribute('placeholder') || '';
                      if (nhan === found.attributeValue || nhan.split(String.fromCharCode(10))[0] === found.attributeValue) {
                        if (typeof el.value === 'string' && el.value.length > 0) return el.value;
                      }
                    }
                    return null;
                  }
                  function chotEnterText() {
                    if (!goDangCho) return;
                    const {found, el, giaTri} = goDangCho;
                    goDangCho = null;
                    for (const k of ['go', 'blur']) { const t = textTimers.get(k); if (t) { clearTimeout(t); textTimers.delete(k); } }
                    let value = docTuSemantics(found);
                    if (value === null) value = (el && el.isConnected && typeof el.value === 'string') ? el.value : giaTri;
                    send({kind: 'action', stage: 'ACTION', action: 'enter_text', ...found, valueType: 'string', value, browser: 'flutter_tester'});
                  }
                  document.addEventListener('focusout', event => {
                    const el = event.target;
                    if (!(el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement)) return;
                    if (!goDangCho) return;
                    // KHONG chot ngay: Flutter trao phan tu editing giua chung phien go va
                    // cu trao nao cung ban focusout — chot tai day la cat cut o ky tu vua go
                    // (dung loi "abyu" da gap). Hoan mot nhip; neu 120ms sau khong co cu go
                    // tiep theo thi day la roi o THAT -> chot (gia tri doc tu semantics).
                    goDangCho.el = el; goDangCho.giaTri = el.value;
                    const old = textTimers.get('blur'); if (old) clearTimeout(old);
                    textTimers.set('blur', setTimeout(chotEnterText, 120));
                  }, true);
                  document.addEventListener('input', event => {
                    const target = event.target;
                    if (!(target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement)) return;
                    const found = semanticNode(event);
                    if (!found) {
                      // Ky tu thu 2 tro di: Flutter da thay phan tu input, dinh danh mat.
                      // Neu dang co phien go thi day la TIEP DIEN cua chinh phien do —
                      // gia han timer va cap nhat phan tu, dung canh bao om som.
                      if (goDangCho) {
                        goDangCho.el = target;
                        goDangCho.giaTri = target.value;
                        const oldBlur = textTimers.get('blur'); if (oldBlur) { clearTimeout(oldBlur); textTimers.delete('blur'); }
                        const old = textTimers.get('go'); if (old) clearTimeout(old);
                        textTimers.set('go', setTimeout(chotEnterText, 700));
                        return;
                      }
                      warnNoTarget();
                      return;
                    }
                    // O nhap PHAI duoc nhan dien bang nhan ngu nghia hoac goi y nhap lieu.
                    // Nhan dang 'text' la chu tinh (vi du chu thich duoi o) — luc replay no tro
                    // vao mot Text widget, khong phai o nhap, va enterText se bao "Bad state: No element".
                    if (found.attribute !== 'label' && found.attribute !== 'hint') {
                      window.parent.postMessage({type: 'GOLDEN_RECORDER_WARNING', payload: {message:
                        'O nhap nay khong co nhan ngu nghia rieng; recorder chi doc duoc chu tinh "'
                        + String(found.attributeValue).slice(0, 40)
                        + '". Hay them Semantics(label: ...) hoac hintText cho o nhap trong Golden Solution.'}}, '*');
                      return;
                    }
                    const key = found.attribute + '=' + found.attributeValue;
                    // Su kien go sau ky tu dau co the khong nhan dien duoc muc tieu (found
                    // null da bi chan o tren) — nen moi su kien TOI DUOC day deu cap nhat
                    // phien dang cho; gia tri that se doc lai luc chot.
                    // Doi o giua chung (goDangCho cua o khac con treo): chot o cu truoc.
                    if (goDangCho && goDangCho.found.attributeValue !== found.attributeValue) chotEnterText();
                    goDangCho = {found, el: target, giaTri: target.value};
                    const oldBlur = textTimers.get('blur'); if (oldBlur) { clearTimeout(oldBlur); textTimers.delete('blur'); }
                    const old = textTimers.get('go'); if (old) clearTimeout(old);
                    textTimers.set('go', setTimeout(chotEnterText, 700));
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
                  // Phan loai MOT node thanh locator ma engine cham tim lai duoc, DUNG thu tu
                  // uu tien cua semanticNode (label -> hint -> text). Dong bo hai ham nay la
                  // bat buoc: item duoc tick phai tro vao dung widget ma luc replay tim thay.
                  function semanticState(node) {
                    const label = node.getAttribute('aria-label') || node.getAttribute('data-semantics-label');
                    if (label && label !== 'Enable accessibility') {
                      // `identifier` nam NGOAI target, chi de hien tren bang tick: checkpoint tao tu
                      // day phai tiep tuc kiem NOI DUNG bang nhan/chu (Q2), khong kiem "co dinh danh".
                      const identifier = identifierOf(node, label);
                      return {target: {label}, attribute: 'label', attributeValue: label, role: node.getAttribute('role') || '',
                              ...(identifier ? {identifier} : {})};
                    }
                    const hint = node.getAttribute('placeholder');
                    if (hint) return {target: {hint}, attribute: 'hint', attributeValue: hint, role: 'text_field'};
                    const text = textOf(node);
                    if (!text || textLeafCount(node) > 1 || text.length > MAX_TEXT_LOCATOR) return null;
                    // Nut tim theo chu (vd "Luu") mang dinh danh o phan tu cha — cung chi de hien.
                    const idChu = identifierOf(node, '');
                    return {target: {text}, attribute: 'text', attributeValue: text, role: node.getAttribute('role') || 'text',
                            ...(idChu ? {identifier: idChu} : {})};
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
                    const root = document.querySelector('flutter-view') || document.body;
                    // 'flt-semantics' bat buoc phai co: node CHU TRAN (tieu de man hinh,
                    // dong "Tong thang: ...") khong mang aria-label/role nao — thieu selector
                    // nay thi quet UI bo sot toan bo text tren man hinh.
                    root.querySelectorAll('flt-semantics, [aria-label], [data-semantics-label], input, textarea, [role]').forEach(node => {
                      if (!(node instanceof Element)) return;
                      const state = semanticState(node);
                      if (!state) return;
                      const key = state.attribute + '=' + state.attributeValue;
                      if (seen.has(key)) return;
                      seen.add(key);
                      items.push(state);
                    });
                    window.parent.postMessage({type: 'GOLDEN_RECORDER_INVENTORY', payload: {items}}, '*');
                  }
                  window.addEventListener('message', event => {
                    if (event.data && event.data.type === COMMAND && event.data.action === 'snapshot_ui') inventory();
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
