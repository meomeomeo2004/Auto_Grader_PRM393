package com.example.grader.service;

import com.example.grader.entity.Exam;
import com.example.grader.repository.ExamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LOẠI ĐỀ — trục của màn "Đề bài" (20/9/2026).
 *
 * <p>Trước đây một đề có thể vừa có file Word gốc vừa có {@code de_bai.md}, vì upload bóc chữ
 * ngầm; giảng viên không biết mình sửa bản nào rồi tải bản nào. Nay mỗi đề mang đúng một bản
 * chính, và phần khó nhất là ĐỀ CŨ: chúng không có dấu loại nào, phải suy ra sao cho không
 * bỗng dưng đổi nghĩa dưới chân người dùng.
 */
class LoaiDeTest {

    @TempDir Path temp;
    private ExamService dichVu;
    private Path handout;

    @BeforeEach
    void dung() throws Exception {
        handout = Files.createDirectories(temp.resolve("exams/DE/handout"));
        dichVu = new ExamService();
        ReflectionTestUtils.setField(dichVu, "templateDir",
                Files.createDirectories(temp.resolve("tmpl")).toString());
        ReflectionTestUtils.setField(dichVu, "examsDir", temp.resolve("exams").toString());
        ExamRepository repo = mock(ExamRepository.class);
        when(repo.findByExamId(anyString())).thenReturn(Optional.empty());
        when(repo.save(any(Exam.class))).thenAnswer(i -> i.getArgument(0));
        ReflectionTestUtils.setField(dichVu, "examRepository", repo);
    }

    private void fileGoc() throws Exception {
        Files.write(handout.resolve("original.docx"), new byte[] {1, 2, 3});
    }

    private void deBai(String noiDung) throws Exception {
        Files.writeString(handout.resolve("de_bai.md"), noiDung, StandardCharsets.UTF_8);
    }

    // ==================== SUY RA CHO ĐỀ CŨ ====================

    @Test
    void chiCoFileGocThiLaDeNhapNgoai() throws Exception {
        fileGoc();

        assertEquals(ExamService.DE_NGOAI, dichVu.loaiDe("DE"));
    }

    @Test
    void coCaHaiThiCoiLaSoanTrongHeThong() throws Exception {
        // Đề cũ bị bóc chữ ngầm nên có cả hai. Phải nghiêng về TRONG: đó là bản mà mọi chức
        // năng hiện hành (AI, hình, xem đề, xuất .docx) đang chạy trên đó. Chọn NGOAI là
        // bỗng dưng khoá mất phần nội dung giảng viên đã sửa trong app.
        fileGoc();
        deBai("# Đề bài");

        assertEquals(ExamService.DE_TRONG, dichVu.loaiDe("DE"));
    }

    @Test
    void khongCoGiCaThiLaDeSoanTrongHeThongConTrong() {
        assertEquals(ExamService.DE_TRONG, dichVu.loaiDe("DE"));
    }

    @Test
    void deChuaTonTaiCungKhongNem() {
        assertEquals(ExamService.DE_TRONG, dichVu.loaiDe("KHONG_CO_DE_NAY"));
    }

    // ==================== DẤU ĐÃ GHI THẮNG PHÉP SUY RA ====================

    @Test
    void dauDaGhiThiTheoDau_khongSuyRaNua() throws Exception {
        fileGoc();
        deBai("# Đề bài");                       // suy ra sẽ ra TRONG
        Files.writeString(handout.resolve("loai_de.txt"), ExamService.DE_NGOAI, StandardCharsets.UTF_8);

        assertEquals(ExamService.DE_NGOAI, dichVu.loaiDe("DE"),
                "đã khai rõ thì đừng đoán lại");
    }

    @Test
    void dauRacThiBoQua_quayVePhepSuyRa() throws Exception {
        fileGoc();
        Files.writeString(handout.resolve("loai_de.txt"), "LUNG_TUNG", StandardCharsets.UTF_8);

        assertEquals(ExamService.DE_NGOAI, dichVu.loaiDe("DE"));
    }

