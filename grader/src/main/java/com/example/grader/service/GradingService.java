package com.example.grader.service;


import com.example.grader.dto.TestCaseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
public class GradingService {

    @Value("${grader.image.prefix:grading-env}")
    private String imagePrefix;

    /** Ảnh nền dùng chung — chạy container chấm và mount testcase + lib vào. */
    @Value("${grader.base-image:grading-base:latest}")
    private String baseImage;

    /** Thư mục grader-base trên host, dùng lấy pubspec base khi cần mount assets của bài nộp. */
    @Value("${grader.template-dir:grader-base}")
    private String templateDir;

    @Value("${grader.runner.process-timeout-seconds:120}")
    private int testcaseProcessTimeoutSeconds;

    @Value("${grader.analyze.enabled:true}")
    private boolean analyzeEnabled;

    /**
     * CPU/RAM mỗi container và watchdog mỗi bài giờ CHỈNH ĐƯỢC lúc đang chạy (trang Chấm bài tự
     * động → Hiệu năng chấm). Đọc lại ngay trước khi bật container nên cấu hình mới có hiệu lực
     * từ bài kế tiếp, không cần khởi động lại backend.
     */
    @org.springframework.beans.factory.annotation.Autowired
    private GradingRuntimeSettingsService runtimeSettings;

    private final ObjectMapper mapper = new ObjectMapper();
    private final SubmissionPackagePolicy submissionPackagePolicy = new SubmissionPackagePolicy();

    /**
     * Container ĐANG chấm của từng phiên (batchId → các lượt chạy). Có sổ này thì nút "Dừng chấm"
     * mới cắt được bài đang dở ngay lập tức; nếu không, người dùng phải chờ hết watchdog
     * ({@code grader.timeout.seconds}) cho từng bài còn lại.
     */
    private final Map<String, Set<RunningContainer>> runningByBatch = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Phiên chấm mà người dùng đã bấm Dừng. Đặt cùng chỗ với sổ container vì cả hai đều là
     * "quyền kiểm soát lượt chấm đang bay": nhờ vậy lượt chấm còn đang giải nén/vá bài cũng
     * kịp thoát trước khi tốn thêm một container.
     */
    private final Set<String> cancelledBatches = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private record RunningContainer(String name, Process process) {}

    // ── Entry point ──────────────────────────────────────────────


    // ── Tìm root project chứa thư mục lib ──────────────────────
    private Path detectProjectRoot(Path extractDir) throws Exception {
        // Kiểm tra ngay ở thư mục gốc xem có thư mục lib không
        if (isLikelyStudentLib(extractDir.resolve("lib"))) return extractDir;

        // Nếu không có, tìm sâu vào 1 cấp (trường hợp SV nén cả thư mục cha chứa lib)
        List<Path> subDirs;
        try (Stream<Path> s = Files.list(extractDir)) {
            subDirs = s.filter(Files::isDirectory).toList();
        }
        for (Path sub : subDirs) {
            if (isLikelyStudentLib(sub.resolve("lib"))) return sub;
        }

        // Tìm sâu hơn nữa (đóng Stream để không rò rỉ file descriptor)
        try (Stream<Path> walk = Files.walk(extractDir)) {
            return walk
                    .filter(p -> p.getFileName().toString().equals("lib") && Files.isDirectory(p))
                    .filter(this::isLikelyStudentLib)
                    .map(Path::getParent)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thư mục lib/ trong file nộp"));
        }
    }

