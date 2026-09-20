package com.example.grader.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ghi file .pdf cho bản đề tải về — anh em của {@link DocxWriter} (cùng đọc
 * {@link HandoutDocument#parse}, chỉ khác đích xuất). Dùng PDFBox (đã có sẵn trong repo qua
 * Maven, thuần Java) thay vì gọi LibreOffice/pandoc: máy chấm không chắc có sẵn phần mềm ngoài,
 * PDFBox thì luôn dựng được PDF y hệt trên mọi máy.
 *
 * <p>PDFBox KHÔNG có sẵn khái niệm "đoạn văn tự xuống dòng" hay "bảng" như POI với .docx — mọi thứ
 * ở đây (xuống dòng, ngắt trang, ô bảng, cỡ hàng theo số dòng chữ) đều tự tính bằng tay dựa trên
 * {@code font.getStringWidth}. Bù lại không cần cài gì thêm ngoài JAR Maven.
 */
public final class PdfWriter implements AutoCloseable {

    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float MARGIN = 50f;
    private static final float CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN;
    private static final float BOTTOM = MARGIN;
    /** Trần CHIỀU CAO của một ảnh minh hoạ (≈14,8cm in ra). Hình minh hoạ giao diện cao gấp đôi
     *  bề ngang, thu vừa bề ngang trang vẫn còn cao hơn cả vùng in — mỗi hình chiếm trọn một
     *  trang rồi vẫn bị cắt. Thu theo chiều nào CHẬT hơn thì ảnh luôn nằm gọn. */
    private static final float MAX_IMAGE_HEIGHT = 420f;

    /** "**đậm**" hoặc "`mã`" — cùng cú pháp DocxWriter#INLINE_MARKUP để hai bản xuất khớp nhau. */
    private static final Pattern INLINE_MARKUP = Pattern.compile("\\*\\*([^*]+)\\*\\*|`([^`]+)`");

    private final PDDocument document = new PDDocument();
    // 14 font chuẩn của PDFBox (Helvetica/Courier) dùng WinAnsiEncoding — KHÔNG có dấu tiếng Việt
    // (ầ, ệ, ư, ữ…), showText() sẽ ném lỗi giữa chừng ngay khi gặp chữ có dấu. Phải nhúng font
    // TrueType phủ Unicode thật (DejaVu Sans — giấy phép tự do, cho phép nhúng lại) thay vì dùng
    // font chuẩn của PDFBox.
    private final PDFont REGULAR = loadFont("/fonts/DejaVuSans.ttf");
    private final PDFont BOLD = loadFont("/fonts/DejaVuSans-Bold.ttf");
    private final PDFont MONO = loadFont("/fonts/DejaVuSansMono.ttf");

    private PDPage page;
    private PDPageContentStream stream;
    private float y;

    public PdfWriter() {
        newPage();
    }

    private PDFont loadFont(String classpathTtf) {
        try (InputStream in = PdfWriter.class.getResourceAsStream(classpathTtf)) {
            if (in == null) throw new IllegalStateException("Thiếu font trong classpath: " + classpathTtf);
            return PDType0Font.load(document, in);
        } catch (Exception e) {
            throw new IllegalStateException("Không nạp được font " + classpathTtf + ": " + e.getMessage(), e);
        }
    }

    private void newPage() {
        try {
            if (stream != null) stream.close();
            page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = PAGE_HEIGHT - MARGIN;
        } catch (Exception e) {
            throw new IllegalStateException("Không tạo được trang PDF: " + e.getMessage(), e);
        }
    }

    /** Sang trang mới nếu phần sắp vẽ (cao {@code neededHeight}) không còn đủ chỗ ở trang hiện tại. */
    private void ensureSpace(float neededHeight) {
        if (y - neededHeight < BOTTOM) newPage();
    }

    public PdfWriter heading(String text, int level) {
        float size = switch (level) { case 1 -> 18f; case 2 -> 14f; default -> 12.5f; };
        float before = level == 1 ? 0 : 14f;
        List<StyledWord> words = inlineWords(text, true);
        ensureSpace(before + size + 6);
        y -= before;
        drawParagraph(words, size, MARGIN, CONTENT_WIDTH);
        y -= 6;
        return this;
    }

    public PdfWriter paragraph(String text) {
        List<StyledWord> words = inlineWords(text, false);
        ensureSpace(11f);
        drawParagraph(words, 11f, MARGIN, CONTENT_WIDTH);
        y -= 6;
        return this;
    }

    /** Gạch đầu dòng: marker "• "/"N. " không tách dòng theo inline, chữ theo sau mới xuống dòng lệ thuộc. */
    public PdfWriter bullet(String text, boolean ordered, int index) {
        String marker = ordered ? index + ". " : "• ";
        List<StyledWord> words = new ArrayList<>();
        words.add(new StyledWord(marker, false, false));
        words.addAll(inlineWords(text, false));
        float indent = MARGIN + 14f;
        ensureSpace(11f);
        drawParagraph(words, 11f, indent, CONTENT_WIDTH - 14f);
        y -= 3;
        return this;
    }

    public PdfWriter code(String text) {
        float size = 9f;
        float lineHeight = size * 1.35f;
        for (String line : text.split("\n", -1)) {
            ensureSpace(lineHeight);
            try {
                stream.beginText();
                stream.setFont(MONO, size);
                stream.newLineAtOffset(MARGIN + 10f, y - size);
                stream.showText(sanitize(line, MONO));
                stream.endText();
            } catch (Exception e) {
                throw new IllegalStateException("Không ghi được PDF: " + e.getMessage(), e);
            }
            y -= lineHeight;
        }
        y -= 6;
        return this;
    }

    /** Bảng: hàng đầu = header (nền xám nhạt, in đậm). Cỡ hàng tự tính theo số dòng chữ đã ngắt. */
    public PdfWriter table(List<String[]> rows) {
        if (rows == null || rows.isEmpty()) return this;
        int columns = rows.get(0).length;
        if (columns == 0) return this;
        float colWidth = CONTENT_WIDTH / columns;
        float cellPad = 4f;
        float fontSize = 9f;
        float lineHeight = fontSize * 1.3f;

        for (int r = 0; r < rows.size(); r++) {
            boolean header = r == 0;
            String[] row = rows.get(r);
            List<List<StyledWord>> wrapped = new ArrayList<>();
            int maxLines = 1;
            for (String cell : row) {
                List<StyledWord> cellWords = inlineWords(cell == null ? "" : cell, header);
                List<String> cellLines = wrapToLines(cellWords, fontSize, colWidth - 2 * cellPad);
                maxLines = Math.max(maxLines, cellLines.size());
                wrapped.add(cellWords);
            }
            float rowHeight = maxLines * lineHeight + 2 * cellPad;
            ensureSpace(rowHeight);
            float rowTop = y;
            try {
                if (header) {
                    stream.setNonStrokingColor(0.93f, 0.94f, 0.98f);
                    stream.addRect(MARGIN, rowTop - rowHeight, CONTENT_WIDTH, rowHeight);
                    stream.fill();
                    // showText() TÔ CHỮ bằng CHÍNH màu "non-stroking" vừa đặt cho nền — quên trả
                    // về đen thì mọi chữ vẽ SAU (kể cả các trang/đoạn văn sau, không riêng bảng
                    // này) sẽ mờ gần như trắng, đọc không ra. Bắt buộc trả lại đen ngay sau khi tô.
                    stream.setNonStrokingColor(0f, 0f, 0f);
                }
                stream.setStrokingColor(0.6f, 0.63f, 0.69f);
                stream.setLineWidth(0.6f);
                for (int c = 0; c <= columns; c++) {
                    float x = MARGIN + c * colWidth;
                    stream.moveTo(x, rowTop);
                    stream.lineTo(x, rowTop - rowHeight);
                    stream.stroke();
                }
                stream.moveTo(MARGIN, rowTop);
                stream.lineTo(MARGIN + CONTENT_WIDTH, rowTop);
                stream.stroke();
                stream.moveTo(MARGIN, rowTop - rowHeight);
                stream.lineTo(MARGIN + CONTENT_WIDTH, rowTop - rowHeight);
                stream.stroke();
            } catch (Exception e) {
                throw new IllegalStateException("Không vẽ được khung bảng PDF: " + e.getMessage(), e);
            }
            for (int c = 0; c < columns; c++) {
                float cellX = MARGIN + c * colWidth + cellPad;
                float cellTop = rowTop - cellPad;
                drawCellLines(wrapped.get(c), fontSize, lineHeight, cellX, cellTop, colWidth - 2 * cellPad);
            }
            y = rowTop - rowHeight;
        }
        y -= 8;
        return this;
    }

    /** Ảnh minh họa — thu nhỏ vừa bề rộng VÀ chiều cao trang, giữ tỉ lệ, không phóng to ảnh nhỏ. */
    public PdfWriter image(byte[] png, int widthPx, int heightPx) {
        if (png == null || png.length == 0 || widthPx <= 0 || heightPx <= 0) return this;
        try {
            PDImageXObject image = PDImageXObject.createFromByteArray(document, png, "screenshot");
            float scale = Math.min(1f, Math.min(CONTENT_WIDTH / widthPx, MAX_IMAGE_HEIGHT / heightPx));
            float drawWidth = widthPx * scale;
            float drawHeight = heightPx * scale;
            ensureSpace(drawHeight + 10f);
            stream.drawImage(image, MARGIN, y - drawHeight, drawWidth, drawHeight);
            y -= drawHeight + 10f;
        } catch (Exception e) {
            throw new IllegalStateException("Không chèn được ảnh vào PDF: " + e.getMessage(), e);
        }
        return this;
    }

    public byte[] build() {
        try {
            stream.close();
            stream = null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Không xuất được file PDF: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try { document.close(); } catch (Exception ignored) { /* build() đã đóng thì bỏ qua */ }
    }

    // ── Chữ có định dạng (đậm/mã) + tự xuống dòng ─────────────────

    private record StyledWord(String text, boolean bold, boolean mono) {}

    /** Tách "**đậm**"/"`mã`" thành các từ có gắn cờ định dạng, tách tiếp theo khoảng trắng để bọc dòng được. */
    private List<StyledWord> inlineWords(String text, boolean forceBold) {
        List<StyledWord> words = new ArrayList<>();
        if (text == null || text.isEmpty()) return words;
        Matcher m = INLINE_MARKUP.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) addWords(words, text.substring(last, m.start()), forceBold, false);
            if (m.group(1) != null) addWords(words, m.group(1), true, false);
            else addWords(words, m.group(2), forceBold, true);
            last = m.end();
        }
        if (last < text.length()) addWords(words, text.substring(last), forceBold, false);
        return words;
    }

    private void addWords(List<StyledWord> out, String segment, boolean bold, boolean mono) {
        for (String w : segment.split(" ")) {
            if (!w.isEmpty()) out.add(new StyledWord(w, bold, mono));
        }
    }

    private PDFont fontOf(StyledWord w) {
        return w.mono() ? MONO : w.bold() ? BOLD : REGULAR;
    }

    private float widthOf(StyledWord w, float size) {
        PDFont font = fontOf(w);
        try {
            return font.getStringWidth(sanitize(w.text(), font)) / 1000f * size;
        } catch (Exception e) {
            return w.text().length() * size * 0.55f;
        }
    }

    /** Ngắt danh sách từ thành các dòng vừa {@code maxWidth} — dùng chung cho vẽ và cho đo cỡ hàng bảng. */
    private List<String> wrapToLines(List<StyledWord> words, float size, float maxWidth) {
        List<String> lines = new ArrayList<>();
        float spaceWidth = widthOf(new StyledWord(" ", false, false), size);
        StringBuilder line = new StringBuilder();
        float lineWidth = 0f;
        for (StyledWord w : words) {
            // Định danh kiểu "DinhDanh.TieuDeCongViec(id)" là MỘT từ không dấu cách nhưng có thể
            // dài hơn cả bề ngang ô bảng — để nguyên sẽ tràn ra ngoài viền. Cắt theo ký tự khi cả
            // từ vượt maxWidth; các mảnh của CÙNG một từ nối liền nhau, không chèn dấu cách.
            List<String> pieces = widthOf(w, size) > maxWidth
                    ? splitByChar(w, size, maxWidth) : List.of(w.text());
            for (int i = 0; i < pieces.size(); i++) {
                String piece = pieces.get(i);
                boolean newWordBoundary = i == 0;
                float ww = widthOf(new StyledWord(piece, w.bold(), w.mono()), size);
                float extra = (line.length() == 0 || !newWordBoundary) ? ww : spaceWidth + ww;
                if (line.length() > 0 && lineWidth + extra > maxWidth) {
                    lines.add(line.toString());
                    line.setLength(0);
                    lineWidth = 0f;
                    extra = ww;
                    newWordBoundary = false; // dòng vừa mới bắt đầu, khỏi chèn dấu cách đầu dòng
                }
                if (line.length() > 0 && newWordBoundary) line.append(' ');
                line.append(piece);
                lineWidth += extra;
            }
        }
        if (line.length() > 0 || lines.isEmpty()) lines.add(line.toString());
        return lines;
    }

    /** Cắt MỘT từ quá dài thành các mảnh vừa {@code maxWidth} theo từng ký tự (không còn cách nào
     *  ngắn hơn để không tràn — từ này vốn không có dấu cách để ngắt theo cách thông thường). */
    private List<String> splitByChar(StyledWord w, float size, float maxWidth) {
        List<String> pieces = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        for (int i = 0; i < w.text().length(); i++) {
            char c = w.text().charAt(i);
            if (!chunk.isEmpty()) {
                float candidateWidth = widthOf(new StyledWord(chunk.toString() + c, w.bold(), w.mono()), size);
                if (candidateWidth > maxWidth) {
                    pieces.add(chunk.toString());
                    chunk.setLength(0);
                }
            }
            chunk.append(c);
        }
        if (!chunk.isEmpty()) pieces.add(chunk.toString());
        return pieces.isEmpty() ? List.of(w.text()) : pieces;
    }

    /** Vẽ một đoạn văn nhiều-kiểu-chữ, tự xuống dòng và tự sang trang khi hết chỗ. */
    private void drawParagraph(List<StyledWord> words, float size, float left, float maxWidth) {
        float lineHeight = size * 1.35f;
        float spaceWidth = widthOf(new StyledWord(" ", false, false), size);
        List<StyledWord> line = new ArrayList<>();
        float lineWidth = 0f;
        for (StyledWord w : words) {
            float ww = widthOf(w, size);
            float extra = line.isEmpty() ? ww : spaceWidth + ww;
            if (!line.isEmpty() && lineWidth + extra > maxWidth) {
                drawLine(line, size, lineHeight, left);
                line = new ArrayList<>();
                lineWidth = 0f;
                extra = ww;
            }
            line.add(w);
            lineWidth += extra;
        }
        if (!line.isEmpty()) drawLine(line, size, lineHeight, left);
    }

    private void drawLine(List<StyledWord> line, float size, float lineHeight, float left) {
        ensureSpace(lineHeight);
        float x = left;
        float spaceWidth = widthOf(new StyledWord(" ", false, false), size);
        try {
            for (int i = 0; i < line.size(); i++) {
                StyledWord w = line.get(i);
                PDFont font = fontOf(w);
                stream.beginText();
                stream.setFont(font, size);
                stream.newLineAtOffset(x, y - size);
                stream.showText(sanitize(w.text(), font));
                stream.endText();
                x += widthOf(w, size) + spaceWidth;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Không ghi được PDF: " + e.getMessage(), e);
        }
        y -= lineHeight;
    }

    private void drawCellLines(List<StyledWord> words, float size, float lineHeight, float x, float top, float maxWidth) {
        List<String> lines = wrapToLines(words, size, maxWidth);
        boolean bold = !words.isEmpty() && words.get(0).bold();
        float cursorY = top - size;
        PDFont font = bold ? BOLD : REGULAR;
        try {
            for (String line : lines) {
                stream.beginText();
                stream.setFont(font, size);
                stream.newLineAtOffset(x, cursorY);
                stream.showText(sanitize(line, font));
                stream.endText();
                cursorY -= lineHeight;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Không ghi được PDF: " + e.getMessage(), e);
        }
    }

    /**
     * DejaVu Sans phủ gần hết Unicode (đủ toàn bộ dấu tiếng Việt) nhưng vẫn có thể thiếu vài ký tự
     * hiếm (emoji, ký hiệu lạ) — {@code showText} ném lỗi giữa chừng nếu gặp glyph không có, làm
     * hỏng cả trang đã vẽ dở. Lọc trước bằng {@code font.encode} từng ký tự, thay ký tự thiếu bằng
     * "?" thay vì để cả file vỡ vì một ký tự hiếm.
     */
    private String sanitize(String s, PDFont font) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            String ch = new String(Character.toChars(cp));
            try {
                font.encode(ch);
                out.append(ch);
            } catch (Exception e) {
                out.append('?');
            }
        });
        return out.toString();
    }
}
