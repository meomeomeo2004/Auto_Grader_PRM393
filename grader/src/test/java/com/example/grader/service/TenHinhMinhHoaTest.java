package com.example.grader.service;

import com.example.grader.repository.ExamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TÊN HÌNH MINH HOẠ — nó là chữ IN RA ĐỀ (thẻ h3 phía trên mỗi hình, xem
 * {@link HandoutDocument#toHtml}), không phải nhãn nội bộ.
 *
 * <p>Lúc lưu, frontend chỉ gửi {@code {id, svg}} nên tên không đi kèm. Trước 20/9/2026 máy chủ chế
 * tên từ mã file, và đề phát cho sinh viên có chú thích kiểu "man danh sach" — mất dấu, gạch nối
 * thành khoảng trắng; ảnh giáo viên tự tải lên còn tệ hơn, thành "anh mu9j97jy". Nay tên đi THEO
 * FILE qua {@code aria-label} trong chính SVG, nên không có nguồn sự thật thứ hai để lệch.
 */
class TenHinhMinhHoaTest {

    @TempDir Path temp;
    private ExamService dichVu;
    private Path mockupDir;

    @BeforeEach
    void dung() throws Exception {
        mockupDir = Files.createDirectories(temp.resolve("DE/handout/mockup"));
        dichVu = new ExamService();
        ExamRepository repo = mock(ExamRepository.class);
        when(repo.findByExamId(anyString())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(dichVu, "examRepository", repo);
        ReflectionTestUtils.setField(dichVu, "examsDir", temp.toString());
        ReflectionTestUtils.setField(dichVu, "templateDir", temp.resolve("tmpl").toString());
    }

    private void hinh(String ten, String svg) throws Exception {
        Files.writeString(mockupDir.resolve(ten + ".svg"), svg, StandardCharsets.UTF_8);
    }

    @Test
    void hinhAiVeGiuDungTenManHinhCoDau() throws Exception {
        hinh("man-danh-sach", MockupRenderer.render("Màn hình danh sách khoản chi",
                List.of(new MockupRenderer.Element("app_bar", "Quản lý chi tiêu"))));

        assertEquals("Màn hình danh sách khoản chi", dichVu.readMockups("DE").get(0).title());
    }

    @Test
    void anhGiaoVienTaiLenGiuDungTenFile() throws Exception {
        // Đúng dạng frontend/lib/mockup-image.ts#imageFileToSvg dựng ra.
        hinh("anh-mu9j97jy", "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\""
                + " width=\"10\" height=\"10\" role=\"img\" aria-label=\"Thiết kế màn danh sách\">"
                + "<rect width=\"10\" height=\"10\"/></svg>");

        assertEquals("Thiết kế màn danh sách", dichVu.readMockups("DE").get(0).title(),
                "không có nhãn thì chú thích dưới ảnh in ra đề là mã sinh tự động, vô nghĩa với sinh viên");
    }

    @Test
    void tenCoKyTuMaHoaXmlDuocGoLaiDungChuThat() throws Exception {
        hinh("man-loc", MockupRenderer.render("Lọc \"Thu\" & <Chi>",
                List.of(new MockupRenderer.Element("app_bar", "Lọc"))));

        assertEquals("Lọc \"Thu\" & <Chi>", dichVu.readMockups("DE").get(0).title());
    }

    @Test
    void svgKhongCoNhanThiQuayVeMaHinh_khongNem() throws Exception {
        hinh("hinh-cu", "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"></svg>");

        assertEquals("hinh cu", dichVu.readMockups("DE").get(0).title(),
                "hình lưu từ bản cũ vẫn phải đọc được, chỉ là tên xấu như trước");
    }

    /** Tên đi vào đề bài phải được rào chắn lại lúc dựng HTML, không phải rào một lần rồi thôi. */
    @Test
    void tenGoRaRoiVaoDeBaiVanDuocRaoChanLai() throws Exception {
        hinh("man-x", MockupRenderer.render("<script>alert(1)</script>",
                List.of(new MockupRenderer.Element("app_bar", "X"))));

        String html = HandoutDocument.toHtml("DE", "Đề", "# Đề", dichVu.readMockups("DE"));

        assertTrue(html.contains("&lt;script&gt;"), html);
        assertTrue(html.indexOf("<h3>&lt;script&gt;") > 0, "tên hình nằm ở thẻ h3 trên mỗi hình");
    }
}
