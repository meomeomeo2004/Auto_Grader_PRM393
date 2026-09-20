package com.example.grader.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Vẽ hình minh họa (khung dây/wireframe) của một màn hình thành SVG THẬT, tất định — không qua
 * AI. AI (xem {@code AiExamAuthorService#proposeMockups}) chỉ mô tả màn hình bằng danh sách
 * {@link Element}; lớp này ghép chúng thành SVG luôn hợp lệ về cú pháp, xuống dòng đều, không
 * lệch khung — tránh rủi ro LLM tự sinh XML tự do ra thẻ hỏng.
 *
 * <p><b>KHUNG MÁY THẬT (20/9/2026).</b> Trước đây hình là một dải trắng rộng 320, cao bao nhiêu
 * tuỳ số thành phần — nhìn không ra màn điện thoại, và tệ hơn là SAI DÁNG: giáo viên vẽ một màn
 * gần vuông rồi sinh viên dựng ra một màn cao gấp đôi. Nay hình là CẢ MỘT MÁY: thanh trạng thái ·
 * app bar · vùng nội dung · thanh dưới của app · nút nổi · thanh điều hướng hệ điều hành.
 *
 * <p>Dáng ngoài theo {@code 412×915} dp — CẢ MÀN HÌNH của máy ảo Pixel 7 API 34 — chứ không phải
 * {@code 412×838} là phần app được vẽ. Lẫn hai số này là vẽ thanh trạng thái vào bên trong vùng
 * mà thanh trạng thái vốn đã bị trừ ra, tức đếm chrome hai lần và hình cao hụt đi 77 dp.
 *
 * <p>Vì khung CAO CỐ ĐỊNH nên nội dung dài phải tự liệu: thu nhỏ đều cho vừa, chạm sàn 0,72 thì
 * cắt phần thừa và vẽ dấu "⋯" — đúng như một màn hình cuộn được ngoài đời. Không bao giờ để nội
 * dung tràn ra ngoài viền máy.
 *
 * <p>SVG sinh ra được lưu trực tiếp làm {@link HandoutDocument.Mockup#svg()}, nên style/màu ở đây
 * cố tình đơn giản (đường viền + chữ, không gradient/ảnh) để nhúng thẳng vào {@code de_bai.html}
 * và xuất sang .docx/.pdf qua đường trình duyệt rasterize hiện có (xem {@code mockup-image.ts}).
 * KHÔNG dùng {@code <defs>}/{@code clipPath}: nhiều hình cùng nhúng vào MỘT trang HTML thì id
 * trùng nhau, trình duyệt lấy cái đầu tiên — nên chỗ nào cần che thì tô đè bằng hình trắng.
 */
public final class MockupRenderer {

    /**
     * Một thành phần trong màn hình.
     *
     * @param type    app_bar|status|tab_bar|search|chip_row|heading|text|input|dropdown|date|
     *                checkbox|button|button_outline|list_item|card|image|total|empty|divider|
     *                fab|bottom_nav — loại lạ rơi về "text"
     * @param label   chữ chính; với tab_bar/chip_row/bottom_nav là danh sách ngăn bởi dấu "|"
     * @param sub     dòng phụ (mô tả dưới tiêu đề, gợi ý trong ô nhập); với app_bar là "back"
     *                hoặc "menu" để chọn icon bên trái
     * @param right   giá trị canh phải (số tiền, ngày, trạng thái)
     * @param actions icon nhỏ: edit|delete|search|filter|add|more|check — ở app_bar là icon góc
     *                phải, ở list_item là icon cuối dòng
     */
    public record Element(String type, String label, String sub, String right, List<String> actions) {
        public Element {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }

        /** Dạng gọn cho thành phần chỉ có chữ chính. */
        public Element(String type, String label) {
            this(type, label, "", "", List.of());
        }
    }

    private MockupRenderer() {}

    // ── Khung máy ────────────────────────────────────────────────
    // HAI con số, đừng lẫn (máy ảo Pixel 7 API 34 — xem BehaviorSuiteMaterializer):
    //   412×915 dp = CẢ MÀN HÌNH, tính từ mép trên xuống mép dưới.
    //   412×838 dp = phần app ĐƯỢC VẼ, sau khi hệ điều hành giữ lại 77 dp cho thanh trạng thái
    //                (~29 dp, Pixel 7 có lỗ khoét camera) và thanh điều hướng (~48 dp).
    // Hình ở đây vẽ CẢ MÁY — có thanh trạng thái, có thanh điều hướng — nên dáng ngoài phải theo
    // 412×915. Lấy 838 làm dáng ngoài là đếm hai lần phần chrome: tự vẽ thêm thanh trạng thái vào
    // trong vùng mà thanh trạng thái vốn đã bị trừ ra rồi.
    // Cỡ ảnh = 4/5 khung: 412 × 4/5 = 329,6 → 330. Chiều cao KHÔNG lấy 915 × 4/5 mà cộng ngược từ
    // ba phần (24 + 671 + 38 = 733) — nhờ thế vùng app bên trong khớp đúng tỉ lệ 838/412, chứ
    // không phải chỉ dáng ngoài khớp còn ruột thì lệch.
    private static final int KHUNG_W = 412;
    private static final int KHUNG_APP_H = 838;     // phần app được vẽ; cả màn hình là 915

    static final int WIDTH = Math.round(KHUNG_W * 4 / 5f);                       // 329,6 → 330
    private static final int STATUS_H = 24;         // thanh trạng thái của hệ điều hành (~29 dp)
    private static final int SYS_NAV_H = 38;        // thanh điều hướng của hệ điều hành (~48 dp)
    /** Vùng app ĐƯỢC VẼ — đúng phần mà ảnh chụp lúc chấm bài lấy được. */
    static final int VUNG_APP_H = Math.round(WIDTH * (float) KHUNG_APP_H / KHUNG_W);   // 671
    static final int HEIGHT = STATUS_H + VUNG_APP_H + SYS_NAV_H;                      // 733

    private static final int RX = 20;          // bo góc thân máy
    private static final int APPBAR_H = 46;
    private static final int NAV_H = 54;       // thanh điều hướng CỦA APP, nằm trong vùng app
    private static final int PAD = 16;
    private static final int INNER_W = WIDTH - PAD * 2;
    private static final double SAN_THU_NHO = 0.72;   // dưới mức này chữ không còn đọc được

    // ── Màu ──────────────────────────────────────────────────────
    private static final String CHINH = "#4f46e5";    // indigo-600
    private static final String CHINH_NHAT = "#eef2ff";
    private static final String CHINH_VIEN = "#c7d2fe";
    private static final String CHU_DAM = "#0f172a";
    private static final String CHU = "#334155";
    private static final String CHU_MO = "#64748b";
    private static final String CHU_RAT_MO = "#94a3b8";
    private static final String VIEN = "#cbd5e1";
    private static final String VIEN_NHAT = "#e2e8f0";
    private static final String NEN_NHAT = "#f1f5f9";
    private static final String TRANG = "#ffffff";

    public static String render(String title, List<Element> elements) {
        List<Element> ds = elements == null ? List.of() : elements;

        // Tách phần "khung" ra khỏi dòng chảy nội dung: ba thứ này KHÔNG xếp hàng cùng các
        // thành phần khác mà bám cứng vào mép máy, đúng như Scaffold của Flutter.
        Element appBar = null, nav = null, fab = null;
        List<Element> luong = new ArrayList<>();
        for (Element el : ds) {
            switch (loai(el)) {
                case "app_bar" -> { if (appBar == null) appBar = el; }
                case "bottom_nav" -> { if (nav == null) nav = el; }
                case "fab" -> { if (fab == null) fab = el; }
                default -> luong.add(el);
            }
        }
        if (appBar == null) appBar = new Element("app_bar", title);

        // Mép dưới vùng app: TRÊN thanh điều hướng của hệ điều hành. Thanh dưới của app (nav) nằm
        // trong vùng app, ngay trên thanh hệ thống — đúng chỗ Scaffold đặt BottomNavigationBar.
        int mepDuoiApp = HEIGHT - SYS_NAV_H;
        int tren = STATUS_H + APPBAR_H + 12;
        int duoi = mepDuoiApp - (nav != null ? NAV_H : 0) - 12;
        int choChua = duoi - tren;

        StringBuilder noiDung = new StringBuilder();
        int cao = 0;
        for (Element el : luong) cao = ve(noiDung, el, cao);

        double ti = cao <= choChua || cao == 0 ? 1.0 : Math.max(SAN_THU_NHO, (double) choChua / cao);
        boolean catBot = cao * ti > choChua;

        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(WIDTH)
           .append(' ').append(HEIGHT).append("\" width=\"").append(WIDTH).append("\" height=\"")
           .append(HEIGHT).append("\" role=\"img\" aria-label=\"")
           .append(HandoutDocument.esc(title)).append("\">\n");

        // Thân máy. width/height GHI SỐ THẬT (khớp viewBox), KHÔNG dùng "100%": SVG này còn được
        // nạp qua new Image() tách rời khỏi trang (frontend/lib/mockup-image.ts#svgToPng, dùng khi
        // tải .docx/.pdf/PNG) — lúc đó không có khung cha nào để "100%" tính theo, trình duyệt rơi
        // về kích thước mặc định rất nhỏ và SAI TỈ LỆ. Co giãn khi hiển thị thì đặt CSS ở nơi NHÚNG.
        rect(svg, 0, 0, WIDTH, HEIGHT, RX, TRANG, null);

        veThanhTren(svg, appBar);

        svg.append("<g transform=\"translate(").append(fmt(WIDTH * (1 - ti) / 2)).append(',')
           .append(tren).append(") scale(").append(fmt(ti)).append(")\">\n")
           .append(noiDung).append("</g>\n");

        if (catBot) {
            // Che phần tràn bằng hình trắng (không clipPath — xem ghi chú đầu lớp), rồi nhoà dần
            // và chấm "⋯": đọc ra ngay là "màn còn cuộn tiếp", không phải "hình bị lỗi". Che tới
            // mép dưới vùng app là đủ — bên dưới đã có thanh của app và của hệ điều hành đè lên.
            rect(svg, 0, duoi, WIDTH, mepDuoiApp - duoi, 0, TRANG, null);
            for (int i = 0; i < 6; i++)
                svg.append("<rect x=\"1\" y=\"").append(duoi - 20 + i * 3).append("\" width=\"")
                   .append(WIDTH - 2).append("\" height=\"3\" fill=\"").append(TRANG)
                   .append("\" opacity=\"").append(fmt(0.16 * i)).append("\"/>\n");
            chu(svg, WIDTH / 2, duoi - 2, "⋯", CHU_RAT_MO, true, "middle", 14);
        }

        if (nav != null) veThanhDuoi(svg, nav, mepDuoiApp - NAV_H);
        if (fab != null) veNutNoi(svg, fab, duoi);
        veThanhHeDieuHanh(svg, mepDuoiApp);

        // Viền vẽ SAU CÙNG nên mọi mảng màu bên trong không đè lên nó ở bốn góc bo.
        svg.append("<rect x=\"0.75\" y=\"0.75\" width=\"").append(WIDTH - 1.5)
           .append("\" height=\"").append(HEIGHT - 1.5).append("\" rx=\"").append(RX)
           .append("\" fill=\"none\" stroke=\"").append(VIEN).append("\" stroke-width=\"1.5\"/>\n");
        svg.append("</svg>");
        return svg.toString();
    }

    // ── Khung: thanh trạng thái + app bar + thanh dưới + nút nổi ──

    /** Thanh trạng thái và app bar dùng chung một mảng màu — đúng cách Android tô status bar
     *  theo màu chủ đạo, và nhờ thế góc trên bo theo thân máy chỉ cần vẽ một lần. */
    private static void veThanhTren(StringBuilder svg, Element bar) {
        int h = STATUS_H + APPBAR_H;
        boGocTren(svg, h, CHINH);

        // Thanh trạng thái: giờ bên trái, sóng/pin bên phải — mấy nét này là thứ khiến người xem
        // nhận ra ngay "đây là điện thoại" trước cả khi đọc chữ.
        chu(svg, PAD, 16, "9:41", TRANG, true, "start", 9);
        for (int i = 0; i < 3; i++)
            svg.append("<rect x=\"").append(WIDTH - PAD - 40 + i * 5).append("\" y=\"")
               .append(13 - i * 2).append("\" width=\"3\" height=\"").append(5 + i * 2)
               .append("\" rx=\"1\" fill=\"").append(TRANG).append("\" opacity=\"0.85\"/>\n");
        svg.append("<rect x=\"").append(WIDTH - PAD - 18).append("\" y=\"7\" width=\"16\" height=\"8\"")
           .append(" rx=\"2\" fill=\"none\" stroke=\"").append(TRANG).append("\" opacity=\"0.85\"/>\n");
        svg.append("<rect x=\"").append(WIDTH - PAD - 16).append("\" y=\"9\" width=\"9\" height=\"4\"")
           .append(" fill=\"").append(TRANG).append("\" opacity=\"0.85\"/>\n");

        int x = PAD;
        String dan = chuThuong(bar.sub());
        if ("back".equals(dan) || "menu".equals(dan)) {
            icon(svg, dan.equals("back") ? "back" : "menu", x, STATUS_H + 16, 14, TRANG);
            x += 24;
        }
        List<String> icons = bar.actions();
        int chuaIcon = Math.min(icons.size(), 3) * 24;
        chu(svg, x, STATUS_H + APPBAR_H / 2 + 5, fit(bar.label(), WIDTH - x - PAD - chuaIcon, 14),
                TRANG, true, "start", 14);
        for (int i = 0; i < Math.min(icons.size(), 3); i++)
            icon(svg, icons.get(i), WIDTH - PAD - 14 - (Math.min(icons.size(), 3) - 1 - i) * 24,
                    STATUS_H + APPBAR_H / 2 - 7, 14, TRANG);
    }

    /** Thanh điều hướng CỦA APP (BottomNavigationBar) — góc vuông, vì nó nằm TRONG vùng app chứ
     *  không chạm mép máy; phần bo góc dưới là việc của thanh hệ điều hành bên dưới nó. */
    private static void veThanhDuoi(StringBuilder svg, Element nav, int y) {
        rect(svg, 0, y, WIDTH, NAV_H, 0, "#fbfcfe", null);
        line(svg, 0, y, WIDTH, y, VIEN_NHAT);
        List<String> muc = tach(nav.label());
        if (muc.isEmpty()) return;
        int n = Math.min(muc.size(), 4);
        int o = WIDTH / n;
        for (int i = 0; i < n; i++) {
            int cx = o * i + o / 2;
            String mau = i == 0 ? CHINH : CHU_RAT_MO;
            icon(svg, muc.get(i), cx - 8, y + 12, 16, mau);
            chu(svg, cx, y + 42, fit(muc.get(i), o - 6, 9), mau, i == 0, "middle", 9);
        }
    }

    /**
     * Thanh điều hướng CỦA HỆ ĐIỀU HÀNH — dải cuối cùng, chạm mép máy nên bo hai góc dưới.
     *
     * <p>Vẽ nó ra không phải để cho đẹp: nó là 48 trong 77 dp mà Android giữ lại, tức phần màn
     * hình app KHÔNG bao giờ vẽ tới được. Có nó thì người soạn nhìn ra ngay đâu là chỗ app được
     * dùng, và hình mang đúng dáng CẢ MÁY (412×915) thay vì dáng riêng của vùng app (412×838).
     */
    private static void veThanhHeDieuHanh(StringBuilder svg, int y) {
        boGocDuoi(svg, y, NEN_NHAT);
        line(svg, 0, y, WIDTH, y, VIEN_NHAT);
        svg.append("<rect x=\"").append(WIDTH / 2 - 55).append("\" y=\"").append(y + SYS_NAV_H / 2 - 2)
           .append("\" width=\"110\" height=\"4\" rx=\"2\" fill=\"").append(CHU_RAT_MO).append("\"/>\n");
    }

    /** Có chữ thì vẽ nút nổi kiểu "mở rộng" (viên thuốc) — đọc ra được việc nút làm gì. */
    private static void veNutNoi(StringBuilder svg, Element fab, int duoi) {
        String nhan = fit(fab.label(), 150, 12);
        int y = duoi - 46;
        if (nhan.isBlank()) {
            svg.append("<circle cx=\"").append(WIDTH - PAD - 24).append("\" cy=\"").append(y + 24)
               .append("\" r=\"24\" fill=\"").append(CHINH).append("\"/>\n");
            icon(svg, "add", WIDTH - PAD - 33, y + 15, 18, TRANG);
            return;
        }
        int w = (int) (nhan.length() * 12 * 0.54) + 50;
        int x = WIDTH - PAD - w;
        rect(svg, x, y + 4, w, 42, 21, CHINH, null);
        icon(svg, "add", x + 16, y + 17, 16, TRANG);
        chu(svg, x + 38, y + 30, nhan, TRANG, true, "start", 12);
    }

    // ── Dòng chảy nội dung ───────────────────────────────────────

    /** @return chiều cao Y sau khi vẽ xong phần tử này (điểm bắt đầu cho phần tử kế tiếp). */
    private static int ve(StringBuilder svg, Element el, int y) {
        String type = loai(el);
        String label = el.label() == null ? "" : el.label();
        String sub = el.sub() == null ? "" : el.sub();
        String right = el.right() == null ? "" : el.right();
        int x = PAD;
        switch (type) {
            case "heading" -> {
                chu(svg, x, y + 16, fit(label, INNER_W, 14), CHU_DAM, true, "start", 14);
                return y + 28;
            }
            case "tab_bar" -> {
                List<String> tab = tach(label);
                if (tab.isEmpty()) return y;
                rect(svg, x, y, INNER_W, 34, 9, NEN_NHAT, null);
                int o = INNER_W / tab.size();
                for (int i = 0; i < tab.size(); i++) {
                    if (i == 0) rect(svg, x + 3, y + 3, o - 6, 28, 7, TRANG, VIEN_NHAT);
                    chu(svg, x + o * i + o / 2, y + 22, fit(tab.get(i), o - 8, 11),
                            i == 0 ? CHINH : CHU_MO, i == 0, "middle", 11);
                }
                return y + 46;
            }
            case "search" -> {
                rect(svg, x, y, INNER_W, 38, 19, NEN_NHAT, null);
                icon(svg, "search", x + 13, y + 12, 14, CHU_RAT_MO);
                chu(svg, x + 35, y + 24, fit(nhanHoacSub(label, sub), INNER_W - 48, 11),
                        CHU_RAT_MO, false, "start", 11);
                return y + 50;
            }
            case "chip_row" -> {
                int cx = x;
                for (String c : tach(label)) {
                    String t = fit(c, 120, 11);
                    int w = (int) (t.length() * 11 * 0.54) + 22;
                    if (cx + w > WIDTH - PAD) break;
                    boolean chon = cx == x;
                    rect(svg, cx, y, w, 28, 14, chon ? CHINH_NHAT : TRANG, chon ? CHINH_VIEN : VIEN);
                    chu(svg, cx + w / 2, y + 18, t, chon ? CHINH : CHU_MO, chon, "middle", 11);
                    cx += w + 8;
                }
                return y + 40;
            }
            case "input", "dropdown", "date" -> {
                int oY = y;
                if (!label.isBlank() && !sub.isBlank()) {
                    chu(svg, x, y + 9, fit(label, INNER_W, 9), CHU_MO, true, "start", 9);
                    oY = y + 15;
                }
                rect(svg, x, oY, INNER_W, 36, 9, TRANG, VIEN);
                boolean coIcon = !"input".equals(type);
                if (coIcon) icon(svg, "date".equals(type) ? "calendar" : "down",
                        x + INNER_W - 26, oY + 11, 14, CHU_RAT_MO);
                chu(svg, x + 13, oY + 23, fit(oY == y ? nhanHoacSub(label, sub) : sub,
                        INNER_W - (coIcon ? 46 : 26), 11), CHU_RAT_MO, false, "start", 11);
                return oY + 36 + 12;
            }
            case "checkbox" -> {
                rect(svg, x, y + 2, 18, 18, 4, TRANG, CHU_RAT_MO);
                chu(svg, x + 27, y + 16, fit(label, INNER_W - 27, 11), CHU, false, "start", 11);
                return y + 30;
            }
            case "button", "button_outline" -> {
                boolean vien = "button_outline".equals(type);
                rect(svg, x, y, INNER_W, 42, 10, vien ? TRANG : CHINH, vien ? CHINH_VIEN : null);
                chu(svg, WIDTH / 2, y + 26, fit(label, INNER_W - 24, 12),
                        vien ? CHINH : TRANG, true, "middle", 12);
                return y + 54;
            }
            case "list_item" -> {
                int h = 56;
                svg.append("<circle cx=\"").append(x + 15).append("\" cy=\"").append(y + 28)
                   .append("\" r=\"15\" fill=\"").append(CHINH_NHAT).append("\"/>\n");
                chu(svg, x + 15, y + 32, dauChu(label), CHINH, true, "middle", 12);
                List<String> icons = el.actions();
                int n = Math.min(icons.size(), 2);
                int mep = WIDTH - PAD;
                for (int i = 0; i < n; i++)
                    icon(svg, icons.get(i), mep - 14 - (n - 1 - i) * 24, y + 21, 14, CHU_RAT_MO);
                if (n > 0) mep -= n * 24;
                if (!right.isBlank()) {
                    chu(svg, mep, y + (sub.isBlank() ? 32 : 26), fit(right, 96, 11), CHU_DAM, true, "end", 11);
                    mep -= (int) (fit(right, 96, 11).length() * 11 * 0.54) + 10;
                }
                int rongChu = mep - (x + 38);
                chu(svg, x + 38, y + (sub.isBlank() ? 32 : 25), fit(label, rongChu, 12), CHU_DAM, true, "start", 12);
                if (!sub.isBlank())
                    chu(svg, x + 38, y + 41, fit(sub, rongChu, 10), CHU_RAT_MO, false, "start", 10);
                line(svg, x, y + h, WIDTH - PAD, y + h, VIEN_NHAT);
                return y + h + 4;
            }
            case "card" -> {
                int h = 62;
                rect(svg, x, y, INNER_W, h, 12, TRANG, VIEN_NHAT);
                int mep = x + INNER_W - 14;
                if (!right.isBlank()) {
                    chu(svg, mep, y + 28, fit(right, 96, 12), CHINH, true, "end", 12);
                    mep -= (int) (fit(right, 96, 12).length() * 12 * 0.54) + 10;
                }
                chu(svg, x + 14, y + 26, fit(label, mep - x - 14, 12), CHU_DAM, true, "start", 12);
                chu(svg, x + 14, y + 45, fit(sub, INNER_W - 28, 10), CHU_RAT_MO, false, "start", 10);
                return y + h + 12;
            }
            case "total" -> {
                int h = 48;
                rect(svg, x, y, INNER_W, h, 12, CHINH_NHAT, CHINH_VIEN);
                int mep = x + INNER_W - 14;
                if (!right.isBlank()) {
                    chu(svg, mep, y + 30, fit(right, 130, 15), CHINH, true, "end", 15);
                    mep -= (int) (fit(right, 130, 15).length() * 15 * 0.54) + 12;
                }
                chu(svg, x + 14, y + (sub.isBlank() ? 29 : 24), fit(label, mep - x - 14, 11),
                        CHU, true, "start", 11);
                if (!sub.isBlank())
                    chu(svg, x + 14, y + 39, fit(sub, INNER_W - 28, 9), CHU_MO, false, "start", 9);
                return y + h + 12;
            }
            case "image" -> {
                int h = 96;
                rect(svg, x, y, INNER_W, h, 10, NEN_NHAT, VIEN);
                line(svg, x, y, x + INNER_W, y + h, VIEN);
                line(svg, x + INNER_W, y, x, y + h, VIEN);
                if (label.isBlank()) return y + h + 12;
                chu(svg, WIDTH / 2, y + h + 16, fit(label, INNER_W, 10), CHU_MO, false, "middle", 10);
                return y + h + 28;
            }
            case "empty" -> {
                svg.append("<circle cx=\"").append(WIDTH / 2).append("\" cy=\"").append(y + 26)
                   .append("\" r=\"19\" fill=\"none\" stroke=\"").append(VIEN_NHAT)
                   .append("\" stroke-width=\"1.5\"/>\n");
                chu(svg, WIDTH / 2, y + 64, fit(label, INNER_W, 11), CHU_RAT_MO, false, "middle", 11);
                return y + 80;
            }
            case "divider" -> {
                line(svg, x, y + 8, WIDTH - PAD, y + 8, VIEN_NHAT);
                return y + 18;
            }
            default -> {   // "text" và mọi loại lạ khác
                List<String> dong = xuongDong(nhanHoacSub(label, sub), INNER_W, 11, 3);
                for (int i = 0; i < dong.size(); i++)
                    chu(svg, x, y + 13 + i * 17, dong.get(i), CHU, false, "start", 11);
                return y + Math.max(1, dong.size()) * 17 + 8;
            }
        }
    }

    // ── Hình cơ bản ──────────────────────────────────────────────

    private static void rect(StringBuilder svg, int x, int y, int w, int h, int rx, String fill, String stroke) {
        svg.append("<rect x=\"").append(x).append("\" y=\"").append(y)
           .append("\" width=\"").append(w).append("\" height=\"").append(h)
           .append("\" rx=\"").append(rx).append("\" fill=\"").append(fill).append('"');
        if (stroke != null) svg.append(" stroke=\"").append(stroke).append("\" stroke-width=\"1.2\"");
        svg.append("/>\n");
    }

    /** Mảng màu bám mép trên, CHỈ bo hai góc trên — để không đè mất góc bo của thân máy. */
    private static void boGocTren(StringBuilder svg, int h, String fill) {
        svg.append("<path d=\"M0 ").append(RX).append("A").append(RX).append(' ').append(RX)
           .append(" 0 0 1 ").append(RX).append(" 0H").append(WIDTH - RX).append('A').append(RX)
           .append(' ').append(RX).append(" 0 0 1 ").append(WIDTH).append(' ').append(RX)
           .append('V').append(h).append("H0Z\" fill=\"").append(fill).append("\"/>\n");
    }

    /** Mảng màu bám mép dưới, CHỈ bo hai góc dưới. */
    private static void boGocDuoi(StringBuilder svg, int y, String fill) {
        svg.append("<path d=\"M0 ").append(y).append('H').append(WIDTH).append('V')
           .append(HEIGHT - RX).append('A').append(RX).append(' ').append(RX).append(" 0 0 1 ")
           .append(WIDTH - RX).append(' ').append(HEIGHT).append('H').append(RX).append('A')
           .append(RX).append(' ').append(RX).append(" 0 0 1 0 ").append(HEIGHT - RX)
           .append("Z\" fill=\"").append(fill).append("\"/>\n");
    }

    private static void line(StringBuilder svg, int x1, int y1, int x2, int y2, String stroke) {
        svg.append("<line x1=\"").append(x1).append("\" y1=\"").append(y1)
           .append("\" x2=\"").append(x2).append("\" y2=\"").append(y2)
           .append("\" stroke=\"").append(stroke).append("\" stroke-width=\"1\"/>\n");
    }

    private static void chu(StringBuilder svg, int x, int y, String label, String color,
                            boolean bold, String anchor, int size) {
        if (label == null || label.isBlank()) return;
        svg.append("<text x=\"").append(x).append("\" y=\"").append(y)
           .append("\" font-family=\"Segoe UI, Roboto, Arial, sans-serif\" font-size=\"").append(size)
           .append('"');
        if (bold) svg.append(" font-weight=\"700\"");
        svg.append(" text-anchor=\"").append(anchor).append("\" fill=\"").append(color).append('"')
           .append('>').append(HandoutDocument.esc(label)).append("</text>\n");
    }

    /**
     * Icon 14–18px vẽ bằng nét, đặt trong ô vuông {@code s} tại (x,y). Tên lạ rơi về ô bo góc —
     * một thanh dưới ba mục vẫn ra ba ô đều nhau, không vỡ bố cục.
     */
    private static void icon(StringBuilder svg, String ten, int x, int y, int s, String mau) {
        String d = switch (khongDau(ten)) {
            case "edit", "sua", "pencil" -> "M1 13l3-.8 8-8-2.2-2.2-8 8z";
            case "delete", "xoa", "trash" -> "M2 3.6h10M5 3.6V1.4h4v2.2M3.2 3.6L4 13h6l.8-9.4";
            case "search", "tim" -> "M6.2 10.4a4.2 4.2 0 100-8.4 4.2 4.2 0 000 8.4zM9.4 9.4l3.2 3.2";
            case "filter", "loc" -> "M1.4 3h11.2M3.6 7h6.8M5.8 11h2.4";
            case "add", "them", "plus" -> "M7 1.8v10.4M1.8 7h10.4";
            case "more" -> "M7 2.6v.1M7 7v.1M7 11.4v.1";
            case "check" -> "M2 7.2l3.4 3.4L12 3.4";
            case "back" -> "M9 1.8L3.6 7 9 12.2";
            case "menu" -> "M1.4 3.2h11.2M1.4 7h11.2M1.4 10.8h11.2";
            case "calendar", "date", "ngay" -> "M1.6 3.4h10.8v9.2H1.6zM1.6 6.2h10.8M4.4 1.4v3M9.6 1.4v3";
            case "down" -> "M3 5.2L7 9.4l4-4.2";
            case "home", "trangchu" -> "M7 1.6l5.4 4.6v6.2H1.6V6.2z";
            case "list", "danhsach" -> "M1.6 3.4h10.8M1.6 7h10.8M1.6 10.6h10.8";
            case "chart", "thongke", "bieudo" -> "M2.4 12.4V7M7 12.4V2.6M11.6 12.4V9";
            case "person", "canhan", "taikhoan" -> "M7 6.6a2.4 2.4 0 100-4.8 2.4 2.4 0 000 4.8zM2.2 12.8a4.8 4.8 0 019.6 0";
            case "settings", "caidat" -> "M7 9.2a2.2 2.2 0 100-4.4 2.2 2.2 0 000 4.4zM7 1.4v1.6M7 11v1.6M1.4 7h1.6M11 7h1.6";
            default -> null;
        };
        double k = s / 14.0;
        if (d == null) {
            svg.append("<rect x=\"").append(fmt(x + k)).append("\" y=\"").append(fmt(y + k))
               .append("\" width=\"").append(fmt(s - 2 * k)).append("\" height=\"").append(fmt(s - 2 * k))
               .append("\" rx=\"3\" fill=\"none\" stroke=\"").append(mau).append("\" stroke-width=\"1.4\"/>\n");
            return;
        }
        svg.append("<path transform=\"translate(").append(x).append(',').append(y).append(") scale(")
           .append(fmt(k)).append(")\" d=\"").append(d).append("\" fill=\"none\" stroke=\"").append(mau)
           .append("\" stroke-width=\"1.4\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>\n");
    }

    // ── Chữ nghĩa ────────────────────────────────────────────────

    /**
     * Cắt chữ cho vừa bề ngang. Không đo font thật được (server không có AWT font metrics chắc
     * chắn giống trình duyệt) nên ước lượng 0,54 × cỡ chữ — với Segoe UI/Roboto ở cỡ 9–15 sai số
     * đủ nhỏ, và cắt hụt một chữ còn hơn để chữ tràn khỏi viền máy.
     */
    private static String fit(String s, int maxPx, int size) {
        if (s == null) return "";
        String t = s.trim();
        int max = Math.max(3, (int) (maxPx / (size * 0.54)));
        return t.length() <= max ? t : t.substring(0, Math.max(1, max - 1)).trim() + "…";
    }

    /** Ngắt dòng theo từ, tối đa {@code soDongToiDa} dòng; phần thừa dồn hết vào dòng CUỐI rồi
     *  cắt bằng {@link #fit} — thà cụt một câu còn hơn nuốt mất mấy dòng mà không dấu hiệu gì. */
    private static List<String> xuongDong(String s, int maxPx, int size, int soDongToiDa) {
        List<String> ra = new ArrayList<>();
        if (s == null || s.isBlank()) return ra;
        int max = Math.max(4, (int) (maxPx / (size * 0.54)));
        String[] tu = s.trim().split("\\s+");
        StringBuilder dong = new StringBuilder();
        for (int i = 0; i < tu.length; i++) {
            if (dong.length() > 0 && dong.length() + 1 + tu[i].length() > max) {
                if (ra.size() == soDongToiDa - 1) {
                    for (int j = i; j < tu.length; j++) dong.append(' ').append(tu[j]);
                    break;
                }
                ra.add(dong.toString());
                dong.setLength(0);
            }
            if (dong.length() > 0) dong.append(' ');
            dong.append(tu[i]);
        }
        ra.add(fit(dong.toString(), maxPx, size));
        return ra;
    }

    private static List<String> tach(String s) {
        List<String> ra = new ArrayList<>();
        if (s == null) return ra;
        for (String p : s.split("[|/·,]")) if (!p.isBlank()) ra.add(p.trim());
        return ra;
    }

    private static String dauChu(String s) {
        String t = s == null ? "" : s.trim();
        return t.isEmpty() ? "•" : t.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    private static String nhanHoacSub(String label, String sub) {
        return label == null || label.isBlank() ? (sub == null ? "" : sub) : label;
    }

    private static String loai(Element el) {
        return el == null || el.type() == null || el.type().isBlank() ? "text" : chuThuong(el.type());
    }

    private static String chuThuong(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Bỏ dấu và mọi ký tự không phải chữ/số, dùng để TRA TÊN ICON. Nhãn thanh dưới AI trả về là
     * tiếng Việt có dấu ("Danh sách", "Thống kê", "Cá nhân"); so thẳng thì không bao giờ khớp và
     * màn nào cũng ra một hàng ô vuông trống.
     */
    private static String khongDau(String s) {
        return java.text.Normalizer.normalize(chuThuong(s), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace("đ", "d")
                .replaceAll("[^a-z0-9]", "");
    }

    /** Số thập phân gọn, luôn dùng dấu chấm — SVG không hiểu "0,72" của Locale tiếng Việt. */
    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
