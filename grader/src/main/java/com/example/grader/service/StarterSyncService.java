package com.example.grader.service;

import com.example.grader.entity.BehaviorArtifact;
import com.example.grader.entity.BehaviorArtifactType;
import com.example.grader.entity.BehaviorSuite;
import com.example.grader.entity.Exam;
import com.example.grader.repository.BehaviorSuiteRepository;
import com.example.grader.repository.ExamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * KIỂM ĐỒNG BỘ KHUNG PHÁT — đối chiếu khung phát cho sinh viên với Golden của bộ đề.
 *
 * <p>Vì sao cần một khâu riêng: quy trình thật là làm Golden trước, xong mới bóc ra thành
 * khung phát. Khung là bản phái sinh, và trong lúc ghi hình người ra đề còn sửa Golden nhiều
 * lần, nên khung rất dễ tụt lại phía sau. Không khâu nào khác trong hệ thống nhìn thấy chuyện
 * đó: máy chấm chỉ mount lib của bài nộp, còn khung phát thì không phải artifact của bộ chấm.
 *
 * <p>Bốn thứ được kiểm, vì lệch cái nào cũng làm hỏng CẢ LỚP chứ không riêng một tiêu chí:
 * <ul>
 *   <li>Cấu trúc bảng database — sinh viên xây trên một schema, máy chấm đọc một schema khác.</li>
 *   <li>Danh sách định danh — lệch một chuỗi là mọi bước tìm theo định danh trượt.</li>
 *   <li>Tên file trong assets — tiêu chí ảnh so đúng tên asset.</li>
 *   <li>Danh sách package — khung cho phép gói mà ảnh chấm không có thì bài nộp không biên dịch.</li>
 * </ul>
 *
 * <p>Vì sao so MÃ NGUỒN chứ không chạy thật: khung phát chỉ là bộ khung, ứng dụng của nó không
 * mở database (màn hình còn là chỗ trống), nên chạy lên cũng không sinh ra bảng nào để đọc.
 * Thứ duy nhất có thật ở cả hai bên là mã nguồn, nên đối chiếu ngay trên mã, sau khi chuẩn hoá
 * khoảng trắng và chữ hoa chữ thường để một dấu cách không thành lỗi.
 */
@Service
public class StarterSyncService {
    private static final Logger log = LoggerFactory.getLogger(StarterSyncService.class);
    private static final int MAX_ZIP_ENTRIES = 20_000;
    private static final long MAX_UNCOMPRESSED_BYTES = 300L * 1024 * 1024;

    /** Trạng thái của một đề trước cửa chấm. */
    public static final String MIEN_TRU = "EXEMPT";
    public static final String DAT = "OK";
    public static final String CHUA_KIEM = "PENDING";
    public static final String HET_HAN = "STALE";

    private final ExamRepository exams;
    private final BehaviorSuiteRepository suites;
    private final BehaviorArtifactService artifacts;

    public StarterSyncService(ExamRepository exams,
                              BehaviorSuiteRepository suites,
                              BehaviorArtifactService artifacts) {
        this.exams = exams;
        this.suites = suites;
        this.artifacts = artifacts;
    }

    // ==================== TRẠNG THÁI ====================

    /**
     * Đề có được phép hiện ở phần chấm không.
     *
     * <p>MIEN_TRU: bộ đề publish từ trước khi có khâu này — không chặn, để bộ đang chạy không
     * đột ngột biến mất. DAT: đã kiểm và Golden vẫn là bản đã kiểm. HET_HAN: đã kiểm nhưng
     * Golden đổi sau đó, nên kết quả cũ không còn nói lên điều gì. CHUA_KIEM: chưa kiểm lần nào.
     */
    public String trangThai(String examId) {
        Optional<Exam> tim = exams.findByExamId(examId);
        if (tim.isEmpty()) return MIEN_TRU;
        Exam exam = tim.get();
        if (!Boolean.TRUE.equals(exam.getStarterCheckRequired())) return MIEN_TRU;
        String daKiem = exam.getStarterCheckedGoldenSha();
        if (daKiem == null || daKiem.isBlank()) return CHUA_KIEM;
        String hienTai = shaGoldenHienTai(examId);
        if (hienTai == null) return CHUA_KIEM;
        return daKiem.equals(hienTai) ? DAT : HET_HAN;
    }

