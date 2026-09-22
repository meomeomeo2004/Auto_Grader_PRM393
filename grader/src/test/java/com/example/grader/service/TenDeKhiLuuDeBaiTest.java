package com.example.grader.service;

import com.example.grader.entity.Exam;
import com.example.grader.repository.ExamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TÊN ĐỀ PHẢI SỐNG SÓT QUA ĐƯỜNG "NHỜ AI SOẠN" (22/9/2026).
 *
 * <p>Hộp "Tạo đề" hỏi mã đề + tên đề rồi cho chọn ba đường vào. Đường TẢI FILE đi qua
 * {@link ExamService#saveOriginalHandoutFile} nên có {@code ensureExamStub} và giữ được tên.
 * Đường AI thì không: nó chỉ ghi {@code handout/de_bai.md} xuống đĩa, không dựng hàng
 * {@code Exam} nào, nên đề hiện ra ở danh sách bằng nhánh QUÉT ĐĨA của {@code listExams} —
 * nhánh đó đặt cứng {@code examName = examId} vì trên đĩa không có chỗ nào ghi tên.
 *
 * <p>Kết quả người dùng thấy: gõ tên đàng hoàng ở hộp Tạo đề, soạn xong bấm Lưu, quay ra danh
 * sách thì cột "Đề bài" in "(chưa đặt tên)". Không có lỗi nào hiện lên, nên ai cũng tưởng mình
 * quên gõ.
 */
class TenDeKhiLuuDeBaiTest {

    @TempDir Path temp;
    private ExamService dichVu;
    private ExamRepository kho;

    @BeforeEach
    void dung() throws Exception {
        dichVu = new ExamService();
        ReflectionTestUtils.setField(dichVu, "templateDir",
                Files.createDirectories(temp.resolve("tmpl")).toString());
        ReflectionTestUtils.setField(dichVu, "examsDir",
                Files.createDirectories(temp.resolve("exams")).toString());
        kho = mock(ExamRepository.class);
        when(kho.findByExamId(anyString())).thenReturn(Optional.empty());
        when(kho.save(any(Exam.class))).thenAnswer(i -> i.getArgument(0));
        ReflectionTestUtils.setField(dichVu, "examRepository", kho);
    }

    private Exam daLuu() {
        ArgumentCaptor<Exam> bat = ArgumentCaptor.forClass(Exam.class);
        verify(kho).save(bat.capture());
        return bat.getValue();
    }

    @Test
    void luuDeBaiKemTenThiTenVaoDuocDb() throws Exception {
        dichVu.saveDeBaiWithMockups("DE", "# Đề mới", "Quản lý chi tiêu cá nhân", List.of());

        assertEquals("Quản lý chi tiêu cá nhân", daLuu().getExamName(),
                "tên gõ ở hộp Tạo đề phải là tên đề, không phải mã đề");
    }

    /** Không tên thì vẫn phải có hàng — chỉ là tên rơi về mã đề, đúng như trước giờ. */
    @Test
    void khongCoTenThiRoiVeMaDe() throws Exception {
        dichVu.saveDeBaiWithMockups("DE", "# Đề mới", "   ", List.of());

        assertEquals("DE", daLuu().getExamName());
    }

    /**
     * Lưu đề bài KHÔNG phải là đổi tên: đề đã có hàng rồi thì tên cũ giữ nguyên.
     *
     * <p>Màn chi tiết gửi kèm tên ở MỌI lần Lưu, kể cả những lần sửa vặt về sau. Nếu chỗ này
     * ghi đè thì một ô tên đang nạp dở (danh sách chưa về kịp) sẽ lẳng lặng đổi tên đề đang có.
     * Muốn đổi tên thì đi đường {@code /rename}.
     */
    @Test
    void deDaCoRoiThiKhongGhiDeTenCu() throws Exception {
        Exam cu = new Exam();
        cu.setExamId("DE");
        cu.setExamName("Tên cũ");
        when(kho.findByExamId("DE")).thenReturn(Optional.of(cu));

        dichVu.saveDeBaiWithMockups("DE", "# Sửa vặt", "Tên gõ nhầm", List.of());

        verify(kho, never()).save(any(Exam.class));
        assertEquals("Tên cũ", cu.getExamName());
    }

    /**
     * Lưu rỗng thì KHÔNG để lại đề trống trong danh sách.
     *
     * <p>Hàng Exam dựng SAU hai cửa chặn đầu hàm. Dựng trước thì một cú bấm Lưu hỏng cũng đẻ
     * ra một đề không nội dung, mà đề đó chỉ xoá được bằng tay.
     */
    @Test
    void luuRongThiKhongDungHangNao() {
        assertThrows(IllegalArgumentException.class,
                () -> dichVu.saveDeBaiWithMockups("DE", "  ", "Tên gì đó", List.of()));

        verify(kho, never()).save(any(Exam.class));
    }
}
