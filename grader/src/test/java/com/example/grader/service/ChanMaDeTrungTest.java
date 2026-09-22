package com.example.grader.service;

import com.example.grader.entity.Exam;
import com.example.grader.repository.ExamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CHẶN MÃ ĐỀ TRÙNG (22/9/2026).
 *
 * <p>Hộp "Tạo đề" trước đây chỉ kiểm tra dạng mã đề. Gõ trùng mã một đề đang có rồi tải file
 * Word lên là {@link ExamService#saveOriginalHandoutFile} xoá file gốc cũ rồi ghi file mới đè
 * lên — không hỏi, không báo, và không có đường lùi.
 *
 * <p>Phần khó không nằm ở chỗ chặn, mà ở chỗ ĐẾM ĐỦ đề đang có. Một đề có thể tồn tại theo
 * hai kiểu khác hẳn nhau, và {@code listExams} phải gộp cả hai lại mới ra danh sách người dùng
 * nhìn thấy:
 * <ul>
 *   <li>có hàng trong bảng {@code exam} — đường tải file, đường nhân bản, đường nhập gói;</li>
 *   <li>chỉ có thư mục {@code exams/<mã đề>/} trên đĩa — đề soạn bằng AI trước 22/9/2026
 *       không dựng hàng nào, và mọi bộ chấm publish ra đĩa cũng nằm ở đây.</li>
 * </ul>
 * Hỏi mỗi DB thì cửa chặn hụt đúng nhóm thứ hai — nhóm không có gì trong DB để khôi phục.
 */
class ChanMaDeTrungTest {

    @TempDir Path temp;
    private ExamService dichVu;
    private ExamRepository kho;
    private Path exams;

    @BeforeEach
    void dung() throws Exception {
        exams = Files.createDirectories(temp.resolve("exams"));
        dichVu = new ExamService();
        ReflectionTestUtils.setField(dichVu, "templateDir",
                Files.createDirectories(temp.resolve("tmpl")).toString());
        ReflectionTestUtils.setField(dichVu, "examsDir", exams.toString());
        kho = mock(ExamRepository.class);
        when(kho.save(any(Exam.class))).thenAnswer(i -> i.getArgument(0));
        ReflectionTestUtils.setField(dichVu, "examRepository", kho);
    }

    @Test
    void maDeChuaAiDungThiKhongChan() {
        assertFalse(dichVu.deDaTonTai("DE_MOI_TINH"));
    }

    @Test
    void coHangTrongDbThiLaDaTonTai() {
        when(kho.existsByExamId(anyString())).thenReturn(true);

        assertTrue(dichVu.deDaTonTai("PE_PRM393_FA26"));
    }

    /**
     * Đây mới là trường hợp dễ lọt: đề chỉ có thư mục trên đĩa.
     *
     * <p>Đề soạn bằng AI sinh ra như vậy, và bộ chấm publish cũng đổ vào đúng {@code exams/}.
     * Cửa chặn chỉ hỏi {@code existsByExamId} thì những đề này vô hình — gõ trùng mã là ghi đè.
     */
    @Test
    void chiCoThuMucTrenDiaCungTinhLaDaTonTai() throws Exception {
        Files.createDirectories(exams.resolve("DE_SOAN_BANG_AI").resolve("handout"));

        assertTrue(dichVu.deDaTonTai("DE_SOAN_BANG_AI"),
                "đề chỉ sống bằng thư mục trên đĩa vẫn là đề đang có");
    }

    /** Thư mục của đề khác thì không được tính lây. */
    @Test
    void thuMucDeKhacKhongTinhLaTrung() throws Exception {
        Files.createDirectories(exams.resolve("DE_A").resolve("handout"));

        assertFalse(dichVu.deDaTonTai("DE_B"));
    }
}
