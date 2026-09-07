package com.example.grader.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cổng gọi Claude (Anthropic Messages API). Cùng phong cách với {@link GeminiClient} và
 * {@link OpenAiClient}: chỉ {@code java.net.http}, không thêm SDK — repo build offline
 * ({@code mvnw -o}) nên thêm dependency mới là hỏng build trên máy không có mạng.
 *
 * <p>Ba chỗ Claude KHÁC hẳn hai hãng kia, sai là lỗi 400 ngay:
 * <ul>
 *   <li>Xác thực bằng header {@code x-api-key} (không phải Bearer) và bắt buộc
 *       {@code anthropic-version}.</li>
 *   <li>{@code system} là THAM SỐ RIÊNG ở cấp cao nhất, không phải một message role.</li>
 *   <li>KHÔNG có JSON mode, và KHÔNG nhận {@code temperature}/{@code top_p} (Opus 5 trả 400).
 *       JSON được bảo đảm bằng chỉ dẫn trong prompt + bóc rào ở {@link LlmService}.</li>
 * </ul>
 */
@Slf4j
@Component
public class AnthropicClient implements LlmClient {

    /** Bản hợp đồng API — Anthropic bắt buộc gửi kèm mọi request. */
    private static final String API_VERSION = "2023-06-01";

    /**
     * Để rộng vì trên Claude Opus 5 mặc định CÓ suy luận, mà max_tokens tính chung cả phần suy
     * luận lẫn câu trả lời — chặt tay là JSON bị cắt giữa chừng.
     */
    private static final int MAX_TOKENS = 16000;

    @Autowired private AiSettingsService settings;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();

    @Override public String key() { return AiModelCatalog.ANTHROPIC; }

    @Override
    public String chat(List<LlmMessage> messages) throws Exception {
        if (!settings.hasApiKey())
            throw new IllegalStateException("Chưa nhập API key cho Claude.");

        StringBuilder system = new StringBuilder();
        List<Map<String, Object>> turns = new ArrayList<>();
        for (LlmMessage m : messages) {
            if ("system".equals(m.role())) {
                if (system.length() > 0) system.append("\n\n");
                system.append(m.content());
                continue;
            }
            turns.add(Map.of("role", "assistant".equals(m.role()) ? "assistant" : "user",
                    "content", m.content()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", settings.model());
        body.put("max_tokens", MAX_TOKENS);
        if (system.length() > 0) body.put("system", system.toString());
        body.put("messages", turns);

        HttpRequest req = HttpRequest.newBuilder(URI.create(settings.baseUrl() + "/messages"))
                .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("x-api-key", settings.apiKey())
                .header("anthropic-version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        int maxTries = 3;
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxTries; attempt++) {
            try {
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                int sc = res.statusCode();
                if (sc / 100 == 2) return readText(res.body());
                last = new RuntimeException("Claude HTTP " + sc + ": " + excerpt(res.body()));
                // 429/5xx là lỗi tạm thời (quá tải, chạm giới hạn phút) → thử lại; còn lại ném luôn.
                if (!(sc == 429 || sc / 100 == 5) || attempt == maxTries) throw last;
            } catch (java.io.IOException ioe) {
                last = new RuntimeException("Claude lỗi mạng: " + ioe.getMessage());
                if (attempt == maxTries) throw last;
            }
            Thread.sleep(2000L * attempt);
        }
        throw last != null ? last : new RuntimeException("Claude: lỗi không xác định");
    }

    /** Gom các khối text của câu trả lời; khối suy luận (thinking) bị bỏ qua. */
    private String readText(String rawBody) throws Exception {
        JsonNode root = mapper.readTree(rawBody);
        String stop = root.path("stop_reason").asText("");

        // Bộ lọc an toàn của Claude từ chối yêu cầu: trả 200 nhưng content rỗng.
        if ("refusal".equals(stop))
            throw new RuntimeException("Claude từ chối yêu cầu này"
                    + describeRefusal(root) + ". Hãy sửa lại mô tả đề rồi thử lại.");

        StringBuilder text = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText(""));
        }
        if (text.length() == 0)
            throw new RuntimeException("Claude không trả về nội dung: " + excerpt(rawBody));

        // Chạm trần token: JSON chắc chắn đứt giữa chừng, báo rõ thay vì để lỗi "JSON không hợp lệ".
        if ("max_tokens".equals(stop))
            throw new RuntimeException("Câu trả lời của Claude bị cắt vì quá dài. "
                    + "Hãy thu hẹp yêu cầu (ít màn hình/ít testcase hơn) rồi thử lại.");
        return text.toString();
    }

    private String describeRefusal(JsonNode root) {
        String category = root.path("stop_details").path("category").asText("");
        return category.isBlank() ? "" : " (nhóm: " + category + ")";
    }

    private static String excerpt(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