    private boolean isLikelyStudentLib(Path lib) {
        if (!Files.isDirectory(lib)) return false;
        if (Files.exists(lib.resolve("models").resolve("user.dart"))) return true;
        if (Files.exists(lib.resolve("screens").resolve("user_list_screen.dart"))) return true;
        try (Stream<Path> s = Files.list(lib)) {
            return s.anyMatch(p -> p.toString().endsWith(".dart"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private String detectPackageName(Path projectRoot) {
        try (Stream<Path> files = Files.walk(projectRoot.resolve("lib"))) {
            Map<String, Integer> counts = new HashMap<>();
            for (Path f : files.filter(p -> p.toString().endsWith(".dart")).toList()) {
                String s = Files.readString(f, StandardCharsets.UTF_8);
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("package:([A-Za-z_][A-Za-z0-9_]*)/")
                        .matcher(s);
                while (m.find()) {
                    String pkg = m.group(1);
                    if (!Set.of("flutter", "flutter_riverpod", "flutter_test",
                            "riverpod_annotation", "path", "sqflite", "sqflite_common_ffi",
                            "sqflite_common_ffi_web", "path_provider", "sembast_web",
                            "image_picker", "intl").contains(pkg)) {
                        counts.merge(pkg, 1, Integer::sum);
                    }
                }
            }
            return counts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("exam_project");
        } catch (Exception ignored) {
            return "exam_project";
        }
    }

    public String gradeSubmission(String batchId, String studentId, String examId, String testcasePath,
                                  Path tempDir, Path zipPath) throws Exception {
        return gradeSubmission(batchId, studentId, examId, testcasePath, tempDir, zipPath, null);
    }

    /**
     * @param evidenceTarget nơi giữ ảnh màn hình bằng chứng của bài này (mỗi luồng một PNG do
     *                       engine chụp). null = không thu bằng chứng. Thư mục tạm bị xoá sau
     *                       lượt chấm nên ảnh phải được CHUYỂN ra đây trước khi dọn.
     */
    public String gradeSubmission(String batchId, String studentId, String examId, String testcasePath,
                                  Path tempDir, Path zipPath, Path evidenceTarget) throws Exception {
        Path extractDir = tempDir.resolve("extracted");
        Files.createDirectories(extractDir);
        Path evidenceDir = tempDir.resolve("evidence");
        Files.createDirectories(evidenceDir);

        try {
            unzip(zipPath.toFile(), extractDir.toFile());

            // Bây giờ hàm này sẽ trả về thư mục ngay sát bên ngoài thư mục lib/
            Path projectRoot = detectProjectRoot(extractDir);
            Path studentLib = projectRoot.resolve("lib");
            // Chặn package ngoài TRƯỚC compile. Import nội bộ theo tên package của starter
            // được đổi sang exam_project vì image chạy --no-pub với package graph đã cache.
            SubmissionPackagePolicy.Policy packagePolicy = submissionPackagePolicy.load(testcasePath);
            submissionPackagePolicy.validateAndNormalize(studentLib, packagePolicy);
            applyPe27CompatibilityPatches(examId, studentLib);

            // Chỉ kiểm tra file .dart bên trong thư mục lib (đóng Stream)
            long dartCount;
            try (Stream<Path> s = Files.walk(studentLib)) {
                dartCount = s.filter(p -> p.toString().endsWith(".dart")).count();
            }
            if (dartCount == 0)
                throw new IllegalArgumentException("Không có file .dart trong lib/");

            log.info("[{}] {} file dart, project root: {}", studentId, dartCount, projectRoot.getFileName());

            // Người dùng bấm Dừng trong lúc giải nén/vá bài: đừng khởi động thêm container nữa.
            // BatchGradingService thấy cờ này nên sẽ ghi CANCELLED thay vì ERROR.
            if (isCancelled(batchId))
                throw new IllegalStateException("Phiên chấm đã bị dừng trước khi container khởi động.");

            return runDockerGrader(
                    batchId, studentId, studentLib.toAbsolutePath().toString(),
                    projectRoot.resolve("assets").toAbsolutePath().toString(),
                    prepareRuntimePubspec(
                            tempDir,
                            Files.isDirectory(projectRoot.resolve("assets")),
                            Files.isDirectory(studentLib.resolve("assets")),
                            "exam_project"),
                    examId, testcasePath, evidenceDir
            );
        } finally {
            // Chuyển ảnh bằng chứng ra chỗ bền TRƯỚC khi tempDir bị xoá. Ghi đè bản của lần
            // chấm trước (chấm lại = bằng chứng mới). Lỗi ở đây chỉ mất bằng chứng, nuốt.
            if (evidenceTarget != null) {
                try {
                    if (Files.isDirectory(evidenceDir)) {
                        try (Stream<Path> pngs = Files.list(evidenceDir)) {
                            List<Path> files = pngs.filter(f -> f.toString().endsWith(".png")).toList();
                            if (!files.isEmpty()) {
                                Files.createDirectories(evidenceTarget);
                                for (Path png : files) {
                                    Files.copy(png, evidenceTarget.resolve(png.getFileName().toString()),
                                            StandardCopyOption.REPLACE_EXISTING);
                                }
                            }
                        }
                    }
                } catch (Exception evidence) {
                    log.warn("[{}] Không lưu được ảnh bằng chứng: {}", studentId, evidence.toString());
                }
            }
            // QUAN TRỌNG: dọn thư mục tạm KHÔNG được ném lỗi đè lên lý do chấm thật (vd probe
            // mount thư mục này → Windows khóa file → Files.walk có thể ném và che mất lỗi 0/0).
            try { deleteDirectory(tempDir.toFile()); }
            catch (Exception cleanup) {
                log.warn("Dọn thư mục tạm {} lỗi (bỏ qua): {}", tempDir, cleanup.toString());
            }
        }
    }

    /**
     * Một số bài PE_27/PE_28/PE_29/PE_30 làm đúng hướng nhưng lệch nhẹ contract starter (User/UserModel,
     * HomeScreen/UserListScreen, user_viewmodel/user_view_model, Riverpod generator v3).
     * Vá trên thư mục extract TẠM để chấm được, không sửa ZIP gốc/audit.
     */
    private void applyPe27CompatibilityPatches(String examId, Path lib) {
        if (!"PE_27".equalsIgnoreCase(examId)
                && !"PE_28".equalsIgnoreCase(examId)
                && !"PE_29".equalsIgnoreCase(examId)
                && !"PE_30".equalsIgnoreCase(examId)) return;
        try {
            copyLegacyRootFoldersIntoLib(lib);
            aliasUserModelIfNeeded(lib);
            fixOverEscapedRelativeImports(lib);
            patchPe29CompileBreakers(lib);
            patchBoxConstraintsShortestSide(lib);
            createMissingSqliteRepositoryShim(lib);
            normalizeInMemoryRepositoryCompatibility(lib);
            patchRiverpod3GeneratedViewModel(lib);
            createOrNormalizeUserRepositoryShim(lib);
            patchBrokenUserModelSyntax(lib);
            createViewModelShimIfMissing(lib);
            patchUserRepositoryProviderVisibility(lib);
            patchNullableIdInViewModel(lib);
            patchLegacyProviderContract(lib);
            replaceBrokenUserListScreenWithMinimalShim(lib);
            createUserListScreenShimIfMissing(lib);
        } catch (Exception e) {
            log.warn("[{}] User Manager compatibility patch lỗi (bỏ qua): {}", examId, e.getMessage());
        }
    }

    private void copyLegacyRootFoldersIntoLib(Path lib) throws IOException {
        Path root = lib.getParent();
        for (String dir : List.of("models", "repositories", "screens", "viewmodels", "widgets")) {
            Path dst = lib.resolve(dir);
            Path src = root.resolve(dir);
            if (!Files.exists(dst) && Files.isDirectory(src)) {
                copyDirectory(src, dst);
            }
        }
        Path views = lib.resolve("views");
        if (!Files.exists(lib.resolve("screens")) && Files.isDirectory(views)) {
            copyDirectory(views, lib.resolve("screens"));
        }
    }

    private void aliasUserModelIfNeeded(Path lib) throws IOException {
        Path model = lib.resolve("models").resolve("user.dart");
        if (!Files.exists(model)) return;
        String s = Files.readString(model, StandardCharsets.UTF_8);
        if (s.contains("class UserModel") || s.contains("typedef UserModel")) return;
        if (s.contains("class User ")) {
            if (!s.contains("const User({")) {
                s = s.replace("User({", "const User({");
                Files.writeString(model, s, StandardCharsets.UTF_8);
            }
            Files.writeString(model, "\n\n// Compatibility cho testcase PE_27: bài dùng class User thay vì UserModel.\n"
                    + "typedef UserModel = User;\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
        }
    }

    private void fixOverEscapedRelativeImports(Path lib) throws IOException {
        try (Stream<Path> files = Files.walk(lib)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".dart")).toList()) {
                String s = Files.readString(f, StandardCharsets.UTF_8);
                String fixed = normalizeLocalPackageImports(s)
                        .replace("package:sqflite_common/sqlite_api.dart", "package:sqflite/sqflite.dart")
                        .replace("package:riverpod/src/framework.dart", "package:flutter_riverpod/flutter_riverpod.dart")
                        .replace("package:exam_project/src/framework.dart", "package:flutter_riverpod/flutter_riverpod.dart")
                        .replace("import '../../models/", "import '../models/")
                        .replace("import \"../../models/", "import \"../models/")
                        .replace("import '../../viewmodels/", "import '../viewmodels/")
                        .replace("import \"../../viewmodels/", "import \"../viewmodels/");
                fixed = rewriteExamProjectImportsToRelative(lib, f, fixed);
                if (!fixed.equals(s)) Files.writeString(f, fixed, StandardCharsets.UTF_8);
            }
        }
    }

    private String normalizeLocalPackageImports(String source) {
        Set<String> deps = Set.of("flutter", "flutter_riverpod", "flutter_test",
                "riverpod", "riverpod_annotation", "path", "sqflite", "sqflite_common_ffi",
                "sqflite_common", "sqflite_common_ffi_web", "path_provider", "sembast_web",
                "image_picker", "intl");
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("package:([A-Za-z_][A-Za-z0-9_]*)/")
                .matcher(source);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String pkg = m.group(1);
            if (deps.contains(pkg) || "exam_project".equals(pkg)) {
                m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(m.group(0)));
            } else {
                m.appendReplacement(out, "package:exam_project/");
            }
        }
        m.appendTail(out);
        return out.toString();
    }

