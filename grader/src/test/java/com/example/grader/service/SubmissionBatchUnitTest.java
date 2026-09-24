package com.example.grader.service;

import com.example.grader.dto.BatchSubmitResponse;
import com.example.grader.entity.BatchStatus;
import com.example.grader.entity.Exam;
import com.example.grader.entity.ExamResult;
import com.example.grader.entity.ExamStatus;
import com.example.grader.entity.GradingBatch;
import com.example.grader.entity.GradingStatus;
import com.example.grader.repository.ExamRepository;
import com.example.grader.repository.ExamResultRepository;
import com.example.grader.repository.GradingBatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sheet UT19 + UT20 — BatchGradingService.enqueueBatch / addToBatch.
 *
 * <p>Cửa nhận bài của người chấm. Hai tính chất phải giữ bằng mọi giá:
 *
 * <ul>
 *   <li>MỘT bài hỏng không được làm hỏng cả lô. Giáo viên tải cả thư mục lớp từ LMS về; một
 *       thư mục đặt sai tên mà làm cả đợt bị từ chối là họ phải ngồi dò tay.</li>
 *   <li>{@code totalFiles} phải là SỐ BÀI THỰC SỰ vào chấm. Đếm cả file bị loại thì phiên chấm
 *       không bao giờ đóng được và màn tiến độ treo ở 9/10 mãi mãi.</li>
 * </ul>
 *
 * <p>Worker không chạy trong bộ kiểm này (không gọi {@code startWorkers}), nên job chỉ nằm lại
 * trong hàng đợi — đúng thứ cần quan sát mà không đụng tới Docker.
 */
class SubmissionBatchUnitTest {

    @TempDir Path thuMuc;

    private static final String MA_DE = "PE_PRM393_FA26";

    private ExamRepository examRepo;
    private ExamResultRepository resultRepo;
    private GradingBatchRepository batchRepo;
    private ExamService examService;
    private GradingService gradingService;
    private BatchGradingService nhanBai;

    private Exam de;
    private final List<ExamResult> daLuu = new ArrayList<>();

    @BeforeEach
    void dungCuaNhanBai() throws Exception {
        examRepo = mock(ExamRepository.class);
        resultRepo = mock(ExamResultRepository.class);
        batchRepo = mock(GradingBatchRepository.class);
        examService = mock(ExamService.class);
        gradingService = mock(GradingService.class);

        de = new Exam();
        de.setExamId(MA_DE);
        de.setStatus(ExamStatus.READY);
        de.setTestcasePath(taoTestcase().toString());
        when(examRepo.findByExamId(MA_DE)).thenReturn(Optional.of(de));

        when(batchRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(resultRepo.save(any())).thenAnswer(i -> {
            ExamResult r = i.getArgument(0);
            daLuu.add(r);
            return r;
        });
        when(resultRepo.findByStudentIdAndExamIdAndMode(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        // batchDir() = examService.resolveSibling(submissionsDir)/<đề>/<batch>
        when(examService.resolveSibling(anyString())).thenReturn(thuMuc.resolve("submissions"));

        // Khâu nhận bài không còn phụ thuộc StarterSyncService: cổng kiểm đồng bộ khung phát đã
        // gỡ 19/9. Điều kiện nhận bài bây giờ chỉ còn trạng thái đề (xem UT20 UTCID07).
        nhanBai = new BatchGradingService();
        for (Object[] o : new Object[][]{
                {"examRepo", examRepo}, {"resultRepo", resultRepo}, {"batchRepo", batchRepo},
                {"examService", examService},
                {"gradingService", gradingService},
                {"submissionsDir", "submissions"}, {"saveSubmissions", true}}) {
            datField(nhanBai, (String) o[0], o[1]);
        }
    }

    private Path taoTestcase() throws Exception {
        Path tc = Files.createDirectories(thuMuc.resolve("exams").resolve(MA_DE).resolve("testcase"));
        Files.writeString(tc.resolve("behavior_plan.json"), "{\"cases\":[]}", StandardCharsets.UTF_8);
        Files.writeString(tc.resolve("exam_test.dart"), "void main() {}", StandardCharsets.UTF_8);
        return tc;
    }

    private static void datField(Object o, String ten, Object giaTri) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(ten);
                f.setAccessible(true);
                f.set(o, giaTri);
                return;
            } catch (NoSuchFieldException tiep) {
                // thử lớp cha
            }
        }
        throw new NoSuchFieldException(ten);
    }

