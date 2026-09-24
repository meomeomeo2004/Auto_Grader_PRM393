package com.example.grader.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.multipart.MultipartFile;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Module SubmissionIntake — các sheet UT21…UT25.
 *
 * <p>Đây là toàn bộ đoạn đường bài sinh viên đi từ ô upload tới trước cửa compile: suy mã SV,
 * loại file không nhận, giải nén an toàn, rồi soi phụ thuộc. Mỗi chốt ở đây hỏng theo một kiểu
 * khác nhau — suy nhầm mã SV thì ghi đè điểm người khác, giải nén lỏng thì bài nộp ghi đè được
 * file ngoài thư mục, soi phụ thuộc lỏng thì bài dùng package ngoài vẫn chấm được như thường.
 *
 * <p>UT19/UT20 (enqueueBatch, addToBatch) nằm ở {@link SubmissionBatchUnitTest} vì phải dựng
 * repository giả, không cùng hình dạng với nhóm này.
 */
class SubmissionIntakeUnitTest {

    @TempDir Path thuMuc;

    private final BatchGradingService batch = new BatchGradingService();
    private final SubmissionPackagePolicy policy = new SubmissionPackagePolicy();

    // ══════════════════ UT21 — BatchGradingService.parseStudentInfo ══════════════════

    static Stream<Arguments> ut21_suyDuocMa() {
        return Stream.of(
                Arguments.of("UTCID01", "HE123456", "HE123456"),
                Arguments.of("UTCID02", "Nguyễn Văn A (HE150123)", "HE150123"),
                Arguments.of("UTCID03", "PE_ca1 - he150123", "HE150123"),
                // Chỉ 5 chữ số nên KHÔNG khớp mẫu mã SV, rơi xuống nhánh rút gọn cả tên.
                Arguments.of("UTCID04", "he12345", "HE12345"),
                Arguments.of("UTCID05", "Tran Thi Binh", "TRAN_THI_BINH"),
                Arguments.of("UTCID06", "Nguyễn Văn Dũng", "NGUYEN_VAN_DUNG"),
                // 27 ký tự sau khi bắt — phải cắt còn 20 vì student_id là varchar(20).
                Arguments.of("UTCID07", "HE1234567890123456789012345", "HE123456789012345678"),
                Arguments.of("UTCID08", "HE123456X", "HE123456")
        );
    }

    @ParameterizedTest(name = "UT21 {0}: \"{1}\" → {2}")
    @MethodSource("ut21_suyDuocMa")
    @DisplayName("UT21 — suy mã SV từ tên thư mục LMS, tên gốc luôn giữ nguyên")
    void ut21_suyMaSinhVien(String utcid, String username, String maMongDoi) {
        BatchGradingService.StudentInfo info = batch.parseStudentInfo(username);

        assertEquals(maMongDoi, info.studentId(), utcid + ": mã SV suy sai");
        assertEquals(username.trim(), info.studentName(),
                utcid + ": tên hiển thị phải là tên thư mục gốc, không bị chuẩn hóa");
        assertTrue(info.studentId().length() <= 20,
                utcid + ": mã SV là cột varchar(20), không được dài hơn");
        assertTrue(info.studentId().matches("[A-Z0-9_-]+"),
                utcid + ": mã SV còn dùng làm tên thư mục nên chỉ được chứa ký tự an toàn");
    }

    @Test
    @DisplayName("UT21 UTCID09 — tên rút gọn về rỗng thì phải báo không suy được mã")
    void ut21_utcid09_tenRutGonVeRong() {
        IllegalArgumentException loi = assertThrows(IllegalArgumentException.class,
                () -> batch.parseStudentInfo("---"));
        assertTrue(loi.getMessage().startsWith("Không suy được mã SV từ tên thư mục"),
                "UTCID09: đang báo: " + loi.getMessage());
    }

