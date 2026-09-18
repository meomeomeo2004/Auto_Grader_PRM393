package com.example.grader.service;

import com.example.grader.entity.BehaviorArtifact;
import com.example.grader.entity.BehaviorArtifactType;
import com.example.grader.entity.BehaviorSuite;
import com.example.grader.repository.BehaviorArtifactRepository;
import com.example.grader.repository.BehaviorSuiteRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

/** Lưu, version và kiểm tra chéo bảy artifact của một behavior suite. */
@Service
public class BehaviorArtifactService {

    private static final long MAX_ARTIFACT_BYTES = 500L * 1024 * 1024;
    private static final Set<String> TESTCASE_FIELDS = Set.of(
            "stage", "attribute", "attributeValue", "valueType", "value", "action", "browser");

    @Value("${grader.behavior-artifacts-dir:behavior-artifacts}")
    private String artifactsDir;

    private final BehaviorArtifactRepository artifacts;
    private final BehaviorSuiteRepository suites;
    private final ExamService exams;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public BehaviorArtifactService(BehaviorArtifactRepository artifacts,
                                   BehaviorSuiteRepository suites,
                                   ExamService exams) {
        this.artifacts = artifacts;
        this.suites = suites;
        this.exams = exams;
    }

    @Transactional
    public Map<String, Object> upload(String suiteId,
                                      BehaviorArtifactType type,
                                      MultipartFile file,
                                      String metadataJson) {
        requireSuite(suiteId);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Artifact không được để trống");
        if (file.getSize() > MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("Artifact vượt quá giới hạn 500 MB");
        }
        String originalName = safeFileName(file.getOriginalFilename(), type);
        validateExtension(type, originalName);
        Path temp = null;
        try {
            Path typeDir = artifactRoot().resolve(suiteId).resolve(type.name().toLowerCase(Locale.ROOT)).normalize();
            if (!typeDir.startsWith(artifactRoot())) throw new IllegalStateException("Đường dẫn artifact không an toàn");
            Files.createDirectories(typeDir);
            temp = Files.createTempFile(typeDir, ".upload-", ".tmp");
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            validateContent(suiteId, type, temp);
            int version = (int) artifacts.countBySuiteIdAndArtifactType(suiteId, type) + 1;
            String storedName = String.format(Locale.ROOT, "v%03d_%s", version, originalName);
            Path target = typeDir.resolve(storedName).normalize();
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            temp = null;

            List<BehaviorArtifact> active = artifacts.findBySuiteIdAndArtifactTypeAndActiveTrue(suiteId, type);
            active.forEach(row -> row.setActive(false));
            artifacts.saveAll(active);

            BehaviorArtifact artifact = new BehaviorArtifact();
            artifact.setSuiteId(suiteId);
            artifact.setArtifactType(type);
            artifact.setVersion(version);
            artifact.setOriginalName(originalName);
            artifact.setStoragePath(target.toAbsolutePath().toString());
            artifact.setSha256(sha256(target));
            artifact.setSizeBytes(Files.size(target));
            artifact.setContentType(file.getContentType());
            artifact.setMetadataJson(normalizeMetadata(metadataJson));
            artifacts.save(artifact);
            return view(artifact);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Không lưu được artifact " + type + ": " + e.getMessage(), e);
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (Exception ignored) {}
        }
    }

    @Transactional
    public Map<String, Object> writeGenerated(String suiteId,
                                              BehaviorArtifactType type,
                                              String fileName,
                                              byte[] content,
                                              Map<String, Object> metadata) {
        return upload(suiteId, type, new ByteArrayMultipartFile(fileName, content), json(metadata));
    }

    /**
     * Gỡ cờ active của mọi bản thuộc một loại artifact.
     *
     * <p>Dùng khi thứ sinh ra artifact đó không còn tồn tại nữa — ví dụ xoá scenario cuối cùng
     * thì không còn bước nào để định nghĩa testcase. Giữ bản cũ đang active nghĩa là để một file
     * mô tả scenario ĐÃ XOÁ nằm chờ publish.
     */
    @Transactional
    public void deactivateAll(String suiteId, BehaviorArtifactType type) {
        List<BehaviorArtifact> active = artifacts.findBySuiteIdAndArtifactTypeAndActiveTrue(suiteId, type);
        if (active.isEmpty()) return;
        active.forEach(row -> row.setActive(false));
        artifacts.saveAll(active);
    }

