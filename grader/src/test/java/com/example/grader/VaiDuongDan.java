package com.example.grader;

import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;
import java.util.TreeSet;

/**
 * Tiện ích dùng chung cho hai bài kiểm bảng đường dẫn theo vai.
 *
 * <p>Tên lớp KHÔNG kết thúc bằng "Test" là cố ý: đây là đồ nghề, không phải bài kiểm.
 * Và hai bài kiểm kia phải là hai lớp TOP-LEVEL riêng — để chung một lớp ngoài rồi lồng
 * hai lớp tĩnh bên trong thì surefire không quét tới (tên file .class thành
 * {@code ...Test$BanNguoiCham.class}, không khớp mẫu {@code *Test.class}), và bộ kiểm thử
 * báo xanh trong khi phép tách vai chưa hề được kiểm. Đã dính đúng chuyện đó một lần.
 */
final class VaiDuongDan {

    private VaiDuongDan() {}

    /** Gom mọi mẫu đường dẫn mà context hiện tại đang phục vụ. */
    static Set<String> cua(RequestMappingHandlerMapping mapping) {
        Set<String> ra = new TreeSet<>();
        mapping.getHandlerMethods().keySet().forEach(info -> {
            if (info.getPathPatternsCondition() != null) {
                info.getPathPatternsCondition().getPatternValues().forEach(ra::add);
            } else if (info.getPatternsCondition() != null) {
                ra.addAll(info.getPatternsCondition().getPatterns());
            }
        });
        return ra;
    }

    static boolean coNhom(Set<String> tatCa, String goc) {
        return tatCa.stream().anyMatch(p -> p.startsWith(goc));
    }
}
