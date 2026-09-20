package com.example.grader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * CHẨN ĐOÁN HỢP ĐỒNG DỮ LIỆU của một bài nộp: hình dạng bảng mà mã nguồn sinh viên khai có
 * khớp với database mà máy chấm thật sự nạp vào hay không.
 *
 * <p>Vì sao cần: {@code database_helper.dart} là file CẤP SẴN, sinh viên không được sửa. Nhưng
 * nếu có sửa thì lúc chấm KHÔNG có lỗi nào nổ ở chỗ sửa — engine xoá database của app rồi chép
 * {@code fixtures/hidden.db} đè lên, nên {@code CREATE TABLE IF NOT EXISTS} trong mã sinh viên
 * không bao giờ chạy. Lỗi nổ muộn, ở câu truy vấn đầu tiên chạm vào một cột không tồn tại, dưới
 * dạng {@code no such column} — cả màn hình chết, hai ba chục tiêu chí trượt một lượt, và vết lỗi
 * trông y như một bug Flutter chứ không giống "em đã sửa file bị cấm sửa".
 *
 * <p><b>CHỈ GIẢI THÍCH, KHÔNG ĐỔI ĐIỂM.</b> Điểm đã mất và đúng là phải mất — lớp này chỉ dán
 * nhãn nguyên nhân để người chấm nhìn một dòng là biết, thay vì lội stack trace. Mọi lối ra ở
 * đây đều cộng thêm chữ, không có lối nào sửa {@code test_cases[]} hay mã chẩn đoán sẵn có.
 *
 * <p>Phạm vi cố ý hẹp: chỉ so HÌNH DẠNG BẢNG (tên bảng, tên cột, kiểu quy về affinity). Sinh
 * viên sửa {@code database_helper.dart} ở chỗ khác — đổi tên hàm, sửa câu {@code SELECT} — thì
 * hidden.db không nói gì được; muốn bắt cả những chỗ đó phải gửi kèm bản gốc của file vào gói
 * bàn giao, việc đó chưa làm.
 */
final class HopDongDuLieu {

