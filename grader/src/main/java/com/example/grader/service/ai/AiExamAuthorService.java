package com.example.grader.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.example.grader.service.ExamService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.grader.config.Vai;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trợ lý soạn đề: sinh/sửa đề bài → sinh hình minh họa giao diện → sinh database mẫu/ẩn.
 *
 * <p>Port từ Grader_App1 nhưng ĐÃ BỎ bước phân tích Item Key/mockup và đề xuất testcase theo
 * template: hai bước đó phụ thuộc {@code TestcaseTemplateService}, một hệ thống Grader_App không
 * còn dùng (đã chuyển sang Golden Solution Record–Abstract–Replay, xem
 * docs/golden-record-abstract-replay.md).
 *
 * <p>ĐÃ BỎ (17/9/2026) bước sinh khung starter + app "lời giải mẫu" (Golden Solution) bằng AI —
 * cùng lúc với việc xoá trang "Tạo Golden". Giáo viên soạn/upload Golden Solution thủ công ở
 * trang "Bộ chấm Golden" ({@code /teacher/archive}).
 *
 * <p>Mỗi bước chỉ trả về BẢN ĐỀ XUẤT cho giáo viên sửa và bấm chấp nhận. Không bước nào tự ghi vào
 * đề đang chấm.
 */
@Slf4j
@Profile(Vai.GIANG_VIEN)
@Service
public class AiExamAuthorService {

    @Autowired private LlmService llm;
    @Autowired private ExamService exams;

    // ── Bước 1: đề bài ───────────────────────────────────────────

    public Map<String, Object> draftExam(Map<String, Object> req, String databaseName,
                                          List<String> allowedPackages) {
        // Giữ lời gọi cũ, nhưng lựa chọn khung phát không còn là ngữ cảnh ra đề của AI.
        return draftExam(req, databaseName);
    }

    public Map<String, Object> draftExam(Map<String, Object> req, String databaseName) {
        JsonNode res = llm.chatJson(List.of(
                LlmMessage.system(AiPrompts.draftSystem(databaseName, imagePackages())),
                LlmMessage.user(AiPrompts.draftUser(req == null ? Map.of() : req))));
        return examResult(res);
    }

    public Map<String, Object> reviseExam(String deBai, String instruction) {
        if (deBai == null || deBai.isBlank())
            throw new IllegalArgumentException("Chưa có đề để sửa.");
        if (instruction == null || instruction.isBlank())
            throw new IllegalArgumentException("Hãy nhập yêu cầu chỉnh sửa cho AI.");
        JsonNode res = llm.chatJson(List.of(
                LlmMessage.system(AiPrompts.reviseSystem() + AiPrompts.packageReference(imagePackages())),
                LlmMessage.user(AiPrompts.reviseUser(deBai, instruction))));
        return examResult(res);
    }

    public Map<String, Object> reviseExam(String deBai, String instruction, List<String> allowedPackages) {
        return reviseExam(deBai, instruction);
    }

    private Map<String, Object> examResult(JsonNode res) {
        String markdown = text(res.path("de_bai_markdown"), "");
        if (markdown.isBlank())
            throw new IllegalStateException("AI không trả về nội dung đề bài. Hãy thử lại.");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("de_bai", markdown.trim());
        out.put("summary", text(res.path("summary"), ""));
        return out;
    }

    private List<String> imagePackages() {
        return exams.goiCoTrongAnhCham().stream().sorted().toList();
    }

    // ── Bước 2 (MỚI): hình minh họa giao diện ─────────────────────

    /**
     * AI đọc mục "3. Hợp đồng giao diện" của đề bài, mô tả MỖI màn hình bằng JSON có cấu trúc
     * (tiêu đề + danh sách thành phần: app_bar/heading/text/input/button/list_item/card/image/
     * checkbox/divider) — KHÔNG tự vẽ SVG, vì LLM sinh XML tự do dễ ra thẻ hỏng/không cân đối.
     * {@link com.example.grader.service.MockupRenderer} vẽ SVG THẬT từ JSON đó, tất định, luôn
     * hợp lệ về cú pháp.
     */
    public List<com.example.grader.service.HandoutDocument.Mockup> proposeMockups(String deBai, String instruction) {
        if (deBai == null || deBai.isBlank())
            throw new IllegalArgumentException("Chưa có đề bài để vẽ hình minh họa.");
        JsonNode res = llm.chatJson(List.of(
                LlmMessage.system(AiPrompts.mockupSystem()),
                LlmMessage.user(AiPrompts.mockupUser(deBai, instruction))));

        List<com.example.grader.service.HandoutDocument.Mockup> out = new ArrayList<>();
        for (JsonNode screen : res.path("screens")) {
            String id = text(screen.path("id"), "");
            if (id.isBlank()) continue;
            String title = text(screen.path("title"), id);
            List<com.example.grader.service.MockupRenderer.Element> elements = new ArrayList<>();
            for (JsonNode el : screen.path("elements")) {
                String label = text(el.path("label"), "");
                if (label.isBlank()) continue;
                elements.add(new com.example.grader.service.MockupRenderer.Element(
                        text(el.path("type"), "text"), label));
            }
            if (elements.isEmpty()) continue;
            String svg = com.example.grader.service.MockupRenderer.render(title, elements);
            out.add(new com.example.grader.service.HandoutDocument.Mockup(id, title, svg));
        }
        if (out.isEmpty())
            throw new IllegalStateException(
                    "AI không nhận diện được màn hình nào từ mục 3 của đề bài để vẽ minh họa.");
        return out;
    }

    private String text(JsonNode node, String fallback) {
        if (node == null || node.isMissingNode() || node.isNull()) return fallback;
        String s = node.asText("").trim();
        return s.isEmpty() ? fallback : s;
    }
}
