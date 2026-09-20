package com.example.grader.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Dựng {@code lib/main.dart} của KHUNG PHÁT từ {@code lib/main.dart} của Golden.
 *
 * <p>Khung là vỏ {@code flutter create} cộng MỌI tham số {@code MaterialApp} của Golden, TRỪ
 * {@code home:} — chỗ đó thay bằng một Scaffold trống, vì đó mới là bài làm.
 *
 * <p>Vì sao phải mang theo cả {@code theme:}: checkpoint {@code component_position} chấm bằng toạ độ
 * pixel tuyệt đối với dung sai 5% (một dòng chữ cao 16px thì sai số chỉ ±0.8px). Đổi cỡ chữ hay mật độ
 * hiển thị là lệch hết. Không có loại checkpoint nào cho điểm việc CHỌN theme, nên cấp theme cho sinh
 * viên không cho không ai điểm nào — còn không cấp thì cả lô bài lệch vị trí mà chẳng ai làm gì sai.
 *
 * <p>Bóc không được thì NÉM LỖI bắt giảng viên sửa, không đoán bừa: khung sai âm thầm thì cả lớp chịu.
 */
final class KhungMainDart {

    private KhungMainDart() {}

    /** Thân màn hình chỗ trống của khung — sinh viên thay bằng màn của mình. */
    static final String HOME_KHUNG =
            "const Scaffold(\n        body: Center(child: Text('Bat dau lam bai tai day')),\n      )";

    private static final String CO_BANNER = "debugShowCheckedModeBanner";

    /**
     * @param goldenMain nội dung {@code lib/main.dart} của Golden
     * @return nội dung {@code lib/main.dart} của khung phát
     */
    static String sinh(String goldenMain) {
        List<String> thamSo = thamSoMaterialApp(goldenMain);

        List<String> giuLai = new ArrayList<>();
        boolean daCoBanner = false;
        for (String ts : thamSo) {
            String ten = tenThamSo(ts);
            if (ten.equals("home")) continue;                 // chỗ này là bài làm
            if (ten.equals(CO_BANNER)) {
                // Ép về false dù Golden khai gì: banner không ảnh hưởng chấm (đã đo — không lệch
                // pixel, không vào cây ngữ nghĩa), nhưng để nó bật thì ảnh chụp của sinh viên khác
                // Golden ở góc trên phải, hỏng mọi tiêu chí so ảnh về sau.
                giuLai.add(CO_BANNER + ": false");
                daCoBanner = true;
                continue;
            }
            giuLai.add(ts);
        }
        if (!daCoBanner) giuLai.add(CO_BANNER + ": false");
        giuLai.add("home: " + HOME_KHUNG);

        StringBuilder sb = new StringBuilder();
        sb.append("import 'package:flutter/material.dart';\n\n")
          .append("void main() {\n  runApp(const MyApp());\n}\n\n")
          .append("class MyApp extends StatelessWidget {\n")
          .append("  const MyApp({super.key});\n\n")
          .append("  @override\n")
          .append("  Widget build(BuildContext context) {\n")
          .append("    return MaterialApp(\n");
        for (String ts : giuLai) sb.append("      ").append(ts).append(",\n");
        sb.append("    );\n  }\n}\n");
        return sb.toString();
    }

    /** Các tham số ở CẤP NGOÀI CÙNG của lời gọi MaterialApp(...), giữ nguyên thứ tự và định dạng. */
    static List<String> thamSoMaterialApp(String nguon) {
        String ma = boChuThich(nguon);
        int mo = viTriMoNgoac(ma);
        if (mo < 0) {
            throw new IllegalArgumentException(
                    "Không tìm thấy lời gọi MaterialApp(...) trong lib/main.dart của Golden."
                            + " Khung phát dựng từ đó nên phải sửa Golden trước khi xuất gói.");
        }
        int dong = ngoacDong(ma, mo);
        if (dong < 0) {
            throw new IllegalArgumentException(
                    "Lời gọi MaterialApp(...) trong lib/main.dart của Golden không đóng ngoặc đúng.");
        }
        return tachThamSo(ma.substring(mo + 1, dong));
    }

    private static int viTriMoNgoac(String ma) {
        int i = ma.indexOf("MaterialApp");
        while (i >= 0) {
            // Bỏ qua chữ MaterialApp nằm trong một danh từ dài hơn (CupertinoMaterialApp…).
            boolean truocSach = i == 0 || !Character.isJavaIdentifierPart(ma.charAt(i - 1));
            int j = i + "MaterialApp".length();
            while (j < ma.length() && Character.isWhitespace(ma.charAt(j))) j++;
            if (truocSach && j < ma.length() && ma.charAt(j) == '(') return j;
            i = ma.indexOf("MaterialApp", i + 1);
        }
        return -1;
    }

