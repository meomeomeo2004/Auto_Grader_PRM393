package com.example.grader.service.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Danh mục model cho trang cấu hình AI.
 *
 * <p>Người dùng chỉ phải CHỌN MODEL rồi DÁN API KEY — nhà cung cấp và endpoint được suy ra từ
 * mã model, không phải nhập tay. Trước đây bắt chọn provider rồi gõ endpoint của hãng là thừa:
 * ai dùng GPT thì cũng chỉ muốn chọn "GPT" chứ không quan tâm URL nào.
 */
final class AiModelCatalog {

    private AiModelCatalog() {}

    /** Provider = cách gọi API, suy ra từ mã model. */
    static final String ANTHROPIC = "anthropic";
    static final String OPENAI = "openai";
    static final String GEMINI = "gemini";

    static final List<String> PROVIDERS = List.of(ANTHROPIC, OPENAI, GEMINI);

    /** Endpoint chính thức của từng hãng — người dùng không cần biết tới. */
    static String defaultBaseUrl(String provider) {
        return switch (provider) {
            case ANTHROPIC -> "https://api.anthropic.com/v1";
            case GEMINI -> "https://generativelanguage.googleapis.com/v1beta";
            default -> "https://api.openai.com/v1";
        };
    }

    static String vendorLabel(String provider) {
        return switch (provider) {
            case ANTHROPIC -> "Claude (Anthropic)";
            case GEMINI -> "Gemini (Google)";
            default -> "GPT (OpenAI)";
        };
    }

    /** Nơi lấy API key, hiện ngay cạnh ô nhập để khỏi phải đi tìm. */
    static String keyUrl(String provider) {
        return switch (provider) {
            case ANTHROPIC -> "https://console.anthropic.com/settings/keys";
            case GEMINI -> "https://aistudio.google.com/app/apikey";
            default -> "https://platform.openai.com/api-keys";
        };
    }

    /**
     * Đoán hãng từ CHÍNH API KEY, để bắt trường hợp key và model lệch hãng — nguyên nhân của thông
     * báo lỗi khó hiểu nhất: người dùng dán key Claude nhưng model vẫn là gpt-5, và OpenAI trả về
     * 401 nói "key OpenAI của bạn sai". Trả {@code null} khi không nhận ra (key của proxy/bên thứ
     * ba dùng tiền tố riêng) — thà im lặng còn hơn báo động nhầm.
     */
    static String vendorOfKey(String apiKey) {
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.startsWith("sk-ant-")) return ANTHROPIC;
        if (key.startsWith("sk-proj-")) return OPENAI;
        if (key.startsWith("AIza")) return GEMINI;
        return null;
    }

    /**
     * Suy ra nhà cung cấp từ mã model. Người dùng gõ tay model lạ (bản self-host, model mới ra)
     * thì rơi về OpenAI-compatible — chuẩn được nhiều nơi dùng lại nhất (Ollama, OpenRouter, Groq).
     */
    static String providerFor(String model) {
        String id = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        if (id.startsWith("claude")) return ANTHROPIC;
        if (id.startsWith("gemini")) return GEMINI;
        return OPENAI;
    }

    /**
     * Model gợi ý sẵn trong ô chọn. Không phải danh sách đóng: người dùng vẫn gõ được mã model
     * bất kỳ, nên model mới ra sau này không cần sửa code.
     */
    static List<Map<String, Object>> models() {
        List<Map<String, Object>> out = new ArrayList<>();
        // Claude — mã model KHÔNG có hậu tố ngày tháng.
        out.add(model("claude-opus-5", "Claude Opus 5"));
        out.add(model("claude-sonnet-5", "Claude Sonnet 5"));
        out.add(model("claude-haiku-4-5", "Claude Haiku 4.5"));
        // GPT
        out.add(model("gpt-5", "GPT-5"));
        out.add(model("gpt-4o", "GPT-4o"));
        out.add(model("gpt-4o-mini", "GPT-4o mini"));
        // Gemini
        out.add(model("gemini-2.0-flash", "Gemini 2.0 Flash"));
        out.add(model("gemini-1.5-pro", "Gemini 1.5 Pro"));
        return out;
    }

    private static Map<String, Object> model(String id, String label) {
        String provider = providerFor(id);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("label", label);
        row.put("provider", provider);
        row.put("vendor", vendorLabel(provider));
        return row;
    }
}
