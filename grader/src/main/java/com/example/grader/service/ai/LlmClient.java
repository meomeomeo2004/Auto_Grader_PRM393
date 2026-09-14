package com.example.grader.service.ai;

import java.util.List;

/**
 * Cổng gọi một mô hình ngôn ngữ. Mỗi nhà cung cấp là một bean cài interface này → đổi provider
 * chỉ là đổi cấu hình trên web, KHÔNG sửa code.
 *
 * <p>Tất cả client đều ép model trả về ĐÚNG MỘT object JSON (JSON mode) vì mọi thứ trợ lý sinh ra
 * (đề bài, key, testcase, bản vẽ) đều phải parse được và validate lại ở backend.
 */
public interface LlmClient {

    /** Khóa định danh provider, khớp giá trị lưu trong cấu hình ("gemini", "openai"). */
    String key();

    /** Gửi cả đoạn hội thoại và nhận về văn bản trả lời (là chuỗi JSON). */
    String chat(List<LlmMessage> messages) throws Exception;
}