    public boolean chamDuoc(String examId) {
        String t = trangThai(examId);
        return MIEN_TRU.equals(t) || DAT.equals(t);
    }

    public Map<String, Object> tomTat(String examId) {
        Optional<Exam> tim = exams.findByExamId(examId);
        Map<String, Object> ra = new LinkedHashMap<>();
        ra.put("exam_id", examId);
        ra.put("state", trangThai(examId));
        ra.put("checked_at", tim.map(Exam::getStarterCheckedAt).orElse(null));
        return ra;
    }

    /**
     * Vân tay của Golden đang gắn với đề.
     *
     * <p>Bản giảng viên đọc thẳng từ artifact của bộ chấm. Bản người chấm KHÔNG có bảng Golden —
     * đề sang đó bằng gói bàn giao — nên nếu chỉ tra bảng thì mọi đề đều rơi về "chưa kiểm" và
     * bị cổng chặn khoá vĩnh viễn, bằng một câu bảo họ vào màn soạn đề mà bản của họ không có.
     * Vì vậy khi không có dữ liệu Golden thì đọc vân tay đã ghi trong tờ khai của gói. Bên đó
     * Golden không đổi được nữa, nên vân tay trong gói chính là vân tay hiện tại.
     */
    private String shaGoldenHienTai(String examId) {
        for (BehaviorSuite suite : suites.findByExamIdOrderByUpdatedAtDesc(examId)) {
            Optional<BehaviorArtifact> golden =
                    artifacts.activeOptional(suite.getId(), BehaviorArtifactType.GOLDEN_SOLUTION);
            if (golden.isPresent()) return golden.get().getSha256();
        }
        return shaGoldenTheoToKhai(examId);
    }

