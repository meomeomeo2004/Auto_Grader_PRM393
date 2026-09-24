package com.example.grader.service;

import com.example.grader.dto.ExamSetupResponse;
import com.example.grader.entity.Exam;
import com.example.grader.entity.ExamStatus;
import com.example.grader.repository.ExamRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sheet UT17, UT28, UT29 — ba hàm của ExamService từng bị xếp là "không test được vì gọi Docker".
 *
 * <p>Không đúng hẳn. Cả ba đều có đường thoát trước khi chạm Docker:
 * <ul>
 *   <li>{@code ensureBaseImage} mở đầu bằng {@code if (baseImageReady.get()) return;} — bật cờ
 *       đó là UT17 chạy trọn vẹn trên đĩa;</li>
 *   <li>{@code goiChoManSoanDe} chỉ gọi Docker qua {@code goiCoTrongAnhCham()}, ghi đè được;</li>
 *   <li>{@code applyPackages} chạy nền, và nhánh đáng kiểm nhất của nó — HOÀN TÁC pubspec khi
 *       hỏng — quan sát được mà không cần lượt build nào thành công.</li>
 * </ul>
 */
class ExamEnvUnitTest {

    @TempDir Path thuMuc;

    /** Tên ảnh cố ý sai chuẩn (có chữ hoa) để docker từ chối NGAY, không đi mạng, không tạo container. */
    private static final String ANH_KHONG_HOP_LE = "KHONG_TON_TAI_ANH_CHAM:test-only";
    private static final String MA_DE = "PE_PRM393_FA26";

    private final ObjectMapper mapper = new ObjectMapper();

    private Path base;          // thư mục grader-base giả
    private Path examsDir;
    private ExamRepository repo;
    private final Map<String, Exam> khoDe = new LinkedHashMap<>();

    private static final String PUBSPEC_BASE = """
            name: exam_project
            environment:
              sdk: ">=3.0.0 <4.0.0"
            dependencies:
              flutter:
                sdk: flutter
              intl: ^0.19.0
              sqflite: ^2.3.0
            dev_dependencies:
              flutter_test:
                sdk: flutter
              flutter_lints: ^4.0.0
            """;

    @BeforeEach
    void dungMoiTruong() throws Exception {
        base = Files.createDirectories(thuMuc.resolve("grader-base"));
        Files.writeString(base.resolve("Dockerfile.base"), "FROM scratch\n", StandardCharsets.UTF_8);
        Files.writeString(base.resolve("pubspec.base.yaml"), PUBSPEC_BASE, StandardCharsets.UTF_8);
        examsDir = Files.createDirectories(thuMuc.resolve("exams"));

        repo = mock(ExamRepository.class);
        when(repo.findByExamId(anyString()))
                .thenAnswer(i -> Optional.ofNullable(khoDe.get((String) i.getArgument(0))));
        when(repo.save(any())).thenAnswer(i -> {
            Exam e = i.getArgument(0);
            khoDe.put(e.getExamId(), e);
            return e;
        });
    }

    // ── Đồ dựng sẵn ────────────────────────────────────────────────────

