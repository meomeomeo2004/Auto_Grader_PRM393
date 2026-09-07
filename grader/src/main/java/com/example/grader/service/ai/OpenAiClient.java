package com.example.grader.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 * Cổng gọi OpenAI/GPT (Chat Completions). KHÔNG dùng SDK ngoài — chỉ {@code java.net.http}.
 * Dùng được cho MỌI endpoint tương thích OpenAI khi đổi endpoint trên web: Azure OpenAI,
 * OpenRouter, Groq, hoặc model chạy local qua Ollama/vLLM.
 */
@Slf4j
@Component
public class OpenAiClient implements LlmClient {

    @Autowired private AiSettingsService settings;

    /** Với họ model REASONING (gpt-5*, o1/o3/o4*): mức suy luận. Bỏ trống = mặc định của API. */
    @Value("${grader.ai.openai.reasoning-effort:low}")
    private String reasoningEffort;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();

    @Override public String key() { return "openai"; }

    @Override
    public String chat(List<LlmMessage> messages) throws Exception {
        if (!settings.hasApiKey())
            throw new IllegalStateException("Chưa nhập API key cho " + settings.endpointLabel("OpenAI") + ".");

        List<Map<String, Object>> msgs = new ArrayList<>();
        for (LlmMessage m : messages) msgs.add(Map.of("role", m.role(), "content", m.content()));

        String model = settings.model();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", msgs);
        if (isReasoning(model)) {
            // Họ gpt-5 / o-series: KHÔNG nhận 'max_tokens'/'temperature' tùy biến.
            body.put("max_completion_tokens", 32768);   // gồm cả token suy luận → để rộng
            if (reasoningEffort != null && !reasoningEffort.isBlank())
                body.put("reasoning_effort", reasoningEffort.trim());
        } else {
            body.put("temperature", 0.35);
            body.put("max_tokens", 16384);
        }
        body.put("response_format", Map.of("type", "json_object"));   // ép trả về 1 object JSON

        HttpRequest req = HttpRequest.newBuilder(URI.create(settings.baseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + settings.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        // Lỗi mạng tạm thời hoặc 429/5xx → thử lại tối đa 3 lần với backoff.
        int maxTries = 3;
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxTries; attempt++) {
            try {
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                int sc = res.statusCode();
                if (sc / 100 == 2) {
                    JsonNode root = mapper.readTree(res.body());
                    JsonNode text = root.path("choices").path(0).path("message").path("content");
                    if (text.isMissingNode() || text.asText().isBlank())
                        throw new RuntimeException(settings.endpointLabel("OpenAI")
                                + " không trả nội dung: " + excerpt(res.body()));
                    return text.asText();
                }
                // Gọi qua dịch vụ trung gian mà báo "OpenAI HTTP 403" thì người dùng đi tìm nhầm
                // chỗ — nêu đúng tên miền đang gọi.
                last = new RuntimeException(settings.endpointLabel("OpenAI")
                        + " HTTP " + sc + ": " + excerpt(res.body()));
                if (!(sc == 429 || sc / 100 == 5) || attempt == maxTries) throw last;
            } catch (java.io.IOException ioe) {
                last = new RuntimeException(settings.endpointLabel("OpenAI")
                        + " lỗi mạng: " + ioe.getMessage());
                if (attempt == maxTries) throw last;
            }
            Thread.sleep(2000L * attempt);
        }
        throw last != null ? last
                : new RuntimeException(settings.endpointLabel("OpenAI") + ": lỗi không xác định");
    }

    /** Họ model "reasoning" cần tham số API khác (max_completion_tokens, không temperature). */
    private static boolean isReasoning(String m) {
        if (m == null) return false;
        String x = m.toLowerCase();
        return x.startsWith("gpt-5") || x.startsWith("o1") || x.startsWith("o3")
                || x.startsWith("o4") || x.startsWith("o5");
    }

    private static String excerpt(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