    @ParameterizedTest(name = "UT21 {0}: tên thư mục trống")
    @MethodSource("ut21_tenTrong")
    @DisplayName("UT21 — tên thư mục trống bị chặn với lời báo riêng")
    void ut21_tenThuMucTrong(String utcid, String username) {
        IllegalArgumentException loi = assertThrows(IllegalArgumentException.class,
                () -> batch.parseStudentInfo(username));
        assertEquals("Thiếu tên thư mục bài nộp.", loi.getMessage(), utcid + ": sai lời báo");
    }

    static Stream<Arguments> ut21_tenTrong() {
        return Stream.of(
                Arguments.of("UTCID10", "   "),
                Arguments.of("UTCID11", ""),
                Arguments.of("UTCID12", null)
        );
    }

    /** Mã SV thành tên thư mục trên đĩa — không bao giờ được chứa ".." hay dấu phân cách. */
    @Test
    @DisplayName("UT21 — mã suy ra không bao giờ mở được đường thoát thư mục")
    void ut21_khongBaoGioSinhDuongThoat() {
        for (String doc : List.of("../khiempg", "..\\khiempg", "a/../../b", "PE:01")) {
            String ma = batch.parseStudentInfo(doc).studentId();
            assertFalse(ma.contains(".."), doc + " → " + ma);
            assertTrue(ma.matches("[A-Z0-9_-]+"), doc + " → " + ma);
        }
    }

    // ══════════════════ UT22 — BatchGradingService.validateZip ══════════════════

    /** Dựng file nộp giả: chỉ cần ba thứ validateZip đọc tới (tên, rỗng hay không, cỡ). */
    private static MultipartFile baiNop(boolean rong, long cỡ) {
        MultipartFile f = mock(MultipartFile.class);
        when(f.isEmpty()).thenReturn(rong);
        when(f.getSize()).thenReturn(cỡ);
        return f;
    }

    static Stream<Arguments> ut22_tenHopLe() {
        return Stream.of(
                Arguments.of("UTCID01", "lib.zip"),
                Arguments.of("UTCID02", "lib.ZIP"),
                Arguments.of("UTCID03", "bai_lam.Zip")
        );
    }

    @ParameterizedTest(name = "UT22 {0}: {1} được nhận")
    @MethodSource("ut22_tenHopLe")
    @DisplayName("UT22 — đuôi .zip không phân biệt hoa thường")
    void ut22_duoiZipKhongPhanBietHoaThuong(String utcid, String ten) {
        assertDoesNotThrow(() -> batch.validateZip(baiNop(false, 1024), ten), utcid);
    }

    static Stream<Arguments> ut22_tenSai() {
        return Stream.of(
                Arguments.of("UTCID04", "lib.rar"),
                Arguments.of("UTCID05", "lib"),
                Arguments.of("UTCID06", ""),
                Arguments.of("UTCID07", (String) null)
        );
    }

    @ParameterizedTest(name = "UT22 {0}: {1} bị loại")
    @MethodSource("ut22_tenSai")
    @DisplayName("UT22 — file không phải .zip bị loại ngay, trước khi ghi ra đĩa")
    void ut22_khongPhaiZipBiLoai(String utcid, String ten) {
        IllegalArgumentException loi = assertThrows(IllegalArgumentException.class,
                () -> batch.validateZip(baiNop(false, 1024), ten));
        assertEquals("Mỗi thư mục bài nộp phải chứa một file .zip (thường là lib.zip)",
                loi.getMessage(), utcid + ": sai lời báo");
    }

    @Test
    @DisplayName("UT22 UTCID08 — file rỗng bị loại")
    void ut22_utcid08_fileRong() {
        IllegalArgumentException loi = assertThrows(IllegalArgumentException.class,
                () -> batch.validateZip(baiNop(true, 0), "lib.zip"));
        assertEquals("File rỗng", loi.getMessage());
    }

    @Test
    @DisplayName("UT22 UTCID09 — đúng 50 MB vẫn nhận (so sánh là >, không phải >=)")
    void ut22_utcid09_dungNguongVanNhan() {
        assertDoesNotThrow(() -> batch.validateZip(baiNop(false, 50L * 1024 * 1024), "lib.zip"));
    }

