package com.example.grader.service;

import com.example.grader.service.MockupRenderer.Element;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HÌNH MINH HOẠ GIAO DIỆN — bất biến về KHUNG (20/9/2026).
 *
 * <p>Đề thi in ra giấy, sinh viên nhìn hình rồi dựng lại màn hình. Hình sai dáng là cả lứa bài
 * dựng sai bố cục mà không ai kịp phát hiện — nên dáng khung là thứ phải khoá bằng test, không
 * phải thứ để mắt thường canh.
 */
class MockupRendererTest {

    private static Element e(String type, String label) { return new Element(type, label); }

    // ==================== DÁNG KHUNG ====================

    /**
     * Hai con số RẤT dễ lẫn: 412×915 là CẢ MÀN HÌNH, 412×838 là phần app được vẽ (chênh 77 dp mà
     * Android giữ cho thanh trạng thái + thanh điều hướng). Hình này vẽ cả máy — có thanh trạng
     * thái, có thanh điều hướng — nên dáng NGOÀI phải theo 915. Lấy nhầm 838 làm dáng ngoài là
     * đếm chrome hai lần: tự vẽ thanh trạng thái vào đúng vùng đã bị trừ nó ra rồi.
     */
    @Test
    void dangNgoaiTheoCaManHinh_ruotTheoVungAppDuocVe() {
        assertEquals(330, MockupRenderer.WIDTH, "412 × 4/5");
        assertEquals(733, MockupRenderer.HEIGHT);

        assertEquals(915.0 / 412.0, (double) MockupRenderer.HEIGHT / MockupRenderer.WIDTH, 0.005,
                "dáng NGOÀI phải là cả máy 412×915");
        assertEquals(838.0 / 412.0, (double) MockupRenderer.VUNG_APP_H / MockupRenderer.WIDTH, 0.005,
                "vùng app BÊN TRONG phải là 412×838 — dáng ngoài đúng mà ruột lệch thì vẫn sai");
    }

    /** Ngắn hay dài gì thì mọi hình vẫn phải bằng nhau: đó là điều kiện để xếp cạnh nhau trong đề
     *  bài và để bản .docx không có hình thì bé tí hình thì chiếm trọn trang. */
    @Test
    void moiManDeuMotCoDuNoiDungDaiHayNgan() {
        String coCo = "width=\"330\" height=\"733\"";
        assertTrue(mot(e("text", "một dòng")).contains(coCo));
        assertTrue(dai(30).contains(coCo));
    }

    @Test
    void noiDungQuaDaiThiThuNhoRoiCat_khongLoiRaNgoaiVien() {
        String svg = dai(30);
        assertTrue(svg.contains("scale(0.72"), "phải thu nhỏ tới sàn 0,72: " + svg);
        assertTrue(svg.contains("⋯"), "cắt rồi thì phải báo là màn còn cuộn tiếp");
    }

    @Test
    void noiDungVuaKhungThiKhongThuNhoVaKhongCat() {
        String svg = dai(6);
        assertTrue(svg.contains("scale(1)"), svg);
        assertFalse(svg.contains("⋯"), "vừa khung mà vẫn báo cắt là nói dối người soạn");
    }

    // ==================== KHUNG MÁY LUÔN ĐỦ BỘ ====================

    @Test
    void quenKhaiAppBarThiLayLuonTenManLamTieuDe() {
        String svg = mot(e("text", "nội dung"));
        assertTrue(svg.contains("Tên màn hình"),
                "thiếu app_bar mà bỏ trống thanh tiêu đề thì hình nhìn như một tờ giấy, không ra máy");
    }

    @Test
    void thanhDuoiCuaAppNamTrongVungApp_khongDeLenThanhHeDieuHanh() {
        String svg = MockupRenderer.render("Màn", List.of(
                e("app_bar", "Màn"), e("text", "x"), e("bottom_nav", "Danh sách|Thống kê|Cá nhân")));
        // Thanh của app bắt đầu ở 733 − 38 (thanh hệ điều hành) − 54 (thanh app) = 641.
        assertTrue(svg.contains("y=\"641\""), svg);
        assertTrue(svg.contains("Thống kê") && svg.contains("Cá nhân"), svg);
    }

    /** Nhãn thanh dưới là tiếng Việt có dấu; tra tên icon mà không bỏ dấu thì màn nào cũng ra một
     *  hàng ô vuông trống. */
    @Test
    void nhanTiengVietVanRaDungIcon() {
        String svg = MockupRenderer.render("Màn", List.of(e("bottom_nav", "Danh sách|Thống kê|Cá nhân")));
        assertFalse(svg.contains("rx=\"3\" fill=\"none\""), "còn ô vuông tức là tra icon trượt: " + svg);
    }

    // ==================== AN TOÀN KHI NHÚNG CHUNG MỘT TRANG ====================

    /**
     * Cả chục hình cùng nhúng thẳng vào MỘT file de_bai.html. Dùng {@code <defs>}/{@code clipPath}
     * là id trùng nhau, trình duyệt lấy cái đầu tiên cho tất cả — hình thứ hai trở đi cắt theo
     * khung của hình thứ nhất.
     */
    @Test
    void khongPhatSinhIdDungChung() {
        String svg = dai(30);
        assertFalse(svg.contains("<defs"), svg);
        assertFalse(svg.contains(" id=\""), svg);
    }

    @Test
    void chuCuaNguoiDungLuonDuocRaoChanXml() {
        String svg = MockupRenderer.render("<script>", List.of(e("heading", "A & B <c>")));
        assertFalse(svg.contains("<script>"), svg);
        assertTrue(svg.contains("A &amp; B &lt;c&gt;"), svg);
    }

    @Test
    void thieuTruongVaLoaiLaDeuKhongNem() {
        String svg = MockupRenderer.render("Màn", List.of(
                new Element(null, null, null, null, null),
                new Element("loai_khong_co", "x", null, null, List.of()),
                e("divider", "")));
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"));
    }

    private static String mot(Element el) {
        return MockupRenderer.render("Tên màn hình", List.of(el));
    }

    private static String dai(int soO) {
        List<Element> ds = new ArrayList<>();
        ds.add(e("app_bar", "Màn dài"));
        for (int i = 1; i <= soO; i++) ds.add(new Element("input", "Trường " + i, "gợi ý " + i, "", List.of()));
        return MockupRenderer.render("Màn dài", ds);
    }
}
