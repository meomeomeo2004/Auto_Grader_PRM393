package com.example.grader.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "exams",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_exams_exam_id", columnNames = "exam_id")
        },
        indexes = {
                @Index(name = "idx_exam_status", columnList = "status"),
                @Index(name = "idx_exam_has_results", columnList = "has_results")
})
public class Exam {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "exam_id", nullable = false, length = 50)
    private String examId;

    @Column(name = "exam_name", length = 200)
    private String examName;

    @Lob
    @Column(name = "teacher_note")
    private String teacherNote;     // ghi chú/đề bài để đối chiếu khi xem kết quả

    @Column(name = "image_name", length = 100)
    private String imageName;

    @Column(name = "testcase_path", length = 500)
    private String testcasePath;   // đường dẫn testcase trên host để mount lúc chấm

    /**
     * Cờ "đã có bài chấm xong" (Counter/Flag Pattern). Bật true khi bài đầu tiên
     * của đề được chấm DONE → trang Thống kê chỉ cần SELECT … WHERE hasResults = true
     * (đọc O(log N) nhờ index) thay vì quét toàn bảng exam_results mỗi lần load.
     */
    @ColumnDefault("false")
    @Column(name = "has_results", nullable = false)
    private boolean hasResults;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private ExamStatus status;

    @Lob
    @Column(name = "allowed_packages")
    private String allowedPackages;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "created_at")
    private Instant createdAt;

    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Cấu hình testcase dạng template-instance; giữ riêng với skills_matrix đang được chấm. */
    @Lob
    @Column(name = "testcase_config_json", columnDefinition = "LONGTEXT")
    private String testcaseConfigJson;

    /** Phiên bản cấu hình testcase cuối cùng của đề. */
    @Column(name = "testcase_version")
    private Integer testcaseVersion;

    /**
     * Phiên làm việc của TRỢ LÝ AI cho bộ này: yêu cầu đã khai, đề bài, khung starter, app lời giải
     * mẫu đang soạn.
     *
     * <p>Lưu ở ĐÂY chứ không chỉ trong localStorage: đề soạn bằng AI mà mở lại trên máy khác (hay
     * sau khi dọn trình duyệt) là mất sạch phần AI đã làm, muốn nhờ AI sửa một chi tiết cũng phải
     * dựng lại toàn bộ từ đầu. Đây là bản NHÁP soạn thảo — không phải bộ testcase đang chấm.
     */
    @Lob
    @Column(name = "ai_author_json", columnDefinition = "LONGTEXT")
    private String aiAuthorJson;

    /** DRAFT/PUBLISHED; không dùng ExamStatus để không phá trạng thái READY cũ. */
    @Column(name = "testcase_status", length = 20)
    private String testcaseStatus;

    @Column(name = "testcase_published_at")
    private Instant testcasePublishedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        if (status == null) status = ExamStatus.BUILDING;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

}