    private MultipartFile zip(String ten) {
        return new MockMultipartFile("files", ten, "application/zip",
                "PK-gia-dinh-la-zip".getBytes(StandardCharsets.UTF_8));
    }

    private List<MultipartFile> baZip() {
        return List.of(zip("lib.zip"), zip("lib.zip"), zip("lib.zip"));
    }

    @SuppressWarnings("unchecked")
    private java.util.Queue<Object> hangDoi() throws Exception {
        Field f = BatchGradingService.class.getDeclaredField("jobQueue");
        f.setAccessible(true);
        return (java.util.Queue<Object>) f.get(nhanBai);
    }

    private GradingBatch batchDaLuu() {
        org.mockito.ArgumentCaptor<GradingBatch> bat =
                org.mockito.ArgumentCaptor.forClass(GradingBatch.class);
        verify(batchRepo, org.mockito.Mockito.atLeastOnce()).save(bat.capture());
        return bat.getValue();
    }

    // ══════════════════ UT19 — enqueueBatch ══════════════════

    @Test
    @DisplayName("UT19 UTCID01 — ba bài hợp lệ: vào hàng đợi đủ, testcase được chụp lại để đối chiếu")
    void ut19_utcid01_baBaiHopLe() throws Exception {
        BatchSubmitResponse ra = nhanBai.enqueueBatch(baZip(),
                List.of("HE111111", "Nguyễn Văn A (HE222222)", "PE_ca1 - he333333"),
                MA_DE, "nguoi-cham");

        assertEquals(3, ra.getTotalQueued());
        assertTrue(ra.getParseErrors().isEmpty());
        assertNotNull(ra.getBatchId());
        assertEquals(3, hangDoi().size());
        assertEquals(3, batchDaLuu().getTotalFiles());
        assertEquals("nguoi-cham", batchDaLuu().getCreatedBy());

        List<String> ma = daLuu.stream().map(ExamResult::getStudentId).toList();
        assertEquals(List.of("HE111111", "HE222222", "HE333333"), ma);
        daLuu.forEach(r -> assertEquals(GradingStatus.QUEUED, r.getStatus()));

        Path chup = thuMuc.resolve("submissions").resolve(MA_DE)
                .resolve(ra.getBatchId()).resolve("_testcase");
        assertTrue(Files.isRegularFile(chup.resolve("behavior_plan.json")),
                "phải chụp lại đúng bộ đề đã dùng, để còn tra khi nghi ngờ chấm sai");
        assertTrue(Files.isRegularFile(thuMuc.resolve("submissions").resolve(MA_DE)
                .resolve(ra.getBatchId()).resolve("HE111111.zip")), "zip phải được ghi ra đĩa");
    }

    @Test
    @DisplayName("UT19 UTCID02/03 — số file và số username phải khớp, thiếu là chặn cả lô")
    void ut19_utcid02_03_lechSoLuong() {
        assertEquals("Mỗi file .zip phải đi kèm đúng tên thư mục username.",
                assertThrows(IllegalArgumentException.class,
                        () -> nhanBai.enqueueBatch(baZip(), List.of("A", "B"), MA_DE, "x")).getMessage(),
                "UTCID02");

        assertEquals("Mỗi file .zip phải đi kèm đúng tên thư mục username.",
                assertThrows(IllegalArgumentException.class,
                        () -> nhanBai.enqueueBatch(baZip(), null, MA_DE, "x")).getMessage(),
                "UTCID03");
    }

    @Test
    @DisplayName("UT19 UTCID04 — đề không tồn tại")
    void ut19_utcid04_deKhongTonTai() {
        when(examRepo.findByExamId("KHONG_CO")).thenReturn(Optional.empty());

        assertEquals("Không tìm thấy đề thi: KHONG_CO",
                assertThrows(IllegalArgumentException.class, () -> nhanBai.enqueueBatch(
                        List.of(zip("lib.zip")), List.of("HE111111"), "KHONG_CO", "x")).getMessage());
    }

