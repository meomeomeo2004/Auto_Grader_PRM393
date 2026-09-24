package com.example.grader.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Module Common — sheet UT32_safeId.
 *
 * <p>Đây là cổng chặn path traversal dùng chung cho cả hai vai: mọi chỗ ghép mã đề hoặc mã SV
 * vào đường dẫn file / lệnh docker đều phải đi qua đây trước. Nới biểu thức một chút là mở
 * đường ghi file ra ngoài thư mục đề, nên bộ kiểm này ghim cả hai biên độ dài lẫn từng lớp ký
 * tự bị cấm.
 */
class CommonUnitTest {

    /** 60 ký tự — đúng trần của biểu thức [A-Za-z0-9_-]{1,60}. */
    private static final String SAU_MUOI = "A".repeat(60);
    private static final String SAU_MUOI_MOT = "A".repeat(61);

    // ══════════════════ UT32 — ExamService.safeId ══════════════════

    static Stream<Arguments> ut32_hopLe() {
        return Stream.of(
                Arguments.of("UTCID01", "PE_PRM393_FA26", "đề"),
                Arguments.of("UTCID02", "A", "đề"),                 // biên dưới: 1 ký tự
                Arguments.of("UTCID03", SAU_MUOI, "đề"),            // biên trên: 60 ký tự
                Arguments.of("UTCID10", "de-thi_01", "đề")
        );
    }

    @ParameterizedTest(name = "UT32 {0}: safeId({1}) trả lại nguyên mã")
    @MethodSource("ut32_hopLe")
    @DisplayName("UT32 — mã hợp lệ được trả lại nguyên vẹn, không bị sửa")
    void ut32_maHopLeTraLaiNguyenVen(String utcid, String id, String what) {
        assertEquals(id, ExamService.safeId(id, what),
                utcid + ": safeId không được đụng vào mã hợp lệ");
    }

    static Stream<Arguments> ut32_khongHopLe() {
        return Stream.of(
                Arguments.of("UTCID04", SAU_MUOI_MOT, "đề"),        // biên trên + 1
                Arguments.of("UTCID05", "", "đề"),
                Arguments.of("UTCID06", null, "đề"),
                Arguments.of("UTCID07", "../../etc/passwd", "bộ testcase"),
                Arguments.of("UTCID08", "PE 01", "đề"),
                Arguments.of("UTCID09", "PE#01", "đề"),
                Arguments.of("UTCID11", "PE\\01", "đề")
        );
    }

    @ParameterizedTest(name = "UT32 {0}: safeId({1}) phải ném")
    @MethodSource("ut32_khongHopLe")
    @DisplayName("UT32 — mã không an toàn bị chặn kèm lý do chỉ đúng tham số what")
    void ut32_maKhongAnToanBiChan(String utcid, String id, String what) {
        IllegalArgumentException loi = assertThrows(IllegalArgumentException.class,
                () -> ExamService.safeId(id, what), utcid + ": phải ném IllegalArgumentException");

        // Lời báo phải gọi đúng tên thứ đang sai ("Mã đề…" / "Mã bộ testcase…"), nếu không
        // người dùng không biết mình gõ sai ô nào.
        assertTrue(loi.getMessage().startsWith("Mã " + what + " không hợp lệ"),
                utcid + ": lời báo phải nêu đúng loại mã, đang là: " + loi.getMessage());
        assertTrue(loi.getMessage().contains("chỉ gồm a-z, A-Z, 0-9, _, -"),
                utcid + ": lời báo phải nói rõ ký tự nào được phép");
    }
}
