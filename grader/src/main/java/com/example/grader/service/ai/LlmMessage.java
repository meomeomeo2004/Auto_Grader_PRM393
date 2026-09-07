package com.example.grader.service.ai;

/**
 * Một lượt hội thoại gửi tới LLM. {@code role} ∈ {"system","user","assistant"} (chuẩn OpenAI).
 * Provider khác (Gemini) tự ánh xạ: assistant→model, system→systemInstruction.
 */
public record LlmMessage(String role, String content) {
    public static LlmMessage system(String c)     { return new LlmMessage("system", c); }
    public static LlmMessage user(String c)       { return new LlmMessage("user", c); }
    public static LlmMessage assistant(String c)  { return new LlmMessage("assistant", c); }
}
