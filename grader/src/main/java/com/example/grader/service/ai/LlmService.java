package com.example.grader.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bộ định tuyến LLM: chọn client theo provider đang cấu hình rồi ủy thác. Thêm nhà cung cấp mới
 * = thêm một bean cài {@link LlmClient}, không phải sửa ở đây.
 *
 * <p>Cũng là nơi DUY NHẤT ép "câu trả lời phải là JSON": model đôi khi bọc JSON trong ```json …```
 * hoặc kèm lời dẫn, nên {@link #chatJson} tự bóc trước khi parse thay vì để cả tính năng chết vì
 * một dấu backtick.
 */
@Slf4j
@Service
public class LlmService {

    @Autowired private AiSettingsService settings;

    private final Map<String, LlmClient> clients = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmService(List<LlmClient> beans) {
        for (LlmClient c : beans) clients.put(c.key(), c);
    }

    public boolean isReady() {
        return settings.hasApiKey() && clients.containsKey(settings.provider());
    }

    private LlmClient active() {
        LlmClient c = clients.get(settings.provider());
        if (c == null)
            throw new IllegalStateException("Nhà cung cấp AI không hợp lệ: " + settings.provider());
        return c;
    }

    /** Gọi LLM và trả về JSON đã parse. Ném lỗi RÕ RÀNG (tiếng Việt) để hiện thẳng lên giao diện. */
    public JsonNode chatJson(List<LlmMessage> messages) {
        if (!settings.hasApiKey())
            throw new IllegalStateException(
                    "Chưa nhập API key. Mở phần \"Trợ lý AI\" → Cấu hình để dán key trước khi dùng.");
        String raw;
        try {
            raw = callWithProtocolFallback(messages);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Đã hủy lời gọi AI.");
        } catch (Exception e) {
            throw new IllegalStateException("Gọi AI thất bại: " + e.getMessage(), e);
        }
        try {
            return mapper.readTree(stripFence(raw));
        } catch (Exception e) {
            throw new IllegalStateException("AI trả về nội dung không phải JSON hợp lệ. "
                    + "Thử lại hoặc đổi sang model mạnh hơn. Trích đoạn: " + excerpt(raw));
        }
    }

    /**
     * Gọi model, có DỰ PHÒNG GIAO THỨC cho endpoint riêng.
     *
     * <p>Dịch vụ trung gian bán lại Claude thường mở CẢ HAI đường: {@code /chat/completions}
     * (giao thức OpenAI) và {@code /messages} (giao thức Anthropic). Cấu hình luôn đi đường
     * OpenAI trước vì phổ biến nhất; nơi nào không có đường đó sẽ trả 404/405 — khi ấy thử lại
     * đúng một lần bằng giao thức kia, thay vì bắt giáo viên tự đoán dịch vụ mình mua nói kiểu gì.
     */
    private String callWithProtocolFallback(List<LlmMessage> messages) throws Exception {
        LlmClient primary = active();
        try {
            return primary.chat(messages);
        } catch (Exception first) {
            LlmClient alternate = alternateProtocol(primary, first);
            if (alternate == null) throw first;
            log.warn("Endpoint riêng không nhận giao thức {} ({}) — thử lại bằng {}",
                    primary.key(), first.getMessage(), alternate.key());
            try {
                return alternate.chat(messages);
            } catch (Exception second) {
                first.addSuppressed(second);
                throw first;      // báo lỗi của đường chính, đường dự phòng chỉ là nỗ lực thêm
            }
        }
    }

    /** Chỉ đổi giao thức khi dùng endpoint riêng VÀ lỗi đúng kiểu "không có đường này". */
    private LlmClient alternateProtocol(LlmClient primary, Exception failure) {
        if (!settings.hasCustomBaseUrl()) return null;
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        if (!message.contains(" 404") && !message.contains(" 405")) return null;
        String other = AiModelCatalog.OPENAI.equals(primary.key())
                ? AiModelCatalog.ANTHROPIC : AiModelCatalog.OPENAI;
        return clients.get(other);
    }

    /** Bóc rào ```json … ``` và phần chữ thừa hai đầu để lấy đúng object JSON. */
    private String stripFence(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstBreak = s.indexOf('\n');
            if (firstBreak > 0) s = s.substring(firstBreak + 1);
            int fence = s.lastIndexOf("```");
            if (fence >= 0) s = s.substring(0, fence);
            s = s.trim();
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        return (start >= 0 && end > start) ? s.substring(start, end + 1) : s;
    }

    /** Gọi thử một lượt ngắn để người dùng biết key/model có dùng được không. */
    public Map<String, Object> testConnection() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", settings.provider());
        out.put("model", settings.model());
        long started = System.currentTimeMillis();
        try {
            if (!settings.hasApiKey())
                throw new IllegalStateException("Chưa nhập API key.");
            String raw = callWithProtocolFallback(List.of(
                    LlmMessage.system("Bạn trả lời bằng JSON. Chỉ trả về {\"ok\":true}."),
                    LlmMessage.user("Trả về đúng {\"ok\":true}")));
            out.put("elapsedMs", System.currentTimeMillis() - started);
            // Việc của phép thử này là xác nhận KEY + ENDPOINT + MODEL gọi được. Model trả về chữ
            // thay vì JSON (hay gặp ở dịch vụ trung gian có chèn sẵn system prompt riêng) là
            // chuyện khác — vẫn coi là kết nối được, nhưng phải nói rõ để giáo viên biết trước.
            out.put("ok", true);
            try {
                mapper.readTree(stripFence(raw));
                out.put("message", "Kết nối thành công.");
            } catch (Exception notJson) {
                out.put("message", "Gọi được model (key, endpoint và mã model đều đúng) nhưng model "
                        + "trả lời không phải JSON, nên các bước soạn đề có thể lỗi. Trích đoạn: "
                        + excerpt(raw));
            }
        } catch (Exception e) {
            out.put("ok", false);
            out.put("elapsedMs", System.currentTimeMillis() - started);
            out.put("message", explainAuthFailure(e.getMessage()));
        }
        return out;
    }

    /**
     * Lỗi 401 kèm ĐÚNG thứ app đã gửi.
     *
     * <p>"API key is invalid" là câu trả lời chung cho mọi kiểu key hỏng, mà ô key trên giao diện
     * luôn hiện dạng che — nên người dùng thấy key mình vừa tạo, tin là đúng, và không có gì để
     * đối chiếu. In ra độ dài + endpoint + model là đủ để tự soi (key Claude dài 108 ký tự; thiếu
     * vài ký tự là biết dán hụt, thừa là dính rác vô hình) mà không lộ nội dung key.
     */
    private String explainAuthFailure(String message) {
        String text = message == null ? "" : message;
        if (!text.contains(" 401") && !text.toLowerCase().contains("api key is invalid")) return text;
        return text + "\n\nApp đã gửi: key dài " + (settings.hasApiKey() ? settings.apiKey().length() : 0)
                + " ký tự tới " + settings.baseUrl() + " cho model " + settings.model() + "."
                + " Key Claude thường dài 108 ký tự và bắt đầu bằng sk-ant-api03-."
                + " Lệch số này nghĩa là key dán thiếu/thừa ký tự; đúng số mà vẫn 401 thì key đã bị"
                + " thu hồi hoặc thuộc workspace khác.";
    }

    private static String excerpt(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() > 200 ? one.substring(0, 200) + "…" : one;
    }
}
