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
 * Cổng gọi Google Gemini (generativelanguage API). KHÔNG dùng SDK ngoài — chỉ {@code java.net.http}.
 * Key/model lấy từ {@link AiSettingsService} nên đổi trên web là có hiệu lực ngay.
 */
@Slf4j
@Component
public class GeminiClient implements LlmClient {

    @Autowired private AiSettingsService settings;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();

    @Override public String key() { return "gemini"; }

    @Override
    public String chat(List<LlmMessage> messages) throws Exception {
        if (!settings.hasApiKey())
            throw new IllegalStateException("Chưa nhập API key cho Gemini.");

        // Gemini tách system ra systemInstruction; hội thoại dùng role user/model.
        StringBuilder sys = new StringBuilder();
        List<Map<String, Object>> contents = new ArrayList<>();
        for (LlmMessage m : messages) {
            if ("system".equals(m.role())) {
                if (sys.length() > 0) sys.append("\n\n");
                sys.append(m.content());
                continue;
            }
            String role = "assistant".equals(m.role()) ? "model" : "user";
            contents.add(Map.of("role", role, "parts", List.of(Map.of("text", m.content()))));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        if (sys.length() > 0)
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", sys.toString()))));
        body.put("contents", contents);
        body.put("generationConfig", Map.of(
                "temperature", 0.35,
                "maxOutputTokens", 8192,
                "responseMimeType", "application/json"));   // ép trả về 1 object JSON

        String url = settings.baseUrl() + "/models/" + settings.model()
                + ":generateContent?key=" + settings.apiKey();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2)
            throw new RuntimeException("Gemini HTTP " + res.statusCode() + ": " + excerpt(res.body()));

        JsonNode root = mapper.readTree(res.body());
        JsonNode text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
        if (text.isMissingNode() || text.asText().isBlank()) {
            String reason = root.path("candidates").path(0).path("finishReason").asText("");
            String feedback = root.path("promptFeedback").toString();
            throw new RuntimeException("Gemini không trả nội dung"
                    + (reason.isBlank() ? "" : " (finishReason=" + reason + ")") + ": " + excerpt(feedback));
        }
        return text.asText();
    }

    private static String excerpt(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