    /**
     * UT19 UTCID05 (cũ) đã bỏ: ca đó đo cổng "chưa qua kiểm đồng bộ khung phát" của
     * StarterSyncService, mà cả service lẫn cổng chặn đều bị gỡ ngày 19/9 cùng màn Kiểm đồng bộ.
     * Điều kiện nhận bài hiện tại chỉ còn trạng thái đề — xem UT19 UTCID06 và UT20 UTCID07.
     */
    @Test
    @DisplayName("UT19 UTCID06 — đề chưa READY thì thử build lại sandbox, đạt thì chấm tiếp")
    void ut19_utcid06_buildSandboxLaiRoiChamTiep() throws Exception {
        de.setStatus(ExamStatus.BUILDING);
        when(examService.buildSandbox(MA_DE)).thenAnswer(i -> {
            de.setStatus(ExamStatus.READY);       // sandbox dựng xong
            return null;
        });

        BatchSubmitResponse ra = nhanBai.enqueueBatch(
                List.of(zip("lib.zip")), List.of("HE111111"), MA_DE, "x");

        assertEquals(1, ra.getTotalQueued());
        verify(examService).buildSandbox(MA_DE);
    }

    @Test
    @DisplayName("UT19 UTCID07 — build sandbox lỗi (Docker tắt) thì báo đề chưa sẵn sàng")
    void ut19_utcid07_buildSandboxLoi() throws Exception {
        de.setStatus(ExamStatus.BUILDING);
        when(examService.buildSandbox(MA_DE)).thenThrow(new IllegalStateException("Docker chưa bật"));

        assertTrue(assertThrows(IllegalStateException.class, () -> nhanBai.enqueueBatch(
                        List.of(zip("lib.zip")), List.of("HE111111"), MA_DE, "x"))
                .getMessage().startsWith("Đề thi chưa sẵn sàng để chấm:"));
    }

    @Test
    @DisplayName("UT19 UTCID08 — một file .rar giữa lô: bỏ đúng file đó, hai bài kia vẫn chấm")
    void ut19_utcid08_motFileHongKhongLamHongCaLo() throws Exception {
        List<MultipartFile> tep = List.of(zip("lib.zip"), zip("bai_lam.rar"), zip("lib.zip"));

        BatchSubmitResponse ra = nhanBai.enqueueBatch(tep,
                List.of("HE111111", "HE222222", "HE333333"), MA_DE, "x");

        assertEquals(2, ra.getTotalQueued());
        assertEquals(1, ra.getParseErrors().size());
        assertTrue(ra.getParseErrors().get(0).startsWith("HE222222/bai_lam.rar:"),
                "lời báo phải chỉ đúng thư mục nào hỏng: " + ra.getParseErrors());
        assertEquals(2, batchDaLuu().getTotalFiles(),
                "totalFiles là SỐ BÀI THỰC SỰ vào chấm, không thì phiên không bao giờ đóng");
        assertEquals(2, hangDoi().size());
    }

    @Test
    @DisplayName("UT19 UTCID09 — hai thư mục cùng suy ra một mã SV: bài sau bị loại")
    void ut19_utcid09_trungMaSvTrongCungLanUpload() throws Exception {
        // Lần đầu lưu xong thì lần tra sau phải thấy bản ghi mang batchId của chính lô này.
        Map<String, ExamResult> kho = new LinkedHashMap<>();
        when(resultRepo.findByStudentIdAndExamIdAndMode(anyString(), anyString(), anyString()))
                .thenAnswer(i -> Optional.ofNullable(kho.get((String) i.getArgument(0))));
        when(resultRepo.save(any())).thenAnswer(i -> {
            ExamResult r = i.getArgument(0);
            kho.put(r.getStudentId(), r);
            return r;
        });

        BatchSubmitResponse ra = nhanBai.enqueueBatch(
                List.of(zip("lib.zip"), zip("lib.zip")),
                List.of("HE111111", "Nguyễn Văn B (HE111111)"), MA_DE, "x");

        assertEquals(1, ra.getTotalQueued());
        assertEquals(1, ra.getParseErrors().size());
        assertTrue(ra.getParseErrors().get(0).contains("Trùng mã SV HE111111"), ra.getParseErrors().toString());
    }

