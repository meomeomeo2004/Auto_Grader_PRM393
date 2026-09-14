package com.example.grader.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Cấu hình LLM dùng cho trợ lý soạn đề/testcase: nhà cung cấp, API key, model, endpoint.
 *
 * <p>CHỈ có DUY NHẤT một hàng (id = {@link #SINGLETON_ID}) — giống
 * {@link GradingRuntimeSetting}: đây là cấu hình của cả hệ thống.
 *
 * <p>Bản AI trước đây bắt dán key vào {@code secret.properties} rồi khởi động lại backend nên
 * gần như không ai dùng được. Ở đây key nhập thẳng trên web và lưu DB; API trả về CHỈ dạng che
 * ({@code sk-…abcd}) chứ không bao giờ trả nguyên key.
 */
@Getter
@Setter
@Entity
@Table(name = "ai_settings")
public class AiSetting {

    public static final long SINGLETON_ID = 1L;

    @Id
    @Column(name = "id", nullable = false)
    private Long id = SINGLETON_ID;

    /** "gemini" hoặc "openai" (openai dùng được cho mọi endpoint tương thích: Ollama, OpenRouter, Groq…). */
    @Column(name = "provider", nullable = false, length = 40)
    private String provider;

    @Column(name = "model", length = 120)
    private String model;

    /** Chỉ dùng cho provider openai; để trống là dùng endpoint chính thức. */
    @Column(name = "base_url", length = 300)
    private String baseUrl;

    /** Key thật. Không bao giờ trả ra API — xem {@code AiSettingsService.describe}. */
    @Column(name = "api_key", length = 300)
    private String apiKey;

    @Column(name = "timeout_seconds")
    private Integer timeoutSeconds;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}