    private String rewriteExamProjectImportsToRelative(Path lib, Path file, String source) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(import\\s+['\"])package:exam_project/([^'\"]+)(['\"];)")
                .matcher(source);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            Path target = lib.resolve(m.group(2)).normalize();
            String rel = file.getParent().relativize(target).toString().replace('\\', '/');
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(m.group(1) + rel + m.group(3)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private void patchPe29CompileBreakers(Path lib) throws IOException {
        Path repo = lib.resolve("repositories").resolve("user_repository.dart");
        if (Files.exists(repo)) {
            String s = Files.readString(repo, StandardCharsets.UTF_8);
            String fixed = s
                    .replace("Future<int> addUser(UserModel user);", "Future<dynamic> addUser(UserModel user);")
                    .replace("Future<int> updateUser(UserModel user);", "Future<dynamic> updateUser(UserModel user);")
                    .replace("Future<int> deleteUser(int id);", "Future<dynamic> deleteUser(int id);");
            if (!fixed.equals(s)) Files.writeString(repo, fixed, StandardCharsets.UTF_8);
        }

        Path model = lib.resolve("models").resolve("user.dart");
        if (Files.exists(model)) {
            String s = Files.readString(model, StandardCharsets.UTF_8);
            String fixed = s.replace("String? fullname,", "String? fullName,")
                    .replace("fullName: fullname ?? this.fullName,", "fullName: fullName ?? this.fullName,");
            if (!fixed.equals(s)) Files.writeString(model, fixed, StandardCharsets.UTF_8);
        }

        Path dbHelper = lib.resolve("core").resolve("database").resolve("database_helper.dart");
        if (Files.exists(dbHelper)) {
            String s = Files.readString(dbHelper, StandardCharsets.UTF_8);
            String fixed = s.replace("getDatabasesPath(path: 'users.db')", "getDatabasesPath()");
            if (!fixed.equals(s)) Files.writeString(dbHelper, fixed, StandardCharsets.UTF_8);
        }
    }

    private void patchBoxConstraintsShortestSide(Path lib) throws IOException {
        Path screen = lib.resolve("screens").resolve("user_list_screen.dart");
        if (!Files.exists(screen)) return;
        String s = Files.readString(screen, StandardCharsets.UTF_8);
        String fixed = s.replace("constraints.shortestSide", "constraints.biggest.shortestSide");
        if (!fixed.equals(s)) Files.writeString(screen, fixed, StandardCharsets.UTF_8);
    }

    private void createMissingSqliteRepositoryShim(Path lib) throws IOException {
        Path sqlite = lib.resolve("repositories").resolve("sqlite_user_repository.dart");
        if (Files.exists(sqlite)) return;
        Path repo = lib.resolve("repositories").resolve("user_repository.dart");
        Path inMemory = lib.resolve("repositories").resolve("in_memory_user_repository.dart");
        if (!Files.exists(repo) || !Files.exists(inMemory)) return;
        Files.writeString(sqlite, """
import '../models/user.dart';
import 'user_repository.dart';

class SqliteUserRepository implements UserRepository {
  final List<UserModel> _users = <UserModel>[];

  Future<List<UserModel>> getUsers() async => List<UserModel>.from(_users);

  Future<void> addUser(UserModel user) async {
    _users.add(user);
  }

  Future<void> updateUser(UserModel user) async {
    final idx = _users.indexWhere((u) => u.id == user.id);
    if (idx != -1) _users[idx] = user;
  }

  Future<void> deleteUser(int id) async {
    _users.removeWhere((u) => u.id == id);
  }
}
""", StandardCharsets.UTF_8);
    }

    private void normalizeInMemoryRepositoryCompatibility(Path lib) throws IOException {
        Path inMemory = lib.resolve("repositories").resolve("in_memory_user_repository.dart");
        if (!Files.exists(inMemory)) return;

        String s = Files.readString(inMemory, StandardCharsets.UTF_8);
        String fixed = s
                .replace("InMemoryUserRepository(this._databaseUser);",
                        "InMemoryUserRepository([DatabaseUser? databaseUser]) : _databaseUser = databaseUser ?? DatabaseUser.instance;")
                .replace("InMemoryUserRepository(this._dbHelper);",
                        "InMemoryUserRepository([DatabaseUsers? dbHelper]) : _dbHelper = dbHelper ?? DatabaseUsers.instance;")
                .replace("InMemoryUserRepository(this._appDatabase);",
                        "InMemoryUserRepository([AppDatabase? appDatabase]) : _appDatabase = appDatabase ?? AppDatabase.instance;")
                .replace("final List<UserModel> _users = _dbHelper.readAllUsers();",
                        "final List<UserModel> _users = <UserModel>[];")
                .replace("static final UserRepository _instance = UserRepository._init();",
                        "static final InMemoryUserRepository _instance = InMemoryUserRepository();")
                .replace("user.id = id;", "_users.add(user.copyWith(id: id));")
                .replace("{'fullName', user.fullName},\n      {'email', user.email},",
                        "{'fullName': user.fullName, 'email': user.email},")
                .replace("_fetchUsers()", "getUsers()");

        if (fixed.contains("DatabaseHelper.instance") && !fixed.contains("database_helper.dart")) {
            String helperImport = null;
            if (Files.exists(lib.resolve("repositories").resolve("database_helper.dart"))) {
                helperImport = "import 'database_helper.dart';\n";
            } else if (Files.exists(lib.resolve("database").resolve("database_helper.dart"))) {
                helperImport = "import '../database/database_helper.dart';\n";
            } else if (Files.exists(lib.resolve("database").resolve("db.dart"))) {
                helperImport = "import '../database/db.dart';\n";
            }
            if (helperImport != null) fixed = helperImport + fixed;
        }
        if (fixed.contains("import 'database_helper.dart';")
                && !Files.exists(lib.resolve("repositories").resolve("database_helper.dart"))) {
            if (Files.exists(lib.resolve("database").resolve("database_helper.dart"))) {
                fixed = fixed.replace("import 'database_helper.dart';", "import '../database/database_helper.dart';");
            } else if (Files.exists(lib.resolve("database").resolve("db.dart"))) {
                fixed = fixed.replace("import 'database_helper.dart';", "import '../database/db.dart';");
            }
        }

        if (fixed.contains("InMemoryUserRepository._init();")
                && !fixed.matches("(?s).*\\n\\s*InMemoryUserRepository\\(\\);.*")) {
            fixed = fixed.replaceAll("(?m)^(\\s*)InMemoryUserRepository\\._init\\(\\);",
                    "$1InMemoryUserRepository();\n$1InMemoryUserRepository._init();");
        }

        int insertAt = fixed.lastIndexOf('}');
        if (insertAt > 0 && !fixed.contains("getUsers(")) {
            String body = fixed.contains("getAllUsers(")
                    ? "  Future<List<UserModel>> getUsers() async => getAllUsers();\n"
                    : "  Future<List<UserModel>> getUsers() async => List<UserModel>.from(_users);\n";
            fixed = fixed.substring(0, insertAt) + """

""" + body + fixed.substring(insertAt);
        }
        insertAt = fixed.lastIndexOf('}');
        if (insertAt > 0 && !fixed.contains("getUser(")) {
            fixed = fixed.substring(0, insertAt) + """

  Future<List<UserModel>> getUser() async => getUsers();
""" + fixed.substring(insertAt);
        }
        insertAt = fixed.lastIndexOf('}');
        if (insertAt > 0 && !fixed.contains("getAllUsers(")) {
            fixed = fixed.substring(0, insertAt) + """

  Future<List<UserModel>> getAllUsers() async => getUsers();
""" + fixed.substring(insertAt);
        }
        insertAt = fixed.lastIndexOf('}');
        if (insertAt > 0 && !fixed.contains("getAll(")) {
            fixed = fixed.substring(0, insertAt) + """

  Future<List<UserModel>> getAll() async => getUsers();
""" + fixed.substring(insertAt);
        }

        if (!fixed.equals(s)) Files.writeString(inMemory, fixed, StandardCharsets.UTF_8);
    }

    private void patchRiverpod3GeneratedViewModel(Path lib) throws IOException {
        Path vm = lib.resolve("viewmodels").resolve("user_view_model.dart");
        Path gen = lib.resolve("viewmodels").resolve("user_view_model.g.dart");
        if (!Files.exists(vm)) return;
        String src = Files.readString(vm, StandardCharsets.UTF_8);
        boolean missingGen = !Files.exists(gen);
        String g = missingGen ? "" : Files.readString(gen, StandardCharsets.UTF_8);
        if (!missingGen && !g.contains("$FunctionalProvider") && !g.contains("$AsyncNotifierProvider")) return;

        if (!src.contains("package:flutter_riverpod/flutter_riverpod.dart")) {
            src = src.replace("import 'package:riverpod_annotation/riverpod_annotation.dart';",
                    "import 'package:flutter_riverpod/flutter_riverpod.dart';\n"
                  + "import 'package:riverpod_annotation/riverpod_annotation.dart';");
        }
        src = src.replace("UserRepository userRepository(Ref ref)",
                "UserRepository userRepository(UserRepositoryRef ref)");
        Files.writeString(vm, src, StandardCharsets.UTF_8);

        boolean syncState = src.contains("class UserState") && src.contains("UserState build()");
        boolean sourceHasRepositoryProvider = src.contains("final userRepositoryProvider");
        String repoProviderShim = sourceHasRepositoryProvider ? "" : """
final userRepositoryProvider = AutoDisposeProvider<UserRepository>((ref) {
  return userRepository(ref);
});

""";
        String shim = syncState ? """
// Generated compatibility shim for PE_28 runtime (Riverpod 2).
part of 'user_view_model.dart';

typedef UserRepositoryRef = AutoDisposeProviderRef<UserRepository>;

%s
abstract class _$UserViewModel extends AutoDisposeNotifier<UserState> {}

final userViewModelProvider =
    AutoDisposeNotifierProvider<UserViewModel, UserState>(UserViewModel.new);
""".formatted(repoProviderShim) : """
// Generated compatibility shim for PE_27 runtime (Riverpod 2).
part of 'user_view_model.dart';

typedef UserRepositoryRef = AutoDisposeProviderRef<UserRepository>;

%s
abstract class _$UserViewModel extends AutoDisposeAsyncNotifier<List<UserModel>> {}

final userViewModelProvider =
    AutoDisposeAsyncNotifierProvider<UserViewModel, List<UserModel>>(UserViewModel.new);
""".formatted(repoProviderShim);
        Files.writeString(gen, shim, StandardCharsets.UTF_8);
    }

    private void createOrNormalizeUserRepositoryShim(Path lib) throws IOException {
        Path repo = lib.resolve("repositories").resolve("user_repository.dart");
        Files.createDirectories(repo.getParent());
        if (Files.exists(repo)) {
            String current = Files.readString(repo, StandardCharsets.UTF_8);
            // Một số bài export native/web repository concrete, kéo theo field Database vào interface
            // khi FakeUserRepository implements UserRepository. Runtime chấm chỉ cần repository bộ nhớ.
            if (!current.contains("export 'user_repository_native.dart'")
                    && !current.contains("export \"user_repository_native.dart\"")) {
                return;
            }
        }
        Files.writeString(repo, """
import '../models/user.dart';

class UserRepository {
  UserRepository([List<UserModel>? seed]) : _users = List<UserModel>.from(seed ?? const <UserModel>[]);

  final List<UserModel> _users;

  static Future<UserRepository> init() async => UserRepository();

  Future<List<UserModel>> getUsers() async => List<UserModel>.from(_users);
  Future<List<UserModel>> getAll() async => getUsers();

  Future<UserModel> addUser(UserModel user) async => insert(user);
  Future<UserModel> insert(UserModel user) async {
    final nextId = user.id ?? (_users.isEmpty
        ? 1
        : (_users.map((u) => u.id ?? 0).reduce((a, b) => a > b ? a : b) + 1));
    final saved = user.copyWith(id: nextId);
    _users.add(saved);
    return saved;
  }

  Future<UserModel> updateUser(UserModel user) async => update(user);
  Future<UserModel> update(UserModel user) async {
    final idx = _users.indexWhere((u) => u.id == user.id);
    if (idx != -1) _users[idx] = user;
    return user;
  }

  Future<void> deleteUser(int id) async => delete(id);
  Future<void> delete(int id) async {
    _users.removeWhere((u) => u.id == id);
  }
}
""", StandardCharsets.UTF_8);
    }

    private void patchBrokenUserModelSyntax(Path lib) throws IOException {
        Path model = lib.resolve("models").resolve("user.dart");
        if (!Files.exists(model)) return;

        String s = Files.readString(model, StandardCharsets.UTF_8);
        int start = s.indexOf("UserModel copyWith(");
        int factory = s.indexOf("factory UserModel.", start);
        if (start < 0 || factory < 0) return;

        String chunk = s.substring(start, factory);
        if (chunk.contains("}) {") || chunk.contains("}) =>")) return;

        String fixedCopyWith = """
UserModel copyWith({
    int? id,
    String? fullName,
    String? email,
    String? avatar,
  }) => this;

  """;
        String fixed = s.substring(0, start) + fixedCopyWith + s.substring(factory);
        Files.writeString(model, fixed, StandardCharsets.UTF_8);
    }

    private void createViewModelShimIfMissing(Path lib) throws IOException {
        Path canonical = lib.resolve("viewmodels").resolve("user_view_model.dart");
        if (Files.exists(canonical)) return;
        Path dir = canonical.getParent();
        Files.createDirectories(dir);

        Path legacyCanonical = lib.getParent().resolve("viewmodels").resolve("user_view_model.dart");
        if (Files.exists(legacyCanonical)) {
            Files.copy(legacyCanonical, canonical, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return;
        }

        Path userViewmodel = dir.resolve("user_viewmodel.dart");
        if (Files.exists(userViewmodel)) {
            String src = Files.readString(userViewmodel, StandardCharsets.UTF_8);
            if (src.contains("userViewModelProvider") && !src.contains("usersNotifierProvider")) {
                Files.writeString(canonical, """
export 'user_viewmodel.dart';
""", StandardCharsets.UTF_8);
            } else {
                Files.writeString(canonical, """
export 'user_viewmodel.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../models/user.dart';
import '../repositories/user_repository.dart';
import 'user_viewmodel.dart';

final userRepositoryProvider = Provider<UserRepository>((ref) {
  throw UnimplementedError('PE_27 test override must provide UserRepository');
});

final userViewModelProvider = usersNotifierProvider;
typedef UserViewModel = UsersNotifier;
""", StandardCharsets.UTF_8);
            }
            return;
        }

        Path notifier = dir.resolve("user_notifier.dart");
        if (Files.exists(notifier)) {
            Files.writeString(canonical, """
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../models/user.dart';
import '../repositories/user_repository.dart';
import 'user_notifier.dart';

final userRepositoryProvider = Provider<UserRepository>((ref) {
  throw UnimplementedError('PE_27 test override must provide UserRepository');
});

class UserViewModel extends StateNotifier<UserState> {
  UserViewModel(this.repository) : super(UserState()) {
    loadUsers();
  }

  final UserRepository repository;

  Future<void> loadUsers() async {
    final users = await repository.getUsers();
    state = state.copyWith(users: users, isLoading: false);
  }

  Future<void> addUser(UserModel user) async {
    final created = await repository.addUser(user);
    state = state.copyWith(users: <UserModel>[...state.users, created], isLoading: false);
  }

  Future<void> updateUser(UserModel user) async {
    final updated = await repository.updateUser(user);
    state = state.copyWith(users: <UserModel>[
      for (final item in state.users) item.id == updated.id ? updated : item
    ], isLoading: false);
  }

  Future<void> deleteUser(int id) async {
    await repository.deleteUser(id);
    state = state.copyWith(
      users: state.users.where((user) => user.id != id).toList(),
      isLoading: false,
    );
  }
}

final userViewModelProvider =
    StateNotifierProvider<UserViewModel, UserState>((ref) {
  return UserViewModel(ref.watch(userRepositoryProvider));
});
""", StandardCharsets.UTF_8);
        }
    }

    private void patchUserRepositoryProviderVisibility(Path lib) throws IOException {
        Path vm = lib.resolve("viewmodels").resolve("user_view_model.dart");
        if (!Files.exists(vm)) return;

        String s = Files.readString(vm, StandardCharsets.UTF_8);
        if (s.contains("final userRepositoryProvider")) return;
        if (!s.contains("userRepositoryProvider")) return;
        if (s.contains("part 'user_view_model.g.dart'")
                && (s.contains("@riverpod") || s.contains("@Riverpod"))
                && s.contains("UserRepository userRepository(")) {
            return;
        }

        String fixed = s
                .replace("import '../providers/providers.dart';", "import '../providers/providers.dart' hide userRepositoryProvider;")
                .replace("import '../provider/providers.dart';", "import '../provider/providers.dart' hide userRepositoryProvider;")
                .replace("import '../providers/user_repository_provider.dart';", "import '../providers/user_repository_provider.dart' hide userRepositoryProvider;")
                .replace("userRepositoryProvider.future", "userRepositoryProvider");

        if (!fixed.contains("../repositories/user_repository.dart")
                && !fixed.contains("\"../repositories/user_repository.dart\"")) {
            fixed = "import '../repositories/user_repository.dart';\n" + fixed;
        }

        if (fixed.contains("repoAsync.maybeWhen")) {
            fixed = fixed.replaceAll("(?s)final userViewModelProvider\\s*=\\s*StateNotifierProvider<UserViewModel, UserState>\\(\\(ref\\) \\{.*?\\n\\}\\);",
                    """
final userViewModelProvider =
    StateNotifierProvider<UserViewModel, UserState>((ref) {
  return UserViewModel(ref.watch(userRepositoryProvider));
});
""");
        }

        fixed = fixed.stripTrailing() + """

final userRepositoryProvider = Provider<UserRepository>((ref) {
  throw UnimplementedError('PE test override must provide UserRepository');
});
""";
        Files.writeString(vm, fixed, StandardCharsets.UTF_8);
    }

    private void patchNullableIdInViewModel(Path lib) throws IOException {
        Path vm = lib.resolve("viewmodels").resolve("user_view_model.dart");
        if (!Files.exists(vm)) return;

        String s = Files.readString(vm, StandardCharsets.UTF_8);
        String fixed = s
                .replace("state.items.map((u) => u.id).reduce((a, b) => a > b ? a : b) + 1",
                        "state.items.map((u) => u.id ?? 0).reduce((a, b) => a > b ? a : b) + 1")
                .replace("state.users.map((u) => u.id).reduce((a, b) => a > b ? a : b) + 1",
                        "state.users.map((u) => u.id ?? 0).reduce((a, b) => a > b ? a : b) + 1");
        if (!fixed.equals(s)) Files.writeString(vm, fixed, StandardCharsets.UTF_8);
    }

    /** Cầu nối cho bài legacy dùng repositoryProvider/userProvider thay vì tên contract PE_30. */
    private void patchLegacyProviderContract(Path lib) throws IOException {
        Path vm = lib.resolve("viewmodels").resolve("user_view_model.dart");
        Path screen = lib.resolve("screens").resolve("user_list_screen.dart");
        if (!Files.exists(vm) || !Files.exists(screen)) return;

        String source = Files.readString(vm, StandardCharsets.UTF_8);
        if (!source.contains("final repositoryProvider")
                || !source.contains("final userProvider")
                || !source.contains("class UserNotifier extends StateNotifier<List<UserModel>>")) return;

        String fixed = source.replaceFirst(
                "(?s)final\\s+repositoryProvider\\s*=\\s*Provider\\s*\\(\\s*\\(ref\\)\\s*=>\\s*InMemoryUserRepository\\(\\)\\s*,?\\s*\\);",
                "final userRepositoryProvider = Provider<UserRepository>(\n"
                        + "  (ref) => InMemoryUserRepository(),\n"
                        + ");\n"
                        + "final repositoryProvider = userRepositoryProvider;");
        if (!fixed.contains("../repositories/user_repository.dart")) {
            fixed = fixed.replace(
                    "import '../repositories/in_memory_user_repository.dart';",
                    "import '../repositories/in_memory_user_repository.dart';\nimport '../repositories/user_repository.dart';");
        }
        fixed = fixed.replace("ref.read(repositoryProvider)", "ref.read(userRepositoryProvider)")
                .replace("final InMemoryUserRepository repository;", "final UserRepository repository;");
        if (!fixed.contains("final userViewModelProvider")) {
            fixed = fixed.replaceFirst(
                    "(?s)(final\\s+userProvider\\s*=.*?;\\s*)class\\s+UserNotifier",
                    "$1\nfinal userViewModelProvider = userProvider;\n\nclass UserNotifier");
        }
        if (!fixed.equals(source)) Files.writeString(vm, fixed, StandardCharsets.UTF_8);

        String screenSource = Files.readString(screen, StandardCharsets.UTF_8);
        String screenFixed = screenSource
                .replace("ref.watch(userProvider)", "ref.watch(userViewModelProvider)")
                .replace("ref.read(userProvider.notifier)", "ref.read(userViewModelProvider.notifier)");
        if (!screenFixed.equals(screenSource)) Files.writeString(screen, screenFixed, StandardCharsets.UTF_8);
    }

    private void replaceBrokenUserListScreenWithMinimalShim(Path lib) throws IOException {
        Path screen = lib.resolve("screens").resolve("user_list_screen.dart");
        if (!Files.exists(screen)) return;

        String s = Files.readString(screen, StandardCharsets.UTF_8);
        boolean compileBreakingStudentDraft = s.contains("usersState")
                || s.contains("_selectedAvatarUrl")
                || s.contains("avatarUrl:")
                || s.matches("(?s).*\\bUser\\s*\\(\\s*fullName\\s*:.*");
        if (!compileBreakingStudentDraft) return;

        Files.writeString(screen, """
import 'package:flutter/material.dart';

class UserListScreen extends StatelessWidget {
  const UserListScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const Scaffold(body: SizedBox.shrink());
  }
}
""", StandardCharsets.UTF_8);
    }

    private void createUserListScreenShimIfMissing(Path lib) throws IOException {
        Path canonical = lib.resolve("screens").resolve("user_list_screen.dart");
        if (Files.exists(canonical)) return;
        Path home = lib.resolve("screens").resolve("home_screen.dart");
        if (!Files.exists(home)) return;
        Files.createDirectories(canonical.getParent());
        Files.writeString(canonical, """
import 'home_screen.dart';

class UserListScreen extends HomeScreen {
  const UserListScreen({super.key});
}
""", StandardCharsets.UTF_8);
    }

    // ── Gọi Docker ───────────────────────────────────────────────
    private String runDockerGrader(String batchId, String studentId, String libPath, String assetsPath,
                                   String pubspecPath, String examId, String testcasePath,
                                   Path evidenceDir) throws Exception {
        // Tách phần mount + ảnh ra để vừa chạy chấm, vừa tái dùng cho probe chẩn đoán khi 0/0.
        List<String> mounts = new ArrayList<>(List.of(
                "-v", toDockerPath(libPath) + ":/app/lib"));
        if (evidenceDir != null && Files.isDirectory(evidenceDir)) {
            // Thư mục RIÊNG mỗi bài cho ảnh bằng chứng — không dùng /app/test vì đó là thư mục
            // CHUNG của cả đề, hai bài chấm song song sẽ ghi đè nhau.
            mounts.add("-v");
            mounts.add(toDockerPath(evidenceDir.toAbsolutePath().toString()) + ":/app/evidence");
        }
        if (assetsPath != null && Files.isDirectory(Path.of(assetsPath))) {
            mounts.add("-v");
            mounts.add(toDockerPath(assetsPath) + ":/app/assets");
        }
        if (pubspecPath != null && Files.exists(Path.of(pubspecPath))) {
            mounts.add("-v");
            mounts.add(toDockerPath(pubspecPath) + ":/app/pubspec.yaml");
        }

        // MOUNT mode: mount testcase vào ảnh nền (không cần image riêng cho đề).
        // Fallback (đề cũ): dùng image grading-env-<đề> đã build sẵn.
        boolean testcaseConfigured = testcasePath != null && !testcasePath.isBlank();
        if (testcaseConfigured && !Files.exists(Path.of(testcasePath))) {
            throw new GradingDiagnosticException(
                    "TESTCASE_FILES_MISSING",
                    GradingDiagnosticException.Origin.TESTCASE,
                    "TESTCASE_SETUP",
                    true,
                    "Không tìm thấy thư mục testcase đã cấu hình: " + testcasePath);
        }
        boolean mountMode = testcaseConfigured;
        String image;
        if (mountMode) {
            mounts.add("-v");
            mounts.add(toDockerPath(testcasePath) + ":/app/test");
            image = baseImage;
        } else {
            image = imagePrefix + "-" + examId.toLowerCase();
        }

        // Chốt cấu hình tài nguyên MỘT LẦN cho cả lượt chấm này: người dùng có đổi giữa chừng thì
        // container và watchdog vẫn dùng chung một mức, không lệch nhau.
        String runMemory = runtimeSettings.memoryArg();
        String runCpus = runtimeSettings.cpusArg();
        int timeoutSeconds = runtimeSettings.timeoutSeconds();

        String containerName = dockerContainerName("grader-run", examId, studentId);
        List<String> command = new ArrayList<>(List.of(
                "docker", "run", "--name", containerName, "--rm", "--memory", runMemory, "--cpus", runCpus));
        command.add("-e");
        command.add("GRADER_ANALYZE_LIB=" + analyzeEnabled);
        if (evidenceDir != null && Files.isDirectory(evidenceDir)) {
            command.add("-e");
            command.add("GRADER_EVIDENCE_DIR=/app/evidence");
        }
        command.add("-e");
        command.add("GRADER_BATCH_TIMEOUT_SECONDS=" + testcaseProcessTimeoutSeconds);
        command.add("-e");
        command.add("GRADER_TOTAL_TIMEOUT_SECONDS=" + Math.max(30, timeoutSeconds - 15));
        command.add("-e");
        command.add("GRADER_PREFLIGHT_TIMEOUT_SECONDS="
                + Math.min(60, testcaseProcessTimeoutSeconds));
        command.addAll(mounts);
        command.add(image);
        command.add("./run_grader.sh");   // chạy chấm tường minh — không phụ thuộc CMD baked trong ảnh

        ProcessBuilder pb = new ProcessBuilder(command);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new GradingDiagnosticException(
                    "DOCKER_START_FAILED",
                    GradingDiagnosticException.Origin.ENVIRONMENT,
                    "CONTAINER_START",
                    true,
                    "Không khởi động được container chấm: " + e.getMessage(), e);
        }

        // Ghi sổ NGAY sau khi container khởi động: từ đây tới lúc chạy xong là khoảng thời gian
        // duy nhất nút "Dừng chấm" có thể can thiệp.
        RunningContainer handle = new RunningContainer(containerName, process);
        registerRunning(batchId, handle);
        try {
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            Thread t1 = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) stdout.append(line).append("\n");
                } catch (IOException ignored) {
                }
            });
            Thread t2 = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) stderr.append(line).append("\n");
                } catch (IOException ignored) {
                }
            });
            t1.start();
            t2.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                removeContainerQuietly(containerName);
                throw new GradingDiagnosticException(
                        "CONTAINER_WATCHDOG_TIMEOUT",
                        GradingDiagnosticException.Origin.ENVIRONMENT,
                        "CONTAINER_WATCHDOG",
                        true,
                        "Container chấm vượt quá watchdog " + timeoutSeconds
                                + " giây sau khi timeout nội bộ của testcase lẽ ra đã kết thúc. "
                                + "Đây là lỗi môi trường/runner; không quy thành 0 điểm sinh viên.");
            }

            t1.join();
            t2.join();

            String output = stdout.toString();
            if (process.exitValue() != 0 && !output.contains("GRADE_RESULT_START")) {
                String compileLog = (stderr + "\n" + output).trim();
                throw GradingCompileDiagnostic.classify(compileLog);
            }

            String resultJson = parseGraderOutput(output);
            // 0/0 (không test nào chạy) → coi là LỖI, không phải DONE; chạy probe để lấy LÝ DO THẬT.
            ensureTestsRan(resultJson, output, mounts, image);
            return resultJson;
        } finally {
            unregisterRunning(batchId, handle);
        }
    }

    // ── Dừng chấm giữa chừng ─────────────────────────────────────
    public void markCancelled(String batchId) {
        if (batchId != null) cancelledBatches.add(batchId);
    }

    public void clearCancelled(String batchId) {
        cancelledBatches.remove(batchId);
    }

    public boolean isCancelled(String batchId) {
        return batchId != null && cancelledBatches.contains(batchId);
    }

    private void registerRunning(String batchId, RunningContainer handle) {
        if (batchId == null) return;
        runningByBatch.computeIfAbsent(batchId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                .add(handle);
    }

    private void unregisterRunning(String batchId, RunningContainer handle) {
        if (batchId == null) return;
        // computeIfPresent để xóa luôn key rỗng — sổ không phình theo số phiên đã chấm.
        runningByBatch.computeIfPresent(batchId, (k, set) -> {
            set.remove(handle);
            return set.isEmpty() ? null : set;
        });
    }

    /**
     * Giết mọi container đang chấm của 1 phiên. Trả về số container đã giết.
     *
     * <p>Phải làm CẢ HAI: {@code destroyForcibly()} chỉ giết tiến trình client `docker run` trên
     * host, container trong daemon vẫn chạy tiếp — nên cần thêm `docker rm -f` theo tên.
     */
    public int killRunning(String batchId) {
        Set<RunningContainer> running = runningByBatch.get(batchId);
        if (running == null || running.isEmpty()) return 0;
        int killed = 0;
        for (RunningContainer rc : List.copyOf(running)) {
            try {
                rc.process().destroyForcibly();
                removeContainerQuietly(rc.name());
                killed++;
            } catch (Exception e) {
                log.warn("Không dừng được container {}: {}", rc.name(), e.getMessage());
            }
        }
        return killed;
    }

    /**
     * Bài Flutter có thể dùng Image.asset('assets/...'). Trước đây chỉ mount lib/ nên asset có
     * trong ZIP vẫn bị mất trong container. Pubspec runtime cần khai assets/ để flutter_test load được.
     */
    private String prepareRuntimePubspec(Path tempDir, boolean includeRootAssets,
                                         boolean includeLibAssets, String packageName) throws Exception {
        Path base = locateTemplateDir().resolve("pubspec.base.yaml");
        if (!Files.exists(base)) return null;
        String content = Files.readString(base, StandardCharsets.UTF_8);
        // Image nền chạy `--no-pub`, nên tên package phải khớp package_graph đã cache.
        content = content.replaceFirst("(?m)^name:\\s*[^\\r\\n]+", "name: exam_project");
        if (includeRootAssets || includeLibAssets) {
            content = ensureAssetsDeclared(content, includeRootAssets, includeLibAssets);
        }
        else content = removeAssetsDeclared(content);
        Path out = tempDir.resolve("pubspec.yaml");
        Files.writeString(out, content, StandardCharsets.UTF_8);
        return out.toAbsolutePath().toString();
    }

    private String ensureAssetsDeclared(String pubspec, boolean includeRootAssets, boolean includeLibAssets) {
        String nl = pubspec.contains("\r\n") ? "\r\n" : "\n";
        String normalized = removeAssetsDeclared(pubspec);
        StringBuilder assetBlock = new StringBuilder("  assets:").append(nl);
        if (includeRootAssets) assetBlock.append("    - assets/").append(nl);
        if (includeLibAssets) assetBlock.append("    - lib/assets/").append(nl);
        String material = "  uses-material-design: true";
        int materialAt = normalized.indexOf(material);
        if (materialAt >= 0) {
            int lineEnd = normalized.indexOf(nl, materialAt);
            if (lineEnd < 0) lineEnd = normalized.length();
            return normalized.substring(0, lineEnd + nl.length())
                    + assetBlock
                    + normalized.substring(lineEnd + nl.length());
        }
        int flutterAt = normalized.indexOf("flutter:");
        if (flutterAt >= 0) {
            int lineEnd = normalized.indexOf(nl, flutterAt);
            if (lineEnd < 0) lineEnd = normalized.length();
            return normalized.substring(0, lineEnd + nl.length())
                    + "  uses-material-design: true" + nl + assetBlock
                    + normalized.substring(lineEnd + nl.length());
        }
        return normalized.stripTrailing() + nl + nl + "flutter:" + nl
                + "  uses-material-design: true" + nl + assetBlock;
    }

    private String removeAssetsDeclared(String pubspec) {
        String nl = pubspec.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = pubspec.split("\\R", -1);
        StringBuilder out = new StringBuilder();
        boolean skippingAssets = false;
        for (String line : lines) {
            if (!skippingAssets && line.matches("^\\s{2}assets:\\s*$")) {
                skippingAssets = true;
                continue;
            }
            if (skippingAssets) {
                if (line.matches("^\\s{4}-\\s+.*$") || line.isBlank()) continue;
                skippingAssets = false;
            }
            out.append(line).append(nl);
        }
        return out.toString().stripTrailing() + nl;
    }

    /**
     * Nếu không có testcase nào chạy (tongSoTest == 0) thì gần như chắc chắn bài nộp KHÔNG
     * biên dịch được với testcase (thiếu file/sai tên class) → ném lỗi RÕ RÀNG thay vì để
     * hệ thống hiểu nhầm là "DONE 0 điểm".
     */
    private void ensureTestsRan(String resultJson, String output, List<String> mounts, String image) {
        try {
            JsonNode n = mapper.readTree(resultJson);
            if (n.path("tongSoTest").asInt(-1) == 0) {
                throw new RuntimeException(diagnoseZeroTests(mounts, image, output));
            }
        } catch (RuntimeException re) {
            throw re;                       // ném tiếp lỗi 0/0
        } catch (Exception ignored) {       // JSON lạ → bỏ qua, giữ luồng cũ
        }
    }

    /**
     * Dựng thông điệp lỗi RÕ RÀNG cho trường hợp 0/0. `grader.dart` chạy `flutter test` rồi chỉ đọc
     * stdout nên lý do biên dịch thật (ở stderr của tiến trình con) bị nuốt mất → ta chạy lại
     * `flutter test test/exam_test.dart` (gộp stderr) để moi đúng file/class/hàm sai.
     */
    private String diagnoseZeroTests(List<String> mounts, String image, String mainOutput) {
        String generic = "Bài nộp không chạy được testcase nào (0/0) — thường do thiếu file/sai tên "
                + "class so với đề, hoặc lỗi biên dịch.";
        String probe = runCompileProbe(mounts, image);
        String scan  = probe.isEmpty() ? mainOutput : probe;
        String hint  = extractCompileErrors(scan);

        if (!hint.isEmpty()) {
            return "Bài nộp KHÔNG biên dịch được với đề (thiếu/sai tên file trong lib/, sai tên "
                 + "class/hàm, hoặc lỗi cú pháp). Chi tiết: " + hint;
        }
        // Probe compile sạch & chạy được test nhưng grader tính 0 → tên test ≠ key skills_matrix.json.
        if (probeRanTests(probe)) {
            return "Test biên dịch & chạy được nhưng grader không map được testcase nào với rubric. "
                 + "Thường do Flutter trả tên dạng 'Tên group TC_...' còn skills_matrix.json dùng 'TC_...'; "
                 + "hãy upload lại testcase để backend tự vá grader.dart, hoặc dùng grader.dart có rubricKeyFor(...).";
        }
        return generic;
    }

    /** Chạy lại flutter test (gộp stderr) chỉ để CHẨN ĐOÁN khi đã 0/0 — không tính điểm. */
    private String runCompileProbe(List<String> mounts, String image) {
        String containerName = dockerContainerName("grader-probe", "compile", "zero-tests");
        try {
            List<String> cmd = new ArrayList<>(List.of(
                    "docker", "run", "--name", containerName, "--rm",
                    "--memory", runtimeSettings.memoryArg(), "--cpus", runtimeSettings.cpusArg()));
            cmd.addAll(mounts);
            cmd.add("--entrypoint");
            cmd.add("bash");
            cmd.add(image);
            cmd.add("-c");
            cmd.add("cd /app && flutter test --no-pub test/exam_test.dart 2>&1");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append("\n");
            }
            if (!p.waitFor(90, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                removeContainerQuietly(containerName);
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Probe chẩn đoán 0/0 lỗi: {}", e.getMessage());
            return "";
        }
    }

    /** Output của probe cho thấy test ĐÃ chạy (pass/fail) chứ không phải lỗi biên dịch. */
    private boolean probeRanTests(String probe) {
        if (probe == null || probe.isEmpty()) return false;
        return probe.contains("All tests passed")
            || probe.contains("Some tests failed")
            || probe.matches("(?s).*\\+\\d+.*")     // "+5: ..." dòng tiến độ của flutter test
            || probe.matches("(?s).*-\\d+:.*");
    }

    /** Trích vài dòng lỗi biên dịch của Dart/Flutter từ output để báo cho người chấm. */
    private String extractCompileErrors(String output) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String line : output.split("\n")) {
            String t = line.trim();
            if (t.startsWith("{")) continue;   // bỏ dòng JSON machine
            if (t.contains("Error:") || t.contains("Error when reading")
                    || t.contains("Compilation failed") || t.contains("Failed to load")
                    || t.contains("Undefined name") || t.contains("The argument type")
                    || t.contains("isn't defined") || t.contains("not found")) {
                if (count > 0) sb.append(" | ");
                sb.append(t.length() > 200 ? t.substring(0, 200) : t);
                if (++count >= 4) break;
            }
        }
        return sb.toString();
    }

    // ── Parse output ─────────────────────────────────────────────
    private String parseGraderOutput(String output) {
        final String START = "--- GRADE_RESULT_START ---", END = "--- GRADE_RESULT_END ---";
        int s = output.indexOf(START);
        int e = output.indexOf(END);
        if (s != -1 && e != -1 && e > s) {
            String json = output.substring(s + START.length(), e).trim();
            try {
                mapper.readTree(json);             // validate JSON kết quả chấm
                return json;
            } catch (Exception ex) {
                // CÓ marker nhưng JSON HỎNG → KHÔNG đoán điểm bằng trọng số ĐỀU (sẽ ra điểm SAI mà
                // vẫn lưu DONE, không ai phát hiện). Ném lỗi để bài vào ERROR cho GV chấm lại.
                throw new RuntimeException("grader.dart xuất JSON kết quả HỎNG — không đọc được điểm chính xác.");
            }
        }
        // Hoàn toàn KHÔNG có marker (đề rất cũ / grader lỗi nặng) → mới dùng cơ chế ước lượng cũ.
        return fallbackParse(output);
    }

    private String fallbackParse(String raw) {
        List<TestCaseResult> details = new ArrayList<>();
        Map<Integer, String> names = new HashMap<>();
        Map<Integer, String> errors = new HashMap<>();
        int pass = 0, fail = 0;
        double earned = 0, max = 0;

        for (String line : raw.split("\n")) {
            if (!line.startsWith("{")) continue;
            try {
                JsonNode n = mapper.readTree(line);
                String type = n.path("type").asText();
                if ("testStart".equals(type))
                    names.put(n.path("test").path("id").asInt(), n.path("test").path("name").asText());
                if ("error".equals(type))
                    errors.put(n.path("testID").asInt(), n.path("error").asText());
                if ("testDone".equals(type)) {
                    int id = n.path("testID").asInt();
                    String nm = names.getOrDefault(id, "");
                    String res = n.path("result").asText();
                    if (nm.isBlank() || nm.startsWith("loading ")) continue;
                    max += 1;
                    if ("success".equals(res)) {
                        pass++;
                        earned++;
                        details.add(new TestCaseResult(nm, "PASS", "+1"));
                    } else {
                        fail++;
                        details.add(new TestCaseResult(nm, "FAILED", errors.getOrDefault(id, "Sai kết quả")));
                    }
                }
            } catch (Exception ignored) {
            }
        }

        double score = max > 0 ? (earned / max) * 10 : 0;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("diem", Math.round(score * 100.0) / 100.0);
        r.put("soTestPass", pass);
        r.put("tongSoTest", pass + fail);
        r.put("chiTiet", details);
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(r);
        } catch (Exception e) {
            return "{\"error\":\"parse error\"}";
        }
    }

    // ── Helpers ──────────────────────────────────────────────────
    private void unzip(File zip, File dest) throws Exception {
        byte[] buf = new byte[64 * 1024];
        String destCanonical = dest.getCanonicalPath() + File.separator;
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File f = safeFile(dest, destCanonical, entry);
                if (entry.isDirectory()) {
                    f.mkdirs();
                } else {
                    f.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(f)) {
                        int len;
                        while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private File safeFile(File dest, String destCanonical, ZipEntry entry) throws Exception {
        File f = new File(dest, entry.getName());
        if (!f.getCanonicalPath().startsWith(destCanonical))
            throw new Exception("Zip Slip: " + entry.getName());
        return f;
    }

    private void copyDirectory(Path src, Path dst) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : walk.toList()) {
                Path target = dst.resolve(src.relativize(p));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void deleteDirectory(File dir) throws Exception {
        if (!dir.exists()) return;
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            walk.sorted(Comparator.reverseOrder())
                    .map(Path::toFile).forEach(File::delete);
        }
    }

    private Path locateTemplateDir() {
        Path configured = Path.of(templateDir);
        String name = configured.getFileName() != null
                ? configured.getFileName().toString() : "grader-base";

        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && p != null; i++) {
            for (Path c : new Path[]{ p.resolve(configured), p.resolve(name) }) {
                if (Files.exists(c.resolve("Dockerfile.base")))
                    return c.toAbsolutePath().normalize();
            }
            p = p.getParent();
        }
        return configured.toAbsolutePath().normalize();
    }

    private String toDockerPath(String path) {
        // Chuyển đường dẫn Windows C:\foo\bar → /c/foo/bar cho Docker
        if (path.length() >= 2 && path.charAt(1) == ':') {
            return "/" + Character.toLowerCase(path.charAt(0))
                    + path.substring(2).replace("\\", "/");
        }
        // Linux/Mac giữ nguyên
        return path.replace("\\", "/");
    }

    private String dockerContainerName(String prefix, String examId, String studentId) {
        String raw = prefix + "-" + examId + "-" + studentId + "-"
                + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-");
    }

    private void removeContainerQuietly(String containerName) {
        if (containerName == null || containerName.isBlank()) return;
        try {
            Process p = new ProcessBuilder("docker", "rm", "-f", containerName).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (Exception e) {
            log.warn("Không xóa được container Docker {} sau timeout: {}", containerName, e.getMessage());
        }
    }
}
