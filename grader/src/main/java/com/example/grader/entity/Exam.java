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

    // CURRENT_TIMESTAMP(6) chứ không phải CURRENT_TIMESTAMP: Hibernate sinh cột này là
    // datetime(6), mà MySQL 8 đòi giá trị mặc định phải cùng độ chính xác — không thì báo
    // "Invalid default value" và BẢNG KHÔNG ĐƯỢC TẠO. Lỗi nằm im rất lâu vì hồi đó
    // mysql/init.sql dựng sẵn bảng bằng tay, Hibernate không phải tạo nên không ai thấy.
    // Nay init.sql đã bỏ: mọi schema đều do Hibernate dựng, sai độ chính xác là lộ ngay.
    @ColumnDefault("CURRENT_TIMESTAMP(6)")
    @Column(name = "created_at")
    private Instant createdAt;

    @ColumnDefault("CURRENT_TIMESTAMP(6)")
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Cấu hình testcase dạng template-instance; giữ riêng với skills_matrix đang được chấm. */
    @Lob
    @Column(name = "testcase_config_json", columnDefinition = "LONGTEXT")
    private String testcaseConfigJson;

    /** Phiên bản cấu hình testcase cuối cùng của đề. */
    @Column(name = "testcase_version")
    private Integer testcaseVersion;

    /** DRAFT/PUBLISHED; không dùng ExamStatus để không phá trạng thái READY cũ. */
    @Column(name = "testcase_status", length = 20)
    private String testcaseStatus;

    @Column(name = "testcase_published_at")
    private Instant testcasePublishedAt;

    /**
     * Đề publish từ khi có khâu "kiểm đồng bộ khung phát" thì phải qua khâu đó mới chấm được.
     * Bộ đề cũ giữ giá trị null/false nên được miễn trừ, không đột ngột biến mất khỏi phần chấm.
     */
    @Column(name = "starter_check_required")
    private Boolean starterCheckRequired;

    /**
     * sha256 của Golden Solution tại lượt kiểm đồng bộ ĐẠT gần nhất. Sửa Golden rồi publish lại
     * thì giá trị này lệch, kết quả cũ hết hiệu lực và đề quay về trạng thái chờ kiểm — nếu
     * không, lần kiểm đầu tiên thành con dấu vĩnh viễn.
     */
    @Column(name = "starter_checked_golden_sha", length = 80)
    private String starterCheckedGoldenSha;

    @Column(name = "starter_checked_at")
    private Instant starterCheckedAt;

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
