package com.example.grader.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /** Như {@link #KHAI_HANG} nhưng lấy cả GIÁ TRỊ: {@code them = 'chi_tieu.them'}. */
    private static final Pattern HANG_KEM_GIA_TRI = Pattern.compile(
            "\\bstatic\\s+(?:const\\s+|final\\s+)?[A-Za-z_][\\w<>?,\\s]*?\\s([A-Za-z_]\\w*)\\s*=\\s*(['\"])(.*?)\\2");

    /** Như {@link #KHAI_HAM} nhưng lấy cả GIÁ TRỊ: {@code dong(int id) => 'chi_tieu.dong.$id'}. */
    private static final Pattern HAM_KEM_GIA_TRI = Pattern.compile(
            "\\bstatic\\s+(?:const\\s+)?[A-Za-z_][\\w<>?,\\s]*?\\s([A-Za-z_]\\w*)\\s*\\([^)]*\\)\\s*=>\\s*(['\"])(.*?)\\2");

    /**
     * Kết quả soát. Tách ba nhóm thay vì một danh sách câu chữ để {@link KetQua#gon()} ghép được
     * một dòng gọn, và để bài test khẳng định được TỪNG nhóm thay vì dò chuỗi.
     */
    record KetQua(String hong, List<String> khaiMaKhongGan, List<String> ganMaKhongKhai,
                  List<String> goThangGiaTri) {

        static KetQua khong() {
            return new KetQua(null, List.of(), List.of(), List.of());
        }

        static KetQua hong(String ly) {
            return new KetQua(ly, List.of(), List.of(), List.of());
        }

        boolean coLech() {
            return hong != null || !khaiMaKhongGan.isEmpty() || !ganMaKhongKhai.isEmpty()
                    || !goThangGiaTri.isEmpty();
        }

        /**
         * Một dòng, đủ để biết phải sửa CÁI GÌ ở đâu — dùng khi từ chối nhận file.
         *
         * <p>Phải gọn thật: câu này đi thẳng vào thanh báo lỗi của giao diện, nối thêm
         * "— Upload không thành công." ở cuối. Liệt kê theo NHÓM chứ không mỗi lệch một đoạn văn,
         * vì sửa một chỗ trong Golden thường đẻ ra hai triệu chứng cùng lúc.
         */
        String gon() {
            if (hong != null) return hong;
            List<String> ve = new ArrayList<>();
            // Vế này đứng TRƯỚC: nó là ca hay gặp nhất, và là ca mà hai vế kia mô tả sai bản chất.
            if (!goThangGiaTri.isEmpty()) {
                ve.add("gõ thẳng giá trị thay vì dùng hằng số: " + String.join(", ", goThangGiaTri));
            }
            if (!khaiMaKhongGan.isEmpty()) {
                ve.add("khai mà không gắn: " + String.join(", ", khaiMaKhongGan));
            }
            if (!ganMaKhongKhai.isEmpty()) {
                ve.add("gắn mà không khai: " + String.join(", ", ganMaKhongKhai));
            }
            return "Định danh chưa nhất quán — " + String.join("; ", ve);
        }
    }

    /**
     * Soát Golden.
     *
     * @param nguon đường dẫn tương đối (bắt đầu bằng {@code lib/}) → nội dung file
     */
    static KetQua kiem(Map<String, String> nguon) {
        String tepKhai = null;
        for (String duong : nguon.keySet()) {
            if (duong.endsWith("/" + TEN_TEP) || duong.equals("lib/" + TEN_TEP)) {
                tepKhai = duong;
                break;
            }
        }
        if (tepKhai == null) {
            return KetQua.hong("Golden thiếu lib/" + TEN_TEP
                    + " — đây là file hợp đồng định danh, khung phát cho sinh viên chép thẳng từ đây. "
                    + "Tải file mẫu ở màn đầu Bộ chấm Golden.");
        }

        String maKhai = MaNguonDart.boChuThich(nguon.get(tepKhai));
        Matcher mLop = TEN_LOP.matcher(maKhai);
        if (!mLop.find()) {
            return KetQua.hong(tepKhai + " không có khai báo class nào — máy không biết tra tham chiếu "
                    + "theo tên gì.");
        }
        String tenLop = mLop.group(1);

        Set<String> daKhai = new LinkedHashSet<>();
        for (Pattern p : List.of(KHAI_HANG, KHAI_HAM)) {
            Matcher m = p.matcher(maKhai);
            while (m.find()) daKhai.add(m.group(1));
        }
        if (daKhai.isEmpty()) {
            return KetQua.hong(tepKhai + " chưa khai định danh nào — xoá phần ví dụ trong file mẫu rồi "
                    + "thì phải thay bằng định danh của đề.");
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

        // GIÁ TRỊ của từng hằng số, để phân biệt hai ca mà bản đầu gộp làm một:
        //  - gõ thẳng ĐÚNG giá trị của một hằng số đã khai  -> chỉ là không dùng hằng số
        //  - gõ thẳng một chuỗi KHÔNG hằng số nào mang      -> sinh viên không có đường biết
        // Bản đầu báo ca thứ nhất thành HAI lỗi rời nhau ("khai mà không gắn X" + "gắn mà không
        // khai 'giá trị của X'"), đọc lên như hai chỗ phải sửa trong khi chỉ có một dòng.
        Map<String, String> giaTri = new LinkedHashMap<>();
        for (Pattern p : List.of(HANG_KEM_GIA_TRI, HAM_KEM_GIA_TRI)) {
            Matcher m = p.matcher(maKhai);
            while (m.find()) giaTri.putIfAbsent(m.group(1), m.group(3));
        }

        List<String> goThang = new ArrayList<>();
        List<String> goTay = new ArrayList<>();
        Set<String> daKeBangGoThang = new LinkedHashSet<>();
        for (String chuoi : chuoiGoTay) {
            String chu = tenHangMangGiaTri(giaTri, chuoi);
            if (chu != null) {
                daKeBangGoThang.add(chu);
                String nhan = tenLop + "." + chu;
                if (!goThang.contains(nhan)) goThang.add(nhan);
            } else {
                goTay.add("'" + chuoi + "'");
            }
        }

        List<String> thua = new ArrayList<>();
        for (String ten : daKhai) {
            if (daDung.contains(ten)) continue;
            // Đã kể ở vế "gõ thẳng giá trị" rồi thì đừng kể lại lần nữa dưới tên khác.
            if (daKeBangGoThang.contains(ten)) continue;
            thua.add(tenLop + "." + ten);
        }
        return new KetQua(null, thua, goTay, goThang);
    }

    /**
     * Hằng số nào mang đúng giá trị {@code chuoi}, hoặc mang khuôn sinh ra nó.
     *
     * <p>Khớp TUYỆT ĐỐI trước rồi mới tới khuôn: {@code 'chi_tieu.dong.$id'} có tiền tố
     * {@code chi_tieu.dong.} nên nếu xét trước, nó sẽ cướp mất một hằng số khác trùng khít.
     * Khuôn có tiền tố RỖNG (giá trị mở đầu bằng {@code $}) thì bỏ qua — nó khớp mọi thứ.
     */
    private static String tenHangMangGiaTri(Map<String, String> giaTri, String chuoi) {
        for (Map.Entry<String, String> e : giaTri.entrySet()) {
            if (e.getValue().equals(chuoi)) return e.getKey();
        }
        for (Map.Entry<String, String> e : giaTri.entrySet()) {
            int dau = e.getValue().indexOf('$');
            if (dau <= 0) continue;
            if (chuoi.startsWith(e.getValue().substring(0, dau))) return e.getKey();
        }
        return null;
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