    @Test
    @DisplayName("UT22 UTCID10 — quá 50 MB một byte là bị loại")
    void ut22_utcid10_quaNguongMotByte() {
        IllegalArgumentException loi = assertThrows(IllegalArgumentException.class,
                () -> batch.validateZip(baiNop(false, 50L * 1024 * 1024 + 1), "lib.zip"));
        assertEquals("Quá 50MB", loi.getMessage());
    }

    // ══════════════════ UT23 — SecureZipExtractor.extract ══════════════════

    /** Nén danh sách (tên → nội dung) thành một file zip trong thư mục tạm. */
    private Path zip(String ten, String[]... muc) throws Exception {
        Path file = thuMuc.resolve(ten);
        try (OutputStream os = Files.newOutputStream(file); ZipOutputStream zos = new ZipOutputStream(os)) {
            for (String[] m : muc) {
                zos.putNextEntry(new ZipEntry(m[0]));
                if (m[1] != null) zos.write(m[1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return file;
    }

    private static final int TOI_DA_MUC = 5;
    private static final long TOI_DA_BYTE = 1024;

    @Test
    @DisplayName("UT23 UTCID01 — zip thường giải đúng cây thư mục")
    void ut23_utcid01_zipThuong() throws Exception {
        Path z = zip("ok.zip",
                new String[]{"lib/main.dart", "void main() {}"},
                new String[]{"lib/models/chi_tieu.dart", "class ChiTieu {}"},
                new String[]{"lib/widgets/danh_sach.dart", "class DanhSach {}"});
        Path dich = thuMuc.resolve("ra-1");

        SecureZipExtractor.extract(z, dich, TOI_DA_MUC, TOI_DA_BYTE);

        assertEquals("void main() {}", Files.readString(dich.resolve("lib/main.dart")));
        assertTrue(Files.isRegularFile(dich.resolve("lib/models/chi_tieu.dart")));
        assertTrue(Files.isRegularFile(dich.resolve("lib/widgets/danh_sach.dart")));
    }

    @Test
    @DisplayName("UT23 UTCID02 — file cũ cùng tên bị ghi đè hẳn, không còn đuôi thừa")
    void ut23_utcid02_ghiDeFileCu() throws Exception {
        Path dich = Files.createDirectories(thuMuc.resolve("ra-2/lib"));
        Files.writeString(dich.resolve("main.dart"), "NOI DUNG CU RAT DAI CAN PHAI BI CAT BO");
        Path z = zip("de.zip", new String[]{"lib/main.dart", "moi"});

        SecureZipExtractor.extract(z, thuMuc.resolve("ra-2"), TOI_DA_MUC, TOI_DA_BYTE);

        assertEquals("moi", Files.readString(dich.resolve("main.dart")),
                "TRUNCATE_EXISTING: không được để sót đuôi của nội dung cũ");
    }

    @Test
    @DisplayName("UT23 UTCID03 — zip slip: entry trỏ ra ngoài thư mục đích bị chặn")
    void ut23_utcid03_zipSlip() throws Exception {
        Path z = zip("slip.zip", new String[]{"../../thoat.dart", "xin chao"});
        Path dich = thuMuc.resolve("ra-3");

        IllegalArgumentException loi = assertThrows(IllegalArgumentException.class,
                () -> SecureZipExtractor.extract(z, dich, TOI_DA_MUC, TOI_DA_BYTE));

        assertEquals("Golden ZIP chứa đường dẫn vượt thư mục đích", loi.getMessage());
        assertFalse(Files.exists(thuMuc.resolve("thoat.dart")), "không được ghi ra ngoài");
    }

    @Test
    @DisplayName("UT23 UTCID04/05 — tên entry rỗng hoặc chứa ký tự NUL bị chặn")
    void ut23_utcid04_05_tenEntryKhongHopLe() throws Exception {
        Path rong = zip("rong.zip", new String[]{"", "x"});
        assertEquals("Golden ZIP chứa tên file không hợp lệ",
                assertThrows(IllegalArgumentException.class,
                        () -> SecureZipExtractor.extract(rong, thuMuc.resolve("ra-4"), TOI_DA_MUC, TOI_DA_BYTE))
                        .getMessage(), "UTCID04");

        Path nul = zip("nul.zip", new String[]{"lib/a\u0000b.dart", "x"});
        assertEquals("Golden ZIP chứa tên file không hợp lệ",
                assertThrows(IllegalArgumentException.class,
                        () -> SecureZipExtractor.extract(nul, thuMuc.resolve("ra-5"), TOI_DA_MUC, TOI_DA_BYTE))
                        .getMessage(), "UTCID05");
    }

    @Test
    @DisplayName("UT23 UTCID06 — đúng trần số entry vẫn giải được")
    void ut23_utcid06_dungTranSoEntry() throws Exception {
        Path z = zip("nam.zip",
                new String[]{"a1.dart", "a"}, new String[]{"a2.dart", "a"}, new String[]{"a3.dart", "a"},
                new String[]{"a4.dart", "a"}, new String[]{"a5.dart", "a"});

        assertDoesNotThrow(() ->
                SecureZipExtractor.extract(z, thuMuc.resolve("ra-6"), TOI_DA_MUC, TOI_DA_BYTE));
    }

    @Test
    @DisplayName("UT23 UTCID07 — vượt trần số entry bị chặn, lời báo nêu đúng trần")
    void ut23_utcid07_vuotTranSoEntry() throws Exception {
        Path z = zip("sau.zip",
                new String[]{"a1.dart", "a"}, new String[]{"a2.dart", "a"}, new String[]{"a3.dart", "a"},
                new String[]{"a4.dart", "a"}, new String[]{"a5.dart", "a"}, new String[]{"a6.dart", "a"});

        assertEquals("Golden ZIP có quá nhiều file (tối đa 5)",
                assertThrows(IllegalArgumentException.class,
                        () -> SecureZipExtractor.extract(z, thuMuc.resolve("ra-7"), TOI_DA_MUC, TOI_DA_BYTE))
                        .getMessage());
    }

    @Test
    @DisplayName("UT23 UTCID08/09 — zip chỉ có thư mục, và zip rỗng, đều không nổ")
    void ut23_utcid08_09_khongCoFileThuong() throws Exception {
        Path chiThuMuc = zip("dir.zip", new String[]{"lib/", null});
        Path dich = thuMuc.resolve("ra-8");
        assertDoesNotThrow(() -> SecureZipExtractor.extract(chiThuMuc, dich, TOI_DA_MUC, TOI_DA_BYTE));
        assertTrue(Files.isDirectory(dich.resolve("lib")), "UTCID08: vẫn phải tạo thư mục");

        Path rong = zip("empty.zip");
        Path dich2 = thuMuc.resolve("ra-9");
        assertDoesNotThrow(() -> SecureZipExtractor.extract(rong, dich2, TOI_DA_MUC, TOI_DA_BYTE));
        assertTrue(Files.isDirectory(dich2), "UTCID09: thư mục đích vẫn được tạo");
    }

    @Test
    @DisplayName("UT23 UTCID10 — đúng trần dung lượng giải nén vẫn qua")
    void ut23_utcid10_dungTranDungLuong() throws Exception {
        Path z = zip("vua.zip", new String[]{"a.dart", "x".repeat(1024)});

        assertDoesNotThrow(() ->
                SecureZipExtractor.extract(z, thuMuc.resolve("ra-10"), TOI_DA_MUC, TOI_DA_BYTE));
    }

    @Test
    @DisplayName("UT23 UTCID11 — zip bomb: vượt trần dung lượng thì dừng giữa chừng")
    void ut23_utcid11_vuotTranDungLuong() throws Exception {
        Path z = zip("bomb.zip", new String[]{"a.dart", "x".repeat(1025)});

        assertEquals("Golden ZIP giải nén vượt giới hạn 1024 byte",
                assertThrows(IllegalArgumentException.class,
                        () -> SecureZipExtractor.extract(z, thuMuc.resolve("ra-11"), TOI_DA_MUC, TOI_DA_BYTE))
                        .getMessage());
    }

    // ══════════════════ UT24 — SubmissionPackagePolicy.load ══════════════════

    /** Danh sách nền khi đề chưa khai gì — phải khớp dependency có sẵn trong grading-base. */
    private static final List<String> NEN = List.of("flutter", "flutter_test", "flutter_riverpod",
            "path", "sqflite", "path_provider", "image_picker", "intl");

    private Path deVoiContract(String ten, String json) throws Exception {
        Path dir = Files.createDirectories(thuMuc.resolve(ten));
        if (json != null) Files.writeString(dir.resolve("contract.json"), json, StandardCharsets.UTF_8);
        return dir;
    }

    @Test
    @DisplayName("UT24 UTCID01 — contract khai allowed_packages thì policy là explicit")
    void ut24_utcid01_khaiTuongMinh() throws Exception {
        Path de = deVoiContract("tc1", "{\"allowed_packages\":[\"intl\",\"sqflite\"]}");

        SubmissionPackagePolicy.Policy p = policy.load(de.toString());

        assertTrue(p.explicit(), "đề đã khai thì phải là explicit");
        assertEquals(java.util.Set.of("flutter", "intl", "sqflite"), p.allowedPackages(),
                "flutter luôn được thêm; danh sách nền KHÔNG được giữ lại khi đề đã khai");
        assertEquals(java.util.Set.of("exam_project"), p.localPackageNames());
    }

    @Test
    @DisplayName("UT24 UTCID02 — khai thêm local_package_names thì tên starter cũng là nội bộ")
    void ut24_utcid02_khaiTenNoiBo() throws Exception {
        Path de = deVoiContract("tc2",
                "{\"allowed_packages\":[\"intl\"],\"local_package_names\":[\"quan_ly_chi_tieu\"]}");

        SubmissionPackagePolicy.Policy p = policy.load(de.toString());

        assertTrue(p.localPackageNames().containsAll(List.of("exam_project", "quan_ly_chi_tieu")));
    }

    @Test
    @DisplayName("UT24 UTCID03 — allowed_packages rỗng nghĩa là chỉ còn flutter, không rơi về nền")
    void ut24_utcid03_khaiMangRong() throws Exception {
        Path de = deVoiContract("tc3", "{\"allowed_packages\":[]}");

        SubmissionPackagePolicy.Policy p = policy.load(de.toString());

        assertEquals(java.util.Set.of("flutter"), p.allowedPackages());
        assertTrue(p.explicit());
    }

    @Test
    @DisplayName("UT24 UTCID04/05 — allowed_packages sai kiểu hoặc thiếu thì giữ danh sách nền")
    void ut24_utcid04_05_khaiSaiKieuHoacThieu() throws Exception {
        for (String[] ca : List.of(
                new String[]{"UTCID04", "tc4", "{\"allowed_packages\":\"intl\"}"},
                new String[]{"UTCID05", "tc5", "{\"schema_version\":1}"})) {
            SubmissionPackagePolicy.Policy p = policy.load(deVoiContract(ca[1], ca[2]).toString());

            assertFalse(p.explicit(), ca[0] + ": không khai được thì không phải explicit");
            assertTrue(p.allowedPackages().containsAll(NEN), ca[0] + ": phải giữ nguyên danh sách nền");
        }
    }

    @Test
    @DisplayName("UT24 UTCID06 — tên package không hợp lệ bị bỏ qua, KHÔNG ném")
    void ut24_utcid06_tenKhongHopLeBiBoQua() throws Exception {
        Path de = deVoiContract("tc6", "{\"allowed_packages\":[\"intl\",123,\"Sai-Ten\"]}");

        SubmissionPackagePolicy.Policy p = policy.load(de.toString());

        assertEquals(java.util.Set.of("flutter", "intl"), p.allowedPackages(),
                "123 và Sai-Ten phải bị loại lặng lẽ, không làm hỏng cả policy");
    }

    static Stream<Arguments> ut24_khongDocDuoc() {
        return Stream.of(
                Arguments.of("UTCID07", (String) null),
                Arguments.of("UTCID08", ""),
                Arguments.of("UTCID09", "   ")
        );
    }

    @ParameterizedTest(name = "UT24 {0}: đường dẫn trống")
    @MethodSource("ut24_khongDocDuoc")
    @DisplayName("UT24 — đường dẫn testcase trống thì rơi về danh sách nền")
    void ut24_duongDanTrong(String utcid, String duong) {
        SubmissionPackagePolicy.Policy p = policy.load(duong);

        assertFalse(p.explicit(), utcid);
        assertTrue(p.allowedPackages().containsAll(NEN), utcid);
    }

    @Test
    @DisplayName("UT24 UTCID10 — không có contract.json thì rơi về danh sách nền")
    void ut24_utcid10_khongCoContract() throws Exception {
        Path de = deVoiContract("tc10", null);

        SubmissionPackagePolicy.Policy p = policy.load(de.toString());

        assertFalse(p.explicit());
        assertTrue(p.allowedPackages().containsAll(NEN));
    }

    @Test
    @DisplayName("UT24 UTCID11 — contract.json hỏng KHÔNG được âm thầm cấm nhầm cả bài")
    void ut24_utcid11_contractHong() throws Exception {
        Path de = deVoiContract("tc11", "{\"allowed_packages\": [\"intl\"");   // JSON cụt

        SubmissionPackagePolicy.Policy p = policy.load(de.toString());

        assertFalse(p.explicit());
        assertTrue(p.allowedPackages().containsAll(NEN),
                "policy hỏng mà rơi về tập rỗng là cấm oan mọi bài — phải rơi về danh sách nền");
    }

    // ══════════════════ UT25 — SubmissionPackagePolicy.validateAndNormalize ══════════════════

    private static final SubmissionPackagePolicy.Policy POLICY_MAU = new SubmissionPackagePolicy.Policy(
            java.util.Set.of("flutter", "intl"),
            java.util.Set.of("exam_project", "quan_ly_chi_tieu"),
            true);

    /** Dựng thư mục lib/ một file với nội dung cho trước. */
    private Path lib(String ten, String noiDung) throws Exception {
        Path lib = Files.createDirectories(thuMuc.resolve(ten).resolve("lib"));
        Files.writeString(lib.resolve("main.dart"), noiDung, StandardCharsets.UTF_8);
        return lib;
    }

    static Stream<Arguments> ut25_khongViPham() {
        return Stream.of(
                Arguments.of("UTCID01", "goi-duoc-phep", "import 'package:intl/intl.dart';\nvoid main() {}"),
                Arguments.of("UTCID04", "comment-dong", "// import 'package:dio/dio.dart';\nvoid main() {}"),
                Arguments.of("UTCID05", "comment-khoi", "/* import 'package:dio/dio.dart'; */\nvoid main() {}"),
                Arguments.of("UTCID06", "chuoi-thuong", "final s = \"package:dio/dio.dart\";\nvoid main() {}")
        );
    }

    @ParameterizedTest(name = "UT25 {0}: {1}")
    @MethodSource("ut25_khongViPham")
    @DisplayName("UT25 — import hợp lệ và ví dụ trong comment đều không bị coi là vi phạm")
    void ut25_khongViPham(String utcid, String ten, String nguon) throws Exception {
        Path lib = lib(ten, nguon);

        assertDoesNotThrow(() -> policy.validateAndNormalize(lib, POLICY_MAU), utcid);
        assertEquals(nguon, Files.readString(lib.resolve("main.dart")),
                utcid + ": không có gì phải sửa thì file phải nguyên vẹn");
    }

    @Test
    @DisplayName("UT25 UTCID02 — import theo tên starter được viết lại thành exam_project")
    void ut25_utcid02_doiTenGoiNoiBo() throws Exception {
        Path lib = lib("noi-bo", "import 'package:quan_ly_chi_tieu/models/chi_tieu.dart';\nvoid main() {}");

        policy.validateAndNormalize(lib, POLICY_MAU);

        assertEquals("import 'package:exam_project/models/chi_tieu.dart';\nvoid main() {}",
                Files.readString(lib.resolve("main.dart")),
                "container luôn đặt tên dự án là exam_project nên import nội bộ phải đổi theo");
    }

    @Test
    @DisplayName("UT25 UTCID08 — package lạ nhưng file có thật trong lib/ vẫn tính là nội bộ")
    void ut25_utcid08_tenLaNhungFileCoThat() throws Exception {
        Path lib = lib("suy-tu-file", "import 'package:mo_ta_khac/a.dart';\nvoid main() {}");
        Files.writeString(lib.resolve("a.dart"), "class A {}", StandardCharsets.UTF_8);

        policy.validateAndNormalize(lib, POLICY_MAU);

        assertEquals("import 'package:exam_project/a.dart';\nvoid main() {}",
                Files.readString(lib.resolve("main.dart")));
    }

    static Stream<Arguments> ut25_viPham() {
        return Stream.of(
                Arguments.of("UTCID03", "import-ngoai", "import 'package:dio/dio.dart';\nvoid main() {}"),
                // Dart chọn URI ở nhánh điều kiện lúc chạy — MỌI nhánh đều phải thuộc policy.
                Arguments.of("UTCID07", "import-dieu-kien",
                        "import 'x.dart' if (dart.library.io) 'package:dio/io.dart';\nvoid main() {}"),
                Arguments.of("UTCID09", "export-ngoai", "export 'package:dio/dio.dart';\nvoid main() {}")
        );
    }

    @ParameterizedTest(name = "UT25 {0}: {1}")
    @MethodSource("ut25_viPham")
    @DisplayName("UT25 — dùng package ngoài danh sách là KẾT QUẢ 0 điểm, không phải sự cố hệ thống")
    void ut25_dungPackageNgoai(String utcid, String ten, String nguon) throws Exception {
        Path lib = lib(ten, nguon);

        GradingDiagnosticException loi = assertThrows(GradingDiagnosticException.class,
                () -> policy.validateAndNormalize(lib, POLICY_MAU), utcid);

        assertEquals("EXTERNAL_PACKAGE", loi.code(), utcid);
        assertEquals(GradingDiagnosticException.Origin.STUDENT, loi.origin(), utcid);
        assertEquals("DEPENDENCY_PREFLIGHT", loi.stage(), utcid);
        assertFalse(loi.manualReview(),
                utcid + ": sai hướng dẫn của đề là 0 điểm, KHÔNG được đẩy sang chấm tay");
        assertTrue(loi.getMessage().contains("dio"), utcid + ": lời báo phải nêu tên package");
        assertTrue(loi.getMessage().contains("main.dart"),
                utcid + ": lời báo phải chỉ ra file nào vi phạm");
    }

    @Test
    @DisplayName("UT25 UTCID10 — nhiều file, chỉ một file vi phạm thì vẫn phải bắt được")
    void ut25_utcid10_motFileViPhamGiuaNhieuFile() throws Exception {
        Path lib = lib("nhieu-file", "import 'package:intl/intl.dart';\nvoid main() {}");
        Files.writeString(lib.resolve("sach.dart"), "class Sach {}", StandardCharsets.UTF_8);
        Files.writeString(lib.resolve("mang.dart"), "import 'package:dio/dio.dart';", StandardCharsets.UTF_8);

        GradingDiagnosticException loi = assertThrows(GradingDiagnosticException.class,
                () -> policy.validateAndNormalize(lib, POLICY_MAU));

        assertTrue(loi.getMessage().contains("mang.dart"), "phải chỉ đúng file vi phạm: " + loi.getMessage());
    }

    @Test
    @DisplayName("UT25 UTCID11 — thư mục lib/ rỗng thì không có gì để phán, không nổ")
    void ut25_utcid11_thuMucRong() throws Exception {
        Path lib = Files.createDirectories(thuMuc.resolve("rong").resolve("lib"));

        assertDoesNotThrow(() -> policy.validateAndNormalize(lib, POLICY_MAU));
    }
}