    private static void datField(Object o, String ten, Object giaTri) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(ten);
                f.setAccessible(true);
                f.set(o, giaTri);
                return;
            } catch (NoSuchFieldException tiep) {
                // thử lớp cha
            }
        }
        throw new NoSuchFieldException(ten);
    }

    private static Object docField(Object o, String ten) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(ten);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException tiep) {
                // thử lớp cha
            }
        }
        throw new NoSuchFieldException(ten);
    }

    /**
     * ExamService dựng tay, đã bật sẵn cờ "ảnh nền có rồi" nên ensureBaseImage thoát ngay.
     * goiCoTrongAnhCham() ghi đè bằng lớp con — đó là cửa Docker duy nhất còn lại của UT28.
     */
    private ExamService examService(Set<String> goiTrongAnh, String anhNen) throws Exception {
        ExamService svc = new ExamService() {
            @Override
            public Set<String> goiCoTrongAnhCham() {
                return goiTrongAnh;
            }
        };
        datField(svc, "examRepository", repo);
        datField(svc, "templateDir", base.toAbsolutePath().toString());
        datField(svc, "examsDir", examsDir.toAbsolutePath().toString());
        datField(svc, "baseImage", anhNen);
        datField(svc, "imagePrefix", "grading-env");
        datField(svc, "submissionsDir", thuMuc.resolve("submissions").toString());
        datField(svc, "runnerProcessTimeoutSeconds", 60);
        datField(svc, "vaiGiangVien", true);
        // Chính là mấu chốt: ensureBaseImage() mở đầu bằng "if (baseImageReady.get()) return;"
        ((AtomicBoolean) docField(svc, "baseImageReady")).set(true);
        return svc;
    }

    private ExamService examService() throws Exception {
        return examService(Set.of("flutter", "flutter_test", "intl", "sqflite"), "grading-base:test");
    }

    private byte[] nen(Map<String, String> tep) throws Exception {
        ByteArrayOutputStream ra = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(ra, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> e : tep.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return ra.toByteArray();
    }

    /** Bộ testcase hợp lệ tối thiểu: ba file bắt buộc, chỉ import package ảnh nền đã khai. */
    private Map<String, String> boTestcase() {
        Map<String, String> tep = new LinkedHashMap<>();
        tep.put("exam_test.dart", "import 'package:intl/intl.dart';\n\nvoid main() {}\n");
        tep.put("grader.dart", "void main() {\n  print('GRADE_RESULT');\n}\n");
        tep.put("skills_matrix.json", "{\"TC_ADD_01\":{\"weight\":1.0}}");
        return tep;
    }

    private Path testcaseCua(String examId) {
        return examsDir.resolve(examId).resolve("testcase");
    }

    // ══════════════════ UT17 — setupExamFromZipBytes ══════════════════

    @Test
    @DisplayName("UT17 UTCID01 — nạp bộ hợp lệ: đề READY, testcase nằm đúng chỗ")
    void ut17_utcid01_napBoHopLe() throws Exception {
        ExamService svc = examService();

        ExamSetupResponse ra = svc.setupExamFromZipBytes(MA_DE, "PE PRM393 FA26", nen(boTestcase()));

        assertEquals(MA_DE, ra.getExamId());
        assertEquals("READY", ra.getStatus());

        Path tc = testcaseCua(MA_DE);
        assertTrue(Files.isRegularFile(tc.resolve("exam_test.dart")));
        assertTrue(Files.isRegularFile(tc.resolve("grader.dart")));
        assertTrue(Files.isRegularFile(tc.resolve("skills_matrix.json")));

        Exam exam = khoDe.get(MA_DE);
        assertEquals(ExamStatus.READY, exam.getStatus());
        assertEquals("PUBLISHED", exam.getTestcaseStatus());
        assertEquals("PE PRM393 FA26", exam.getExamName());
        assertEquals(tc.toAbsolutePath().normalize().toString(), exam.getTestcasePath());
        assertEquals(1, exam.getTestcaseVersion());
        assertNotNull(exam.getTestcasePublishedAt());
    }

    @Test
    @DisplayName("UT17 UTCID02 — nạp đè: bản cũ được LƯU LẠI, không xoá thẳng")
    void ut17_utcid02_luuBanCuTruocKhiDe() throws Exception {
        ExamService svc = examService();
        Path tc = Files.createDirectories(testcaseCua(MA_DE));
        Files.writeString(tc.resolve("dau-vet-ban-cu.txt"), "bo de dot truoc");

        svc.setupExamFromZipBytes(MA_DE, "PE PRM393 FA26", nen(boTestcase()));

        assertFalse(Files.exists(tc.resolve("dau-vet-ban-cu.txt")), "thư mục mới phải sạch");
        Path kho = examsDir.resolve(MA_DE).resolve("testcase-archive");
        assertTrue(Files.isDirectory(kho), "phải có kho lưu bản cũ");
        try (Stream<Path> ban = Files.list(kho)) {
            List<Path> cac = ban.toList();
            assertEquals(1, cac.size());
            assertTrue(Files.isRegularFile(cac.get(0).resolve("dau-vet-ban-cu.txt")),
                    "còn tra lại được đúng bộ đề đã từng dùng khi nghi ngờ chấm sai");
        }
    }

    static Stream<Arguments> ut17_giuTenCu() {
        return Stream.of(
                Arguments.of("UTCID03", "   "),
                Arguments.of("UTCID04", (String) null)
        );
    }

    @ParameterizedTest(name = "UT17 {0}: examName = [{1}]")
    @MethodSource("ut17_giuTenCu")
    @DisplayName("UT17 — examName trống thì GIỮ tên cũ, không xoá trắng tên đề")
    void ut17_giuTenCu(String utcid, String tenMoi) throws Exception {
        ExamService svc = examService();
        Exam cu = new Exam();
        cu.setExamId(MA_DE);
        cu.setExamName("Tên đặt từ trước");
        khoDe.put(MA_DE, cu);

        svc.setupExamFromZipBytes(MA_DE, tenMoi, nen(boTestcase()));

        assertEquals("Tên đặt từ trước", khoDe.get(MA_DE).getExamName(), utcid);
    }

    static Stream<Arguments> ut17_maDeKhongAnToan() {
        return Stream.of(
                Arguments.of("UTCID05", "../etc"),
                Arguments.of("UTCID06", "PE 01"),
                Arguments.of("UTCID07", (String) null)
        );
    }

    @ParameterizedTest(name = "UT17 {0}: examId = [{1}]")
    @MethodSource("ut17_maDeKhongAnToan")
    @DisplayName("UT17 — mã đề không an toàn bị chặn TRƯỚC khi tạo thư mục nào")
    void ut17_maDeKhongAnToan(String utcid, String examId) throws Exception {
        ExamService svc = examService();

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> svc.setupExamFromZipBytes(examId, "x", nen(boTestcase())))
                .getMessage().startsWith("Mã đề không hợp lệ"), utcid);

        try (Stream<Path> con = Files.list(examsDir)) {
            assertEquals(List.of(), con.toList(), utcid + ": không được tạo thư mục nào");
        }
    }

    static Stream<Arguments> ut17_thieuFileBatBuoc() {
        return Stream.of(
                Arguments.of("UTCID08", "exam_test.dart"),
                Arguments.of("UTCID09", "grader.dart"),
                Arguments.of("UTCID10", "skills_matrix.json")
        );
    }

    @ParameterizedTest(name = "UT17 {0}: thiếu {1}")
    @MethodSource("ut17_thieuFileBatBuoc")
    @DisplayName("UT17 — thiếu file bắt buộc thì báo đúng tên file còn thiếu")
    void ut17_thieuFileBatBuoc(String utcid, String thieu) throws Exception {
        ExamService svc = examService();
        Map<String, String> tep = boTestcase();
        tep.remove(thieu);

        assertEquals("Thiếu file bắt buộc: " + thieu,
                assertThrows(IllegalArgumentException.class,
                        () -> svc.setupExamFromZipBytes(MA_DE, "x", nen(tep))).getMessage(), utcid);
    }

    @Test
    @DisplayName("UT17 UTCID11 — file bắt buộc rỗng 0 byte bị chặn (nén hụt thì không im lặng)")
    void ut17_utcid11_fileRong() throws Exception {
        ExamService svc = examService();
        Map<String, String> tep = boTestcase();
        tep.put("skills_matrix.json", "");

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> svc.setupExamFromZipBytes(MA_DE, "x", nen(tep)))
                .getMessage().startsWith("File bắt buộc bị RỖNG (0 byte): skills_matrix.json"));
    }

    @Test
    @DisplayName("UT17 UTCID12 — grader.dart không có main() thì file sai nội dung/encoding")
    void ut17_utcid12_graderKhongCoMain() throws Exception {
        ExamService svc = examService();
        Map<String, String> tep = boTestcase();
        tep.put("grader.dart", "// file rong ruot, khong co ham nao\n");

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> svc.setupExamFromZipBytes(MA_DE, "x", nen(tep)))
                .getMessage().startsWith("grader.dart không có hàm main()"));
    }

    @Test
    @DisplayName("UT17 UTCID13 — không kèm contract.json thì backend tự tạo bản tương thích")
    void ut17_utcid13_tuTaoContract() throws Exception {
        ExamService svc = examService();

        svc.setupExamFromZipBytes(MA_DE, "x", nen(boTestcase()));

        Path contract = testcaseCua(MA_DE).resolve("contract.json");
        assertTrue(Files.isRegularFile(contract), "phải tự sinh để tải xuống/nạp lại không đổi hành vi");
        JsonNode root = mapper.readTree(Files.readString(contract, StandardCharsets.UTF_8));
        assertEquals(1, root.get("schema_version").asInt());
        assertFalse(root.get("require_keys").asBoolean());
    }

    @Test
    @DisplayName("UT17 UTCID14 — có gửi contract.json thì kiểm chặt: rỗng hoặc không phải JSON object đều chặn")
    void ut17_utcid14_contractGuiKemPhaiHopLe() throws Exception {
        ExamService svc = examService();

        Map<String, String> rong = boTestcase();
        rong.put("contract.json", "");
        assertEquals("contract.json được cung cấp nhưng bị RỖNG (0 byte)",
                assertThrows(IllegalArgumentException.class,
                        () -> svc.setupExamFromZipBytes(MA_DE, "x", nen(rong))).getMessage());

        Map<String, String> hong = boTestcase();
        hong.put("contract.json", "[1, 2, 3]");
        assertEquals("contract.json phải là một JSON object.",
                assertThrows(IllegalArgumentException.class,
                        () -> svc.setupExamFromZipBytes(MA_DE, "x", nen(hong))).getMessage());
    }

    @Test
    @DisplayName("UT17 — testcase chỉ import package ảnh nền đã khai thì không phải build lại gì")
    void ut17_importNamTrongDanhSachThiDiThang() throws Exception {
        ExamService svc = examService();
        Map<String, String> tep = boTestcase();
        tep.put("exam_test.dart",
                "import 'package:flutter_test/flutter_test.dart';\n"
                + "import 'package:sqflite/sqflite.dart';\n\nvoid main() {}\n");

        assertEquals("READY", svc.setupExamFromZipBytes(MA_DE, "x", nen(tep)).getStatus());
    }

    // ══════════════════ UT28 — goiChoManSoanDe ══════════════════

    @SuppressWarnings("unchecked")
    private List<String> tenDirect(Map<String, Object> ra) {
        return ((List<Map<String, Object>>) ra.get("direct")).stream()
                .map(m -> String.valueOf(m.get("name"))).toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> transitive(Map<String, Object> ra) {
        return (List<String>) ra.get("transitive");
    }

    @Test
    @DisplayName("UT28 UTCID01 — tách gói khai thẳng và gói kéo theo, hai nhóm không chồng nhau")
    void ut28_utcid01_tachHaiNhom() throws Exception {
        ExamService svc = examService(
                Set.of("flutter", "flutter_test", "intl", "sqflite", "path", "meta", "collection"),
                "grading-base:test");

        Map<String, Object> ra = svc.goiChoManSoanDe();

        assertEquals(true, ra.get("image_read"));
        assertEquals(List.of("flutter", "intl", "sqflite"), tenDirect(ra),
                "flutter_test/flutter_lints là đồ nghề soạn bài, không thuộc lựa chọn runtime của đề");
        assertEquals(List.of("collection", "meta", "path"), transitive(ra), "gói kéo theo phải sắp xếp");
        assertTrue(transitive(ra).stream().noneMatch(tenDirect(ra)::contains),
                "chồng nhau là bảng tick hiện một gói hai lần, tick chỗ này không thấy đổi chỗ kia");
    }

    @Test
    @DisplayName("UT28 UTCID02 — Docker tắt: image_read=false và KHÔNG được coi là ảnh rỗng")
    void ut28_utcid02_dockerTat() throws Exception {
        ExamService svc = examService(Set.of(), "grading-base:test");

        Map<String, Object> ra = svc.goiChoManSoanDe();

        assertEquals(false, ra.get("image_read"),
                "màn soạn đề phải hiểu là \"không biết\" và giữ nguyên danh sách đã lưu");
        assertEquals(List.of(), transitive(ra));
        assertEquals(List.of("flutter", "intl", "sqflite"), tenDirect(ra),
                "phần đọc từ pubspec vẫn còn, chỉ là chưa đối chiếu được với ảnh");
    }

    @Test
    @DisplayName("UT28 UTCID03 — không có pubspec.base.yaml thì báo lỗi rõ, không trả bảng rỗng im lặng")
    void ut28_utcid03_thieuPubspecBase() throws Exception {
        Files.delete(base.resolve("pubspec.base.yaml"));
        ExamService svc = examService();

        assertEquals("Không đọc được dependencies nguồn khung phát.",
                assertThrows(IllegalStateException.class, svc::goiChoManSoanDe).getMessage());
    }

    @Test
    @DisplayName("UT28 UTCID04 — gói ảnh có mà pubspec không khai đều rơi vào nhóm kéo theo")
    void ut28_utcid04_goiLaChiCoTrongAnh() throws Exception {
        ExamService svc = examService(
                Set.of("flutter", "flutter_test", "intl", "sqflite", "dio"), "grading-base:test");

        Map<String, Object> ra = svc.goiChoManSoanDe();

        assertEquals(List.of("dio"), transitive(ra));
        assertFalse(tenDirect(ra).contains("dio"));
    }

    // ══════════════════ UT29 — applyPackages ══════════════════

    /** Luồng nền chạy bất đồng bộ; đợi tới khi nó tự hạ cờ building. */
    private Map<String, Object> doiBuildXong(ExamService svc) throws Exception {
        long han = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < han) {
            Map<String, Object> tt = svc.buildStatus();
            if (!Boolean.TRUE.equals(tt.get("building"))) return tt;
            Thread.sleep(50);
        }
        return fail("Luồng cập nhật thư viện không kết thúc trong 60 giây");
    }

    @Test
    @DisplayName("UT29 UTCID01 — trả về NGAY với RESOLVING, phần nặng đẩy sang luồng nền")
    void ut29_utcid01_traVeNgay() throws Exception {
        ExamService svc = examService(Set.of(), ANH_KHONG_HOP_LE);

        long truoc = System.currentTimeMillis();
        Map<String, Object> ra = svc.applyPackages(List.of(Map.of("name", "intl", "version", "^0.19.0")));
        long ton = System.currentTimeMillis() - truoc;

        assertEquals("RESOLVING", ra.get("status"));
        assertTrue(ton < 3000, "không được chặn luồng gọi, đang tốn " + ton + "ms");
        doiBuildXong(svc);
    }

    @Test
    @DisplayName("UT29 UTCID02 — đang build dở thì từ chối lượt thứ hai")
    void ut29_utcid02_dangBuildThiTuChoi() throws Exception {
        ExamService svc = examService();
        datField(svc, "envBuilding", true);

        assertEquals("Đang build môi trường, vui lòng đợi build xong.",
                assertThrows(IllegalStateException.class,
                        () -> svc.applyPackages(List.of(Map.of("name", "intl", "version", "^0.19.0"))))
                        .getMessage());
    }

    @Test
    @DisplayName("UT29 UTCID03 — tên package sai định dạng: FAILED, pubspec KHÔNG bị đụng tới")
    void ut29_utcid03_tenPackageSai() throws Exception {
        ExamService svc = examService(Set.of(), ANH_KHONG_HOP_LE);
        String truoc = Files.readString(base.resolve("pubspec.base.yaml"), StandardCharsets.UTF_8);

        svc.applyPackages(List.of(Map.of("name", "Intl-Sai", "version", "^0.19.0")));
        Map<String, Object> tt = doiBuildXong(svc);

        assertEquals("FAILED", tt.get("status"));
        assertTrue(String.valueOf(tt.get("message")).contains("Tên package không hợp lệ: 'Intl-Sai'"),
                String.valueOf(tt.get("message")));
        assertEquals(truoc, Files.readString(base.resolve("pubspec.base.yaml"), StandardCharsets.UTF_8));
    }

    /**
     * Ca đáng giá nhất của UT29: pubspec ĐÃ bị ghi đè rồi bước sau mới hỏng. Không hoàn tác thì
     * máy kẹt lại với một pubspec mô tả môi trường KHÔNG tồn tại, và mọi lần nạp đề sau đó đối
     * chiếu nhầm vào đó.
     */
    @Test
    @DisplayName("UT29 UTCID04 — hỏng ở bước cập nhật ảnh nền thì pubspec phải được HOÀN TÁC")
    void ut29_utcid04_hoanTacPubspec() throws Exception {
        ExamService svc = examService(Set.of(), ANH_KHONG_HOP_LE);
        String truoc = Files.readString(base.resolve("pubspec.base.yaml"), StandardCharsets.UTF_8);

        // Khai đủ version nên không phải gọi `flutter pub add`; đi thẳng tới bước ghi + docker.
        svc.applyPackages(List.of(
                Map.of("name", "intl", "version", "^0.19.0"),
                Map.of("name", "sqflite", "version", "^2.3.0"),
                Map.of("name", "path", "version", "^1.9.0")));
        Map<String, Object> tt = doiBuildXong(svc);

        assertEquals("FAILED", tt.get("status"));
        assertTrue(String.valueOf(tt.get("message")).endsWith("— đã hoàn tác thay đổi."),
                String.valueOf(tt.get("message")));
        assertEquals(truoc, Files.readString(base.resolve("pubspec.base.yaml"), StandardCharsets.UTF_8),
                "pubspec kẹt ở bản mới là mọi lần nạp đề sau đó đối chiếu nhầm");
    }

    @Test
    @DisplayName("UT29 UTCID05 — danh sách rỗng vẫn chạy trọn luồng, không nổ")
    void ut29_utcid05_danhSachRong() throws Exception {
        ExamService svc = examService(Set.of(), ANH_KHONG_HOP_LE);

        svc.applyPackages(List.of());
        Map<String, Object> tt = doiBuildXong(svc);

        assertNotNull(tt.get("status"));
        assertFalse(Boolean.TRUE.equals(tt.get("building")), "cờ building luôn phải được hạ");
    }

    @Test
    @DisplayName("UT29 UTCID06 — gói lõi và tên trùng lặp bị lọc trước khi ghi")
    void ut29_utcid06_locGoiLoiVaTrungLap() throws Exception {
        ExamService svc = examService(Set.of(), ANH_KHONG_HOP_LE);

        svc.applyPackages(List.of(
                // Version là bắt buộc cho MỌI dòng từ 19/9 — gói lõi cũng phải khai, dù sau đó bị lọc.
                Map.of("name", "flutter", "version", "^3.0.0"),         // lõi — bỏ qua
                Map.of("name", "flutter_test", "version", "^3.0.0"),    // lõi — bỏ qua
                Map.of("name", "intl", "version", "^0.19.0"),
                Map.of("name", "intl", "version", "^0.18.0")));         // trùng — giữ lần đầu
        Map<String, Object> tt = doiBuildXong(svc);

        // Hỏng ở bước docker nên pubspec đã hoàn tác; điều cần khẳng định là KHÔNG nổ vì gói lõi.
        assertEquals("FAILED", tt.get("status"));
        assertFalse(String.valueOf(tt.get("message")).contains("Tên package không hợp lệ"),
                String.valueOf(tt.get("message")));
    }

    @Test
    @DisplayName("UT29 — cờ building luôn được hạ dù luồng nền hỏng, không khoá vĩnh viễn màn hình")
    void ut29_coBuildingLuonDuocHa() throws Exception {
        ExamService svc = examService(Set.of(), ANH_KHONG_HOP_LE);

        svc.applyPackages(List.of(Map.of("name", "Sai-Ten", "version", "^1.0.0")));
        doiBuildXong(svc);

        // Hạ cờ rồi thì lượt sau phải bấm được, không còn báo "đang build".
        assertEquals("RESOLVING",
                svc.applyPackages(List.of(Map.of("name", "intl", "version", "^0.19.0"))).get("status"));
        doiBuildXong(svc);
    }
}
