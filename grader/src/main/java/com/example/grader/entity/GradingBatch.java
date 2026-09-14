package com.example.grader.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "grading_batches",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_grading_batches_batch_id", columnNames = "batch_id")
        },
        indexes = {
                @Index(name = "idx_batch_exam", columnList = "exam_id")
        })
public class GradingBatch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "batch_id", nullable = false, length = 80)
    private String batchId;

    @Column(name = "exam_id", nullable = false, length = 50)
    private String examId;

    @ColumnDefault("0")
    @Column(name = "total_files")
    private Integer totalFiles = 0;

    @ColumnDefault("0")
    @Column(name = "done_count")
    private Integer doneCount = 0;

    @ColumnDefault("0")
    @Column(name = "error_count")
    private Integer errorCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private BatchStatus status;

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

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        if (status     == null) status     = BatchStatus.IN_PROGRESS;
        if (totalFiles == null) totalFiles = 0;
        if (doneCount  == null) doneCount  = 0;
        if (errorCount == null) errorCount = 0;
    }

}