    private static final Logger log = LoggerFactory.getLogger(HopDongDuLieu.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Khoá trong result.json — nằm ở tầng gốc, KHÔNG chạm vào test_cases[] nơi điểm được tính. */
    static final String KHOA = "hop_dong_du_lieu";

    /** Trần số dòng: một bài thay cả database_helper có thể lệch hàng chục cột, in hết là vô dụng. */
    private static final int TRAN_DONG = 20;

    /** Mở đầu một định nghĩa RÀNG BUỘC CẤP BẢNG chứ không phải một cột — phải bỏ khi đếm cột. */
    private static final List<String> TU_KHOA_RANG_BUOC =
            List.of("PRIMARY", "FOREIGN", "UNIQUE", "CHECK", "CONSTRAINT");

    private HopDongDuLieu() {}

    // ==================== LỐI VÀO ====================

    /**
     * So mã nguồn bài nộp với database chấm. Trả về danh sách rỗng khi khớp, hoặc khi không đủ
     * dữ kiện để so.
     *
     * <p>TUYỆT ĐỐI không ném: đây là lời giải thích thêm: hỏng nó thì mất một dòng chữ, còn để
     * nó làm hỏng lượt chấm thì mất cả bài. Mọi trục trặc chỉ ghi log.
     */
    static List<String> kiem(Path duAnBaiNop, Path hiddenDb) {
        if (duAnBaiNop == null || hiddenDb == null || !Files.isRegularFile(hiddenDb)) return List.of();
        try {
            Map<String, Map<String, String>> db = hinhDangTuDatabase(hiddenDb);
            if (db.isEmpty()) return List.of();
            return soSanh(hinhDangTuNguon(duAnBaiNop), db);
        } catch (Exception e) {
            log.warn("Không chẩn đoán được hợp đồng dữ liệu ({}): {}", hiddenDb.getFileName(), e.toString());
            return List.of();
        }
    }

    /** {@code <testcase>/fixtures/hidden.db} — null khi đề chưa xuất bản testcase. */
    static Path hiddenDbCuaTestcase(String testcasePath) {
        if (testcasePath == null || testcasePath.isBlank()) return null;
        return Path.of(testcasePath).resolve("fixtures").resolve("hidden.db");
    }

    // ==================== HAI PHÍA ====================

    /** Hình dạng thật của database chấm: {@code bảng -> (CỘT -> affinity)}. */
    static Map<String, Map<String, String>> hinhDangTuDatabase(Path db) throws Exception {
        Map<String, Map<String, String>> ra = new TreeMap<>();
        // CHỈ ĐỌC, có chủ đích: đây là hidden.db GỐC của đề, thứ mọi bài được chấm trên đó. Một
        // chẩn đoán phụ trợ không được có cửa nào chạm vào nó, và nhiều luồng chấm chạy song
        // song sẽ mở cùng file này cùng lúc — mở ghi là tự tạo ra tranh chấp không cần thiết.
        org.sqlite.SQLiteConfig chiDoc = new org.sqlite.SQLiteConfig();
        chiDoc.setReadOnly(true);
        try (Connection ket = DriverManager.getConnection(
                "jdbc:sqlite:" + db.toAbsolutePath(), chiDoc.toProperties())) {
            List<String> bang = new ArrayList<>();
            try (Statement st = ket.createStatement();
                 ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' "
                         + "AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
                while (rs.next()) bang.add(rs.getString(1));
            }
            for (String ten : bang) {
                // Tên bảng đi thẳng vào câu PRAGMA nên phải tự kiểm hình dạng; hidden.db là file
                // của giảng viên chứ không phải của sinh viên, nhưng một cái tên lạ ở đây mà lọt
                // xuống thì thành lỗ tiêm SQL, và ta cũng chẳng so được cái tên như thế với mã Dart.
                if (!ten.matches("[A-Za-z_][A-Za-z0-9_]*")) continue;
                Map<String, String> cot = new TreeMap<>();
                try (Statement st = ket.createStatement();
                     ResultSet rs = st.executeQuery("PRAGMA table_info(\"" + ten + "\")")) {
                    while (rs.next()) {
                        cot.put(rs.getString("name").toUpperCase(Locale.ROOT), affinity(rs.getString("type")));
                    }
                }
                if (!cot.isEmpty()) ra.put(ten.toLowerCase(Locale.ROOT), cot);
            }
        }
        return ra;
    }

    /** Hình dạng sinh viên KHAI trong mã Dart, bóc từ chính bộ phân tích dùng cho khung phát. */
    static Map<String, Map<String, String>> hinhDangTuNguon(Path duAn) throws Exception {
        Map<String, Map<String, String>> ra = new TreeMap<>();
        for (String dong : MaNguonDart.moTaBang(duAn)) {
            int mo = dong.indexOf('(');
            if (mo < 0 || !dong.endsWith(")")) continue;
            String bang = dong.substring(0, mo);
            // Cùng một bảng khai ở hai file thì giữ bản GẶP TRƯỚC: moTaBang đã sắp sẵn nên thứ tự
            // ổn định giữa các lần chấm, còn bản nào "đúng" thì ta không có cơ sở để chọn.
            if (ra.containsKey(bang)) continue;
            Map<String, String> cot = new TreeMap<>();
            for (String muc : tachCot(dong.substring(mo + 1, dong.length() - 1))) {
                String[] tu = muc.trim().split("\\s+");
                if (tu.length == 0 || tu[0].isEmpty()) continue;
                String ten = tu[0].replace("[", "").replace("]", "").replace("'", "");
                if (TU_KHOA_RANG_BUOC.contains(ten)) continue;
                cot.put(ten.toUpperCase(Locale.ROOT), affinity(tu.length > 1 ? tu[1] : ""));
            }
            if (!cot.isEmpty()) ra.put(bang, cot);
        }
        return ra;
    }

    // ==================== SO ====================

    static List<String> soSanh(Map<String, Map<String, String>> nguon,
                               Map<String, Map<String, String>> db) {
        List<String> ra = new ArrayList<>();

        // Không có CREATE TABLE nào: đừng liệt kê từng bảng thiếu — đó là một sự việc, không
        // phải N sự việc, và bản chất là "file cấp sẵn đã bị thay", không phải "quên một cột".
        if (nguon.isEmpty()) {
            return List.of("Mã nguồn bài nộp không có câu CREATE TABLE nào, trong khi database chấm có "
                    + db.size() + " bảng (" + String.join(", ", db.keySet())
                    + "). database_helper.dart nhiều khả năng đã bị thay hoặc xoá.");
        }

        for (String bang : new TreeSet<>(nguon.keySet())) {
            if (!db.containsKey(bang)) {
                ra.add("Mã nguồn tạo bảng \"" + bang + "\" mà database chấm không có bảng này.");
            }
        }
        for (String bang : new TreeSet<>(db.keySet())) {
            if (!nguon.containsKey(bang)) {
                ra.add("Database chấm có bảng \"" + bang + "\" mà mã nguồn không khai.");
            }
        }

        for (Map.Entry<String, Map<String, String>> e : nguon.entrySet()) {
            Map<String, String> cotDb = db.get(e.getKey());
            if (cotDb == null) continue;
            Map<String, String> cotNguon = e.getValue();
            for (Map.Entry<String, String> c : cotNguon.entrySet()) {
                String kieuDb = cotDb.get(c.getKey());
                if (kieuDb == null) {
                    // Đây là hướng NGUY HIỂM: truy vấn của sinh viên sẽ gọi tên cột này.
                    ra.add("Bảng \"" + e.getKey() + "\": mã nguồn khai cột " + c.getKey()
                            + " mà database chấm không có — mọi truy vấn chạm cột này sẽ ném \"no such column\".");
                } else if (!kieuDb.equals(c.getValue())) {
                    ra.add("Bảng \"" + e.getKey() + "\": cột " + c.getKey() + " khai kiểu "
                            + c.getValue() + ", database chấm là " + kieuDb + ".");
                }
            }
            for (String ten : cotDb.keySet()) {
                if (!cotNguon.containsKey(ten)) {
                    ra.add("Bảng \"" + e.getKey() + "\": database chấm có cột " + ten
                            + " mà mã nguồn không khai.");
                }
            }
        }

        if (ra.size() <= TRAN_DONG) return List.copyOf(ra);
        List<String> cat = new ArrayList<>(ra.subList(0, TRAN_DONG));
        cat.add("… và " + (ra.size() - TRAN_DONG) + " khác biệt nữa.");
        return List.copyOf(cat);
    }

    // ==================== ĐƯA KẾT LUẬN RA NGOÀI ====================

    /**
     * Gắn kết luận vào result.json ở tầng GỐC. Hỏng thì trả nguyên chuỗi cũ: thà mất lời giải
     * thích còn hơn trả về một JSON vỡ mà cả đường đọc kết quả phía sau không phân tích được.
     */
    static String ganVaoKetQua(String json, List<String> lech) {
        if (lech == null || lech.isEmpty() || json == null || json.isBlank()) return json;
        try {
            var goc = mapper.readTree(json);
            if (!(goc instanceof ObjectNode root)) return json;
            Map<String, Object> khoi = new LinkedHashMap<>();
            khoi.put("khop", false);
            khoi.put("khac_biet", lech);
            root.set(KHOA, mapper.valueToTree(khoi));
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Không gắn được chẩn đoán hợp đồng dữ liệu vào kết quả: {}", e.toString());
            return json;
        }
    }

    /**
     * Đọc ngược kết luận đã gắn ở {@link #ganVaoKetQua}. Dùng cho lối bài CHẠY XONG nhưng trượt:
     * ở đó chẩn đoán nằm trong result.json mà người chấm không mở JSON ra xem, nên phải nối
     * thêm vào thông điệp hiện trên bảng kết quả.
     */
    static List<String> docTuKetQua(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            var khac = mapper.readTree(json).path(KHOA).path("khac_biet");
            if (!khac.isArray()) return List.of();
            List<String> ra = new ArrayList<>();
            khac.forEach(n -> ra.add(n.asText()));
            return List.copyOf(ra);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Bài chết trước khi có result.json (thường là compile lỗi) — mà đó chính là lúc lời giải
     * thích này đáng giá nhất. Nối vào THÔNG ĐIỆP, giữ nguyên code/origin/stage/manualReview để
     * không đụng vào cách hệ thống phân loại và định đoạt lượt chấm.
     */
    static GradingDiagnosticException themVaoChanDoan(GradingDiagnosticException goc, List<String> lech) {
        if (goc == null || lech == null || lech.isEmpty()) return goc;
        String them = " · Hợp đồng dữ liệu lệch: " + String.join(" ", lech);
        return new GradingDiagnosticException(goc.code(), goc.origin(), goc.stage(),
                goc.manualReview(), goc.getMessage() + them, goc);
    }

    // ==================== TIỆN ÍCH ====================

    /**
     * Quy kiểu khai báo về LỚP LƯU TRỮ của SQLite, theo đúng thứ tự luật trong tài liệu SQLite.
     *
     * <p>Phải quy đổi chứ không so chữ: {@code VARCHAR(50)} và {@code TEXT} là CÙNG một kiểu với
     * SQLite. So thẳng mặt chữ thì mỗi bài viết khác cách một chút lại thành một báo động oan,
     * mà báo động oan nhiều lần thì đến lần thật người chấm cũng bỏ qua.
     */
    static String affinity(String kieu) {
        String k = kieu == null ? "" : kieu.toUpperCase(Locale.ROOT).trim();
        if (k.contains("INT")) return "INTEGER";
        if (k.contains("CHAR") || k.contains("CLOB") || k.contains("TEXT")) return "TEXT";
        if (k.isEmpty() || k.contains("BLOB")) return "BLOB";
        if (k.contains("REAL") || k.contains("FLOA") || k.contains("DOUB")) return "REAL";
        return "NUMERIC";
    }

    /**
     * Cắt thân CREATE TABLE thành từng định nghĩa, chỉ ngắt ở dấu phẩy NGOÀI mọi cặp ngoặc —
     * nếu không thì {@code PRIMARY KEY (a, b)} và {@code DECIMAL(10,2)} bị xé làm đôi.
     */
    static List<String> tachCot(String than) {
        List<String> ra = new ArrayList<>();
        StringBuilder hienTai = new StringBuilder();
        int sau = 0;
        for (char c : than.toCharArray()) {
            if (c == '(') sau++;
            if (c == ')') sau--;
            if (c == ',' && sau == 0) {
                ra.add(hienTai.toString());
                hienTai.setLength(0);
            } else {
                hienTai.append(c);
            }
        }
        ra.add(hienTai.toString());
        return ra;
    }
}
