package com.example.grader.service;

import java.util.List;

/**
 * Vẽ hình minh họa (khung dây/wireframe) của một màn hình thành SVG THẬT, tất định — không qua
 * AI. AI (xem {@code AiExamAuthorService#proposeMockups}) chỉ mô tả màn hình bằng danh sách
 * {@link Element} (loại thành phần + nhãn); lớp này ghép chúng thành SVG luôn hợp lệ về cú pháp,
 * xuống dòng đều, không lệch khung — tránh rủi ro LLM tự sinh XML tự do ra thẻ hỏng.
 *
 * <p>SVG sinh ra được lưu trực tiếp làm {@link HandoutDocument.Mockup#svg()}, nên style/màu ở đây
 * cố tình đơn giản (đường viền + chữ, không gradient/ảnh) để nhúng thẳng vào {@code de_bai.html}
 * và xuất sang .docx/.pdf qua đường trình duyệt rasterize hiện có (xem {@code mockup-image.ts}).
 */
public final class MockupRenderer {

    /** Một thành phần trong màn hình. type: app_bar|heading|text|input|button|list_item|card|
     *  image|checkbox|divider — loại lạ rơi về "text". */
    public record Element(String type, String label) {}

    private MockupRenderer() {}

    private static final int WIDTH = 320;
    private static final int PAD = 14;
    private static final int INNER_W = WIDTH - PAD * 2;
    private static final int TITLE_H = 34;

    public static String render(String title, List<Element> elements) {
        int y = TITLE_H;
        StringBuilder body = new StringBuilder();
        for (Element el : elements) {
            y = renderElement(body, el, y);
        }
        int height = y + PAD;

        // width/height GHI SỐ THẬT (khớp viewBox), KHÔNG dùng "100%": SVG này còn được nạp qua
        // new Image() tách rời khỏi trang (xem frontend/lib/mockup-image.ts#svgToPng, dùng khi
        // tải .docx/.pdf/PNG) — lúc đó không có khung cha nào để "100%" tính theo, trình duyệt rơi
        // về kích thước mặc định rất nhỏ và SAI TỈ LỆ (khoảng 300x150), ảnh nhúng ra bé xíu. Ghi số
        // pixel thật thì kích thước gốc luôn đúng ở MỌI nơi dùng lại hình; hiển thị co giãn trên
        // màn hình (nếu cần) thì đặt CSS width/height ở nơi NHÚNG (CSS luôn đè lên thuộc tính này).
        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(WIDTH)
           .append(' ').append(height).append("\" width=\"").append(WIDTH).append("\" height=\"")
           .append(height).append("\" role=\"img\" aria-label=\"")
           .append(HandoutDocument.esc(title)).append("\">\n");
        svg.append("<rect x=\"0\" y=\"0\" width=\"").append(WIDTH).append("\" height=\"").append(height)
           .append("\" rx=\"12\" fill=\"#ffffff\" stroke=\"#cbd5e1\" stroke-width=\"1.5\"/>\n");
        svg.append(body);
        svg.append("</svg>");
        return svg.toString();
    }

