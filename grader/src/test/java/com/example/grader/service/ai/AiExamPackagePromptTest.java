package com.example.grader.service.ai;

import com.example.grader.service.ExamService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class AiExamPackagePromptTest {
    @Test void draftUsesOnlyImageInventoryAndPutsOutsideImageRecommendationsAtStartOfExam() throws Exception {
        LlmService llm = mock(LlmService.class);
        AtomicReference<List<LlmMessage>> sent = new AtomicReference<>();
        when(llm.chatJson(anyList())).thenAnswer(call -> {
            sent.set(call.getArgument(0));
            return new ObjectMapper().readTree("{\"de_bai_markdown\":\"[CẦN BỔ SUNG THƯ VIỆN] camera: cần chụp ảnh.\\n# Đề mẫu\",\"summary\":\"Đề mẫu\"}");
        });
        AiExamAuthorService author = new AiExamAuthorService();
        ReflectionTestUtils.setField(author, "llm", llm);
        ExamService exams = mock(ExamService.class);
        when(exams.goiCoTrongAnhCham()).thenReturn(java.util.Set.of("flutter", "shared_preferences", "image_picker"));
        when(exams.listManagedPackages()).thenReturn(List.of(Map.of("name", "shared_preferences", "version", "^2.5.0")));
        ReflectionTestUtils.setField(author, "exams", exams);
        Map<String, Object> result = author.draftExam(Map.of("topic", "Ghi nhớ", "allowed_packages", List.of("selection_only")), "hidden.db", List.of("selection_only"));
        String system = sent.get().get(0).content();
        assertFalse(sent.get().stream().anyMatch(message -> message.content().contains("selection_only")), "AI không nhận lựa chọn package của đề");
        assertTrue(system.contains("shared_preferences"));
        assertTrue(system.contains("image_picker"), "AI cần biết gói còn có trong ảnh để recommend");
        assertFalse(system.contains("rồi liệt kê"), "Đề bài không được liệt kê package");
        assertFalse(system.contains("Không đề xuất package không có"), "AI được đề xuất package ngoài ảnh cho giảng viên cân nhắc");
        assertTrue(system.contains("[CẦN BỔ SUNG THƯ VIỆN]"));
        assertTrue(system.contains("dòng đầu tiên"));
        assertTrue(String.valueOf(result.get("de_bai")).startsWith("[CẦN BỔ SUNG THƯ VIỆN] camera"));
        assertTrue(system.contains("0 điểm"));
    }

    @Test void revisionUsesSameImageScopeAndFrontNoticeWithoutSelection() throws Exception {
        LlmService llm = mock(LlmService.class);
        AtomicReference<List<LlmMessage>> sent = new AtomicReference<>();
        when(llm.chatJson(anyList())).thenAnswer(call -> {
            sent.set(call.getArgument(0));
            return new ObjectMapper().readTree("{\"de_bai_markdown\":\"[CẦN BỔ SUNG THƯ VIỆN] camera: cần chụp ảnh.\\n# Đề sửa\"}");
        });
        ExamService exams = mock(ExamService.class);
        when(exams.goiCoTrongAnhCham()).thenReturn(java.util.Set.of("flutter", "intl"));
        AiExamAuthorService author = new AiExamAuthorService();
        ReflectionTestUtils.setField(author, "llm", llm);
        ReflectionTestUtils.setField(author, "exams", exams);
        author.reviseExam("# Đề hiện tại", "Thêm chụp ảnh", List.of("selection_only"));
        assertFalse(sent.get().stream().anyMatch(message -> message.content().contains("selection_only")));
        assertTrue(sent.get().get(0).content().contains("intl"));
        assertTrue(sent.get().get(0).content().contains("[CẦN BỔ SUNG THƯ VIỆN]"));
        assertTrue(sent.get().get(0).content().contains("dòng đầu tiên"));
    }
}
