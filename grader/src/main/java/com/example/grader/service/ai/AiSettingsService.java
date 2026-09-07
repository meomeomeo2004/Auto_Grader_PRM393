package com.example.grader.service.ai;

import com.example.grader.entity.AiSetting;
import com.example.grader.repository.AiSettingRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cấu hình LLM đang có hiệu lực.
 *
 * <p>MODEL là thứ duy nhất người dùng chọn; nhà cung cấp và endpoint được suy ra từ mã model
 * ({@link AiModelCatalog#providerFor}). Bắt chọn provider rồi gõ endpoint của hãng là thừa —
 * người dùng chỉ cần biết mình muốn dùng Claude, GPT hay Gemini.
 *
 * <p>Thứ tự ưu tiên: giá trị nhập trên web (DB) → biến môi trường/application.properties.
 * Nhờ vậy máy đã cắm key qua env vẫn chạy như cũ.
 */
@Slf4j
@Service
public class AiSettingsService {

    public static final List<String> PROVIDERS = AiModelCatalog.PROVIDERS;

    /** Model mặc định cho từng provider khi người dùng để trống. */
    private static final Map<String, String> DEFAULT_MODEL = Map.of(
            AiModelCatalog.ANTHROPIC, "claude-opus-5",
            AiModelCatalog.GEMINI, "gemini-2.0-flash",
            AiModelCatalog.OPENAI, "gpt-4o-mini");

    @Value("${grader.ai.provider:gemini}")
    private String envProvider;
    @Value("${grader.ai.gemini.api-key:}")
    private String envGeminiKey;
    @Value("${grader.ai.gemini.model:}")
    private String envGeminiModel;
    @Value("${grader.ai.openai.api-key:}")
    private String envOpenAiKey;
    @Value("${grader.ai.openai.model:}")
    private String envOpenAiModel;
    @Value("${grader.ai.openai.base-url:https://api.openai.com/v1}")
    private String envOpenAiBaseUrl;
    @Value("${grader.ai.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}")
    private String envGeminiBaseUrl;
    @Value("${grader.ai.anthropic.api-key:}")
    private String envAnthropicKey;
    @Value("${grader.ai.anthropic.model:}")
    private String envAnthropicModel;
    @Value("${grader.ai.timeout-seconds:180}")
    private int envTimeoutSeconds;

    @Autowired
    private AiSettingRepository repo;

    /** Mã model chỉ gồm ký tự an toàn — nó được ghép thẳng vào URL của Gemini. */
    private static final java.util.regex.Pattern SAFE_MODEL =
            java.util.regex.Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,80}$");

    private volatile String model;
    private volatile String baseUrl;
    private volatile String apiKey;
    private volatile int timeoutSeconds;
    private volatile Instant updatedAt;
    private volatile String updatedBy;

    @PostConstruct
    public void load() {
        applyEnvDefaults();
        try {
            repo.findById(AiSetting.SINGLETON_ID).ifPresent(row -> {
                model = blankToNull(row.getModel());
                baseUrl = blankToNull(row.getBaseUrl());
                apiKey = blankToNull(row.getApiKey()) != null ? row.getApiKey() : apiKey;
                timeoutSeconds = row.getTimeoutSeconds() != null ? row.getTimeoutSeconds() : timeoutSeconds;
                updatedAt = row.getUpdatedAt();
                updatedBy = row.getUpdatedBy();
            });
        } catch (Exception e) {
            log.warn("Không đọc được cấu hình AI từ DB, dùng cấu hình môi trường: {}", e.getMessage());
        }
        log.info("Cấu hình AI: model={} ({}) {}", model(), provider(),
                hasApiKey() ? "(đã có API key)" : "(CHƯA có API key)");
    }

    /** Máy đã cắm key qua biến môi trường vẫn chạy y như trước; provider lấy theo cấu hình cũ. */
    private void applyEnvDefaults() {
        String envSide = normalizeProvider(envProvider);
        timeoutSeconds = envTimeoutSeconds > 0 ? envTimeoutSeconds : 180;
        switch (envSide) {
            case AiModelCatalog.ANTHROPIC -> {
                apiKey = blankToNull(envAnthropicKey);
                model = blankToNull(envAnthropicModel);
                baseUrl = null;                       // dùng endpoint chính thức
            }
            case AiModelCatalog.OPENAI -> {
                apiKey = blankToNull(envOpenAiKey);
                model = blankToNull(envOpenAiModel);
                baseUrl = blankToNull(envOpenAiBaseUrl);
            }
            default -> {
                apiKey = blankToNull(envGeminiKey);
                model = blankToNull(envGeminiModel);
                baseUrl = blankToNull(envGeminiBaseUrl);
            }
        }
        // Không có model trong env thì lấy mặc định của chính nhà cung cấp đó, để provider()
        // (vốn suy từ model) không nhảy sang hãng khác.
        if (model == null) model = DEFAULT_MODEL.get(envSide);
    }

    // ── Giá trị đang dùng ────────────────────────────────────────

    /**
     * Suy từ mã model — không lưu riêng, nên không bao giờ lệch nhau.
     *
     * <p>NGOẠI LỆ: đã khai endpoint riêng thì đó là dịch vụ trung gian (relay/proxy, cổng nội bộ
     * của trường, Ollama…). Các dịch vụ này đều nói GIAO THỨC OpenAI kể cả khi model tên là
     * {@code claude-*}, nên phải gọi theo kiểu OpenAI chứ không phải kiểu Anthropic.
     */
    public String provider()  {
        return hasCustomBaseUrl() ? AiModelCatalog.OPENAI : AiModelCatalog.providerFor(model());
    }

    public boolean hasCustomBaseUrl() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    /**
     * Tên hiển thị trong THÔNG BÁO LỖI. Gọi qua dịch vụ trung gian mà báo "OpenAI HTTP 403" thì
     * người dùng đi tìm nhầm chỗ (đã xảy ra thật) — nên lấy đúng tên miền của endpoint đang gọi.
     */
    public String endpointLabel(String vendorName) {
        if (!hasCustomBaseUrl()) return vendorName;
        try {
            return java.net.URI.create(baseUrl()).getHost();
        } catch (Exception e) {
            return "endpoint riêng";
        }
    }
    public String apiKey()    { return apiKey; }
    public boolean hasApiKey(){ return apiKey != null && !apiKey.isBlank(); }
    public int timeoutSeconds() { return timeoutSeconds > 0 ? timeoutSeconds : 180; }

    public String model() {
        return model != null && !model.isBlank() ? model : "claude-opus-5";
    }

    public String baseUrl() {
        if (baseUrl != null && !baseUrl.isBlank()) return baseUrl.trim().replaceAll("/+$", "");
        return AiModelCatalog.defaultBaseUrl(provider());
    }

    /**
     * Chạy một việc trên cấu hình NHÁP (model/key/endpoint đang gõ) rồi trả lại nguyên trạng —
     * KHÔNG ghi gì xuống DB.
     *
     * <p>Dùng cho nút "Kiểm tra kết nối": trước đây muốn thử thì phải lưu trước, nên key sai vẫn
     * bị ghi đè lên key đang dùng được. Giờ thử trên bản nháp, thử xong thấy tốt mới bấm Lưu.
     *
     * <p>Ô nào để trống thì GIỮ giá trị đã lưu — gõ mỗi mã model mới, không nhập lại key, vẫn thử
     * được bằng key cũ.
     *
     * <p>{@code synchronized} + khôi phục trong {@code finally}: app chỉ chạy localhost một người
     * dùng, nhưng một lượt soạn đề chạy song song mà vớ phải key nháp thì rất khó lần ra.
     */
    public synchronized Map<String, Object> withDraft(String draftModel, String draftApiKey,
                                                      String draftBaseUrl,
                                                      java.util.function.Supplier<Map<String, Object>> action) {
        String savedModel = model, savedKey = apiKey, savedBaseUrl = baseUrl;
        try {
            if (draftModel != null && !draftModel.isBlank()) {
                String candidate = draftModel.trim();
                if (!SAFE_MODEL.matcher(candidate).matches())
                    throw new IllegalArgumentException("Mã model không hợp lệ: " + candidate);
                model = candidate;
            }
            if (draftApiKey != null && !draftApiKey.isBlank()) apiKey = draftApiKey.trim();
            // Chuỗi rỗng ở đây là CÓ Ý: "quay lại endpoint chính thức của hãng", khác với null
            // (không đụng tới). Đúng quy ước của ô nhập bên giao diện.
            if (draftBaseUrl != null) baseUrl = draftBaseUrl.trim();
            return action.get();
        } finally {
            model = savedModel;
            apiKey = savedKey;
            baseUrl = savedBaseUrl;
        }
    }

    // ── API cho controller ───────────────────────────────────────

    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", model());
        m.put("provider", provider());
        // Dùng endpoint riêng thì tên hãng không còn đúng — key là của dịch vụ trung gian đó.
        m.put("vendor", hasCustomBaseUrl()
                ? "Dịch vụ trung gian (giao thức OpenAI)"
                : AiModelCatalog.vendorLabel(provider()));
        m.put("keyUrl", hasCustomBaseUrl() ? null : AiModelCatalog.keyUrl(provider()));
        m.put("hasApiKey", hasApiKey());
        m.put("apiKeyMasked", mask(apiKey));                  // KHÔNG bao giờ trả key nguyên vẹn
        // Độ dài là thứ DUY NHẤT soi được khi key "nhìn thì đúng" mà hãng vẫn từ chối: key Claude
        // dài ~108 ký tự, thấy 104 hay 112 là biết đã dán thiếu/thừa. Không lộ nội dung key.
        m.put("apiKeyLength", apiKey == null ? 0 : apiKey.length());
        m.put("keyWarning", keyWarning());
        m.put("timeoutSeconds", timeoutSeconds());
        m.put("models", AiModelCatalog.models());
        m.put("baseUrl", baseUrl());
        m.put("customBaseUrl", baseUrl != null && !baseUrl.isBlank());
        m.put("ready", hasApiKey());
        m.put("updatedAt", updatedAt != null ? updatedAt.toString() : null);
        m.put("updatedBy", updatedBy);
        return m;
    }

    /**
     * Lưu cấu hình mới. Bỏ trống {@code apiKey} = GIỮ NGUYÊN key cũ (giao diện chỉ hiện key che,
     * không thể gửi lại key thật) — muốn xoá key thì gửi {@code apiKey: ""} kèm
     * {@code clearApiKey: true}.
     */
    public Map<String, Object> update(Map<String, Object> body, String actor) {
        String newModel = text(body, "model", model());
        if (newModel == null || newModel.isBlank())
            throw new IllegalArgumentException("Hãy chọn model AI muốn dùng.");
        if (!SAFE_MODEL.matcher(newModel).matches())
            throw new IllegalArgumentException("Mã model không hợp lệ: " + newModel
                    + ". Chỉ gồm chữ, số và các ký tự . _ - :");

        String newBaseUrl = text(body, "baseUrl", null);
        String rawKey = text(body, "apiKey", null);
        // Dọn ký tự vô hình TRƯỚC khi kiểm: dán key dính U+200B thì bộ kiểm cũ cho qua, hãng lại
        // trả 401 "API key is invalid" và không có cách nào nhìn ra vì ô key luôn hiện dạng che.
        String suppliedKey = sanitizeKey(rawKey);
        if (suppliedKey != null && suppliedKey.isBlank()) suppliedKey = null;
        if (suppliedKey != null) validateSuppliedKey(suppliedKey);
        boolean clearKey = Boolean.TRUE.equals(body.get("clearApiKey"));
        int newTimeout = (int) number(body.get("timeoutSeconds"), timeoutSeconds());
        if (newTimeout < 30 || newTimeout > 900)
            throw new IllegalArgumentException("Thời gian chờ mỗi lần gọi AI phải trong khoảng 30 – 900 giây");

        // Endpoint riêng dùng cho dịch vụ trung gian bán lại API, cổng nội bộ, Ollama/OpenRouter…
        // Cho phép với MỌI model: nhiều dịch vụ phục vụ model claude-* qua giao thức OpenAI, chặn
        // theo tên model là khoá luôn đường dùng hợp lệ đó.
        boolean custom = newBaseUrl != null && !newBaseUrl.isBlank();
        if (custom && !newBaseUrl.startsWith("http://") && !newBaseUrl.startsWith("https://"))
            throw new IllegalArgumentException("Endpoint phải bắt đầu bằng http:// hoặc https://");

        // Đổi sang HÃNG KHÁC mà không nhập key mới: key cũ chắc chắn không dùng được (key Claude
        // không phải key OpenAI). Xoá luôn để giao diện báo "chưa có key" ngay, thay vì để người
        // dùng đâm vào lỗi 401 khó hiểu ở lần sinh đề đầu tiên. Phải tính SAU khi biết có endpoint
        // riêng hay không, vì endpoint riêng đổi luôn giao thức sang OpenAI.
        String newProvider = custom ? AiModelCatalog.OPENAI : AiModelCatalog.providerFor(newModel);
        boolean vendorChanged = !newProvider.equals(provider());
        String finalKey = clearKey || (vendorChanged && suppliedKey == null)
                ? null
                : (suppliedKey != null ? suppliedKey : apiKey);

        Instant now = Instant.now();
        try {
            AiSetting row = repo.findById(AiSetting.SINGLETON_ID).orElseGet(AiSetting::new);
            row.setId(AiSetting.SINGLETON_ID);
            row.setProvider(newProvider);          // lưu để tra cứu/audit; khi đọc vẫn suy từ model
            row.setModel(newModel);
            row.setBaseUrl(newBaseUrl);
            row.setApiKey(finalKey);
            row.setTimeoutSeconds(newTimeout);
            row.setUpdatedAt(now);
            row.setUpdatedBy(actor);
            repo.save(row);
        } catch (Exception e) {
            throw new IllegalStateException("Không lưu được cấu hình AI: " + e.getMessage(), e);
        }

        model = newModel;
        baseUrl = newBaseUrl;
        apiKey = finalKey;
        timeoutSeconds = newTimeout;
        updatedAt = now;
        updatedBy = actor;
        log.info("Cấu hình AI cập nhật: model={} ({}) (bởi {})", model(), provider(), actor);
        return describe();
    }

    /**
     * Chặn key dán vào bị dính rác ở đầu. Bẫy thật đã gặp: ô nhập là {@code type=password} nên
     * trình duyệt tự điền mật khẩu đã lưu vào đó, người dùng dán key nối tiếp phía sau và lưu
     * thành {@code <mật khẩu>sk-ant-…}. Hãng chỉ trả về "invalid x-api-key" — không tài nào đoán
     * ra là dư ký tự ở đầu, nên phải bắt ngay lúc lưu.
     *
     * <p>KHÔNG in phần thừa ra thông báo: nó thường chính là mật khẩu người dùng đã lưu.
     */
    /**
     * Gạt bỏ mọi ký tự KHÔNG PHẢI ASCII in được ra khỏi key.
     *
     * <p>Đây là nguyên nhân của cảnh "key đúng mà hãng vẫn báo API key is invalid": copy key từ
     * trang web, email hay khung chat rất hay dính ký tự VÔ HÌNH — zero-width space (U+200B),
     * BOM (U+FEFF), non-breaking space, soft hyphen. Mắt không thấy, độ dài nhìn cũng như thường,
     * và {@code Character.isWhitespace} KHÔNG bắt được U+200B/U+FEFF nên bộ kiểm cũ cho lọt.
     * Key của mọi hãng đều thuần ASCII in được, nên cắt sạch phần còn lại là an toàn.
     */
    private String sanitizeKey(String raw) {
        if (raw == null) return null;
        StringBuilder clean = new StringBuilder(raw.length());
        raw.codePoints().filter(c -> c > 0x20 && c < 0x7F).forEach(clean::appendCodePoint);
        return clean.toString();
    }

    private void validateSuppliedKey(String key) {
        if (key.chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException(
                    "API key không được chứa khoảng trắng hay ký tự xuống dòng. Hãy dán lại đúng chuỗi key.");
        for (String prefix : java.util.List.of("sk-ant-", "sk-proj-", "sk-", "AIza")) {
            int at = key.indexOf(prefix);
            if (at == 0) return;                       // đúng dạng key của hãng
            if (at > 0)
                throw new IllegalArgumentException("API key đang có " + at + " ký tự thừa ở đầu, trước \""
                        + prefix + "\" — thường là do trình duyệt tự điền mật khẩu đã lưu vào ô này. "
                        + "Hãy xoá sạch ô API key rồi dán lại.");
        }
        // Key của dịch vụ trung gian có thể không theo tiền tố nào — không chặn.
    }

    /**
     * Cảnh báo (KHÔNG chặn) khi key trông như của hãng khác với model đang chọn. Đây là cái bẫy
     * hay gặp nhất: dán key Claude nhưng quên đổi model, rồi nhận về lỗi 401 của OpenAI nói
     * "key OpenAI sai" — người dùng không thể đoán ra là do model.
     */
    private String keyWarning() {
        if (!hasApiKey()) return null;
        // Qua trung gian thì key là của dịch vụ đó, không việc gì phải giống tiền tố của hãng.
        if (hasCustomBaseUrl()) return null;
        String keyVendor = AiModelCatalog.vendorOfKey(apiKey);
        if (keyVendor == null || keyVendor.equals(provider())) return null;
        return "API key đang lưu trông giống key của " + AiModelCatalog.vendorLabel(keyVendor)
                + ", nhưng model đang chọn (" + model() + ") gọi tới "
                + AiModelCatalog.vendorLabel(provider())
                + ". Hãy đổi model cho khớp, hoặc dán key của đúng hãng đó.";
    }

    /** Che key khi trả về UI: giữ 4 ký tự cuối để người dùng nhận ra mình đang dùng key nào. */
    private String mask(String key) {
        if (key == null || key.isBlank()) return null;
        String trimmed = key.trim();
        if (trimmed.length() <= 8) return "••••";
        return trimmed.substring(0, 3) + "••••" + trimmed.substring(trimmed.length() - 4);
    }

    /** Chỉ dùng để đọc cấu hình MÔI TRƯỜNG cũ; giá trị lạ rơi về gemini như hành vi trước đây. */
    private String normalizeProvider(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return PROVIDERS.contains(value) ? value : AiModelCatalog.GEMINI;
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String text(Map<String, Object> body, String key, String fallback) {
        Object v = body == null ? null : body.get(key);
        if (v == null) return fallback;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? fallback : s;
    }

    private double number(Object v, double fallback) {
        if (v == null) return fallback;
        try {
            return v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
