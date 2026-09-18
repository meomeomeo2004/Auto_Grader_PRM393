package com.example.grader.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "exam_results",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_student_exam_mode", columnNames = {
                        "student_id", "exam_id", "mode"
                })
        },
        indexes = {
                @Index(name = "idx_batch",   columnList = "batch_id"),
                @Index(name = "idx_student", columnList = "student_id"),
                @Index(name = "idx_exam",    columnList = "exam_id"),
                @Index(name = "idx_status",  columnList = "status")
})
public class ExamResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "student_id", nullable = false, length = 20)
    private String studentId;

    @Column(name = "student_name", length = 100)
    private String studentName;

    @Column(name = "exam_id", nullable = false, length = 50)
    private String examId;

    @Column(name = "batch_id", length = 60)
    private String batchId;

    @Column(name = "score")
    private Float score;

    /**
     * Điểm của lần chấm TRƯỚC, chỉ ghi khi bấm "Chấm lại" (xem {@code BatchGradingService}).
     * Có nó thì màn hình Lịch sử mới so được lần chấm mới với lần cũ để cảnh báo lệch điểm —
     * luồng chấm lại vốn xoá {@code score} về null trước khi chấm nên số cũ sẽ mất hẳn.
     */
    @Column(name = "previous_score")
    private Float previousScore;

    /**
     * SHA-256 của file .zip bài nộp ĐÚNG LÚC ĐEM ĐI CHẤM.
     *
     * <p>Tính tại thời điểm chấm chứ không phải lúc xuất hồ sơ: zip trong {@code submissions/} bị
     * dọn sau 30 ngày, còn hồ sơ có thể xuất bất cứ lúc nào. Có chuỗi này thì đối chiếu được bản
     * code lưu ở kho ngoài với đúng bản đã chấm — trả lời được cáo buộc "bài em nộp khác bài
     * được chấm" mà không cần giữ file.
     */
    @Column(name = "submission_hash", length = 64)
    private String submissionHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private GradingStatus status;

    @ColumnDefault("'submit'")
    @Column(name = "mode", length = 10)
    private String mode;

    @Column(name = "details", columnDefinition = "LONGTEXT")
    private String details;

    @Column(name = "result_json", columnDefinition = "LONGTEXT")
    private String resultJson;     // JSON đầy đủ cho lịch sử, năng lực và xuất dữ liệu

    @Column(name = "error_log", columnDefinition = "LONGTEXT")
    private String errorLog;

    /** Mã/ngồn/giai đoạn lỗi ở cấp lượt chấm; không trộn với lỗi assertion của từng testcase. */
    @Column(name = "diagnostic_code", length = 80)
    private String diagnosticCode;

    @Column(name = "diagnostic_origin", length = 30)
    private String diagnosticOrigin;

    @Column(name = "diagnostic_stage", length = 40)
    private String diagnosticStage;

    @ColumnDefault("false")
    @Column(name = "requires_manual_review", nullable = false)
    private boolean requiresManualReview;

    // ── Chấm thủ công theo tiêu chí (ghi đè/ bổ sung cho điểm tự động) ──
    @Column(name = "manual_score")
    private Float manualScore;

    @Column(name = "manual_json", columnDefinition = "LONGTEXT")
    private String manualJson;   // JSON: breakdown điểm từng tiêu chí + nhận xét

    @Column(name = "manual_by", length = 100)
    private String manualBy;     // email GV chấm tay

    @Column(name = "manual_at")
    private Instant manualAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    // ── Mốc thời gian của LƯỢT CHẤM ─────────────────────────────────────────────
    // submittedAt là lúc bài vào hàng đợi, updatedAt là lần ghi cuối (chấm tay cũng đổi nó) —
    // cả hai đều KHÔNG trả lời được "máy chấm bài này mất bao lâu". Hai cột dưới mới trả lời được,
    // và đó là thứ bảng điểm xuất ra cần.
    @Column(name = "grading_started_at")
    private Instant gradingStartedAt;

    @Column(name = "grading_finished_at")
    private Instant gradingFinishedAt;

    @PrePersist
    protected void onCreate() {
        submittedAt = Instant.now();
        updatedAt   = Instant.now();
        if (status == null) status = GradingStatus.QUEUED;
        if (mode   == null) mode   = "submit";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

}
