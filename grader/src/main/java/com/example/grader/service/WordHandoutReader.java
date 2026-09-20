package com.example.grader.service;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

/** Đọc cấu trúc Word thành cú pháp đề bài; không biến bảng thành một chuỗi các ô rời nhau. */
final class WordHandoutReader {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private final Map<String, Element> styles = new HashMap<>();
    private final Map<String, Element> nums = new HashMap<>();
    private final Map<String, Element> abstracts = new HashMap<>();
    private final Map<String, int[]> counters = new HashMap<>();

    static String read(byte[] bytes) {
        try {
            Map<String, Document> parts = new HashMap<>();
            try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (!List.of("word/document.xml", "word/styles.xml", "word/numbering.xml").contains(name)) continue;
                    byte[] xml = zip.readNBytes(8 * 1024 * 1024 + 1);
                    if (xml.length > 8 * 1024 * 1024) throw new IllegalArgumentException("Phần XML của Word quá lớn.");
                    var factory = DocumentBuilderFactory.newInstance();
                    factory.setNamespaceAware(true);
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                    parts.put(name, factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml)));
                }
            }
            Document document = parts.get("word/document.xml");
            if (document == null) throw new IllegalArgumentException("File .docx thiếu word/document.xml.");
            var reader = new WordHandoutReader();
            reader.index(parts.get("word/styles.xml"), "style", "styleId", reader.styles);
            reader.index(parts.get("word/numbering.xml"), "num", "numId", reader.nums);
            reader.index(parts.get("word/numbering.xml"), "abstractNum", "abstractNumId", reader.abstracts);
            var out = new StringBuilder();
            reader.body(child(document.getDocumentElement(), "body"), out);
            return out.toString().strip();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Không đọc được cấu trúc Word: " + e.getMessage(), e);
        }
    }

    private void index(Document doc, String tag, String id, Map<String, Element> target) {
        if (doc == null) return;
        for (Element e : children(doc.getDocumentElement(), tag)) target.put(attr(e, id), e);
    }

    private void body(Element parent, StringBuilder out) {
        if (parent == null) return;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element e) || !W.equals(e.getNamespaceURI())) continue;
            switch (e.getLocalName()) {
                case "p" -> out.append(paragraph(e, true)).append("\n\n");
                case "tbl" -> table(e, out);
                case "sdt" -> body(child(e, "sdtContent"), out);
                default -> { }
            }
        }
    }

    private String paragraph(Element p, boolean layout) {
        String text = text(p).strip();
        if (text.isEmpty()) return "";
        String styleId = attr(child(child(p, "pPr"), "pStyle"), "val");
        Element outline = property(p, "outlineLvl");
        int level = integer(attr(outline, "val"), -1);
        if (level < 0 && styleId.matches("(?i)heading[1-6]")) level = Integer.parseInt(styleId.substring(7)) - 1;
        String prefix = level >= 0 && level < 6 ? "#".repeat(Math.min(3, level + 1)) + " " : listPrefix(p);
        if (!layout) return prefix + text;
        String align = attr(property(p, "jc"), "val");
        if ("both".equals(align)) align = "justify";
        if ("start".equals(align)) align = "left";
        if ("end".equals(align)) align = "right";
        if (!List.of("left", "center", "right", "justify").contains(align)) align = "left";
        Element ind = property(p, "ind");
        int indent = Math.min(240, Math.max(0, integer(attr(ind, "left"), integer(attr(ind, "start"), 0)) / 15));
        String directive = !"left".equals(align) || indent > 0 ? "<!-- layout:" + align + ":" + indent + " -->\n" : "";
        return directive + prefix + text;
    }

    /** Lần theo basedOn để Heading/ListParagraph đặt trong styles.xml vẫn có hiệu lực. */
    private Element property(Element paragraph, String name) {
        Element properties = child(paragraph, "pPr");
        Element found = child(properties, name);
        String id = attr(child(properties, "pStyle"), "val");
        for (int depth = 0; found == null && !id.isEmpty() && depth < 12; depth++) {
            Element style = styles.get(id);
            if (style == null) break;
            found = child(child(style, "pPr"), name);
            id = attr(child(style, "basedOn"), "val");
        }
        return found;
    }

    private String listPrefix(Element p) {
        Element numPr = property(p, "numPr");
        String id = attr(child(numPr, "numId"), "val");
        if (id.isEmpty() || "0".equals(id)) return "";
        int depth = Math.min(8, Math.max(0, integer(attr(child(numPr, "ilvl"), "val"), 0)));
        Element num = nums.get(id);
        Element definition = abstracts.get(attr(child(num, "abstractNumId"), "val"));
        Element lvl = indexedChild(definition, "lvl", "ilvl", depth);
        Element override = indexedChild(num, "lvlOverride", "ilvl", depth);
        if (child(override, "lvl") != null) lvl = child(override, "lvl");
        String format = attr(child(lvl, "numFmt"), "val");
        if ("bullet".equals(format)) return "- ";
        if ("none".equals(format)) return "";
        int start = integer(attr(child(override, "startOverride"), "val"), integer(attr(child(lvl, "start"), "val"), 1));
        int[] values = counters.computeIfAbsent(id, key -> {
            int[] a = new int[9];
            java.util.Arrays.fill(a, -1);
            return a;
        });
        values[depth] = values[depth] < 0 ? start : values[depth] + 1;
        for (int i = depth + 1; i < values.length; i++) values[i] = -1;
        String label = attr(child(lvl, "lvlText"), "val");
        if (label.isEmpty()) label = "%" + (depth + 1) + ".";
        for (int i = 0; i <= depth; i++) {
            Element parentLevel = indexedChild(definition, "lvl", "ilvl", i);
            String fmt = i == depth ? format : attr(child(parentLevel, "numFmt"), "val");
            int value = values[i] < 0 ? integer(attr(child(parentLevel, "start"), "val"), 1) : values[i];
            label = label.replace("%" + (i + 1), formatNumber(value, fmt));
        }
        return label + " ";
    }

    private static String formatNumber(int n, String format) {
        if (("lowerLetter".equals(format) || "upperLetter".equals(format)) && n > 0) {
            StringBuilder s = new StringBuilder();
            while (n > 0) { s.insert(0, (char) ('A' + (n - 1) % 26)); n = (n - 1) / 26; }
            return "lowerLetter".equals(format) ? s.toString().toLowerCase(java.util.Locale.ROOT) : s.toString();
        }
        if (("lowerRoman".equals(format) || "upperRoman".equals(format)) && n > 0 && n < 4000) {
            int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
            String[] digits = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < values.length; i++) while (n >= values[i]) { s.append(digits[i]); n -= values[i]; }
            return "lowerRoman".equals(format) ? s.toString().toLowerCase(java.util.Locale.ROOT) : s.toString();
        }
        return String.valueOf(n);
    }

    private void table(Element table, StringBuilder out) {
        List<List<String>> rows = new ArrayList<>();
        int columns = 0;
        for (Element row : children(table, "tr")) {
            List<String> cells = new ArrayList<>();
            for (Element cell : children(row, "tc")) {
                List<String> paragraphs = new ArrayList<>();
                for (Element p : children(cell, "p")) paragraphs.add(paragraph(p, false));
                cells.add(String.join("\n", paragraphs).replace("|", "\\|").replace("\n", "<br>"));
                // Markdown không gộp ô: giữ vị trí các cột sau ô gộp bằng ô trống.
                int span = Math.min(100, Math.max(1, integer(attr(child(child(cell, "tcPr"), "gridSpan"), "val"), 1)));
                for (int i = 1; i < span; i++) cells.add("");
            }
            columns = Math.max(columns, cells.size());
            rows.add(cells);
        }
        if (columns == 0) return;
        out.append('\n');
        for (int r = 0; r < rows.size(); r++) {
            List<String> cells = rows.get(r);
            while (cells.size() < columns) cells.add("");
            out.append("| ").append(String.join(" | ", cells)).append(" |\n");
            if (r == 0) out.append('|').append(" --- |".repeat(columns)).append('\n');
        }
        out.append('\n');
    }

    private static String text(Node parent) {
        StringBuilder out = new StringBuilder();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element e)) continue;
            String tag = W.equals(e.getNamespaceURI()) ? e.getLocalName() : "";
            switch (tag) {
                case "t" -> out.append(e.getTextContent());
                case "br", "cr" -> out.append('\n');
                case "tab" -> out.append("    ");
                case "del", "pPr", "rPr" -> { }
                default -> out.append(text(e));
            }
        }
        return out.toString();
    }

    private static Element indexedChild(Element parent, String tag, String attribute, int value) {
        return children(parent, tag).stream().filter(e -> attr(e, attribute).equals(String.valueOf(value))).findFirst().orElse(null);
    }
    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        if (parent != null) for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element e && W.equals(e.getNamespaceURI()) && tag.equals(e.getLocalName())) out.add(e);
        return out;
    }
    private static Element child(Element parent, String tag) {
        List<Element> matches = children(parent, tag);
        return matches.isEmpty() ? null : matches.get(0);
    }
    private static String attr(Element e, String name) { return e == null ? "" : e.getAttributeNS(W, name); }
    private static int integer(String s, int fallback) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }
}