    // ==================== UPLOAD KHÔNG CÒN BÓC CHỮ NGẦM ====================

    @Test
    void taiFileLenKhiDeConTrongThiFileDoLaBanChinh() throws Exception {
        dichVu.saveOriginalHandoutFile("DE", "Đề X", "de.docx", new byte[] {1, 2, 3});

        assertEquals(ExamService.DE_NGOAI, dichVu.loaiDe("DE"));
        assertTrue(Files.notExists(handout.resolve("de_bai.md")),
                "KHÔNG được bóc chữ ngầm — đó là cách đề tự nhiên có hai bản");
    }

    @Test
    void taiFileLenKhiDeDaCoNoiDungThiChiLaDinhKem() throws Exception {
        deBai("# Đề bài đã soạn trong hệ thống");

        dichVu.saveOriginalHandoutFile("DE", "Đề X", "tham-khao.docx", new byte[] {1, 2, 3});

        assertEquals(ExamService.DE_TRONG, dichVu.loaiDe("DE"),
                "đề đang soạn trong hệ thống thì file tải lên là tài liệu đính kèm, không phải đề bài");
        assertEquals("# Đề bài đã soạn trong hệ thống",
                Files.readString(handout.resolve("de_bai.md"), StandardCharsets.UTF_8));
    }

    // ==================== LƯU LÀ ĐỔI LOẠI ====================

    /**
     * Luật từ 20/9: loại đề do HÀNH ĐỘNG quyết, không do một nút khai báo. Tải lên là NGOAI,
     * LƯU lần đầu là TRONG. Trước đó phải bấm "Đưa vào hệ thống" mới xem/sửa được — bắt người
     * dùng khai một điều mà thao tác của họ đã nói rồi.
     */
    @Test
    void luuDeBaiLanDauThiDeThanhSoanTrongHeThong() throws Exception {
        fileGoc();
        assertEquals(ExamService.DE_NGOAI, dichVu.loaiDe("DE"), "chưa ai sửa thì file Word là bản chính");

        dichVu.saveDeBaiWithMockups("DE", "# Giảng viên vừa sửa", java.util.List.of());

        assertEquals(ExamService.DE_TRONG, dichVu.loaiDe("DE"));
        assertTrue(Files.isRegularFile(handout.resolve("original.docx")),
                "file Word cũ GIỮ LẠI làm tài liệu đính kèm, không xoá");
    }

    /** Đề chưa ai sửa vẫn phải ĐỌC được nội dung — đó là thứ thay cho bước "Đưa vào hệ thống". */
    @Test
    void deChuaSuaVanDocDuocNoiDungTuFileWord() throws Exception {
        Files.write(handout.resolve("original.docx"), wordToiThieu());
        ReflectionTestUtils.setField(dichVu, "documentReader", new ExamDocumentReader());

        String md = dichVu.readDeBai("DE");

        assertTrue(md != null && md.contains("de thi thuc hanh quan ly thu vien"), String.valueOf(md));
        assertTrue(Files.notExists(handout.resolve("de_bai.md")),
                "chỉ ĐỌC để hiển thị, tuyệt đối không ghi xuống đĩa — ghi ra là tự tạo bản thứ hai");
        assertEquals(ExamService.DE_NGOAI, dichVu.loaiDe("DE"), "đọc để xem không phải là sửa");
    }

    private static byte[] wordToiThieu() throws Exception {
        String ns = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        var ra = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(ra)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
            zip.write(("<w:document xmlns:w=\"" + ns + "\"><w:body><w:p><w:r><w:t>Noi dung tu Word: de thi thuc hanh quan ly thu vien, thoi gian 120 phut.</w:t>"
                    + "</w:r></w:p></w:body></w:document>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return ra.toByteArray();
    }
}
