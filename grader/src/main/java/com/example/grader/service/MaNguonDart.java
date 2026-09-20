package com.example.grader.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Bóc dữ liệu từ MÃ NGUỒN Dart của một dự án: cấu trúc bảng và danh sách định danh.
 *
 * <p>Tách ra khỏi {@code StarterSyncService} khi bỏ màn Kiểm đồng bộ (19/9). Màn đó không còn
 * đối tượng để kiểm — khung phát nay do máy sinh từ Golden nên so khung với Golden là so một thứ
 * với đầu ra của chính nó. Nhưng hai bộ phân tích thì vẫn cần: chúng là cách duy nhất đọc được
 * hợp đồng dữ liệu và danh sách định danh từ một thư mục {@code lib/} bất kỳ, kể cả của BÀI NỘP.
 */
final class MaNguonDart {

    private MaNguonDart() {}

    private static final Pattern MO_BANG = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[\"'`\\[]?([A-Za-z_][A-Za-z0-9_]*)[\"'`\\]]?\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CHUOI_DART = Pattern.compile("'([^'\\n]*)'|\"([^\"\\n]*)\"");

    private static final char NHAY_DON = (char) 39;
    private static final char NHAY_KEP = (char) 34;
    private static final char GACH_NGUOC = (char) 92;
    private static final char XUONG_DONG = (char) 10;

    /** Mỗi bảng một dòng {@code ten(cot1 kieu, cot2 kieu...)}, đã chuẩn hoá, sắp theo tên bảng. */
    static List<String> moTaBang(Path duAn) throws Exception {
        List<String> ra = new ArrayList<>();
        for (Path tep : cacTepDart(duAn)) {
            String ma = boChuThich(Files.readString(tep, StandardCharsets.UTF_8));
            Matcher m = MO_BANG.matcher(ma);
            while (m.find()) {
                String than = thanTrongNgoac(ma, m.end() - 1);
                if (than == null) continue;
                ra.add(m.group(1).toLowerCase(Locale.ROOT) + "(" + chuanHoaCot(than) + ")");
            }
        }
        ra.sort(Comparator.naturalOrder());
        return ra;
    }

    /** Mọi chuỗi hằng trong file định danh — đó mới là thứ máy chấm tra, không phải chú thích. */
    static List<String> dinhDanh(Path duAn) throws Exception {
        Set<String> ra = new LinkedHashSet<>();
        for (Path tep : cacTepDart(duAn)) {
            if (!tep.getFileName().toString().equals("dinh_danh.dart")) continue;
            Matcher m = CHUOI_DART.matcher(boChuThich(Files.readString(tep, StandardCharsets.UTF_8)));
            while (m.find()) {
                String s = m.group(1) != null ? m.group(1) : m.group(2);
                if (s != null && !s.isBlank()) ra.add(s);
            }
        }
        List<String> sap = new ArrayList<>(ra);
        sap.sort(Comparator.naturalOrder());
        return sap;
    }

    /** Cắt phần trong cặp ngoặc bắt đầu tại vị trí {@code mo}, đếm ngoặc lồng nhau. */
    static String thanTrongNgoac(String ma, int mo) {
        int sau = 0;
        for (int i = mo; i < ma.length(); i++) {
            char c = ma.charAt(i);
            if (c == '(') sau++;
            else if (c == ')') {
                sau--;
                if (sau == 0) return ma.substring(mo + 1, i);
            }
        }
        return null;
    }

    /** Gộp khoảng trắng, bỏ dấu nháy quanh tên cột, viết hoa để một dấu cách không thành lỗi. */
    static String chuanHoaCot(String than) {
        List<String> cot = new ArrayList<>();
        int sau = 0;
        StringBuilder hienTai = new StringBuilder();
        for (char c : than.toCharArray()) {
            if (c == '(') sau++;
            if (c == ')') sau--;
            if (c == ',' && sau == 0) {
                cot.add(hienTai.toString());
                hienTai.setLength(0);
            } else {
                hienTai.append(c);
            }
        }
        cot.add(hienTai.toString());
        List<String> ra = new ArrayList<>();
        for (String c : cot) {
            String s = c.replace("\"", "").replace("`", "")
                    .replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
            if (!s.isEmpty()) ra.add(s);
        }
        return String.join(", ", ra);
    }

    /** Thay chú thích bằng khoảng trắng; chuỗi để yên vì URI trong chuỗi có thể chứa "//". */
    static String boChuThich(String ma) {
        StringBuilder ra = new StringBuilder(ma.length());
        int i = 0;
        while (i < ma.length()) {
            char c = ma.charAt(i);
            if (c == NHAY_DON || c == NHAY_KEP) {
                char nhay = c;
                ra.append(c);
                i++;
                while (i < ma.length()) {
                    char d = ma.charAt(i);
                    ra.append(d);
                    i++;
                    if (d == GACH_NGUOC && i < ma.length()) {
                        ra.append(ma.charAt(i));
                        i++;
                        continue;
                    }
                    if (d == nhay || d == XUONG_DONG) break;
                }
                continue;
            }
            if (c == '/' && i + 1 < ma.length() && ma.charAt(i + 1) == '/') {
                while (i < ma.length() && ma.charAt(i) != XUONG_DONG) i++;
                continue;
            }
            if (c == '/' && i + 1 < ma.length() && ma.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < ma.length() && !(ma.charAt(i) == '*' && ma.charAt(i + 1) == '/')) i++;
                i = Math.min(ma.length(), i + 2);
                ra.append(' ');
                continue;
            }
            ra.append(c);
            i++;
        }
        return ra.toString();
    }

    static List<Path> cacTepDart(Path duAn) throws Exception {
        Path lib = duAn.resolve("lib");
        if (!Files.isDirectory(lib)) return List.of();
        try (Stream<Path> tep = Files.walk(lib)) {
            return tep.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".dart"))
                    .sorted()
                    .toList();
        }
    }
}
