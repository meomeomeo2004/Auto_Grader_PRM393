package com.example.grader.config;

/**
 * Hệ thống được giao cho HAI người khác nhau, mỗi người một bản chạy trên máy riêng:
 * giảng viên ra đề và người chấm chạy máy chấm. Không chia bằng đăng nhập — bản nào giao
 * cho ai thì bản đó chỉ bật phần của vai đó.
 *
 * Nhãn ở đây là profile của Spring, đặt qua biến môi trường GRADER_ROLE. Controller nào
 * không thuộc profile đang bật thì KHÔNG được nạp: gọi vào nhận 404 thật, chứ không phải
 * kiểu giấu nút trên giao diện mà API vẫn mở.
 *
 * Không gắn nhãn = cả hai bản đều có (ví dụ Thư viện chấm: hai bên đều phải chạy Docker).
 * GRADER_ROLE là BẮT BUỘC — không có bản nào thấy cả hai vai, xem {@link VaiBatBuoc}.
 *
 * Nhãn CHỈ gắn cho controller. Service thì để nguyên: nạp thừa một service không dùng chỉ
 * tốn chút bộ nhớ, còn gắn nhãn nhầm một service mà controller còn sống đang cần thì ứng
 * dụng chết ngay lúc khởi động.
 */
public final class Vai {

    /** Bản giảng viên: Bộ chấm Golden, Khung năng lực, Thư viện chấm. */
    public static final String GIANG_VIEN = "gv";

    /** Bản người chấm: Chấm tự động, Lịch sử chấm, Thư viện chấm. */
    public static final String NGUOI_CHAM = "nc";

    /**
     * Profile của bộ kiểm thử. Vài bài test chỉ cần nạp service chứ không cần controller nào,
     * nên chúng được miễn yêu cầu khai vai — xem {@link VaiBatBuoc}.
     */
    public static final String KIEM_THU = "test";

    private Vai() {}
}
