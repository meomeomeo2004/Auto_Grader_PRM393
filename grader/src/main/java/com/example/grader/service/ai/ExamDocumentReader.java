package com.example.grader.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Bóc CHỮ từ file đề giáo viên tải lên (.txt/.md, .docx, .pdf) để đưa cho AI phân tích.
 *
 * <p>Repo build offline ({@code mvnw -o}) nên KHÔNG thêm được POI hay PDFBox — mọi thứ ở đây
 * viết bằng {@code java.util.zip} và regex:
 * <ul>
 *   <li>.docx là file ZIP: đọc {@code word/document.xml}, mỗi {@code <w:p>} là một đoạn.</li>
 *   <li>.pdf: giải nén stream FlateDecode rồi lấy chữ trong toán tử {@code Tj}/{@code TJ}.
 *       Cách này ăn được PDF xuất từ Word/trình soạn thảo; PDF scan (chỉ có ảnh) hoặc PDF dùng
 *       font nhúng mã hóa riêng thì phải báo thẳng cho giáo viên đổi sang .docx thay vì trả về
 *       một mớ ký tự rác rồi để AI đoán mò.</li>
 * </ul>
 */
@Slf4j
@Component
public class ExamDocumentReader {

    /** Trần an toàn: đề bài dài nhất cũng chỉ vài chục KB chữ. */
    private static final int MAX_TEXT_CHARS = 200_000;

    private static final Pattern DOCX_PARAGRAPH = Pattern.compile("<w:p[ >].*?</w:p>", Pattern.DOTALL);
    private static final Pattern DOCX_TEXT = Pattern.compile("<w:t(?:\\s[^>]*)?>(.*?)</w:t>", Pattern.DOTALL);
    private static final Pattern PDF_STREAM = Pattern.compile("stream\\r?\\n?(.*?)endstream", Pattern.DOTALL);
    private static final Pattern PDF_SHOW_TEXT = Pattern.compile("\\(((?:\\\\.|[^\\\\()])*)\\)\\s*(?:Tj|TJ|')");

    /**
     * @return { text, format, warnings } — {@code text} là đề bài dạng văn bản thuần
     */
    public Map<String, Object> read(String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0)
            throw new IllegalArgumentException("File rỗng.");
        String name = fileName == null ? "" : fileName.toLowerCase();
        List<String> warnings = new ArrayList<>();
        String text;
        String format;

