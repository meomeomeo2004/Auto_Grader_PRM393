package com.example.grader.dto;

import com.example.grader.entity.GradingOutcome;
import com.example.grader.entity.GradingStatus;

import java.time.Instant;

public record ExamHistoryRow(
        Long id,
        String studentId,
        String studentName,
        Float score,
        Float manualScore,
        GradingStatus status,
        String batchId,
        Instant submittedAt,
        Instant updatedAt,
        String details,
        String errorLog,
        String diagnosticCode,
        String diagnosticOrigin,
        String diagnosticStage,
        boolean requiresManualReview,
        Boolean hasJson,
        /** Máy bắt đầu chấm bài này lúc nào; null = chưa tới lượt. */
        Instant gradingStartedAt,
        /** Máy chấm xong lúc nào; null = chưa xong hoặc bị dừng giữa chừng. */
        Instant gradingFinishedAt,
        /**
         * JSON chấm tay (breakdown điểm từng tiêu chí). Cột LONGTEXT nhưng thực tế chỉ vài KB —
         * controller đọc để ĐẾM tiêu chí đạt rồi bỏ, không phát hành nguyên văn ra API.
         */
        String manualJson,
        /** Điểm của lần chấm trước (chỉ có sau khi bấm Chấm lại) — để cảnh báo lệch điểm. */
        Float previousScore,
        /** Kết luận cho người chấm — xem {@link GradingOutcome}. Suy từ status, không lưu DB. */
        GradingOutcome outcome
) {
    /** Ctor cho JPQL (20 cột); {@code outcome} là dẫn xuất — xem {@link ResultRow}. */
    public ExamHistoryRow(Long id, String studentId, String studentName, Float score, Float manualScore,
                          GradingStatus status, String batchId, Instant submittedAt, Instant updatedAt,
                          String details, String errorLog, String diagnosticCode, String diagnosticOrigin,
                          String diagnosticStage, boolean requiresManualReview, Boolean hasJson,
                          Instant gradingStartedAt, Instant gradingFinishedAt,
                          String manualJson, Float previousScore) {
        this(id, studentId, studentName, score, manualScore, status, batchId, submittedAt, updatedAt,
                details, errorLog, diagnosticCode, diagnosticOrigin, diagnosticStage,
                requiresManualReview, hasJson, gradingStartedAt, gradingFinishedAt,
                manualJson, previousScore, GradingOutcome.of(status));
    }
}
