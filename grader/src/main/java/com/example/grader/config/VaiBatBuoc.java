package com.example.grader.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Chặn ngay lúc khởi động nếu không khai vai.
 *
 * <p>Vì sao phải chặn thay vì chọn một mặc định: cắt vai bằng profile nghĩa là controller của
 * vai không được bật sẽ không tồn tại. Chạy mà quên khai vai thì ứng dụng vẫn lên bình thường,
 * chỉ có điều KHÔNG endpoint nào tồn tại — giao diện mở được, bấm gì cũng 404, và người dùng
 * sẽ đi tìm lỗi ở mạng, ở cổng, ở CORS, chứ không ai nghĩ tới một biến môi trường bỏ trống.
 * Chết ngay với một câu nói rõ vẫn rẻ hơn nhiều.
 *
 * <p>Cũng KHÔNG mặc định về "bật cả hai vai". Đó là cấu hình duy nhất mà bộ chấm vừa xuất bản
 * hiện thẳng ở phần chấm bài, không đi qua gói bàn giao — tức là cấu hình duy nhất che được
 * một khâu bàn giao đang hỏng, trong khi đó lại là con đường mà cả hai người nhận đều phải đi.
 *
 * <p>Bộ kiểm thử được miễn: nhiều bài chỉ dựng service để kiểm logic, không cần controller nào.
 */
@Component
public class VaiBatBuoc implements InitializingBean {

    private final Environment moiTruong;

    public VaiBatBuoc(Environment moiTruong) {
        this.moiTruong = moiTruong;
    }

    @Override
    public void afterPropertiesSet() {
        if (moiTruong.acceptsProfiles(Profiles.of(Vai.KIEM_THU))) return;
        if (moiTruong.acceptsProfiles(Profiles.of(Vai.GIANG_VIEN, Vai.NGUOI_CHAM))) return;

        throw new IllegalStateException(
                "Chưa khai vai của bản đang chạy nên sẽ không có endpoint nào tồn tại."
                        + " Đặt biến môi trường GRADER_ROLE=gv (bản giảng viên) hoặc GRADER_ROLE=nc"
                        + " (bản người chấm). Chạy qua start-all.ps1 thì dùng tham số -Vai gv / -Vai nc,"
                        + " hoặc chạy bản đã cắt trong dist\\gv và dist\\nc.");
    }
}
