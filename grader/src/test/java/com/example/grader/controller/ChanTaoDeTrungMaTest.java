package com.example.grader.controller;

import com.example.grader.service.ExamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CÙNG MỘT ĐƯỜNG API, HAI Ý ĐỊNH NGƯỢC NHAU.
 *
 * <p>{@code POST /handout/original} phục vụ cả hộp "Tạo đề" (đề mới tinh) lẫn nút "Tải Word"
 * ở màn chi tiết (tải đè lên đề đang có). Hàm lưu thì xoá file gốc cũ rồi mới ghi file mới,
 * nên với ý định thứ nhất, trùng mã là mất bản Word của đề cũ — còn với ý định thứ hai, ghi đè
 * CHÍNH LÀ việc phải làm.
 *
 * <p>Cờ {@code moi} là thứ duy nhất phân biệt hai ý định đó. Bài kiểm này giữ cho cả hai vế
 * cùng đúng: bỏ cửa chặn thì vế trên hỏng, mà chặn cứng cho cả hai thì vế dưới hỏng.
 */
class ChanTaoDeTrungMaTest {

    private ExamService dichVu;
    private MockMvc mvc;

    @BeforeEach
    void dung() {
        dichVu = mock(ExamService.class);
        ExamSetupController dieuKhien = new ExamSetupController();
        ReflectionTestUtils.setField(dieuKhien, "examService", dichVu);
        mvc = MockMvcBuilders.standaloneSetup(dieuKhien).build();
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "de_bai.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[]{1, 2, 3});
    }

    @Test
    void taoDeMoiTrungMaThiBiChanVaKhongGhiDeGiCa() throws Exception {
        when(dichVu.deDaTonTai("PE_PRM393_FA26")).thenReturn(true);

        mvc.perform(multipart("/api/exam-setup/PE_PRM393_FA26/handout/original")
                        .file(file())
                        .param("moi", "true")
                        .param("examName", "Đề trùng mã"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("đã có rồi")));

        // Điểm cốt lõi: hàm lưu KHÔNG được chạy. Chặn sau khi đã ghi thì chặn để làm gì.
        verify(dichVu, never()).saveOriginalHandoutFile(anyString(), anyString(), anyString(), any());
    }

    @Test
    void taoDeMoiVoiMaChuaAiDungThiVanChay() throws Exception {
        when(dichVu.deDaTonTai("DE_MOI_TINH")).thenReturn(false);
        when(dichVu.saveOriginalHandoutFile(anyString(), anyString(), anyString(), any()))
                .thenReturn(Map.of("exam_id", "DE_MOI_TINH"));

        mvc.perform(multipart("/api/exam-setup/DE_MOI_TINH/handout/original")
                        .file(file())
                        .param("moi", "true")
                        .param("examName", "Đề mới"))
                .andExpect(status().isOk());

        verify(dichVu).saveOriginalHandoutFile(anyString(), anyString(), anyString(), any());
    }

    /**
     * Nút "Tải Word" ở màn chi tiết KHÔNG gửi cờ — và nó phải ghi đè được, vì đó đúng là việc
     * người dùng đang làm: sửa đề trong Word rồi tải bản mới lên thay bản cũ.
     */
    @Test
    void taiDeLenDeDangCoThiKhongBiChan() throws Exception {
        when(dichVu.deDaTonTai(anyString())).thenReturn(true);
        when(dichVu.saveOriginalHandoutFile(anyString(), any(), anyString(), any()))
                .thenReturn(Map.of("exam_id", "PE_PRM393_FA26"));

        mvc.perform(multipart("/api/exam-setup/PE_PRM393_FA26/handout/original")
                        .file(file()))
                .andExpect(status().isOk());

        verify(dichVu).saveOriginalHandoutFile(anyString(), any(), anyString(), any());
    }
}