        if (name.endsWith(".docx")) {
            format = "docx";
            text = readDocx(bytes);
        } else if (name.endsWith(".pdf")) {
            format = "pdf";
            text = readPdf(bytes, warnings);
        } else if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".markdown")) {
            format = "text";
            text = stripBom(new String(bytes, StandardCharsets.UTF_8));
        } else if (name.endsWith(".doc")) {
            // .doc là định dạng nhị phân cũ, không bóc được nếu không có thư viện.
            throw new IllegalArgumentException("File .doc (Word 97-2003) không đọc được. "
                    + "Hãy mở bằng Word rồi lưu lại thành .docx.");
        } else {
            throw new IllegalArgumentException("Chỉ đọc được file .docx, .pdf, .txt hoặc .md "
                    + "(nhận được: " + (fileName == null ? "không rõ" : fileName) + ").");
        }

        text = normalize(text);
        if (text.length() < 40)
            throw new IllegalArgumentException("Không bóc được chữ từ file này"
                    + ("pdf".equals(format) ? " (thường là PDF bản scan — chỉ có ảnh, không có lớp chữ). "
                                              + "Hãy tải lên bản .docx." : ". Hãy kiểm tra lại file."));
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS);
            warnings.add("Đề quá dài nên chỉ lấy " + MAX_TEXT_CHARS + " ký tự đầu.");
        }
        if (garbledRatio(text) > 0.12)
            warnings.add("Chữ bóc ra từ file có nhiều ký tự lạ (font nhúng mã hóa riêng). "
                    + "Hãy đọc lại đề ở bước sau, hoặc tải lên bản .docx để chính xác hơn.");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", text);
        out.put("format", format);
        out.put("warnings", warnings);
        log.info("📥 Đã đọc đề tải lên ({}): {} ký tự, {} cảnh báo", format, text.length(), warnings.size());
        return out;
    }

    // ── .docx ────────────────────────────────────────────────────

    private String readDocx(byte[] bytes) {
        String xml = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!"word/document.xml".equals(entry.getName())) continue;
                xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                break;
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("File .docx hỏng hoặc không đúng định dạng: " + e.getMessage());
        }
        if (xml == null)
            throw new IllegalArgumentException("File .docx thiếu word/document.xml — không phải file Word hợp lệ.");

        StringBuilder out = new StringBuilder();
        Matcher paragraphs = DOCX_PARAGRAPH.matcher(xml);
        while (paragraphs.find()) {
            String p = paragraphs.group();
            StringBuilder line = new StringBuilder();
            Matcher runs = DOCX_TEXT.matcher(p);
            while (runs.find()) line.append(unescapeXml(runs.group(1)));
            // <w:tab/> giữa các run là cột trong danh sách/bảng → thay bằng khoảng trắng rộng.
            if (p.contains("<w:tab/>") && line.length() > 0) line.append(' ');
            out.append(line.toString().strip()).append('\n');
        }
        return out.toString();
    }

    // ── .pdf ─────────────────────────────────────────────────────

    private String readPdf(byte[] bytes, List<String> warnings) {
        // Latin-1 giữ nguyên từng byte thành từng ký tự → định vị được stream nhị phân bằng regex.
        String raw = new String(bytes, StandardCharsets.ISO_8859_1);
        StringBuilder out = new StringBuilder();
        Matcher streams = PDF_STREAM.matcher(raw);
        int decoded = 0;
        while (streams.find()) {
            byte[] chunk = streams.group(1).getBytes(StandardCharsets.ISO_8859_1);
            String content = inflate(chunk);
            if (content == null) continue;         // ảnh, font… không phải nội dung trang
            decoded++;
            appendPdfText(content, out);
        }
        if (decoded == 0)
            warnings.add("PDF này không nén theo kiểu thường gặp nên chữ bóc ra có thể thiếu.");
        return out.toString();
    }

    /** Giải nén FlateDecode; trả null nếu không phải dữ liệu nén hoặc không phải nội dung chữ. */
    private String inflate(byte[] data) {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0) break;
                sink.write(buffer, 0, n);
                if (sink.size() > 8 * 1024 * 1024) break;    // chặn stream ảnh khổng lồ
            }
        } catch (Exception e) {
            return null;
        } finally {
            inflater.end();
        }
        if (sink.size() == 0) return null;
        String s = new String(sink.toByteArray(), StandardCharsets.ISO_8859_1);
        return s.contains("Tj") || s.contains("TJ") ? s : null;
    }

    private void appendPdfText(String content, StringBuilder out) {
        Matcher shown = PDF_SHOW_TEXT.matcher(content);
        boolean any = false;
        while (shown.find()) {
            out.append(unescapePdf(shown.group(1)));
            any = true;
        }
        if (any) out.append('\n');
    }

    private String unescapePdf(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') { out.append(c); continue; }
            if (++i >= s.length()) break;
            char next = s.charAt(i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'b', 'f' -> out.append(' ');
                default -> {
                    if (next >= '0' && next <= '7') {     // mã bát phân \053
                        int value = next - '0';
                        for (int k = 0; k < 2 && i + 1 < s.length(); k++) {
                            char digit = s.charAt(i + 1);
                            if (digit < '0' || digit > '7') break;
                            value = value * 8 + (digit - '0');
                            i++;
                        }
                        out.append((char) value);
                    } else {
                        out.append(next);                  // \( \) \\
                    }
                }
            }
        }
        return out.toString();
    }

    // ── Dùng chung ───────────────────────────────────────────────

    /** Tỉ lệ ký tự "rác" — dấu hiệu font nhúng mã hóa riêng, chữ đọc ra không dùng được. */
    private double garbledRatio(String text) {
        if (text.isEmpty()) return 0;
        int bad = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\t' || c == '\r') continue;
            if (Character.isLetterOrDigit(c) || Character.isWhitespace(c)) continue;
            if (".,;:!?()[]{}<>/\\\"'-–—+=*%&#@$~`^|_…“”‘’".indexOf(c) >= 0) continue;
            bad++;
        }
        return (double) bad / text.length();
    }

    private String normalize(String text) {
        if (text == null) return "";
        String s = text.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\n{3,}", "\n\n");
        return s.strip();
    }

    private String stripBom(String s) {
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    private String unescapeXml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }
}
