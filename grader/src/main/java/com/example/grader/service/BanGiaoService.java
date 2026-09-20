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
import java.util.Set;
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
 * <p>Cùng lúc với gói này, bản giảng viên tải luôn KHUNG PHÁT dựng từ chính Golden đang xuất
 * (xem {@code ExamService#zipKhungPhat}). Hai thứ ra từ một bản Golden trong một thao tác nên
 * không còn cửa sổ nào để chúng trôi khỏi nhau — đó là lý do màn "Kiểm đồng bộ khung phát" cũ
 * đã bỏ: nó so khung với Golden, mà khung nay chính là đầu ra của Golden.
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
    private final String baseImage;

    // Nhận qua hàm dựng để test được mà không cần cả Spring lẫn Docker.
    public BanGiaoService(ExamRepository examRepository,
                          ExamService examService,
                          @Value("${grader.base-image:grading-base:latest}") String baseImage) {
        this.examRepository = examRepository;
        this.examService = examService;
        this.baseImage = baseImage;
    }

    // ==================== XUẤT (bản giảng viên) ====================

    /**
     * Nén bộ chấm của một đề thành gói bàn giao.
     *
     * <p>Đóng dấu vân tay khung phát vào bản ghi đề: lần xuất sau còn biết Golden đã đổi ở đúng
     * những chỗ khung lấy về hay chưa, để nhắc giảng viên phát lại khung cho sinh viên.
     */
    public byte[] xuatGoi(String examId) throws Exception {
        Exam exam = examRepository.findByExamId(examId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bộ chấm: " + examId));

        String vanTay = examService.vanTayKhungPhat(examId);
        exam.setKhungVanTay(vanTay);
        examRepository.save(exam);

        Path testcase = thuMucTestcase(exam);
        Map<String, Object> meta = dungToKhai(exam, vanTay, testcase);

        ByteArrayOutputStream ra = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(ra, StandardCharsets.UTF_8)) {
            try (var duyet = Files.walk(testcase)) {
                List<Path> tep = duyet.filter(Files::isRegularFile).sorted().toList();
                for (Path p : tep) {
                    String ten = testcase.relativize(p).toString().replace('\\', '/');
                    // Tờ khai của lần xuất TRƯỚC (nếu bộ này từng được nạp từ gói khác) phải bỏ đi,
                    // nếu không gói mới sẽ mang hai tờ khai và bên kia đọc nhầm tờ cũ.
                    if (ten.equals(TEN_META)) continue;
                    if (ten.equals("contract.json")) {
                        zip.putNextEntry(new ZipEntry(ten));
                        zip.write(hopDongKemPhienBan(examId, Files.readString(p, StandardCharsets.UTF_8))
                                .getBytes(StandardCharsets.UTF_8));
                        zip.closeEntry();
                        continue;
                    }
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

    /**
     * Bù {@code allowed_package_specs} vào hợp đồng nếu nó còn thiếu, lấy từ dependencies của
     * Golden đang dùng.
     *
     * <p>Vì sao phải bù ở đây: khoá này chỉ có từ 19/9, còn bộ chấm publish trước đó chỉ ghi
     * TÊN gói. Bên người chấm thiếu gói thì phải thêm vào ảnh, mà version nay là bắt buộc —
     * không có ràng buộc thì họ phải tự đoán, mà đoán sai chính là thứ luật bắt buộc kia sinh
     * ra để chặn. Bù lúc xuất thì bộ cũ dùng được ngay, không bắt giảng viên publish lại.
     *
     * <p>Chỉ sửa BẢN TRONG GÓI, không ghi đè contract.json trên đĩa: thư mục testcase đã xuất
     * bản là bản gốc, lần publish sau sẽ tự sinh lại đầy đủ.
     */
    private String hopDongKemPhienBan(String examId, String goc) {
        try {
            Map<String, Object> hopDong = mapper.readValue(goc, LinkedHashMap.class);
            Object daCo = hopDong.get("allowed_package_specs");
            if (daCo instanceof Map<?, ?> m && !m.isEmpty()) return goc;
            Map<String, Object> deps = examService.starterDependencies(examId);
            if (deps.isEmpty()) return goc;
            Map<String, String> spec = new LinkedHashMap<>();
            deps.forEach((ten, v) -> spec.put(ten, v instanceof String s ? s : ""));
            hopDong.put("allowed_package_specs", spec);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(hopDong);
        } catch (Exception e) {
            // Không đọc được Golden (bộ cũ, artifact đã dọn) thì giao hợp đồng nguyên trạng —
            // thiếu version còn hơn hỏng cả gói bàn giao.
            log.warn("Không bù được ràng buộc phiên bản vào contract.json của {}: {}", examId, e.toString());
            return goc;
        }
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

    private Map<String, Object> dungToKhai(Exam exam, String vanTayKhung, Path testcase) {
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
        // Vân tay của khung phát đi kèm lần xuất này — bên người chấm không dùng để chặn gì,
        // nhưng khi hai bên nghi ngờ nhau thì đây là con số đối chiếu được.
        meta.put("khung_van_tay", vanTayKhung);
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
     * Nạp gói bàn giao: dựng lại đề y như bên giảng viên.
     *
     * <p>CHẶN TRƯỚC KHI DỰNG nếu ảnh chấm trên máy này thiếu package mà hợp đồng đòi. Trước
     * 19/9 chỗ này chỉ cảnh báo rồi vẫn dựng: bộ chấm hiện ngay trong danh sách, chọn đi chấm
     * được, mà mọi bài sẽ không biên dịch nổi — còn lời cảnh báo thì mất ngay khi đổi màn. Một
     * bộ không chấm được thì đừng để nó tồn tại trông như chấm được.
     *
     * <p>Đọc hợp đồng THẲNG TRONG ZIP chứ không dựng rồi kiểm rồi xoá: dựng lên là đã ghi bản
     * ghi đề và đổ file ra đĩa, xoá ngược lại luôn có đường hụt.
     *
     * @return tóm tắt để giao diện nói được đã nhận đề nào.
     * @throws PackageAvailabilityException ảnh chấm thiếu package — kèm tên và ràng buộc phiên
     *         bản để màn Thư viện chấm mở sẵn đúng những gói đó.
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

        chanNeuAnhChamThieuGoi(zipBytes);

        // Dùng lại đúng đường nạp testcase cũ: giải nén, kiểm tra đủ file, chuẩn hóa tên test,
        // ghi bản ghi đề và chuẩn bị sandbox. Không viết lại khâu nào.
        examService.setupExamFromZipBytes(examId, chuoi(meta.get("exam_name")),
                chuoi(meta.get("teacher_note")), zipBytes);

        Exam exam = examRepository.findByExamId(examId)
                .orElseThrow(() -> new IllegalStateException("Nạp xong nhưng không thấy bản ghi đề " + examId));

        Map<String, Object> ra = new LinkedHashMap<>();
        ra.put("exam_id", examId);
        ra.put("exam_name", exam.getExamName());
        ra.put("base_image_cua_goi", meta.get("base_image"));
        ra.put("base_image_may_nay", baseImage);
        ra.put("khung_van_tay", meta.get("khung_van_tay"));
        return ra;
    }

    /**
     * Ảnh chấm máy này thiếu package đề đòi thì từ chối cả gói.
     *
     * <p>Không đọc được ảnh chấm ({@code goiCoTrongAnhCham} trả tập rỗng) thì CHO QUA: "không
     * biết" phải khác "ảnh không có gì", nếu không thì Docker chưa chạy là chặn oan mọi gói.
     */
    private void chanNeuAnhChamThieuGoi(byte[] zipBytes) throws IOException {
        Set<String> coSan = examService.goiCoTrongAnhCham();
        if (coSan.isEmpty()) return;
        String contract = docTepTrongZip(zipBytes, "contract.json");
        if (contract == null) return;
        Map<String, String> deCan;
        try {
            deCan = ExamService.goiDeCanTrongHopDong(contract);
        } catch (Exception e) {
            log.warn("Gói bàn giao có contract.json không đọc được, bỏ qua phép kiểm package: {}", e.toString());
            return;
        }
        Map<String, Object> thieu = new LinkedHashMap<>();
        deCan.forEach((ten, rangBuoc) -> {
            if (!coSan.contains(ten)) thieu.put(ten, rangBuoc);
        });
        if (!thieu.isEmpty()) throw new PackageAvailabilityException(thieu,
                "Ảnh chấm máy này chưa có package mà bộ chấm đòi: " + String.join(", ", thieu.keySet())
                        + ". Gói CHƯA được nhận. Sang Thư viện chấm thêm đúng những gói đó, dựng lại"
                        + " ảnh chấm, rồi nạp lại gói này.");
    }

    /** Đọc tờ khai từ trong zip mà không giải nén cả gói ra đĩa. */
    private Map<String, Object> docToKhai(byte[] zipBytes) throws IOException {
        String noiDung = docTepTrongZip(zipBytes, TEN_META);
        return noiDung == null ? null : mapper.readValue(noiDung, Map.class);
    }

    /** Một file văn bản ở GỐC gói, đọc thẳng từ luồng zip. null = gói không có file đó. */
    private String docTepTrongZip(byte[] zipBytes, String tenCanTim) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String ten = e.getName().replace('\\', '/');
                if (!ten.equals(tenCanTim)) continue;
                // Chặn trên 1 MB: cả tờ khai lẫn hợp đồng đều là JSON nhỏ. Gói lạ khai một file
                // cùng tên mà khổng lồ thì đây là chỗ nó nuốt hết bộ nhớ.
                return new String(zin.readNBytes(1024 * 1024), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String chuoi(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