    /**
     * Version một artifact do runner sinh mà không nạp toàn bộ file vào heap. Output DB
     * có thể lớn nên không được chuyển qua byte[] như các JSON nhỏ.
     */
    @Transactional
    public Map<String, Object> writeGeneratedFile(String suiteId,
                                                  BehaviorArtifactType type,
                                                  String fileName,
                                                  Path source,
                                                  Map<String, Object> metadata) {
        requireSuite(suiteId);
        Path normalizedSource = source == null ? null : source.toAbsolutePath().normalize();
        if (normalizedSource == null || !Files.isRegularFile(normalizedSource)) {
            throw new IllegalArgumentException("Artifact sinh tự động không tồn tại: " + source);
        }
        try {
            long size = Files.size(normalizedSource);
            if (size <= 0 || size > MAX_ARTIFACT_BYTES) {
                throw new IllegalArgumentException("Artifact sinh tự động rỗng hoặc vượt quá 500 MB");
            }
            String originalName = safeFileName(fileName, type);
            validateExtension(type, originalName);
            validateContent(suiteId, type, normalizedSource);

            Path typeDir = artifactRoot().resolve(suiteId).resolve(type.name().toLowerCase(Locale.ROOT)).normalize();
            if (!typeDir.startsWith(artifactRoot())) {
                throw new IllegalStateException("Đường dẫn artifact không an toàn");
            }
            Files.createDirectories(typeDir);
            int version = (int) artifacts.countBySuiteIdAndArtifactType(suiteId, type) + 1;
            Path target = typeDir.resolve(String.format(Locale.ROOT, "v%03d_%s", version, originalName)).normalize();
            Path staging = Files.createTempFile(typeDir, ".generated-", ".tmp");
            try {
                Files.copy(normalizedSource, staging, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(staging);
            }

            List<BehaviorArtifact> active = artifacts.findBySuiteIdAndArtifactTypeAndActiveTrue(suiteId, type);
            active.forEach(row -> row.setActive(false));
            artifacts.saveAll(active);

            BehaviorArtifact artifact = new BehaviorArtifact();
            artifact.setSuiteId(suiteId);
            artifact.setArtifactType(type);
            artifact.setVersion(version);
            artifact.setOriginalName(originalName);
            artifact.setStoragePath(target.toAbsolutePath().toString());
            artifact.setSha256(sha256(target));
            artifact.setSizeBytes(Files.size(target));
            artifact.setContentType("application/octet-stream");
            artifact.setMetadataJson(json(metadata));
            artifacts.save(artifact);
            return view(artifact);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Không lưu được artifact sinh tự động " + type + ": " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> list(String suiteId) {
        requireSuite(suiteId);
        return artifacts.findBySuiteIdOrderByArtifactTypeAscVersionDesc(suiteId).stream()
                .map(this::view).toList();
    }

    /** Xóa cả metadata và thư mục artifact của một suite, không chạm suite khác. */
    @Transactional
    public void deleteSuiteArtifacts(String suiteId) {
        requireSuite(suiteId);
        Path root = artifactRoot();
        Path target = root.resolve(suiteId).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalStateException("Đường dẫn xóa artifact không an toàn");
        }
        try {
            if (Files.exists(target)) {
                try (var paths = Files.walk(target)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
            artifacts.deleteBySuiteId(suiteId);
        } catch (Exception e) {
            throw new IllegalStateException("Không xóa được artifact của bộ chấm: " + e.getMessage(), e);
        }
    }

    public Resource resource(String suiteId, BehaviorArtifactType type) {
        BehaviorArtifact artifact = active(suiteId, type);
        try {
            Resource resource = new UrlResource(Path.of(artifact.getStoragePath()).toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new IllegalStateException("File artifact không còn tồn tại trên đĩa");
            }
            return resource;
        } catch (Exception e) {
            throw new IllegalStateException("Không đọc được artifact: " + e.getMessage(), e);
        }
    }

    public BehaviorArtifact active(String suiteId, BehaviorArtifactType type) {
        requireSuite(suiteId);
        return artifacts.findFirstBySuiteIdAndArtifactTypeAndActiveTrueOrderByVersionDesc(suiteId, type)
                .orElseThrow(() -> new IllegalStateException("Thiếu artifact bắt buộc: " + type));
    }

    public Optional<BehaviorArtifact> activeOptional(String suiteId, BehaviorArtifactType type) {
        requireSuite(suiteId);
        return artifacts.findFirstBySuiteIdAndArtifactTypeAndActiveTrueOrderByVersionDesc(suiteId, type);
    }

    public Map<String, Object> readiness(String suiteId) {
        requireSuite(suiteId);
        Map<String, Object> status = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (BehaviorArtifactType type : BehaviorArtifactType.values()) {
            // STUDENT_DATABASE da BO HAN khoi quy trinh: engine chi doc hidden_fixture_path,
            // khong bao gio mo student.db, con viec canh cau truc bang da chuyen sang khau kiem
            // dong bo khung phat. Khong bao cao trang thai nua de man soan de khong con o nao
            // khien nguoi ra de tuong minh thieu buoc. Hang cu trong CSDL van con, chi la khong
            // ai nhin toi — xoa hang thi bo de cu khong doc len duoc.
            if (type == BehaviorArtifactType.STUDENT_DATABASE) continue;
            Optional<BehaviorArtifact> row = artifacts
                    .findFirstBySuiteIdAndArtifactTypeAndActiveTrueOrderByVersionDesc(suiteId, type);
            status.put(type.name(), row.map(this::view).orElse(null));
            if (row.isEmpty()) missing.add(type.name());
        }
        return Map.of(
                "suite_id", suiteId,
                "ready", missing.isEmpty(),
                "missing", missing,
                "artifacts", status);
    }

    public void requireComplete(String suiteId) {
        Map<String, Object> readiness = readiness(suiteId);
        if (!Boolean.TRUE.equals(readiness.get("ready"))) {
            throw new IllegalStateException("Bộ chấm chưa đủ 6 artifact: " + readiness.get("missing"));
        }
        BehaviorArtifact hiddenDb = active(suiteId, BehaviorArtifactType.HIDDEN_DATABASE);
        BehaviorArtifact outputDb = active(suiteId, BehaviorArtifactType.OUTPUT_DATABASE);
        // Output do chinh Golden sinh ra khi replay tren hidden: hai file phai cung cau truc,
        // lech nghia la Golden doi schema giua chung.
        compareSqliteSchema(Path.of(hiddenDb.getStoragePath()), Path.of(outputDb.getStoragePath()));

        BehaviorSuite suite = suites.findById(suiteId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy behavior suite: " + suiteId));
        try {
            JsonNode contract = mapper.readTree(suite.getDatabaseContractJson());
            if (!contract.path("enabled").asBoolean(false)) {
                throw new IllegalStateException("database_contract.enabled phải là true");
            }
            String driver = contract.path("driver").asText("sqlite");
            if (!"sqlite".equalsIgnoreCase(driver)) {
                throw new IllegalStateException("Record–Replay hiện chỉ hỗ trợ database_contract.driver=sqlite");
            }
            if (contract.path("database_name").asText().isBlank()
                    && contract.path("path").asText().isBlank()) {
                throw new IllegalStateException("database_contract phải có database_name hoặc path");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("database_contract không phải JSON hợp lệ", e);
        }
    }

    /** Các đầu vào tối thiểu phải ổn định trước khi ghi một luồng Golden. */
    public void requireRecordingInputs(String suiteId) {
        // Chi con hai dau vao that su can de ghi hinh: database an va Golden.
        active(suiteId, BehaviorArtifactType.HIDDEN_DATABASE);
        active(suiteId, BehaviorArtifactType.GOLDEN_SOLUTION);
    }

    /**
     * Suy ra oracle DB từ trạng thái trước (hidden) và trạng thái sau khi thao tác Golden
     * (output). Mỗi thay đổi trở thành một checkpoint độc lập để không mất toàn bộ điểm
     * CRUD chỉ vì một assertion gộp bị fail.
     */
    public List<Map<String, Object>> databaseDiffCheckpoints(String suiteId) {
        BehaviorArtifact before = active(suiteId, BehaviorArtifactType.HIDDEN_DATABASE);
        BehaviorArtifact after = active(suiteId, BehaviorArtifactType.OUTPUT_DATABASE);
        compareSqliteSchema(Path.of(before.getStoragePath()), Path.of(after.getStoragePath()));
        return sqliteDiff(Path.of(before.getStoragePath()), Path.of(after.getStoragePath()), ignoredColumns(suiteId));
    }

    public Map<String, Object> activeManifest(String suiteId) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (BehaviorArtifactType type : BehaviorArtifactType.values()) {
            artifacts.findFirstBySuiteIdAndArtifactTypeAndActiveTrueOrderByVersionDesc(suiteId, type)
                    .ifPresent(row -> result.put(type.name(), Map.of(
                            "version", row.getVersion(),
                            "file_name", row.getOriginalName(),
                            "sha256", row.getSha256(),
                            "size_bytes", row.getSizeBytes())));
        }
        return result;
    }

    /**
     * Đối chiếu tên database mà mã Golden thực sự mở với hợp đồng của bộ chấm.
     *
     * Điểm hẹn dữ liệu chỉ có MỘT dây an toàn là cái tên này; lệch một chữ thì replay
     * boot database trống và output bị chụp từ file không ai ghi — cả hai đều hỏng
     * IM LẶNG (đã xảy ra thật 29/8: hợp đồng ghi hidden.db, golden mở app.db). Chặn
     * ngay tại cửa upload rẻ hơn vô hạn so với truy vết ba ngày sau ở kịch bản xóa.
     *
     * Cách quét: gom mọi chuỗi '*.db' trong lib/**.dart của ZIP, bỏ chuỗi có '/'
     * (đường dẫn assets như 'assets/hidden.db' là nguồn NẠP, không phải file MỞ).
     * Phải tìm được đúng một tên: giao diện không còn cho khai hoặc sửa bằng tay.
     */
    public void crossCheckGoldenDatabaseName(String suiteId, Path goldenZip) throws Exception {
        Set<String> found = scanDartDatabaseNames(goldenZip);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("Golden không tìm thấy tên file .db trong mã lib/*.dart. "
                    + "Hãy khai rõ một tên database (ví dụ 'app.db'), chỉnh Golden rồi tải ZIP lại.");
        }
        if (found.size() != 1) {
            throw new IllegalArgumentException("Golden tìm thấy nhiều tên file .db: " + found + ". "
                    + "Cần đúng một tên database; hãy chỉnh Golden rồi tải ZIP lại.");
        }
        // ZIP là nguồn sự thật; bản Golden mới có thể đổi tên, không giữ tên cũ để ghi nhầm DB.
        ghiTenDatabase(suiteId, found.iterator().next());
    }

    /** Khóa tên đã dò cả ở API; thay tên chỉ bằng cách tải ZIP Golden mới. */
    public void crossCheckDeclaredDatabaseName(String suiteId, String declared) {
        artifacts.findFirstBySuiteIdAndArtifactTypeAndActiveTrueOrderByVersionDesc(
                        suiteId, BehaviorArtifactType.GOLDEN_SOLUTION)
                .ifPresent(golden -> {
                    try {
                        Set<String> found = scanDartDatabaseNames(Path.of(golden.getStoragePath()));
                        if (found.size() != 1 || declared == null || !found.contains(declared)) {
                            throw new IllegalArgumentException(
                                    "Tên database được tự động nhận diện từ Golden " + found
                                    + ", không thể sửa hoặc xóa bằng tay. Hãy chỉnh Golden rồi tải ZIP lại.");
                        }
                    } catch (IllegalArgumentException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException("Không đọc được Golden ZIP để đối chiếu: " + e.getMessage(), e);
                    }
                });
    }

    /** Giữ các tùy chọn chấm đã lưu, chỉ thay tên database bằng tên dò từ Golden. */
    private void ghiTenDatabase(String suiteId, String ten) {
        suites.findById(suiteId).ifPresent(suite -> {
            try {
                com.fasterxml.jackson.databind.node.ObjectNode contract =
                        suite.getDatabaseContractJson() == null || suite.getDatabaseContractJson().isBlank()
                                ? mapper.createObjectNode()
                                : (com.fasterxml.jackson.databind.node.ObjectNode)
                                        mapper.readTree(suite.getDatabaseContractJson());
                contract.put("enabled", true);
                contract.put("driver", "sqlite");
                contract.put("database_name", ten);
                // Engine ưu tiên path: bỏ alias cũ để tên vừa dò thật sự được dùng khi replay.
                contract.remove("path");
                contract.remove("name");
                suite.setDatabaseContractJson(mapper.writeValueAsString(contract));
                suites.save(suite);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Không ghi được tên database đọc từ Golden: " + e.getMessage(), e);
            }
        });
    }

    private static final java.util.regex.Pattern VALUE_KEY_PATTERN = java.util.regex.Pattern.compile(
            "\\bValueKey(?:<[^>]*>)?\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)|\\bKey\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");
    /** Tên constructor Widget ngay trước 1 key — kể cả constructor đặt tên (`ListView.builder(`). */
    private static final java.util.regex.Pattern WIDGET_CTOR_PATTERN = java.util.regex.Pattern.compile(
            "([A-Z][A-Za-z0-9_]*)(?:\\.[a-zA-Z_][A-Za-z0-9_]*)?\\(");
    /** Khối comment `//` liền nhau ngay phía trên 1 vị trí trong source. */
    private static final java.util.regex.Pattern COMMENT_BLOCK_PATTERN = java.util.regex.Pattern.compile(
            "(?m)(?:^[ \\t]*//[^\\n]*\\n?)+");
    /**
     * Hàm/method Dart bao quanh (`_horizontalList() {`, `_openSheet() async {`...) — tín
     * hiệu KHÔNG cần golden author viết thêm gì (khác comment quy ước), vì hầu hết code đã
     * đặt tên hàm dựng widget có nghĩa sẵn theo thói quen bình thường.
     */
    private static final java.util.regex.Pattern METHOD_NAME_PATTERN = java.util.regex.Pattern.compile(
            "\\b([_a-z][A-Za-z0-9_]*)\\s*\\([^()]*\\)\\s*(?:async\\s*)?\\{");
    private static final Set<String> GENERIC_METHOD_NAMES = Set.of(
            "build", "main", "initState", "dispose", "createState", "didChangeDependencies",
            "didUpdateWidget", "setState", "call", "if", "for", "while", "switch");
    private static final int KEY_CONTEXT_WINDOW = 500;

    /**
     * Quét TĨNH source Golden tìm các `ValueKey('...')`/`Key('...')` literal — dùng để gợi ý
     * key cho giáo viên khi soạn tiêu chí Chương 7 (bố cục nâng cao), vì ValueKey không lộ ra
     * DOM/semantics của Flutter Web nên không quét được qua recorder như 4 tiêu chí kia.
     * Kèm theo 2 tín hiệu để phân biệt các key TRÔNG GIỐNG NHAU (vd "ch7.hlist" và
     * "ch7.vlist") khi người soạn testcase không phải người viết golden, không đọc được
     * source để đoán nghĩa:
     * - "widgetType": tên constructor Widget đứng ngay trước key (đoán bằng tìm ngược) — lọc
     *   gợi ý theo đúng loại widget mà từng tiêu chí Chương 7 cần (vd Stack cho "Stack xếp
     *   chồng").
     * - "hint": ưu tiên khối comment `//` liền kề ngay phía trên (nếu người viết golden có
     *   ghi chú kiểu "// SCROLL_DIRECTION: listKey=ch7.hlist, direction=horizontal" như quy
     *   ước sẵn có của repo); NẾU KHÔNG CÓ COMMENT NÀO (golden author không viết, trường hợp
     *   phổ biến hơn), rơi về tên hàm Dart bao quanh đã "người hoá" (vd `_verticalLazyList`
     *   → "Vertical Lazy List") — tín hiệu này gần như luôn có sẵn vì không đòi hỏi golden
     *   author làm thêm gì ngoài đặt tên hàm có nghĩa như thói quen viết code bình thường.
     *   Rỗng chỉ khi cả 2 nguồn đều không tìm được gì.
     */
    public List<Map<String, String>> scanGoldenValueKeys(String suiteId) {
        BehaviorArtifact golden = active(suiteId, BehaviorArtifactType.GOLDEN_SOLUTION);
        Map<String, String[]> found = new java.util.LinkedHashMap<>();
        try (ZipFile file = new ZipFile(Path.of(golden.getStoragePath()).toFile())) {
            var entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !entryName.endsWith(".dart") || !entryName.contains("lib/")) continue;
                String source = new String(file.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                java.util.regex.Matcher m = VALUE_KEY_PATTERN.matcher(source);
                while (m.find()) {
                    String name = m.group(1) != null ? m.group(1) : m.group(2);
                    if (name == null || name.isBlank()) continue;
                    String window = source.substring(Math.max(0, m.start() - KEY_CONTEXT_WINDOW), m.start());
                    String hint = hintIn(window);
                    if (hint.isBlank()) hint = enclosingMethodLabel(window);
                    found.putIfAbsent(name, new String[] {widgetTypeIn(window), hint});
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Không đọc được Golden ZIP để quét key: " + e.getMessage(), e);
        }
        List<Map<String, String>> result = new java.util.ArrayList<>();
        for (String key : new java.util.TreeSet<>(found.keySet())) {
            String[] info = found.get(key);
            result.add(Map.of("key", key, "widgetType", info[0], "hint", info[1]));
        }
        return result;
    }

    private String enclosingMethodLabel(String window) {
        java.util.regex.Matcher m = METHOD_NAME_PATTERN.matcher(window);
        String last = "";
        while (m.find()) {
            String name = m.group(1);
            if (!GENERIC_METHOD_NAMES.contains(name)) last = name;
        }
        return last.isEmpty() ? "" : humanizeIdentifier(last);
    }

    private String humanizeIdentifier(String identifier) {
        String trimmed = identifier.replaceFirst("^_+", "");
        if (trimmed.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isUpperCase(c) && i > 0) sb.append(' ');
            sb.append(i == 0 ? Character.toUpperCase(c) : c);
        }
        return sb.toString();
    }

    private String widgetTypeIn(String window) {
        java.util.regex.Matcher m = WIDGET_CTOR_PATTERN.matcher(window);
        String last = "";
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }

    private String hintIn(String window) {
        java.util.regex.Matcher m = COMMENT_BLOCK_PATTERN.matcher(window);
        String lastBlock = "";
        int lastEnd = -1;
        while (m.find()) {
            lastBlock = m.group();
            lastEnd = m.end();
        }
        if (lastBlock.isBlank()) return "";
        // Comment chỉ thật sự mô tả key đang xét nếu hàm/khối mà nó mở ra CHƯA ĐÓNG trước
        // khi tới key — dò cân bằng ngoặc {} từ ngay sau comment: nếu độ sâu tụt về đáy (một
        // hàm đã đóng trọn) rồi mới gặp key, nghĩa là comment đó thuộc hàm liền trước đã kết
        // thúc, trôi vào cửa sổ tìm ngược do tình cờ đủ gần — không phải của key đang xét.
        // (Bug thật gặp phải: 2 hàm ngắn liền nhau khiến comment của hàm 1 lại bị gán nhầm
        // cho key ở hàm 2 — phát hiện qua unit test, không phải suy diễn.)
        String gap = window.substring(lastEnd);
        int depth = 0;
        boolean opened = false;
        for (int i = 0; i < gap.length(); i++) {
            char c = gap.charAt(i);
            if (c == '{') { depth++; opened = true; }
            else if (c == '}') {
                depth--;
                if (opened && depth <= 0) return "";
            }
        }
        String joined = String.join(" ", lastBlock.lines()
                .map(line -> line.trim().replaceFirst("^//\\s*", ""))
                .filter(line -> !line.isBlank())
                .toList());
        return joined.length() > 160 ? joined.substring(0, 160) + "…" : joined;
    }

    private Set<String> scanDartDatabaseNames(Path zip) throws Exception {
        Set<String> found = new java.util.LinkedHashSet<>();
        java.util.regex.Pattern mau = java.util.regex.Pattern.compile("['\"]([-A-Za-z0-9_./]+[.]db)['\"]");
        // Giữ nguyên chuỗi nhưng bỏ comment: nhắc tên DB cũ trong chú thích không phải mở thêm DB.
        java.util.regex.Pattern token = java.util.regex.Pattern.compile(
                "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|//[^\\r\\n]*|/\\*[\\s\\S]*?\\*/");
        try (ZipFile file = new ZipFile(zip.toFile())) {
            var entries = file.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !entryName.endsWith(".dart") || !entryName.contains("lib/")) continue;
                String source = new String(file.getInputStream(entry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                java.util.regex.Matcher tokens = token.matcher(source);
                while (tokens.find()) {
                    java.util.regex.Matcher m = mau.matcher(tokens.group());
                    if (!m.matches()) continue;
                    String name = m.group(1);
                    if (!name.contains("/") && !name.contains("\\")) found.add(name);
                }
            }
        }
        return found;
    }

    private void validateContent(String suiteId, BehaviorArtifactType type, Path candidate) throws Exception {
        switch (type) {
            case STUDENT_DATABASE, HIDDEN_DATABASE, OUTPUT_DATABASE -> validateSqlite(candidate);
            case AUTOMATION_RECORD, TESTCASE_DEFINITION, GRADING_ENVIRONMENT -> validateJson(type, candidate);
            case GOLDEN_SOLUTION -> { validateZip(candidate); crossCheckGoldenDatabaseName(suiteId, candidate); }
        }
        // Ba phep so cau truc bang voi STUDENT_DATABASE da go bo cung luc voi chinh o artifact
        // do. Ca ba deu lay student.db lam moc, ma nay khong con ai tai len nen chung chi la ba
        // nhanh chet — giu lai thi bo de cu con mot student.db lech schema se chan luon ca lan
        // tai Database an moi len. Phep canh cau truc that nay nam o kiem dong bo khung phat,
        // noi no so tren MA NGUON cua khung phat voi Golden.
    }

    private void validateJson(BehaviorArtifactType type, Path file) throws Exception {
        JsonNode root = mapper.readTree(file.toFile());
        if (type == BehaviorArtifactType.GRADING_ENVIRONMENT) {
            if (!root.isObject()) throw new IllegalArgumentException("File môi trường chấm phải là JSON object");
            return;
        }
        JsonNode rows = root.isArray() ? root : root.path("steps");
        if (!rows.isArray() || rows.isEmpty()) {
            throw new IllegalArgumentException(type + " phải có mảng steps không rỗng");
        }
        int index = 0;
        for (JsonNode row : rows) {
            index++;
            if (!row.isObject()) throw new IllegalArgumentException("Step " + index + " không phải JSON object");
            for (String field : TESTCASE_FIELDS) {
                if (!row.has(field)) {
                    throw new IllegalArgumentException("Step " + index + " thiếu trường " + field);
                }
            }
            if (row.path("stage").asText().isBlank()
                    || row.path("action").asText().isBlank()
                    || row.path("browser").asText().isBlank()) {
                throw new IllegalArgumentException("Step " + index + " có Stage/Action/Browser rỗng");
            }
        }
    }

    private void validateSqlite(Path file) {
        try {
            byte[] signature = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
            byte[] header = new byte[signature.length];
            try (InputStream input = Files.newInputStream(file)) {
                if (input.read(header) != signature.length) {
                    throw new IllegalArgumentException("File SQLite quá ngắn");
                }
            }
            for (int i = 0; i < signature.length; i++) {
                if (header[i] != signature[i]) {
                    throw new IllegalArgumentException("File không đúng định dạng SQLite 3");
                }
            }
            sqliteSchema(file);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Không mở được SQLite: " + e.getMessage(), e);
        }
    }

    private void compareSqliteSchema(Path studentDb, Path hiddenDb) {
        Map<String, List<String>> publicSchema = sqliteSchema(studentDb);
        Map<String, List<String>> hiddenSchema = sqliteSchema(hiddenDb);
        if (!publicSchema.equals(hiddenSchema)) {
            throw new IllegalArgumentException(
                    "Database ẩn phải cùng cấu trúc với database phát sinh viên. Public="
                            + publicSchema + ", hidden=" + hiddenSchema);
        }
    }

    private Map<String, List<String>> sqliteSchema(Path file) {
        Map<String, List<String>> schema = new TreeMap<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet tables = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            List<String> names = new ArrayList<>();
            while (tables.next()) names.add(tables.getString(1));
            for (String table : names) {
                if (!table.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new IllegalArgumentException("Tên bảng SQLite không an toàn: " + table);
                }
                List<String> columns = new ArrayList<>();
                try (Statement pragma = connection.createStatement();
                     ResultSet rows = pragma.executeQuery("PRAGMA table_info(\"" + table + "\")")) {
                    while (rows.next()) {
                        columns.add(rows.getString("name") + ":" + rows.getString("type")
                                + ":notnull=" + rows.getInt("notnull") + ":pk=" + rows.getInt("pk"));
                    }
                }
                schema.put(table, columns);
            }
            try (Statement objects = connection.createStatement();
                 ResultSet rows = objects.executeQuery(
                         "SELECT type,name,tbl_name,sql FROM sqlite_master "
                                 + "WHERE type IN ('index','trigger','view') AND sql IS NOT NULL "
                                 + "ORDER BY type,name")) {
                while (rows.next()) {
                    String key = rows.getString("type") + ":" + rows.getString("name");
                    String sql = rows.getString("sql").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
                    schema.put(key, List.of("table=" + rows.getString("tbl_name"), "sql=" + sql));
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Không đọc được schema SQLite " + file.getFileName()
                    + ": " + e.getMessage(), e);
        }
        if (schema.isEmpty()) throw new IllegalArgumentException("SQLite không có bảng nghiệp vụ nào");
        return schema;
    }

    private List<Map<String, Object>> sqliteDiff(Path beforeFile,
                                                 Path afterFile,
                                                 Set<String> ignoredColumns) {
        List<Map<String, Object>> checkpoints = new ArrayList<>();
        try (Connection before = DriverManager.getConnection("jdbc:sqlite:" + beforeFile.toAbsolutePath());
             Connection after = DriverManager.getConnection("jdbc:sqlite:" + afterFile.toAbsolutePath())) {
            List<String> tables = businessTables(after);
            for (String table : tables) {
                List<String> primaryKeys = primaryKeyColumns(after, table);
                List<String> columns = tableColumns(after, table).stream()
                        .filter(column -> !ignoredColumns.contains(column) || primaryKeys.contains(column))
                        .toList();
                List<Map<String, Object>> beforeRows = tableRows(before, table, columns);
                List<Map<String, Object>> afterRows = tableRows(after, table, columns);
                Map<String, Map<String, Object>> oldByKey = indexRows(beforeRows, primaryKeys, columns);
                Map<String, Map<String, Object>> newByKey = indexRows(afterRows, primaryKeys, columns);

                for (Map.Entry<String, Map<String, Object>> entry : newByKey.entrySet()) {
                    Map<String, Object> oldRow = oldByKey.get(entry.getKey());
                    if (oldRow == null) {
                        checkpoints.add(databaseCheckpoint(table, "INSERT", entry.getValue(), false));
                    } else if (!oldRow.equals(entry.getValue())) {
                        checkpoints.add(databaseCheckpoint(table, "UPDATE", entry.getValue(), false));
                    }
                }
                for (Map.Entry<String, Map<String, Object>> entry : oldByKey.entrySet()) {
                    if (!newByKey.containsKey(entry.getKey())) {
                        Map<String, Object> identity = new LinkedHashMap<>();
                        List<String> identityColumns = primaryKeys.isEmpty() ? columns : primaryKeys;
                        for (String column : identityColumns) identity.put(column, entry.getValue().get(column));
                        checkpoints.add(databaseCheckpoint(table, "DELETE", identity, true));
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Không suy ra được thay đổi Output Database: " + e.getMessage(), e);
        }
        return checkpoints;
    }

    private Set<String> ignoredColumns(String suiteId) {
        try {
            BehaviorSuite suite = suites.findById(suiteId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy behavior suite: " + suiteId));
            JsonNode node = mapper.readTree(suite.getDatabaseContractJson()).path("ignore_columns");
            Set<String> result = new HashSet<>();
            if (node.isArray()) node.forEach(item -> result.add(item.asText()));
            return result;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Không đọc được ignore_columns trong database_contract", e);
        }
    }

    private List<String> businessTables(Connection connection) throws Exception {
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (rows.next()) tables.add(rows.getString(1));
        }
        return tables;
    }

    private List<String> tableColumns(Connection connection, String table) throws Exception {
        List<String> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(\"" + table + "\")")) {
            while (rows.next()) columns.add(rows.getString("name"));
        }
        return columns;
    }

    private List<String> primaryKeyColumns(Connection connection, String table) throws Exception {
        Map<Integer, String> ordered = new TreeMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(\"" + table + "\")")) {
            while (rows.next()) {
                int order = rows.getInt("pk");
                if (order > 0) ordered.put(order, rows.getString("name"));
            }
        }
        return new ArrayList<>(ordered.values());
    }

    private List<Map<String, Object>> tableRows(Connection connection,
                                                 String table,
                                                 List<String> columns) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM \"" + table + "\"")) {
            while (rows.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (String column : columns) {
                    Object value = rows.getObject(column);
                    row.put(column, value instanceof byte[] bytes
                            ? Base64.getEncoder().encodeToString(bytes) : value);
                }
                result.add(row);
            }
        }
        return result;
    }

    private Map<String, Map<String, Object>> indexRows(List<Map<String, Object>> rows,
                                                        List<String> primaryKeys,
                                                        List<String> columns) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        List<String> keys = primaryKeys.isEmpty() ? columns : primaryKeys;
        for (Map<String, Object> row : rows) {
            String identity = keys.stream().map(key -> key + "=" + String.valueOf(row.get(key)))
                    .collect(java.util.stream.Collectors.joining("\u001f"));
            result.put(identity, row);
        }
        return result;
    }

