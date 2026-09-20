package com.example.grader.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Đối chiếu {@code lib/dinh_danh.dart} với những chỗ THẬT SỰ gắn định danh trong Golden.
 *
 * <p>Vì sao phải có: file {@code dinh_danh.dart} được chép NGUYÊN BYTE từ Golden sang khung phát
 * cho sinh viên ({@code ExamService.zipKhungPhat}). Nên hai lệch dưới đây đều rơi thẳng xuống đầu
 * sinh viên mà không khâu nào khác bắt được — kể cả Kiểm Golden, vì Golden luôn tự khớp với chính
 * nó:
 *
 * <ul>
 *   <li>KHAI MÀ KHÔNG GẮN — hằng số có trong file phát ra nhưng Golden không gắn nó vào widget nào.
 *       Sinh viên đọc file, tưởng phải gắn, gắn xong không tiêu chí nào chấm tới.</li>
 *   <li>GẮN MÀ KHÔNG KHAI — Golden gõ thẳng chuỗi vào {@code identifier:} thay vì đi qua lớp hằng
 *       số. Tên đó không có trong file sinh viên cầm, nên không có đường nào biết mà gắn.</li>
 * </ul>
 *
 * <p>Phép "đã gắn" tính theo THAM CHIẾU {@code <Lớp>.<thành viên>} ở bất kỳ đâu trong {@code lib/},
 * không chỉ ngay sau {@code identifier:}. Cố ý rộng như vậy: đề có thể dựng danh sách định danh rồi
 * truyền xuống widget qua biến, mà báo oan một hằng số đang dùng thì lần sau không ai đọc cảnh báo
 * nữa.
 */
final class KiemDinhDanh {

    private KiemDinhDanh() {}

    private static final String TEN_TEP = "dinh_danh.dart";

    /** {@code abstract final class DinhDanh {} } — lấy tên lớp để còn tìm tham chiếu của nó. */
    private static final Pattern TEN_LOP = Pattern.compile("\\bclass\\s+([A-Za-z_]\\w*)");

    /** {@code static const String them = '...'} */
    private static final Pattern KHAI_HANG = Pattern.compile(
            "\\bstatic\\s+(?:const\\s+|final\\s+)?[A-Za-z_][\\w<>?,\\s]*?\\s([A-Za-z_]\\w*)\\s*=");

    /** {@code static String dong(int id) => '...'} */
    private static final Pattern KHAI_HAM = Pattern.compile(
            "\\bstatic\\s+(?:const\\s+)?[A-Za-z_][\\w<>?,\\s]*?\\s([A-Za-z_]\\w*)\\s*\\(");

    /**
     * Soát Golden. Trả về danh sách câu lỗi đã diễn giải; rỗng nghĩa là khớp.
     *
     * @param nguon đường dẫn tương đối (bắt đầu bằng {@code lib/}) → nội dung file
     */
    static List<String> kiem(Map<String, String> nguon) {
        List<String> loi = new ArrayList<>();

        String tepKhai = null;
        for (String duong : nguon.keySet()) {
            if (duong.endsWith("/" + TEN_TEP) || duong.equals("lib/" + TEN_TEP)) {
                tepKhai = duong;
                break;
            }
        }
        if (tepKhai == null) {
            loi.add("Golden chưa có lib/" + TEN_TEP
                    + ". Đây là file hợp đồng định danh, khung phát cho sinh viên chép thẳng từ đây "
                    + "— thiếu nó thì không xuất được khung. Tải file mẫu ở màn đầu Bộ chấm Golden.");
            return loi;
        }

        String maKhai = MaNguonDart.boChuThich(nguon.get(tepKhai));
        Matcher mLop = TEN_LOP.matcher(maKhai);
        if (!mLop.find()) {
            loi.add(tepKhai + " không có khai báo class nào — máy không biết tra tham chiếu theo tên gì.");
            return loi;
        }
        String tenLop = mLop.group(1);

        Set<String> daKhai = new LinkedHashSet<>();
        for (Pattern p : List.of(KHAI_HANG, KHAI_HAM)) {
            Matcher m = p.matcher(maKhai);
            while (m.find()) daKhai.add(m.group(1));
        }
        if (daKhai.isEmpty()) {
            loi.add(tepKhai + " chưa khai định danh nào. Xoá phần ví dụ trong file mẫu rồi thì phải "
                    + "thay bằng định danh của đề.");
            return loi;
        }

        // Tham chiếu <Lớp>.<thành viên> ở mọi file KHÁC file khai.
        Pattern thamChieu = Pattern.compile("\\b" + Pattern.quote(tenLop) + "\\.([A-Za-z_]\\w*)");
        Set<String> daDung = new LinkedHashSet<>();
        Set<String> chuoiGoTay = new LinkedHashSet<>();
        for (Map.Entry<String, String> tep : nguon.entrySet()) {
            if (tep.getKey().equals(tepKhai)) continue;
            String ma = MaNguonDart.boChuThich(tep.getValue());
            Matcher m = thamChieu.matcher(ma);
            while (m.find()) daDung.add(m.group(1));
            chuoiGoTay.addAll(chuoiGanThang(ma));
        }

        for (String ten : daKhai) {
            if (!daDung.contains(ten)) {
                loi.add("KHAI MÀ KHÔNG GẮN: " + tenLop + "." + ten + " có trong " + tepKhai
                        + " nhưng không chỗ nào trong lib/ dùng tới. Sinh viên nhận được file này sẽ "
                        + "đi gắn một định danh không tiêu chí nào chấm — xoá nó, hoặc gắn nó vào widget.");
            }
        }
        for (String chuoi : chuoiGoTay) {
            loi.add("GẮN MÀ KHÔNG KHAI: Golden gắn identifier: '" + chuoi + "' bằng chuỗi gõ thẳng, "
                    + "không đi qua " + tenLop + ". Chuỗi này không có trong " + tepKhai + " nên sinh viên "
                    + "không có đường nào biết mà gắn. Khai nó thành hằng số rồi dùng lại.");
        }
        return loi;
    }

    /**
     * Những {@code identifier:} nhận thẳng một chuỗi hằng.
     *
     * <p>Đọc biểu thức sau dấu hai chấm bằng bộ đếm ngoặc chứ không cắt tại dấu phẩy đầu tiên:
     * {@code identifier: DinhDanh.dong(e.id ?? 0)} có cả ngoặc lẫn phẩy bên trong.
     */
    private static Set<String> chuoiGanThang(String ma) {
        Set<String> ra = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\bidentifier\\s*:\\s*").matcher(ma);
        while (m.find()) {
            String bieuThuc = bieuThucTu(ma, m.end());
            if (bieuThuc.isEmpty()) continue;
            char dau = bieuThuc.charAt(0);
            if (dau != '\'' && dau != '"') continue;
            int cuoi = bieuThuc.indexOf(dau, 1);
            if (cuoi > 1) ra.add(bieuThuc.substring(1, cuoi));
        }
        return ra;
    }

    /** Biểu thức bắt đầu tại {@code tu}, dừng ở dấu phẩy hoặc ngoặc đóng ở ĐỘ SÂU 0. */
    private static String bieuThucTu(String ma, int tu) {
        int sau = 0;
        for (int i = tu; i < ma.length(); i++) {
            char c = ma.charAt(i);
            if (c == '(' || c == '[') sau++;
            else if (c == ')' || c == ']') {
                if (sau == 0) return ma.substring(tu, i).trim();
                sau--;
            } else if (c == ',' && sau == 0) {
                return ma.substring(tu, i).trim();
            }
        }
        return ma.substring(tu).trim();
    }
}