    /** @return chiều cao Y sau khi vẽ xong phần tử này (điểm bắt đầu cho phần tử kế tiếp). */
    private static int renderElement(StringBuilder svg, Element el, int y) {
        String type = el.type() == null ? "text" : el.type();
        String label = el.label() == null ? "" : el.label();
        int x = PAD;
        switch (type) {
            case "app_bar" -> {
                int h = 40;
                rect(svg, 0, y, WIDTH, h, 0, "#4f46e5", null);
                text(svg, WIDTH / 2, y + h / 2 + 5, label, "#ffffff", true, "middle", 14);
                return y + h + 10;
            }
            case "heading" -> {
                text(svg, x, y + 16, label, "#0f172a", true, "start", 15);
                return y + 30;
            }
            case "input" -> {
                int h = 34;
                rect(svg, x, y, INNER_W, h, 8, "#f8fafc", "#94a3b8");
                text(svg, x + 10, y + h / 2 + 4, label, "#64748b", false, "start", 11);
                return y + h + 10;
            }
            case "button" -> {
                int h = 34;
                rect(svg, x, y, INNER_W, h, 8, "#4f46e5", null);
                text(svg, WIDTH / 2, y + h / 2 + 4, label, "#ffffff", true, "middle", 12);
                return y + h + 10;
            }
            case "list_item" -> {
                int h = 32;
                circle(svg, x + 8, y + h / 2, 6, "#c7d2fe");
                text(svg, x + 22, y + h / 2 + 4, label, "#334155", false, "start", 11);
                line(svg, x, y + h + 4, WIDTH - PAD, y + h + 4, "#e2e8f0");
                return y + h + 12;
            }
            case "card" -> {
                int h = 46;
                rect(svg, x, y, INNER_W, h, 10, "#f8fafc", "#e2e8f0");
                text(svg, x + 10, y + h / 2 + 4, label, "#334155", false, "start", 11);
                return y + h + 10;
            }
            case "checkbox" -> {
                int h = 22;
                rect(svg, x, y + 2, 16, 16, 3, "#ffffff", "#94a3b8");
                text(svg, x + 24, y + h - 4, label, "#334155", false, "start", 11);
                return y + h + 8;
            }
            case "image" -> {
                int h = 70;
                rect(svg, x, y, INNER_W, h, 8, "#f1f5f9", "#cbd5e1");
                line(svg, x, y, x + INNER_W, y + h, "#cbd5e1");
                line(svg, x + INNER_W, y, x, y + h, "#cbd5e1");
                if (!label.isBlank()) text(svg, WIDTH / 2, y + h + 14, label, "#64748b", false, "middle", 10);
                return y + h + (label.isBlank() ? 10 : 24);
            }
            case "divider" -> {
                line(svg, x, y + 6, WIDTH - PAD, y + 6, "#e2e8f0");
                return y + 14;
            }
            default -> { // "text" và mọi loại lạ khác
                text(svg, x, y + 12, label, "#334155", false, "start", 11);
                return y + 24;
            }
        }
    }

    private static void rect(StringBuilder svg, int x, int y, int w, int h, int rx, String fill, String stroke) {
        svg.append("<rect x=\"").append(x).append("\" y=\"").append(y)
           .append("\" width=\"").append(w).append("\" height=\"").append(h)
           .append("\" rx=\"").append(rx).append("\" fill=\"").append(fill).append('"');
        if (stroke != null) svg.append(" stroke=\"").append(stroke).append("\" stroke-width=\"1.2\"");
        svg.append("/>\n");
    }

    private static void circle(StringBuilder svg, int cx, int cy, int r, String fill) {
        svg.append("<circle cx=\"").append(cx).append("\" cy=\"").append(cy).append("\" r=\"").append(r)
           .append("\" fill=\"").append(fill).append("\"/>\n");
    }

    private static void line(StringBuilder svg, int x1, int y1, int x2, int y2, String stroke) {
        svg.append("<line x1=\"").append(x1).append("\" y1=\"").append(y1)
           .append("\" x2=\"").append(x2).append("\" y2=\"").append(y2)
           .append("\" stroke=\"").append(stroke).append("\" stroke-width=\"1\"/>\n");
    }

    private static void text(StringBuilder svg, int x, int y, String label, String color,
                              boolean bold, String anchor, int size) {
        if (label.isBlank()) return;
        svg.append("<text x=\"").append(x).append("\" y=\"").append(y)
           .append("\" font-family=\"Segoe UI, Roboto, Arial, sans-serif\" font-size=\"").append(size)
           .append('"');
        if (bold) svg.append(" font-weight=\"700\"");
        svg.append(" text-anchor=\"").append(anchor).append("\" fill=\"").append(color).append('"')
           .append('>').append(HandoutDocument.esc(label)).append("</text>\n");
    }
}