    private Map<String, Object> databaseCheckpoint(String table,
                                                    String operation,
                                                    Map<String, Object> row,
                                                    boolean absent) {
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("kind", "database_observation");
        checkpoint.put("checkpoint", true);
        checkpoint.put("scope", "database");
        checkpoint.put("stage", "ASSERT");
        checkpoint.put("attribute", "table");
        checkpoint.put("attributeValue", table);
        checkpoint.put("valueType", "json");
        checkpoint.put("value", row);
        checkpoint.put("action", "observe_database");
        checkpoint.put("browser", "sqlite");
        checkpoint.put("table", table);
        checkpoint.put("operation", operation);
        checkpoint.put("row", row);
        checkpoint.put("absent", absent);
        checkpoint.put("name", operation + " trên bảng " + table);
        checkpoint.put("weight", 1.0);
        return checkpoint;
    }

    private void validateZip(Path file) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file.toFile())) {
            if (!zip.entries().hasMoreElements()) throw new IllegalArgumentException("Golden Solution ZIP rỗng");
            boolean hasDartEntry = false;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../")) {
                    throw new IllegalArgumentException("Golden Solution ZIP chứa đường dẫn không an toàn");
                }
                if (name.equals("main.dart") || name.equals("lib/main.dart") || name.endsWith("/lib/main.dart")) {
                    hasDartEntry = true;
                }
            }
            if (!hasDartEntry) {
                throw new IllegalArgumentException("Golden Solution ZIP phải chứa dự án có lib/main.dart");
            }
            Map<String, Object> dependencies = PubspecDependencies.readZip(file);
            Set<String> inventory = exams.goiCoTrongAnhCham();
            // Docker tắt là chưa biết thư viện, theo cùng quy ước với bước lưu cấu hình.
            if (!inventory.isEmpty()) {
                List<String> missing = dependencies.keySet().stream()
                        .filter(name -> !name.equals("flutter") && !inventory.contains(name)).sorted().toList();
                if (!missing.isEmpty()) throw new PackageAvailabilityException(missing);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Golden Solution không phải ZIP hợp lệ", e);
        }
    }

    private void validateExtension(BehaviorArtifactType type, String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        boolean valid = switch (type) {
            case STUDENT_DATABASE, HIDDEN_DATABASE, OUTPUT_DATABASE ->
                    lower.endsWith(".db") || lower.endsWith(".sqlite") || lower.endsWith(".sqlite3");
            case GOLDEN_SOLUTION -> lower.endsWith(".zip");
            case AUTOMATION_RECORD, GRADING_ENVIRONMENT, TESTCASE_DEFINITION -> lower.endsWith(".json");
        };
        if (!valid) throw new IllegalArgumentException("Sai định dạng file cho " + type + ": " + fileName);
    }

    private String normalizeMetadata(String value) {
        if (value == null || value.isBlank()) return "{}";
        try {
            JsonNode node = mapper.readTree(value);
            if (!node.isObject()) throw new IllegalArgumentException("metadata phải là JSON object");
            return mapper.writeValueAsString(node);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("metadata JSON không hợp lệ", e);
        }
    }

    private String safeFileName(String value, BehaviorArtifactType type) {
        String fileName = value == null ? "" : Path.of(value).getFileName().toString();
        if (fileName.isBlank()) fileName = type.name().toLowerCase(Locale.ROOT) + ".bin";
        return fileName.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * Thư mục ảnh chuẩn (golden screenshot) của một suite — mỗi luồng một tệp
     * {@code <execution_code>.png}, GHI ĐÈ khi capture lại. Cố ý không đi qua bảng
     * artifact: writeGeneratedFile chỉ giữ MỘT bản active mỗi loại, còn ảnh chuẩn
     * cần một bản mới nhất cho TỪNG luồng.
     */
    public Path goldenScreenshotDir(String suiteId) {
        requireSuite(suiteId);
        Path dir = artifactRoot().resolve(suiteId).resolve("golden_screenshot").normalize();
        if (!dir.startsWith(artifactRoot())) throw new IllegalStateException("Đường dẫn artifact không an toàn");
        return dir;
    }

    private Path artifactRoot() {
        return Path.of(artifactsDir).toAbsolutePath().normalize();
    }

    private void requireSuite(String suiteId) {
        if (!suites.existsById(suiteId)) throw new IllegalArgumentException("Không tìm thấy behavior suite: " + suiteId);
    }

    private String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private Map<String, Object> view(BehaviorArtifact artifact) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", artifact.getId());
        out.put("suite_id", artifact.getSuiteId());
        out.put("type", artifact.getArtifactType().name());
        out.put("version", artifact.getVersion());
        out.put("file_name", artifact.getOriginalName());
        out.put("sha256", artifact.getSha256());
        out.put("size_bytes", artifact.getSizeBytes());
        out.put("content_type", artifact.getContentType());
        out.put("active", artifact.getActive());
        out.put("created_at", artifact.getCreatedAt() == null ? null : artifact.getCreatedAt().toString());
        try {
            out.put("metadata", mapper.readValue(artifact.getMetadataJson(), Map.class));
        } catch (Exception e) {
            out.put("metadata", Map.of());
        }
        return out;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Không mã hóa được metadata", e);
        }
    }

    /** MultipartFile nội bộ để version cả artifact sinh tự động bằng cùng một luồng kiểm tra. */
    private record ByteArrayMultipartFile(String fileName, byte[] bytes) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return fileName; }
        @Override public String getContentType() { return "application/octet-stream"; }
        @Override public boolean isEmpty() { return bytes == null || bytes.length == 0; }
        @Override public long getSize() { return bytes == null ? 0 : bytes.length; }
        @Override public byte[] getBytes() { return bytes; }
        @Override public InputStream getInputStream() { return new java.io.ByteArrayInputStream(bytes); }
        @Override public void transferTo(java.io.File dest) throws java.io.IOException {
            Files.write(dest.toPath(), bytes);
        }
    }
}