    /** Vị trí dấu ')' khớp với '(' tại {@code mo}; bỏ qua ngoặc nằm trong chuỗi. */
    private static int ngoacDong(String ma, int mo) {
        int sau = 0;
        for (int i = mo; i < ma.length(); i++) {
            char c = ma.charAt(i);
            if (c == '\'' || c == '"') { i = cuoiChuoi(ma, i); continue; }
            if (c == '(' || c == '[' || c == '{') sau++;
            else if (c == ')' || c == ']' || c == '}') {
                sau--;
                if (sau == 0) return i;
            }
        }
        return -1;
    }

    /**
     * Tách danh sách tham số có tên, cắt ở dấu phẩy ĐỘ SÂU 0 mà NGAY SAU nó là một tên tham số.
     *
     * <p>Không đếm ngoặc nhọn để biết độ sâu: {@code <String, WidgetBuilder>} có dấu phẩy bên trong,
     * nhưng {@code >} còn là toán tử so sánh và là nửa sau của {@code =>}, đếm kiểu ngoặc là sai.
     * Tham số của {@code MaterialApp} đều có tên nên mốc {@code tên:} là dấu hiệu chắc chắn hơn.
     */
    private static List<String> tachThamSo(String than) {
        List<String> ra = new ArrayList<>();
        int sau = 0, dau = 0;
        for (int i = 0; i < than.length(); i++) {
            char c = than.charAt(i);
            if (c == '\'' || c == '"') { i = cuoiChuoi(than, i); continue; }
            if (c == '(' || c == '[' || c == '{') sau++;
            else if (c == ')' || c == ']' || c == '}') sau--;
            else if (c == ',' && sau == 0 && batDauThamSoMoi(than, i + 1)) {
                themNeuCo(ra, than.substring(dau, i));
                dau = i + 1;
            }
        }
        themNeuCo(ra, than.substring(dau));
        return ra;
    }

    /** Sau vị trí này có phải một tham số mới dạng {@code tên:} không (bỏ qua khoảng trắng). */
    private static boolean batDauThamSoMoi(String than, int tu) {
        int i = tu;
        while (i < than.length() && Character.isWhitespace(than.charAt(i))) i++;
        int dau = i;
        while (i < than.length() && Character.isJavaIdentifierPart(than.charAt(i))) i++;
        if (i == dau) return false;
        while (i < than.length() && Character.isWhitespace(than.charAt(i))) i++;
        // ':' chu khong phai '::' hay ':=' — Dart khong co hai cai sau, nhung phong cho chac.
        return i < than.length() && than.charAt(i) == ':';
    }

    private static void themNeuCo(List<String> ra, String phan) {
        String s = phan.strip();
        // Dart cho phep dau phay treo o tham so cuoi; giu lai thi luc sinh ma thanh ",,".
        while (s.endsWith(",")) s = s.substring(0, s.length() - 1).stripTrailing();
        if (!s.isEmpty()) ra.add(s);
    }

    /** Tên tham số đứng trước dấu ':' ở đầu; rỗng nếu là tham số vị trí. */
    private static String tenThamSo(String thamSo) {
        int i = thamSo.indexOf(':');
        if (i < 0) return "";
        String ten = thamSo.substring(0, i).strip();
        return ten.matches("[A-Za-z_][A-Za-z0-9_]*") ? ten : "";
    }

    /** Vị trí ký tự đóng của chuỗi bắt đầu tại {@code mo}; tôn trọng dấu thoát. */
    private static int cuoiChuoi(String ma, int mo) {
        char nhay = ma.charAt(mo);
        for (int i = mo + 1; i < ma.length(); i++) {
            char c = ma.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == nhay) return i;
        }
        return ma.length() - 1;
    }

    /**
     * Thay chú thích bằng khoảng trắng, GIỮ NGUYÊN độ dài để mọi chỉ số vẫn trỏ đúng chỗ.
     * Chuỗi được để yên vì URI trong chuỗi có thể chứa "//".
     */
    static String boChuThich(String nguon) {
        StringBuilder ra = new StringBuilder(nguon.length());
        for (int i = 0; i < nguon.length(); i++) {
            char c = nguon.charAt(i);
            char ke = i + 1 < nguon.length() ? nguon.charAt(i + 1) : '\0';
            if (c == '\'' || c == '"') {
                int cuoi = cuoiChuoi(nguon, i);
                ra.append(nguon, i, cuoi + 1);
                i = cuoi;
            } else if (c == '/' && ke == '/') {
                while (i < nguon.length() && nguon.charAt(i) != '\n') { ra.append(' '); i++; }
                if (i < nguon.length()) ra.append('\n');
            } else if (c == '/' && ke == '*') {
                ra.append("  ");
                i += 2;
                while (i < nguon.length() && !(nguon.charAt(i) == '*' && i + 1 < nguon.length()
                        && nguon.charAt(i + 1) == '/')) {
                    ra.append(nguon.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < nguon.length()) { ra.append("  "); i++; }
            } else {
                ra.append(c);
            }
        }
        return ra.toString();
    }
}
