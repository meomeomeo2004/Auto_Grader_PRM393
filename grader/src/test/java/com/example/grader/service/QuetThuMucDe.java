package com.example.grader.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Thư mục đề giả dùng chung cho hai bài kiểm phép quét đĩa theo vai.
 *
 * <p>Đường dẫn phải TUYỆT ĐỐI: ExamService chỉ dùng thẳng giá trị cấu hình khi nó tuyệt đối,
 * còn đường tương đối thì nó tự giải theo vị trí của grader-base, trỏ ra ngoài thư mục test.
 */
final class QuetThuMucDe {

    static final String MA_DE = "DE_CHI_CO_TREN_DIA";
    static final Path THU_MUC = Path.of("target", "kiem-quet-exams").toAbsolutePath().normalize();

    private QuetThuMucDe() {}

    /** Dựng sẵn trước khi Spring khởi động. */
    static String duong() throws Exception {
        Path tc = THU_MUC.resolve(MA_DE).resolve("testcase");
        Files.createDirectories(tc);
        Files.writeString(tc.resolve("skills_matrix.json"), "{}", StandardCharsets.UTF_8);
        return THU_MUC.toString();
    }

    static boolean coTrongDanhSach(List<Map<String, Object>> ds) {
        return ds.stream().anyMatch(m -> MA_DE.equals(String.valueOf(m.get("examId"))));
    }
}
