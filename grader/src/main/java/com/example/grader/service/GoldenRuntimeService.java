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
    // v27: snapshot_rects trả CẢ thành phần lặp kèm số lần lặp, để đếm số cột — list đổi thành
    // grid là dấu hiệu reflow miễn phí không tạo ra được
    // · v26: thêm lệnh snapshot_rects — đo khung mọi thành phần để so hai khung (điện thoại ↔
    // desktop) tìm chỗ app thật sự co giãn, phục vụ kĩ năng responsive
    // · v25: ép khung xem thử dàn bố cục bằng metric Android — xem writeRecorderEntry; bản trước
    // dàn theo metric desktop nên cùng một màn danh sách, khung web chứa 11 dòng còn máy ảo
    // Pixel 7 chỉ chứa 9 · v24: thêm lệnh
    // snapshot_identifiers — liệt kê định danh của MỌI widget, kể cả widget
    // không chữ (bảng tick bỏ qua chúng vì không có nhãn), để màn soạn đề gợi ý đích cho
    // tiêu chí bố cục Ch.7 · v23: locator ĐỌC ĐƯỢC định danh của lớp bọc ngoài (v22 chỉ đọc trên chính node, nên
    // nút chỉ có icon không ghi hình được và nút có chữ thì ghi bằng chữ chứ không bằng định
    // danh) · v22: bảng tick KHÔNG loại thành phần lặp lại nữa, bày một dòng kèm số lượng (v20 vứt
    // sạch nút Xóa của mọi dòng vì nhãn trùng nhau) · v21: bảng tick giữ nhãn/chữ làm target và
    // gửi kèm định danh để HIỆN (v20 đổi target sang định danh, làm tiêu chí thôi kiểm nội
    // dung) · v20: locator duy nhất + chốt giá trị nhập theo ranh giới thao tác · v4: quét
    // thành phần CHỈ trong flutter-view.
    // v28: nạp DB ẩn vào tên dò từ Golden, bỏ tên app.db cố định; đổi version để rebuild cache cũ.
    // v29: đường leo cha dừng khi KHUNG PHÌNH quá 4 lần phần tử vừa bấm — chặn ca Golden bọc
    // Semantics(identifier:) quanh cả Form/Column rồi mọi ô con ghi chung một đích (xem identifierOf).
    // v30: ghép hai lớp DOM đòi thêm KÍCH THƯỚC TƯƠNG ĐƯƠNG, không chỉ chồng lấp — bấm vào khoảng
    // trống không còn bị ghi thành một cú bấm vào nút nào đó (xem spatialSemanticNode).
    // v31: bấm vào khoảng trống thì IM LẶNG bỏ qua, không còn nhả cảnh báo mỗi cú bấm trượt; chỉ
    // còn cảnh báo khi bấm TRÚNG một control mà không suy ra được đích (xem coControlTaiDiem).
    private static final String RECORDER_BRIDGE_VERSION = "identifier-scope-v31";

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
        String contractJson = suite(suiteId).getDatabaseContractJson();
        var contract = mapper.readTree(contractJson == null || contractJson.isBlank() ? "{}" : contractJson);
        // Bộ cũ có thể chỉ khai path; engine ưu tiên nó, web dùng phần tên file cùng hợp đồng.
        String databaseName = contract.path("path").asText("").replace('\\', '/');
        if (!databaseName.isBlank()) databaseName = databaseName.substring(databaseName.lastIndexOf('/') + 1);
        if (databaseName.isBlank()) databaseName = contract.path("database_name").asText("");
        if (databaseName.isBlank()) databaseName = contract.path("name").asText("");
        if (!databaseName.matches("[-A-Za-z0-9_.]+[.]db")) {
            throw new IllegalArgumentException("Chưa xác định được tên database hợp lệ. Hãy tải lại ZIP Golden.");
        }
        writeRecorderEntry(target.resolve("lib"), databaseName);
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
        // Bo khoi assets co san TRUOC khi noi khoi moi: pubspec.base.yaml nay da khai
        // "assets: - assets/" (de duong cham nap duoc anh cua de). Noi them mot khoi nua
        // thanh HAI khoa `assets:` trong cung mot mapping, va yaml chet ngay o dong do voi
        // "Duplicate mapping key" — bao build hong ma khong noi vi sao.
        String pubspec = boKhoiAssets(Files.readString(basePubspec, StandardCharsets.UTF_8));
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
    /**
     * Bo khoi "assets:" (va cac dong danh sach ben duoi no) khoi mot pubspec.
     *
     * Chi bo dung khoi do, giu nguyen moi thu khac ke ca chu thich, de ban web va ban cham
     * van dung chung mot nguon phu thuoc.
     */
    static String boKhoiAssets(String pubspec) {
        final char xuongDong = (char) 10;
        StringBuilder ra = new StringBuilder(pubspec.length());
        boolean dangBo = false;
        for (String dong : pubspec.split(String.valueOf(xuongDong), -1)) {
            String rut = dong.strip();
            if (dangBo) {
                // Con trong khoi khi dong la mot muc danh sach hoac dong trong.
                if (rut.isEmpty() || rut.startsWith("- ")) continue;
                dangBo = false;
            }
            if (rut.equals("assets:")) {
                dangBo = true;
                continue;
            }
            ra.append(dong).append(xuongDong);
        }
        return ra.toString();
    }

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
    private void writeRecorderEntry(Path lib, String databaseName) throws Exception {
        Files.createDirectories(lib);
        Files.writeString(lib.resolve("_recorder_entry.dart"), """
                // Tệp do hệ thống sinh cho phiên ghi thao tác — không có trong bài nộp sinh viên.
                import 'dart:ui_web' as ui_web;

                import 'package:flutter/foundation.dart' show debugPrint;
                import 'package:flutter/semantics.dart';
                import 'package:flutter/services.dart' show ByteData, rootBundle;
                import 'package:flutter/widgets.dart';
                import 'package:sqflite_common/sqflite.dart' as sqflite_common;
                import 'package:sqflite_common_ffi_web/sqflite_ffi_web.dart' as sqflite_web;

                import 'main.dart' as golden_app;

                Future<void> main() async {
                  // KHUNG XEM THỬ PHẢI DÀN BỐ CỤC BẰNG METRIC ANDROID.
                  //
                  // Bản web này chạy trong Chrome trên Windows nên defaultTargetPlatform trả về
                  // TargetPlatform.windows, và ThemeData đọc thẳng giá trị đó: windows kéo theo
                  // VisualDensity.compact (-8 dp mỗi chiều) lẫn MaterialTapTargetSize.shrinkWrap.
                  // Hậu quả đo được: ListTile hai dòng cao 64 dp thay vì 72, hàng chip mất ràng
                  // buộc tối thiểu 48 dp — cùng một màn danh sách, khung web chứa 11 dòng còn máy
                  // ảo Pixel 7 chỉ chứa 9. Máy chấm chạy `flutter test`, mà Flutter ép biến môi
                  // trường FLUTTER_TEST về android, nên 72 dp mới là con số thật.
                  //
                  // Đặt NGAY DÒNG ĐẦU, trước cả ensureInitialized: Flutter đọc
                  // ui_web.browser.operatingSystem đúng một lần rồi nhớ mãi (_browserPlatform là
                  // biến final cấp thư viện), nên đặt muộn một nhịp là vô nghĩa.
                  //
                  // Vì sao không dùng debugDefaultTargetPlatformOverride: setter của nó ném
                  // FlutterError khi !kDebugMode, mà runtime này build --release.
                  ui_web.browser.debugOperatingSystemOverride = ui_web.OperatingSystem.android;
                  WidgetsFlutterBinding.ensureInitialized();
                  // Dấu phiên bản để phân định bản build đang CHẠY với bản bị cache.
                  debugPrint('recorder-entry {{VERSION}}');
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
                      '{{DATABASE_NAME}}',
                      bytes.buffer.asUint8List(bytes.offsetInBytes, bytes.lengthInBytes),
                    );
                  } catch (e) {
                    // Thiếu hidden.db (chưa upload) hoặc Golden kiểu cũ tự quản dữ liệu —
                    // app vẫn phải lên để ghi thao tác, recorder không được chết theo.
                    debugPrint('Recorder: khong nap duoc hidden.db: $e');
                  }
                  golden_app.main();
                }
                """.replace("{{VERSION}}", RECORDER_BRIDGE_VERSION)
                        .replace("{{DATABASE_NAME}}", databaseName), StandardCharsets.UTF_8);
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
                  const runtimeBase = new URL(document.querySelector('base')?.getAttribute('href') || '/', location.origin);
                  const logicalRoute = () => {
                    const path = location.pathname.startsWith(runtimeBase.pathname)
                      ? '/' + location.pathname.slice(runtimeBase.pathname.length).replace(/^\\/+/, '')
                      : location.pathname;
                    return path + location.search + location.hash;
                  };
                  const browserRoute = uri => {
                    const raw = String(uri || '/');
                    // pushState cấm đổi origin. Với deep link tuyệt đối, chỉ đưa
                    // pathname/query/fragment vào runtime hiện tại; plan vẫn giữ nguyên
                    // URI gốc để widget-test kiểm tra Router của bài sinh viên.
                    try {
                      const parsed = new URL(raw, location.origin);
                      const clean = parsed.pathname.replace(/^\\/+/, '');
                      return runtimeBase.pathname + clean + parsed.search + parsed.hash;
                    } catch (_) {
                      const clean = raw.replace(/^\\/+/, '');
                      return runtimeBase.pathname + clean;
                    }
                  };
                  const sendRoute = () => window.parent.postMessage({
                    type: 'GOLDEN_RECORDER_ROUTE',
                    payload: {uri: logicalRoute()}
                  }, '*');
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
                  // Dem CO BAO NHIEU thanh phan mang cung mot khoa dinh vi. Tach rieng khoi
                  // uniqueLocator vi hai cho can hai thu khac nhau: duong GHI THAO TAC can
                  // "dung mot" (cham dai mot trong sau nut Xoa la ghi nham dong), con BANG TICK
                  // can biet "co sau cai" de bay ra mot dong kem so luong.
                  function soKhopLocator(kind, value, elements = allSemanticElements()) {
                    if (!value) return 0;
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
                    return leafMost(matches).length;
                  }
                  function uniqueLocator(kind, value, elements = allSemanticElements()) {
                    return soKhopLocator(kind, value, elements) === 1;
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
                    // Dinh danh cua CHINH node, hoac cua lop boc ngoai gan nhat. Vi sao phai leo:
                    // Semantics(identifier:) boc quanh mot nut sinh ra DOM hai tang — tang mang
                    // dinh danh KHONG co role, tang co role=button lai KHONG mang dinh danh. Chi
                    // doc tren chinh node thi dinh danh khong bao gio duoc dung; nut chi co ICON
                    // (khong chu, khong nhan) khong con duong nao va recorder tu choi ca cu bam.
                    // identifierOf dung lai khi cha mang NHAN KHAC nen khong vo nham dinh danh
                    // cua dong danh sach khi bam nut Xoa trong dong (do 4/9/2026).
                    const semanticId = semanticIdOf(node)
                        || identifierOf(node, node.getAttribute('aria-label') || '');
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
                  // Flutter Web renders the editable HTML input in a separate editing
                  // layer. Its composedPath therefore does not always contain the
                  // flt-semantics node that owns the TextField/Button. Match that layer
                  // back to exactly one interactive semantic rectangle instead of ever
                  // falling back to a Form/group ancestor.
                  function spatialSemanticNode(event, mode, elements) {
                    const target = event.target instanceof Element ? event.target : null;
                    const targetRect = target && target.getBoundingClientRect
                      ? target.getBoundingClientRect() : null;
                    const hasPoint = Number.isFinite(event.clientX) && Number.isFinite(event.clientY)
                      && (event.clientX !== 0 || event.clientY !== 0);
                    const pointX = hasPoint ? event.clientX
                      : targetRect ? targetRect.left + targetRect.width / 2 : NaN;
                    const pointY = hasPoint ? event.clientY
                      : targetRect ? targetRect.top + targetRect.height / 2 : NaN;
                    const candidates = [];
                    for (const node of elements) {
                      const role = roleOf(node);
                      const isInput = node instanceof HTMLInputElement || node instanceof HTMLTextAreaElement
                        || INPUT_ROLES.has(role);
                      if (mode === 'input' && !isInput) continue;
                      if (mode === 'tap' && !INTERACTIVE_ROLES.has(role)) continue;
                      const rect = node.getBoundingClientRect ? node.getBoundingClientRect() : null;
                      if (!rect || rect.width <= 0 || rect.height <= 0) continue;
                      const containsPoint = Number.isFinite(pointX) && Number.isFinite(pointY)
                        && pointX >= rect.left && pointX <= rect.right
                        && pointY >= rect.top && pointY <= rect.bottom;
                      const overlapWidth = targetRect
                        ? Math.max(0, Math.min(rect.right, targetRect.right) - Math.max(rect.left, targetRect.left)) : 0;
                      const overlapHeight = targetRect
                        ? Math.max(0, Math.min(rect.bottom, targetRect.bottom) - Math.max(rect.top, targetRect.top)) : 0;
                      const overlapArea = overlapWidth * overlapHeight;
                      const minArea = targetRect
                        ? Math.min(rect.width * rect.height, Math.max(1, targetRect.width * targetRect.height)) : 1;
                      const overlapRatio = overlapArea / minArea;
                      // LONG NHAU THI CHUA DU. minArea lay theo cai NHO HON, nen moi nut nam gon
                      // trong khung cua phan tu bi cham deu ra overlapRatio = 1. Bam vao KHOANG
                      // TRONG duoi bieu mau thi phan tu bi cham la ca FLUTTER-VIEW, moi nut tren man
                      // deu thanh ung vien "hoan hao", containsPoint sai het, roi sizeDelta cham lay
                      // cai nao kich thuoc gan ca man nhat - ghi ra mot cu bam khong ai bam.
                      // Do 20/9/2026 tren DOM that: bam (640,600) o cho trong -> ghi thanh
                      // chi_tieu.form.luu, sau ung vien deu overlapRatio 1.
                      //
                      // Nhanh nay sinh ra de ghep HAI LOP DOM cua CUNG mot control, ma hai lop do
                      // khung xap xi nhau: do duoc input 1256x62 vs flt-semantics 1248x56, ti le dien
                      // tich 0,90-0,92. Con ung vien luc bam cho trong chi 0,003-0,065. Khoang giua
                      // rat rong nen doi them dieu kien kich thuoc tuong duong la du tach.
                      const tiLeKhung = targetRect
                        ? dienTichKhung(node) / Math.max(1, targetRect.width * targetRect.height) : 1;
                      if (!containsPoint
                          && (overlapRatio < 0.6 || tiLeKhung < 0.5 || tiLeKhung > 2)) continue;
                      const found = locatorFor(node, mode, elements);
                      if (!found) continue;
                      // Prefer the rectangle under the real pointer, then the closest
                      // sized/most specific interactive rectangle. A deterministic
                      // locator remains mandatory, so a visual parent cannot leak in.
                      const sizeDelta = targetRect
                        ? Math.abs(rect.width - targetRect.width) + Math.abs(rect.height - targetRect.height) : 0;
                      candidates.push({found, containsPoint, overlapRatio, area: rect.width * rect.height, sizeDelta});
                    }
                    candidates.sort((a, b) => Number(b.containsPoint) - Number(a.containsPoint)
                      || b.overlapRatio - a.overlapRatio || a.sizeDelta - b.sizeDelta || a.area - b.area
                      || String(a.found.attributeValue).localeCompare(String(b.found.attributeValue)));
                    if (!candidates.length) return null;
                    const best = candidates[0];
                    const equallyGood = candidates.filter(item => item !== best
                      && item.containsPoint === best.containsPoint
                      && Math.abs(item.overlapRatio - best.overlapRatio) < 0.01
                      && Math.abs(item.sizeDelta - best.sizeDelta) < 1
                      && item.found.attributeValue !== best.found.attributeValue);
                    if (equallyGood.length) {
                      lastReject = 'Nhieu semantic control trung khop tai cung vi tri.';
                      return null;
                    }
                    return best.found;
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
                  //
                  // CHAN THU HAI - KHUNG PHINH TO. Nhan cua cha chi chan duoc khi cha CO nhan; ma
                  // mot Semantics(identifier:) tran boc ca cum (Form, Column, Card) khong co nhan
                  // nao ca, nen no lot qua het. Do tren DOM that ngay 20/9/2026, ban web cua Golden
                  // FA26 (19 dinh danh):
                  //   - lop boc DUNG cua control: khung trung TUNG PIXEL voi phan tu bi cham, 19/19
                  //     deu ra ti le dien tich 1.00 (Semantics(identifier:) khong tu chiem cho nao).
                  //   - bac tren nua: 32x (ca dong danh sach), 176x va 250x (ca man hinh).
                  // Khoang trong giua 1 va 32 rat rong nen nguong 4 lan vua an toan vua du chat:
                  // con cho cho nguoi boc them Padding quanh nut (48x48 + dem 8 -> 1.78 lan) ma van
                  // chan duoc lop bao ca cum. Thieu chan nay thi ba o nhap trong mot Form deu ghi
                  // thanh MOT dich, im lang, toi luc cham moi vo.
                  const NGUONG_PHINH = 4;
                  function dienTichKhung(el) {
                    if (!el || !el.getBoundingClientRect) return 0;
                    const r = el.getBoundingClientRect();
                    return Math.max(0, r.width) * Math.max(0, r.height);
                  }
                  function identifierOf(node, nhanCuaNode) {
                    const dienTichGoc = dienTichKhung(node);
                    let cur = node;
                    for (let buoc = 0; cur instanceof Element && buoc < 5; buoc++) {
                      const id = cur.getAttribute('flt-semantics-identifier');
                      if (id) return id;
                      const cha = cur.parentElement;
                      if (!cha) break;
                      const nhanCha = cha.getAttribute('aria-label') || '';
                      if (nhanCha && nhanCha !== nhanCuaNode) break;
                      if (dienTichGoc > 0 && dienTichKhung(cha) > dienTichGoc * NGUONG_PHINH) break;
                      cur = cha;
                    }
                    return '';
                  }
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
                    const spatial = spatialSemanticNode(event, mode, elements);
                    if (spatial) return spatial;
                    if (!lastReject) lastReject = mode === 'input'
                      ? 'O nhap khong co semantic locator rieng.'
                      : 'Khong tim thay semantic control tuong tac duy nhat tai vi tri bam.';
                    return null;
                  }
                  function warnNoTarget() {
                    const message = 'Khong ghi duoc thao tac nay. '
                      + (lastReject || 'Khong suy ra duoc semantic locator duy nhat.')
                      + ' Hay gan Semantics(identifier: ...) duy nhat cho control do.';
                    window.parent.postMessage({type: 'GOLDEN_RECORDER_WARNING', payload: {message}}, '*');
                  }
                  // CO CONTROL NAO NAM DUOI DIEM BAM KHONG.
                  //
                  // Bam vao KHOANG TRONG la viec binh thuong luc ghi hinh: cuon man, bo focus khoi o
                  // nhap, hay bam bang quo. Khong co gi de ghi, va cung khong ai lam gi sai - nen
                  // khong duoc canh bao. Canh bao moi cu bam truot thi nguoi soan quen luon ca nhung
                  // canh bao that.
                  //
                  // Con bam TRUNG mot control ma khong suy ra duoc dich thi VAN phai keu: do la ca
                  // "quen gan dinh danh / nhan bi trung", dung thu can bao.
                  function coControlTaiDiem(event) {
                    const x = event.clientX, y = event.clientY;
                    if (!Number.isFinite(x) || !Number.isFinite(y) || (x === 0 && y === 0)) return true;
                    for (const node of allSemanticElements()) {
                      if (!INTERACTIVE_ROLES.has(roleOf(node))) continue;
                      const r = node.getBoundingClientRect ? node.getBoundingClientRect() : null;
                      if (!r || r.width <= 0 || r.height <= 0) continue;
                      if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return true;
                    }
                    return false;
                  }
                  function action(event, name, value = '') {
                    const found = semanticNode(event, 'tap');
                    if (!found) {
                      if (coControlTaiDiem(event)) warnNoTarget();
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
                  // Cùng một TextFormField có thể được Flutter Web công bố lúc đầu bằng
                  // "label + hint", rồi dựng lại node chỉ còn "label" trong khi đang gõ.
                  // So theo định danh logic ưu tiên thay vì so nguyên chuỗi aria-label để
                  // khoảng dừng hoặc một lần rebuild DOM không chốt nhầm giá trị trung gian.
                  const cungLocator = (a, b) => {
                    if (!a || !b) return false;
                    const ta = a.target || {};
                    const tb = b.target || {};
                    for (const key of ['semanticId', 'valueKey', 'key']) {
                      if (ta[key] && tb[key]) return ta[key] === tb[key];
                    }
                    if (ta.label && tb.label) return ta.label === tb.label;
                    if (!ta.label && !tb.label && ta.hint && tb.hint) return ta.hint === tb.hint;
                    return a.attribute === b.attribute && a.attributeValue === b.attributeValue;
                  };
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
                  // BANG TICK khac duong ghi thao tac: target phai la NHAN/CHU, khong phai
                  // dinh danh. Tieu chi sinh ra tu day co viec kiem NOI DUNG ("man hinh co
                  // dong chu X"); doi target sang dinh danh la tieu chi chi con kiem "co gan
                  // dinh danh", khong ai kiem chu nua (quyet dinh Q2 ke hoach Dinh danh).
                  // Dinh danh van duoc doc va gui kem, nhung nam NGOAI target — chi de hien.
                  function semanticState(node, elements) {
                    const role = roleOf(node);
                    const label = node.getAttribute('aria-label') || node.getAttribute('data-semantics-label');
                    if (label && label !== 'Enable accessibility') {
                      // Chi identifier duoc phep dai dien cho container co chu dich. Nhan tong
                      // hop cua Form/group/generic khong duoc dua vao inventory de tick nham.
                      if (CONTAINER_ROLES.has(role) || textLeafCount(node) > 1) return null;
                      // KHONG loai thanh phan lap lai. Nut Xoa cua moi dong danh sach mang cung
                      // mot nhan nen phep kiem "dung mot" cua ban v20 vut sach chung khoi bang
                      // tick — dung cai ma may cham nay da biet cham theo nhom (moi dong mot
                      // cai, vi tri do tuong doi trong dong). Bay ra MOT dong kem so luong.
                      const soNhan = soKhopLocator('label', label, elements);
                      if (soNhan === 0) return null;
                      const identifier = semanticIdOf(node) || identifierOf(node, label);
                      return {target: {label}, attribute: 'label', attributeValue: label, role: checkpointRoleOf(node),
                              ...(soNhan > 1 ? {count: soNhan} : {}), ...(identifier ? {identifier} : {})};
                    }
                    const hint = node.getAttribute('placeholder');
                    const soGoiY = soKhopLocator('hint', hint || '', elements);
                    if (hint && soGoiY > 0) {
                      const idHint = semanticIdOf(node) || identifierOf(node, '');
                      return {target: {hint}, attribute: 'hint', attributeValue: hint, role: 'text_field',
                              ...(soGoiY > 1 ? {count: soGoiY} : {}), ...(idHint ? {identifier: idHint} : {})};
                    }
                    const text = textOf(node);
                    if (!text || textLeafCount(node) > 1 || text.length > MAX_TEXT_LOCATOR) return null;
                    const soChu = soKhopLocator('text', text, elements);
                    if (soChu === 0) return null;
                    // Nut tim theo chu (vd "Luu") mang dinh danh o phan tu CHA — cung chi de hien.
                    const idChu = semanticIdOf(node) || identifierOf(node, '');
                    return {target: {text}, attribute: 'text', attributeValue: text, role: checkpointRoleOf(node),
                            ...(soChu > 1 ? {count: soChu} : {}), ...(idChu ? {identifier: idChu} : {})};
                  }
                  // LIET KE MOI DINH DANH dang co tren man, ke ca cua widget KHONG co chu.
                  // Vi sao tach khoi inventory(): semanticState bo qua node khong nhan/khong
                  // chu, ma dung nhung node do (ListView, Stack, Table boc Semantics) moi la
                  // dich cua tieu chi bo cuc Ch.7. Bang tick giu nguyen, khong them dong nao.
                  function identifierSnapshot() {
                    const ids = [];
                    const seen = new Set();
                    allSemanticElements().forEach(node => {
                      const id = semanticIdOf(node);
                      if (!id || seen.has(id)) return;
                      seen.add(id);
                      ids.push(id);
                    });
                    window.parent.postMessage(
                      {type: 'GOLDEN_RECORDER_IDENTIFIERS', payload: {identifiers: ids}}, '*');
                  }
                  // DO KHUNG cua moi thanh phan — dung cho ki nang responsive: chup o khung dien
                  // thoai, chup lai o khung desktop, cap nao DOI quan he thi do la cho app that
                  // su co gian.
                  //
                  // Tra ve pixel CLIENT chu KHONG quy ve dp. Pane/trinh duyet co the dang thu nho
                  // ca trang (da gap that: flt-semantics-host mang scale(1.18932) nen moi phep do
                  // lech 2,4 lan), ma quan he bo cuc thi BAT BIEN voi ti le mien la hai khung
                  // cung mot luot do. Kem theo khung goc de ben kia tinh sai so theo % man hinh.
                  //
                  // GIU CA thanh phan LAP (nut Xoa o moi dong), kem so lan lap o truong `dup`.
                  // Ben soan de bo chung ra khoi phep so quan he — mot khoa ung voi nhieu khung
                  // thi vi tri khong xac dinh — nhung VAN CAN chung de dem so cot: list doi
                  // thanh grid la mot trong vai dau hieu ma reflow mien phi khong tao ra duoc.
                  function rectSnapshot() {
                    const host = document.querySelector('flutter-view')
                      || document.querySelector('flt-glass-pane')
                      || document.body;
                    const goc = host.getBoundingClientRect();
                    const tho = [];
                    const dem = new Map();
                    allSemanticElements().forEach(node => {
                      const id = semanticIdOf(node);
                      const nhan = ((node.getAttribute('aria-label') || '') + '').replace(/\\s+/g, ' ').trim();
                      const khoa = id || nhan;
                      if (!khoa) return;
                      const r = node.getBoundingClientRect();
                      if (r.width <= 0 || r.height <= 0) return;
                      dem.set(khoa, (dem.get(khoa) || 0) + 1);
                      tho.push({
                        key: khoa,
                        identifier: id || '',
                        label: nhan.slice(0, 60),
                        role: node.getAttribute('role') || '',
                        x: r.x - goc.x, y: r.y - goc.y, w: r.width, h: r.height,
                      });
                    });
                    const items = tho.map(it => Object.assign({}, it, {dup: dem.get(it.key)}));
                    window.parent.postMessage({type: 'GOLDEN_RECORDER_RECTS', payload: {
                      screen: {w: goc.width, h: goc.height},
                      items,
                    }}, '*');
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
                  // Anh minh hoa cho de bai (khong lien quan oracle cham diem). Chup thang trong
                  // TRANG NAY (cung tai lieu voi canvas Flutter) nen khong dinh CORS/tainted-canvas
                  // nhu chup tu ngoai iframe. CanvasKit ve toan bo UI vao MOT the <canvas>; HTML
                  // renderer (hiem gap voi ban build release mac dinh) khong co canvas nay, luc do
                  // bao loi de giao vien biet ma chup bang cach khac (vd chup man hinh tay).
                  function screenshot() {
                    const canvas = document.querySelector('flt-glass-pane canvas') || document.querySelector('canvas');
                    if (!canvas) {
                      window.parent.postMessage({type: 'GOLDEN_RECORDER_SCREENSHOT',
                        payload: {error: 'Khong tim thay canvas de chup (renderer khong ho tro).'}}, '*');
                      return;
                    }
                    try {
                      const dataUrl = canvas.toDataURL('image/png');
                      const comma = dataUrl.indexOf(',');
                      window.parent.postMessage({type: 'GOLDEN_RECORDER_SCREENSHOT',
                        payload: {png_base64: dataUrl.slice(comma + 1)}}, '*');
                    } catch (e) {
                      window.parent.postMessage({type: 'GOLDEN_RECORDER_SCREENSHOT', payload: {error: String(e)}}, '*');
                    }
                  }
                  window.addEventListener('message', event => {
                    if (!event.data || event.data.type !== COMMAND) return;
                    if (event.data.action === 'perform_route_action') {
                      chotEnterText();
                      const routeAction = event.data.route_action || '';
                      const uri = event.data.uri || '/';
                      if (routeAction === 'boot_with_uri') {
                        history.replaceState(history.state, '', browserRoute(uri));
                        location.reload();
                        return;
                      }
                      if (routeAction === 'open_uri') {
                        history.pushState({}, '', browserRoute(uri));
                        // Flutter Web RouteInformationProvider nhận thay đổi history qua
                        // popstate. pushState tự nó không phát event nên phải phát rõ ràng.
                        window.dispatchEvent(new PopStateEvent('popstate', {state: history.state}));
                        sendRoute();
                      }
                      if (routeAction === 'browser_back') history.back();
                      if (routeAction === 'browser_forward') history.forward();
                      if (routeAction === 'reload') location.reload();
                    }
                    if (event.data.action === 'snapshot_ui') {
                      chotEnterText();
                      inventory();
                    }
                    if (event.data.action === 'snapshot_identifiers') {
                      identifierSnapshot();
                    }
                    if (event.data.action === 'snapshot_rects') {
                      rectSnapshot();
                    }
                    if (event.data.action === 'capture_screenshot') {
                      screenshot();
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
                  window.addEventListener('popstate', () => setTimeout(sendRoute, 0));
                  window.addEventListener('hashchange', () => setTimeout(sendRoute, 0));
                  const enable = () => {
                    const placeholder = document.querySelector('flt-semantics-placeholder[aria-label="Enable accessibility"]');
                    if (placeholder) placeholder.click();
                  };
                  window.addEventListener('flutter-first-frame', () => setTimeout(enable, 100));
                  setTimeout(enable, 1000);
                  window.parent.postMessage({type: 'GOLDEN_RECORDER_READY'}, '*');
                  sendRoute();
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