    /** Đọc starter_check.golden_sha trong tờ khai bàn giao nằm cùng thư mục testcase. */
    private String shaGoldenTheoToKhai(String examId) {
        try {
            Optional<Exam> tim = exams.findByExamId(examId);
            if (tim.isEmpty()) return null;
            String duong = tim.get().getTestcasePath();
            if (duong == null || duong.isBlank()) return null;
            Path toKhai = Path.of(duong).resolve(BanGiaoService.TEN_META);
            if (!Files.isRegularFile(toKhai)) return null;
            Matcher m = SHA_TRONG_TO_KHAI.matcher(Files.readString(toKhai, StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            log.warn("Không đọc được tờ khai bàn giao của {}: {}", examId, e.getMessage());
            return null;
        }
    }

    /** Bóc đúng một trường thay vì kéo cả bộ phân tích JSON vào lớp này. */
    private static final Pattern SHA_TRONG_TO_KHAI =
            Pattern.compile("\"golden_sha\"\\s*:\\s*\"([^\"]+)\"");

    // ==================== KIỂM ====================

    public Map<String, Object> kiem(String examId, MultipartFile khungZip) throws Exception {
        if (khungZip == null || khungZip.isEmpty()) {
            throw new IllegalArgumentException("Chưa chọn gói khung phát cho sinh viên (.zip)");
        }
        Exam exam = exams.findByExamId(examId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đề " + examId));
        BehaviorSuite suite = suites.findByExamIdOrderByUpdatedAtDesc(examId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Đề " + examId + " chưa gắn với bộ chấm nào nên không có Golden để đối chiếu."));
        BehaviorArtifact goldenArtifact = artifacts
                .activeOptional(suite.getId(), BehaviorArtifactType.GOLDEN_SOLUTION)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Bộ chấm của đề chưa có Golden Solution."));

        Path lamViec = Files.createTempDirectory("kiem-dong-bo-");
        try {
            Path goldenGoc = lamViec.resolve("golden");
            SecureZipExtractor.extract(Path.of(goldenArtifact.getStoragePath()), goldenGoc,
                    MAX_ZIP_ENTRIES, MAX_UNCOMPRESSED_BYTES);
            Path khungNen = lamViec.resolve("khung.zip");
            khungZip.transferTo(khungNen.toFile());
            Path khungGoc = lamViec.resolve("khung");
            SecureZipExtractor.extract(khungNen, khungGoc, MAX_ZIP_ENTRIES, MAX_UNCOMPRESSED_BYTES);

            Path golden = duAnFlutter(goldenGoc, "Golden");
            Path khung = duAnFlutter(khungGoc, "Khung phát");

            List<Map<String, Object>> phepKiem = new ArrayList<>();
            phepKiem.add(soSanh("Cấu trúc bảng database",
                    moTaBang(golden), moTaBang(khung),
                    "Sinh viên sẽ xây trên một cấu trúc, còn máy chấm đọc một cấu trúc khác."));
            phepKiem.add(soSanh("Danh sách định danh",
                    dinhDanh(golden), dinhDanh(khung),
                    "Lệch một chuỗi là mọi bước tìm theo định danh đều trượt."));
            phepKiem.add(soSanh("Tên file trong assets",
                    tenAsset(golden), tenAsset(khung),
                    "Tiêu chí ảnh so đúng tên asset."));
            phepKiem.add(soSanh("Danh sách package",
                    goiPhuThuoc(golden), goiPhuThuoc(khung),
                    "Khung cho phép gói mà ảnh chấm không có thì bài nộp không biên dịch được."));

            boolean dat = phepKiem.stream().allMatch(p -> Boolean.TRUE.equals(p.get("passed")));
            if (dat) {
                exam.setStarterCheckedGoldenSha(goldenArtifact.getSha256());
                exam.setStarterCheckedAt(Instant.now());
            } else {
                // Trượt thì XOÁ dấu cũ: đề quay về trạng thái chưa kiểm chứ không giữ con dấu
                // của lần trước rồi vẫn cho chấm.
                exam.setStarterCheckedGoldenSha(null);
                exam.setStarterCheckedAt(null);
            }
            exams.save(exam);

            Map<String, Object> ra = new LinkedHashMap<>();
            ra.put("exam_id", examId);
            ra.put("suite_id", suite.getId());
            ra.put("golden_sha256", goldenArtifact.getSha256());
            ra.put("passed", dat);
            ra.put("state", trangThai(examId));
            ra.put("checks", phepKiem);
            ra.put("checked_at", exam.getStarterCheckedAt());
            return ra;
        } finally {
            xoaSach(lamViec);
        }
    }

    private Map<String, Object> soSanh(String ten, List<String> golden, List<String> khung, String haiQua) {
        Map<String, Object> ra = new LinkedHashMap<>();
        ra.put("name", ten);
        boolean dat = golden.equals(khung);
        ra.put("passed", dat);
        if (dat) {
            ra.put("detail", golden.isEmpty() ? "Cả hai bên đều không khai gì." : String.join(" · ", golden));
            return ra;
        }
        List<String> chiGolden = new ArrayList<>(golden);
        chiGolden.removeAll(khung);
        List<String> chiKhung = new ArrayList<>(khung);
        chiKhung.removeAll(golden);
        StringBuilder sb = new StringBuilder();
        if (!chiGolden.isEmpty()) sb.append("Có ở Golden mà khung thiếu: ").append(String.join(" · ", chiGolden));
        if (!chiKhung.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("Có ở khung mà Golden không có: ").append(String.join(" · ", chiKhung));
        }
        if (sb.length() == 0) sb.append("Cùng nội dung nhưng khác thứ tự.");
        sb.append("\n").append(haiQua);
        ra.put("detail", sb.toString());
        return ra;
    }

    // ==================== BÓC DỮ LIỆU TỪ MÃ NGUỒN ====================

    private static final Pattern MO_BANG = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[\"'`\\[]?([A-Za-z_][A-Za-z0-9_]*)[\"'`\\]]?\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    /** Mỗi bảng một dòng "ten(cot1 kieu, cot2 kieu...)", đã chuẩn hoá, sắp theo tên bảng. */
    List<String> moTaBang(Path duAn) throws Exception {
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

    /** Cắt phần trong cặp ngoặc bắt đầu tại vị trí `mo`, đếm ngoặc lồng nhau. */
    private String thanTrongNgoac(String ma, int mo) {
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
    private String chuanHoaCot(String than) {
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

    private static final Pattern CHUOI_DART = Pattern.compile("'([^'\\n]*)'|\"([^\"\\n]*)\"");

    /** Mọi chuỗi hằng trong file định danh — đó mới là thứ máy chấm tra, không phải chú thích. */
    List<String> dinhDanh(Path duAn) throws Exception {
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

    List<String> tenAsset(Path duAn) throws Exception {
        Path thuMuc = duAn.resolve("assets");
        if (!Files.isDirectory(thuMuc)) return List.of();
        try (Stream<Path> tep = Files.walk(thuMuc)) {
            List<String> ra = new ArrayList<>();
            for (Path p : tep.filter(Files::isRegularFile).toList()) {
                String ten = thuMuc.relativize(p).toString().replace('\\', '/');
                if (ten.startsWith(".")) continue;
                ra.add(ten);
            }
            ra.sort(Comparator.naturalOrder());
            return ra;
        }
    }

    /** Tên các package khai ở khối dependencies của pubspec (bỏ qua dev_dependencies). */
    List<String> goiPhuThuoc(Path duAn) throws Exception {
        Path pubspec = duAn.resolve("pubspec.yaml");
        if (!Files.isRegularFile(pubspec)) return List.of();
        List<String> ra = new ArrayList<>();
        boolean trongKhoi = false;
        Pattern ten = Pattern.compile("^ {2}([A-Za-z0-9_]+)\\s*:");
        for (String tho : Files.readAllLines(pubspec, StandardCharsets.UTF_8)) {
            String dong = tho.replace("\t", "  ");
            if (dong.isBlank() || dong.trim().startsWith("#")) continue;
            if (!dong.startsWith(" ")) {
                trongKhoi = dong.split(":")[0].trim().equals("dependencies");
                continue;
            }
            Matcher m = ten.matcher(dong);
            if (trongKhoi && m.find()) ra.add(m.group(1));
        }
        ra.sort(Comparator.naturalOrder());
        return ra;
    }

    /**
     * Bỏ chú thích khỏi mã Dart, giữ nguyên chuỗi hằng.
     *
     * Vì sao cần: file định danh có những dòng mô tả kiểu {@code /// Nút "Cancel"}, và chuỗi
     * trong đó không phải định danh. Nhặt cả chúng thì sửa một câu chú thích cũng thành lệch,
     * còn người ra đề thì không hiểu vì sao. Phải đi qua từng ký tự chứ không thay bằng biểu
     * thức chính quy, vì dấu {@code //} nằm trong một chuỗi hằng là chuỗi chứ không phải chú thích.
     */
    private static final char NHAY_DON = (char) 39;
    private static final char NHAY_KEP = (char) 34;
    private static final char GACH_NGUOC = (char) 92;
    private static final char XUONG_DONG = (char) 10;

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

    private List<Path> cacTepDart(Path duAn) throws Exception {
        Path lib = duAn.resolve("lib");
        if (!Files.isDirectory(lib)) return List.of();
        try (Stream<Path> tep = Files.walk(lib)) {
            return tep.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".dart"))
                    .sorted()
                    .toList();
        }
    }

    private Path duAnFlutter(Path daBung, String nhan) throws Exception {
        try (Stream<Path> tep = Files.walk(daBung, 8)) {
            return tep.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("main.dart"))
                    .filter(p -> p.getParent() != null
                            && p.getParent().getFileName().toString().equals("lib"))
                    .findFirst()
                    .map(p -> p.getParent().getParent())
                    .orElseThrow(() -> new IllegalArgumentException(
                            nhan + " không phải dự án Flutter: không thấy lib/main.dart"));
        }
    }

    private void xoaSach(Path goc) {
        try (Stream<Path> tep = Files.walk(goc)) {
            tep.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // Thư mục tạm, dọn được tới đâu hay tới đó.
                }
            });
        } catch (Exception e) {
            log.warn("Không dọn được thư mục tạm {}: {}", goc, e.getMessage());
        }
    }
}