    @Test
    @DisplayName("UT19 UTCID10 — cả lô đều hỏng thì đóng phiên NGAY, không treo tiến độ")
    void ut19_utcid10_caLoDeuHong() throws Exception {
        BatchSubmitResponse ra = nhanBai.enqueueBatch(
                List.of(zip("a.rar"), zip("b.txt"), zip("c")),
                List.of("HE111111", "HE222222", "HE333333"), MA_DE, "x");

        assertEquals(0, ra.getTotalQueued());
        assertEquals(3, ra.getParseErrors().size());
        assertEquals(0, hangDoi().size());
        GradingBatch b = batchDaLuu();
        assertEquals(BatchStatus.COMPLETED, b.getStatus(),
                "không có job nào để kích hoạt checkBatchComplete — phải tự đóng tại đây");
        assertNotNull(b.getCompletedAt());
        assertEquals(0, b.getTotalFiles());
    }

    @Test
    @DisplayName("UT19 UTCID11 — createdBy null thì ghi \"unknown\", không để trống cột audit")
    void ut19_utcid11_createdByNull() throws Exception {
        nhanBai.enqueueBatch(List.of(zip("lib.zip")), List.of("HE111111"), MA_DE, null);

        assertEquals("unknown", batchDaLuu().getCreatedBy());
    }

    /** Chấm lại = GHI ĐÈ: phải tái dùng bản ghi cũ và xoá sạch dấu vết lượt chấm trước. */
    @Test
    @DisplayName("UT19 — nộp lại đè lên bản ghi cũ và reset mọi dấu vết lượt chấm trước")
    void ut19_nopLaiGhiDeBanGhiCu() throws Exception {
        ExamResult cu = new ExamResult();
        cu.setStudentId("HE111111");
        cu.setExamId(MA_DE);
        cu.setBatchId("BATCH_CU");
        cu.setScore(7.5f);
        cu.setDetails("ket qua cu");
        cu.setErrorLog("loi cu");
        cu.setDiagnosticCode("EXTERNAL_PACKAGE");
        cu.setRequiresManualReview(true);
        cu.setGradingStartedAt(java.time.Instant.parse("2026-09-01T00:00:00Z"));
        cu.setGradingFinishedAt(java.time.Instant.parse("2026-09-01T00:05:00Z"));
        when(resultRepo.findByStudentIdAndExamIdAndMode("HE111111", MA_DE, "submit"))
                .thenReturn(Optional.of(cu));

        nhanBai.enqueueBatch(List.of(zip("lib.zip")), List.of("HE111111"), MA_DE, "x");

        assertEquals(1, daLuu.size());
        assertEquals(cu, daLuu.get(0), "phải tái dùng bản ghi cũ, không tạo dòng thứ hai");
        assertNull(cu.getScore());
        assertNull(cu.getDetails());
        assertNull(cu.getErrorLog());
        assertNull(cu.getDiagnosticCode());
        assertEquals(false, cu.isRequiresManualReview());
        assertNull(cu.getGradingStartedAt(), "mốc của lượt chấm CŨ không được dính sang lượt mới");
        assertNull(cu.getGradingFinishedAt());
        assertEquals(GradingStatus.QUEUED, cu.getStatus());
    }

    // ══════════════════ UT20 — addToBatch ══════════════════

    private GradingBatch phien(String batchId, BatchStatus trangThai) {
        GradingBatch b = new GradingBatch();
        b.setBatchId(batchId);
        b.setExamId(MA_DE);
        b.setStatus(trangThai);
        b.setTotalFiles(5);
        if (trangThai == BatchStatus.COMPLETED) b.setCompletedAt(java.time.Instant.now());
        when(batchRepo.findByBatchId(batchId)).thenReturn(Optional.of(b));
        return b;
    }

    @Test
    @DisplayName("UT20 UTCID01 — nạp thêm vào phiên đang chạy: cộng dồn totalFiles")
    void ut20_utcid01_napThemVaoPhienDangChay() throws Exception {
        GradingBatch b = phien("B_RUN", BatchStatus.IN_PROGRESS);

        BatchSubmitResponse ra = nhanBai.addToBatch(
                List.of(zip("lib.zip"), zip("lib.zip")),
                List.of("HE111111", "HE222222"), "B_RUN");

        assertEquals(2, ra.getTotalQueued());
        assertEquals("B_RUN", ra.getBatchId());
        assertEquals(7, b.getTotalFiles(), "5 + 2");
        assertEquals(2, hangDoi().size());
    }

