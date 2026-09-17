package com.example.grader.service;

import com.example.grader.entity.BehaviorArtifact;
import com.example.grader.entity.BehaviorArtifactType;
import com.example.grader.entity.BehaviorSuite;
import com.example.grader.repository.BehaviorArtifactRepository;
import com.example.grader.repository.BehaviorSuiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BehaviorArtifactServiceTest {

    @TempDir
    Path tempDir;

    private final List<BehaviorArtifact> stored = new ArrayList<>();
    private BehaviorArtifactService service;

    @BeforeEach
    void setUp() {
        BehaviorArtifactRepository artifacts = mock(BehaviorArtifactRepository.class);
        BehaviorSuiteRepository suites = mock(BehaviorSuiteRepository.class);
        when(suites.existsById("suite-1")).thenReturn(true);
        BehaviorSuite suite = new BehaviorSuite();
        suite.setId("suite-1");
        suite.setDatabaseContractJson("{\"driver\":\"sqlite\",\"database_name\":\"grader.db\"}");
        when(suites.findById("suite-1")).thenReturn(Optional.of(suite));
        when(artifacts.countBySuiteIdAndArtifactType(any(), any())).thenAnswer(invocation -> {
            BehaviorArtifactType type = invocation.getArgument(1);
            return stored.stream().filter(row -> row.getArtifactType() == type).count();
        });
        when(artifacts.findBySuiteIdAndArtifactTypeAndActiveTrue(any(), any())).thenAnswer(invocation -> {
            BehaviorArtifactType type = invocation.getArgument(1);
            return stored.stream().filter(row -> row.getArtifactType() == type && Boolean.TRUE.equals(row.getActive())).toList();
        });
        when(artifacts.findFirstBySuiteIdAndArtifactTypeAndActiveTrueOrderByVersionDesc(any(), any()))
                .thenAnswer(invocation -> {
                    BehaviorArtifactType type = invocation.getArgument(1);
                    return stored.stream()
                            .filter(row -> row.getArtifactType() == type && Boolean.TRUE.equals(row.getActive()))
                            .max(Comparator.comparing(BehaviorArtifact::getVersion));
                });
        when(artifacts.save(any(BehaviorArtifact.class))).thenAnswer(invocation -> {
            BehaviorArtifact row = invocation.getArgument(0);
            if (row.getId() == null) row.setId(UUID.randomUUID().toString());
            if (row.getActive() == null) row.setActive(true);
            stored.add(row);
            return row;
        });
        when(artifacts.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service = new BehaviorArtifactService(artifacts, suites);
        ReflectionTestUtils.setField(service, "artifactsDir", tempDir.resolve("artifacts").toString());
    }

    @Test
    void nhanDatabaseAnDuDuLieuKhacBanCu() throws Exception {
        Path hiddenDb = sqlite("hidden.db", "CREATE TABLE users(uid TEXT PRIMARY KEY, name TEXT NOT NULL)",
                "INSERT INTO users VALUES ('HIDDEN_99', 'Hidden user')");
        Path hiddenMoi = sqlite("hidden2.db", "CREATE TABLE users(uid TEXT PRIMARY KEY, name TEXT NOT NULL)",
                "INSERT INTO users VALUES ('HIDDEN_01', 'Nguoi khac')");

        service.upload("suite-1", BehaviorArtifactType.HIDDEN_DATABASE, multipart(hiddenDb), "{}");
        service.upload("suite-1", BehaviorArtifactType.HIDDEN_DATABASE, multipart(hiddenMoi), "{}");

        assertEquals(2, stored.size());
        assertNotEquals(stored.get(0).getSha256(), stored.get(1).getSha256(),
                "Tải Database ẩn bản mới phải sinh version mới, không bị chặn vì khác dữ liệu");
    }

    @Test
    void khongConChanDatabaseAnLechCauTrucVoiStudentDbCu() throws Exception {
        // ĐỔI HÀNH VI CÓ CHỦ Ý, 16/9/2026: bỏ hẳn ô "Database phát cho sinh viên".
        //
        // Trước đây tải Database ẩn lên sẽ bị chặn nếu cấu trúc bảng lệch với student.db. Phép so
        // ấy lấy student.db làm mốc, mà máy chấm không bao giờ mở file đó — nên với bộ đề cũ còn
        // sót một student.db lệch schema, nó chặn luôn cả lần tải Database ẩn ĐÚNG lên.
        //
        // Phép canh cấu trúc thật nay nằm ở khâu kiểm đồng bộ khung phát, so trên MÃ NGUỒN giữa
        // khung phát và Golden. Test này giữ để không ai dựng lại phép so cũ.
        Path studentCu = sqlite("public.db", "CREATE TABLE users(uid TEXT PRIMARY KEY, name TEXT NOT NULL)");
        Path hiddenDb = sqlite("hidden.db", "CREATE TABLE users(uid TEXT PRIMARY KEY, full_name TEXT, age INTEGER)");
        service.upload("suite-1", BehaviorArtifactType.STUDENT_DATABASE, multipart(studentCu), "{}");

        service.upload("suite-1", BehaviorArtifactType.HIDDEN_DATABASE, multipart(hiddenDb), "{}");

        assertEquals(2, stored.size(), "Database ẩn lệch cấu trúc với student.db cũ vẫn phải tải lên được");
    }

    @Test
    void testcaseDefinitionRequiresAllSevenExecutionColumns() {
        byte[] invalid = """
                {"steps":[{"stage":"ACTION","attribute":"semanticId","attributeValue":"add.button",
                "valueType":"string","value":"","action":"tap"}]}
                """.getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "testcase-definition.json", "application/json", invalid);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.upload("suite-1", BehaviorArtifactType.TESTCASE_DEFINITION, file, "{}"));

        assertTrue(error.getMessage().contains("browser"));
    }

    @Test
    void derivesIndependentInsertUpdateDeleteCheckpointsFromOutputDatabase() throws Exception {
        Path hiddenDb = sqlite("hidden-diff.db",
                "CREATE TABLE users(uid TEXT PRIMARY KEY, name TEXT NOT NULL)",
                "INSERT INTO users VALUES ('U1', 'Before')",
                "INSERT INTO users VALUES ('U2', 'Delete me')");
        Path outputDb = sqlite("output-diff.db",
                "CREATE TABLE users(uid TEXT PRIMARY KEY, name TEXT NOT NULL)",
                "INSERT INTO users VALUES ('U1', 'After')",
                "INSERT INTO users VALUES ('U3', 'Inserted')");

        service.upload("suite-1", BehaviorArtifactType.HIDDEN_DATABASE, multipart(hiddenDb), "{}");
        service.upload("suite-1", BehaviorArtifactType.OUTPUT_DATABASE, multipart(outputDb), "{}");

        List<java.util.Map<String, Object>> checkpoints = service.databaseDiffCheckpoints("suite-1");
        assertEquals(3, checkpoints.size());
        assertEquals(java.util.Set.of("UPDATE", "INSERT", "DELETE"),
                new java.util.HashSet<>(checkpoints.stream()
                        .map(row -> String.valueOf(row.get("operation"))).toList()));
        assertTrue(checkpoints.stream().allMatch(row -> "database_observation".equals(row.get("kind"))));
    }

    @Test
    void scanGoldenValueKeysPrefersCommentButFallsBackToEnclosingMethodName() throws Exception {
        Path zip = tempDir.resolve("golden.zip");
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            putEntry(out, "lib/main.dart", "void main() {}\n");
            putEntry(out, "lib/widgets_demo.dart", """
                    import 'package:flutter/widgets.dart';

                    // SCROLL_DIRECTION: listKey=demo.hlist, direction=horizontal
                    Widget buildCommented() {
                      return ListView(key: const ValueKey<String>('demo.hlist'));
                    }

                    Widget buildVerticalLazyList() {
                      return ListView(key: const ValueKey<String>('demo.vlist'));
                    }
                    """);
        }
        service.upload("suite-1", BehaviorArtifactType.GOLDEN_SOLUTION,
                new MockMultipartFile("file", "golden.zip", "application/zip", Files.readAllBytes(zip)), "{}");

        List<java.util.Map<String, String>> keys = service.scanGoldenValueKeys("suite-1");
        java.util.Map<String, java.util.Map<String, String>> byKey = keys.stream()
                .collect(java.util.stream.Collectors.toMap(k -> k.get("key"), k -> k));

        assertEquals("ListView", byKey.get("demo.hlist").get("widgetType"));
        assertTrue(byKey.get("demo.hlist").get("hint").startsWith("SCROLL_DIRECTION"),
                "Có comment ngay trên key thì phải ưu tiên dùng comment làm hint");

        assertEquals("ListView", byKey.get("demo.vlist").get("widgetType"));
        assertEquals("Build Vertical Lazy List", byKey.get("demo.vlist").get("hint"),
                "Không có comment thì phải rơi về tên hàm Dart bao quanh đã người hoá, không được để trống");
    }

    private void putEntry(java.util.zip.ZipOutputStream out, String name, String content) throws Exception {
        out.putNextEntry(new java.util.zip.ZipEntry(name));
        out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private Path sqlite(String fileName, String... statements) throws Exception {
        Path path = tempDir.resolve(fileName);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            for (String sql : statements) statement.execute(sql);
        }
        return path;
    }

    private MockMultipartFile multipart(Path path) throws Exception {
        return new MockMultipartFile("file", path.getFileName().toString(),
                "application/vnd.sqlite3", Files.readAllBytes(path));
    }
}
