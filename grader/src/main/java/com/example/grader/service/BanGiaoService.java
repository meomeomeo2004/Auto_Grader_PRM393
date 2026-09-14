package com.example.grader.service;

import com.example.grader.entity.Exam;
import com.example.grader.repository.ExamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * BÀN GIAO BỘ CHẤM giữa hai bản của hệ thống.
 *
 * <p>Bản giảng viên xuất một gói .zip, bản người chấm nạp gói đó vào. Ruột gói chính là thư mục
 * testcase đã xuất bản — đặt ở GỐC zip, đúng hình dạng mà đường nạp testcase sẵn có vẫn nhận —
 * cộng thêm một tờ khai {@value #TEN_META}. Nhờ vậy toàn bộ khâu kiểm tra và chuẩn hóa cũ được
 * dùng lại nguyên vẹn, không có đường nạp thứ hai nào lỏng lẻo hơn.
 *
 * <p>Tờ khai nằm chung thư mục với behavior_plan.json, contract.json… nên nó theo bộ chấm suốt
 * đời, đọc lại lúc nào cũng được. Danh sách package KHÔNG chép vào tờ khai: contract.json trong
 * gói đã khai rồi, hai chỗ cùng nói một điều là sớm muộn cũng lệch nhau.
 *
 * <p>Điểm cần nhớ nhất: DẤU KIỂM ĐỒNG BỘ KHUNG PHÁT. Bên người chấm không có bảng Golden nên
 * không thể tự kiểm lại — cổng chặn ở đường chấm bài sẽ khóa mọi đề nếu không có đường truyền
 * dấu này sang. Vì thế dấu được ghi vào tờ khai lúc xuất, và {@link StarterSyncService} đọc tờ
 * khai khi máy không có dữ liệu Golden.
 */
@Service
public class BanGiaoService {

    private static final Logger log = LoggerFactory.getLogger(BanGiaoService.class);

    /** Tên tờ khai bàn giao, nằm ở gốc gói và ở lại trong thư mục testcase sau khi nạp. */
    public static final String TEN_META = "exam_meta.json";

    /** Gói bàn giao có cả ảnh màn hình và database mẫu nên nặng hơn zip testcase thường. */
    private static final long CHAN_TREN_BYTE = 200L * 1024 * 1024;

    private final ObjectMapper mapper = new ObjectMapper();

    private final ExamRepository examRepository;
    private final ExamService examService;
    private final StarterSyncService starterSyncService;
    private final String baseImage;

    // Nhận qua hàm dựng để test được mà không cần cả Spring lẫn Docker.
    public BanGiaoService(ExamRepository examRepository,
                          ExamService examService,
                          StarterSyncService starterSyncService,
                          @Value("${grader.base-image:grading-base:latest}") String baseImage) {
        this.examRepository = examRepository;
        this.examService = examService;
        this.starterSyncService = starterSyncService;
        this.baseImage = baseImage;
    }

    // ==================== XUẤT (bản giảng viên) ====================

    /**
     * Nén bộ chấm của một đề thành gói bàn giao.
     *
     * <p>Chặn ngay tại đây nếu đề chưa qua kiểm đồng bộ khung phát. Đây là nơi DUY NHẤT còn đủ
     * dữ kiện để phán: bên người chấm không có Golden nên sang tới đó thì chỉ biết tin vào tờ
     * khai. Chặn ở đây cũng là chặn đúng người — người sửa được là giảng viên.
     */
    public byte[] xuatGoi(String examId) throws Exception {
        Exam exam = examRepository.findByExamId(examId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bộ chấm: " + examId));

        String trangThaiKiem = starterSyncService.trangThai(examId);
        if (!starterSyncService.chamDuoc(examId)) {
            throw new IllegalStateException(
                    "Đề này chưa qua kiểm đồng bộ khung phát (" + trangThaiKiem + ") nên chưa giao được."
                            + " Nạp gói khung phát cho sinh viên để đối chiếu với Golden, đạt rồi hãy xuất."
                            + " Xuất lúc này thì bên người chấm cũng không chấm được.");
        }

        Path testcase = thuMucTestcase(exam);
        Map<String, Object> meta = dungToKhai(exam, trangThaiKiem, testcase);

        ByteArrayOutputStream ra = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(ra, StandardCharsets.UTF_8)) {
            try (var duyet = Files.walk(testcase)) {
                List<Path> tep = duyet.filter(Files::isRegularFile).sorted().toList();
                for (Path p : tep) {
                    String ten = testcase.relativize(p).toString().replace('\\', '/');
                    // Tờ khai của lần xuất TRƯỚC (nếu bộ này từng được nạp từ gói khác) phải bỏ đi,
                    // nếu không gói mới sẽ mang hai tờ khai và bên kia đọc nhầm tờ cũ.
                    if (ten.equals(TEN_META)) continue;
                    zip.putNextEntry(new ZipEntry(ten));
                    Files.copy(p, zip);
                    zip.closeEntry();
                }
            }
            zip.putNextEntry(new ZipEntry(TEN_META));
            zip.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(meta));
            zip.closeEntry();
        }
        log.info("Xuất gói bàn giao {} ({} byte)", examId, ra.size());
        return ra.toByteArray();
    }

    /** Tên file gợi ý cho trình duyệt khi tải gói về. */
    public String tenTepGoi(String examId) {
        return "bo-cham-" + examId + ".zip";
    }

    private Path thuMucTestcase(Exam exam) {
        String duong = exam.getTestcasePath();
        if (duong == null || duong.isBlank())
            throw new IllegalStateException("Bộ chấm " + exam.getExamId()
                    + " chưa xuất bản testcase nên chưa có gì để giao.");
        Path p = Path.of(duong);
        if (!Files.isDirectory(p))
            throw new IllegalStateException("Không tìm thấy thư mục testcase của "
                    + exam.getExamId() + ": " + duong);
        return p;
    }

    private Map<String, Object> dungToKhai(Exam exam, String trangThaiKiem, Path testcase) {
        Map<String, Object> dauKiem = new LinkedHashMap<>();
        dauKiem.put("state", trangThaiKiem);
        dauKiem.put("golden_sha", exam.getStarterCheckedGoldenSha());
        dauKiem.put("checked_at", exam.getStarterCheckedAt() == null
                ? null : exam.getStarterCheckedAt().toString());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("schema_version", 1);
        meta.put("exam_id", exam.getExamId());
        meta.put("exam_name", exam.getExamName());
        meta.put("teacher_note", exam.getTeacherNote());
        // Ảnh nền quyết định bài sinh viên biên dịch được hay không. Ghi lại nhãn để bên nhận
        // đối chiếu; hai máy khác ảnh là cùng một bài cho hai kết quả khác nhau.
        meta.put("base_image", baseImage);
        // Vân tay của engine chấm nằm trong gói — để khi điểm hai bên lệch nhau còn biết có phải
        // do khác engine hay không, thay vì đoán.
        meta.put("engine_sha256", vanTayEngine(testcase));
        meta.put("starter_check", dauKiem);
        meta.put("exported_at", Instant.now().toString());
        return meta;
    }

    private String vanTayEngine(Path testcase) {
        Path engine = testcase.resolve("exam_test.dart");
        try {
            if (!Files.isRegularFile(engine)) return null;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bam = md.digest(Files.readAllBytes(engine));
            StringBuilder sb = new StringBuilder(bam.length * 2);
            for (byte b : bam) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                                 .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            log.warn("Không tính được vân tay engine: {}", e.getMessage());
            return null;
        }
    }

    // ==================== NẠP (bản người chấm) ====================

    /**
     * Nạp gói bàn giao: dựng lại đề y như bên giảng viên rồi chép dấu kiểm đồng bộ sang.
     *
     * @return tóm tắt để giao diện nói được đã nhận đề nào và còn thiếu package gì.
     */
    public synchronized Map<String, Object> nhapGoi(byte[] zipBytes) throws Exception {
        if (zipBytes == null || zipBytes.length == 0)
            throw new IllegalArgumentException("Chưa chọn gói bàn giao (.zip).");
        if (zipBytes.length > CHAN_TREN_BYTE)
            throw new IllegalArgumentException("Gói bàn giao vượt quá giới hạn 200 MB.");

        Map<String, Object> meta = docToKhai(zipBytes);
        if (meta == null)
            throw new IllegalArgumentException("Gói này không có " + TEN_META
                    + " nên không phải gói bàn giao. Hãy dùng nút Xuất bộ chấm ở bản giảng viên.");

        String examId = chuoi(meta.get("exam_id"));
        if (examId.isBlank())
            throw new IllegalArgumentException("Tờ khai trong gói không ghi mã đề.");

        // Dùng lại đúng đường nạp testcase cũ: giải nén, kiểm tra đủ file, chuẩn hóa tên test,
        // ghi bản ghi đề và chuẩn bị sandbox. Không viết lại khâu nào.
        examService.setupExamFromZipBytes(examId, chuoi(meta.get("exam_name")),
                chuoi(meta.get("teacher_note")), zipBytes);

        Exam exam = examRepository.findByExamId(examId)
                .orElseThrow(() -> new IllegalStateException("Nạp xong nhưng không thấy bản ghi đề " + examId));
        chepDauKiem(exam, meta);
        examRepository.save(exam);

        Map<String, Object> ra = new LinkedHashMap<>();
        ra.put("exam_id", examId);
        ra.put("exam_name", exam.getExamName());
        ra.put("base_image_cua_goi", meta.get("base_image"));
        ra.put("base_image_may_nay", baseImage);
        ra.put("starter_check", meta.get("starter_check"));
        ra.put("cham_duoc", starterSyncService.chamDuoc(examId));
        ra.put("goi_thieu", examService.goiConThieuCuaDe(examId));
        return ra;
    }

    /**
     * Chép dấu kiểm đồng bộ từ tờ khai sang bản ghi đề.
     *
     * <p>EXEMPT là bộ publish từ trước khi có khâu kiểm — giữ nguyên diện miễn trừ, không tự
     * dựng thêm rào cho bộ đang chạy. Còn lại thì giữ nguyên yêu cầu kiểm và ghi lại vân tay
     * Golden đã kiểm; bên này không có Golden để đổi nên dấu đó là bất biến.
     */
    private void chepDauKiem(Exam exam, Map<String, Object> meta) {
        Map<?, ?> dau = meta.get("starter_check") instanceof Map<?, ?> m ? m : Map.of();
        String state = chuoi(dau.get("state"));
        if (StarterSyncService.MIEN_TRU.equals(state)) {
            exam.setStarterCheckRequired(false);
            return;
        }
        exam.setStarterCheckRequired(true);
        exam.setStarterCheckedGoldenSha(chuoi(dau.get("golden_sha")));
        String luc = chuoi(dau.get("checked_at"));
        if (!luc.isBlank()) {
            try { exam.setStarterCheckedAt(Instant.parse(luc)); }
            catch (Exception ignored) { /* mốc thời gian hỏng không đáng chặn cả lần nạp */ }
        }
    }

    /** Đọc tờ khai từ trong zip mà không giải nén cả gói ra đĩa. */
    private Map<String, Object> docToKhai(byte[] zipBytes) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String ten = e.getName().replace('\\', '/');
                if (!ten.equals(TEN_META)) continue;
                byte[] noiDung = zin.readNBytes(1024 * 1024);   // tờ khai không thể to hơn thế
                return mapper.readValue(noiDung, Map.class);
            }
        }
        return null;
    }

    private static String chuoi(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