    /**
     * Phiên đã xong phải được MỞ LẠI, và cờ "đã dừng" phải gỡ. Thiếu một trong hai thì bài mới
     * nạp vào bị ghi CANCELLED ngay khi worker nhặt lên, hoặc màn tiến độ đứng im mãi mãi.
     */
    @Test
    @DisplayName("UT20 UTCID02/03 — phiên đã xong hoặc từng bị Dừng đều phải mở lại được")
    void ut20_utcid02_03_moLaiPhien() throws Exception {
        GradingBatch xong = phien("B_DONE", BatchStatus.COMPLETED);

        nhanBai.addToBatch(List.of(zip("lib.zip")), List.of("HE111111"), "B_DONE");

        assertEquals(BatchStatus.IN_PROGRESS, xong.getStatus());
        assertNull(xong.getCompletedAt(), "còn completedAt thì checkBatchComplete không đóng lại được nữa");
        verify(gradingService).clearCancelled("B_DONE");
    }

    @Test
    @DisplayName("UT20 UTCID04 — phiên đã HỦY thì không nạp thêm được")
    void ut20_utcid04_phienDaHuy() {
        phien("B_CANCEL", BatchStatus.CANCELLED);

        assertEquals("Phiên chấm đã bị hủy — hãy tạo phiên mới.",
                assertThrows(IllegalArgumentException.class, () -> nhanBai.addToBatch(
                        List.of(zip("lib.zip")), List.of("HE111111"), "B_CANCEL")).getMessage());
    }

    @Test
    @DisplayName("UT20 UTCID05 — batchId không tồn tại")
    void ut20_utcid05_batchKhongTonTai() {
        when(batchRepo.findByBatchId("KHONG_CO")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> nhanBai.addToBatch(
                List.of(zip("lib.zip")), List.of("HE111111"), "KHONG_CO"));
    }

    @Test
    @DisplayName("UT20 UTCID06 — số file lệch số username")
    void ut20_utcid06_lechSoLuong() {
        phien("B_RUN", BatchStatus.IN_PROGRESS);

        assertEquals("Mỗi file .zip phải đi kèm đúng tên thư mục username.",
                assertThrows(IllegalArgumentException.class, () -> nhanBai.addToBatch(
                        baZip(), List.of("A"), "B_RUN")).getMessage());
    }

    @Test
    @DisplayName("UT20 UTCID07 — đề của phiên không còn READY thì dừng, KHÔNG tự build lại")
    void ut20_utcid07_deKhongConReady() throws Exception {
        phien("B_RUN", BatchStatus.IN_PROGRESS);
        de.setStatus(ExamStatus.BUILDING);

        assertEquals("Đề thi chưa sẵn sàng để chấm: BUILDING",
                assertThrows(IllegalStateException.class, () -> nhanBai.addToBatch(
                        List.of(zip("lib.zip")), List.of("HE111111"), "B_RUN")).getMessage());
        verify(examService, never()).buildSandbox(anyString());
    }

    /**
     * UT20 UTCID08 (cũ) đã bỏ cùng lý do với UT19 UTCID05: dấu kiểm đồng bộ khung phát không
     * còn tồn tại, nên giữa hai đợt thu bài không có dấu nào để mất.
     */
    @Test
    @DisplayName("UT20 UTCID09 — trùng mã SV trong CÙNG một lần nạp thêm bị loại")
    void ut20_utcid09_trungMaTrongCungLanNap() throws Exception {
        phien("B_RUN", BatchStatus.IN_PROGRESS);

        BatchSubmitResponse ra = nhanBai.addToBatch(
                List.of(zip("lib.zip"), zip("lib.zip")),
                List.of("HE111111", "PE_ca2 - he111111"), "B_RUN");

        assertEquals(1, ra.getTotalQueued());
        assertTrue(ra.getParseErrors().get(0).contains("Trùng mã SV HE111111"));
    }

    /**
     * Khác UT19 UTCID09: cùng SV nộp lại ở ĐỢT SAU là chuyện bình thường (nộp bù), phải ghi đè
     * kết quả cũ chứ không được từ chối.
     */
    @Test
    @DisplayName("UT20 UTCID10 — cùng SV nộp lại ở đợt sau thì GHI ĐÈ, không bị coi là trùng")
    void ut20_utcid10_nopBuODotSau() throws Exception {
        phien("B_RUN", BatchStatus.IN_PROGRESS);
        ExamResult cu = new ExamResult();
        cu.setStudentId("HE111111");
        cu.setExamId(MA_DE);
        cu.setBatchId("B_RUN");
        cu.setScore(4.0f);
        cu.setResultJson("{\"diem\":4}");
        when(resultRepo.findByStudentIdAndExamIdAndMode("HE111111", MA_DE, "submit"))
                .thenReturn(Optional.of(cu));

        BatchSubmitResponse ra = nhanBai.addToBatch(
                List.of(zip("lib.zip")), List.of("HE111111"), "B_RUN");

        assertEquals(1, ra.getTotalQueued(), "nộp bù không phải là trùng");
        assertNull(cu.getScore());
        assertNull(cu.getResultJson(), "addToBatch còn phải xoá cả result_json của lượt trước");
        assertEquals(GradingStatus.QUEUED, cu.getStatus());
    }

    @Test
    @DisplayName("UT20 — cả lô nạp thêm đều hỏng thì KHÔNG đụng vào trạng thái phiên")
    void ut20_caLoHongThiKhongDoiTrangThaiPhien() throws Exception {
        GradingBatch xong = phien("B_DONE", BatchStatus.COMPLETED);
        java.time.Instant hoanTat = xong.getCompletedAt();

        BatchSubmitResponse ra = nhanBai.addToBatch(
                List.of(zip("a.rar")), List.of("HE111111"), "B_DONE");

        assertEquals(0, ra.getTotalQueued());
        assertEquals(BatchStatus.COMPLETED, xong.getStatus());
        assertEquals(hoanTat, xong.getCompletedAt());
        assertEquals(5, xong.getTotalFiles());
        assertEquals(0, hangDoi().size());
    }

    /** Mã SV là tên thư mục lưu bài — tên thư mục LMS kiểu gì cũng phải ra đường dẫn an toàn. */
    @Test
    @DisplayName("UT19/UT20 — tên thư mục LMS kỳ quặc vẫn ghi zip đúng chỗ, không thoát thư mục")
    void ut19_20_tenThuMucKyQuacVanAnToan() throws Exception {
        BatchSubmitResponse ra = nhanBai.enqueueBatch(
                List.of(zip("lib.zip"), zip("lib.zip")),
                List.of("../../thoat", "Trần Thị B - ca 2"), MA_DE, "x");

        assertEquals(2, ra.getTotalQueued());
        Path goc = thuMuc.resolve("submissions").resolve(MA_DE).resolve(ra.getBatchId());
        try (var ds = Files.list(goc)) {
            List<String> ten = ds.map(p -> p.getFileName().toString()).sorted().toList();
            assertTrue(ten.contains("THOAT.zip"), ten.toString());
            assertTrue(ten.contains("TRAN_THI_B_CA_2.zip"), ten.toString());
        }
        assertFalse(Files.exists(thuMuc.resolve("thoat.zip")), "không được ghi ra ngoài thư mục phiên");
    }

    /** Job vào hàng đợi SAU khi chốt totalFiles, nếu không worker có thể đóng phiên quá sớm. */
    @Test
    @DisplayName("UT19 — mỗi job trong hàng đợi trỏ đúng zip đã ghi ra đĩa")
    void ut19_jobTroDungZipDaGhi() throws Exception {
        BatchSubmitResponse ra = nhanBai.enqueueBatch(
                List.of(zip("lib.zip")), List.of("HE111111"), MA_DE, "x");

        Object job = hangDoi().peek();
        assertNotNull(job);
        String duong = String.valueOf(job.getClass()
                .getMethod("zipPath").invoke(job));
        assertTrue(Files.isRegularFile(Path.of(duong)), "zipPath phải trỏ tới file có thật: " + duong);
        assertTrue(duong.contains(ra.getBatchId()));
        assertEquals(Arrays.asList("HE111111"),
                List.of(String.valueOf(job.getClass().getMethod("studentId").invoke(job))));
    }
}
