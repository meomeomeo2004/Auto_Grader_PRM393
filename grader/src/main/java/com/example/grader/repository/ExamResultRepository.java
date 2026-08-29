package com.example.grader.repository;

import com.example.grader.entity.Exam;
import com.example.grader.dto.ExamHistoryRow;
import com.example.grader.entity.ExamResult;
import com.example.grader.entity.ExamStatus;
import com.example.grader.entity.GradingStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ExamResultRepository extends JpaRepository<ExamResult, Long> {
    // Lấy toàn bộ bài trong 1 batch
    List<ExamResult> findByBatchIdOrderByStudentId(String batchId);

    // NHẸ cho /batch/progress (poll 3s): bỏ cột LONGTEXT result_json/manual_json
    @Query("select new com.example.grader.dto.ResultRow(r.id, r.studentId, r.studentName, " +
           "r.status, r.score, r.details, r.errorLog, r.diagnosticCode, r.diagnosticOrigin, " +
           "r.diagnosticStage, r.requiresManualReview, r.gradingStartedAt, r.gradingFinishedAt) " +
           "from ExamResult r where r.batchId = :batchId order by r.studentId")
    List<com.example.grader.dto.ResultRow> findRowsByBatchId(@Param("batchId") String batchId);

    // NHẸ cho Thống kê: chỉ điểm/trạng thái/mốc thời gian (không kéo LONGTEXT)
    @Query("select new com.example.grader.dto.ResultStat(r.studentId, r.mode, r.score, r.status, " +
           "r.submittedAt, r.updatedAt) from ExamResult r")
    List<com.example.grader.dto.ResultStat> findAllStats();

    @Query("select new com.example.grader.dto.ResultStat(r.studentId, r.mode, r.score, r.status, " +
           "r.submittedAt, r.updatedAt) from ExamResult r where r.examId = :examId")
    List<com.example.grader.dto.ResultStat> findStatsByExamId(@Param("examId") String examId);

    @Query("""
        select count(r),
               count(distinct r.studentId),
               coalesce(sum(case when r.status = :done then 1 else 0 end), 0),
               coalesce(sum(case when r.status = :error then 1 else 0 end), 0),
               coalesce(sum(case when r.status = :queued then 1 else 0 end), 0),
               coalesce(sum(case when r.status = :grading then 1 else 0 end), 0),
               coalesce(sum(case when r.status = :manualReview then 1 else 0 end), 0),
               coalesce(sum(case when r.status = :done and r.score >= :passThreshold then 1 else 0 end), 0),
               coalesce(sum(case when r.status = :done and r.score < :passThreshold then 1 else 0 end), 0),
               coalesce(avg(case when r.status = :done and r.score is not null then r.score else null end), 0)
        from ExamResult r
        where (:examId is null or r.examId = :examId)
          and (r.mode is null or lower(r.mode) = 'submit')
    """)
    Object[] aggregateStats(@Param("examId") String examId,
                            @Param("done") GradingStatus done,
                            @Param("error") GradingStatus error,
                            @Param("queued") GradingStatus queued,
                            @Param("grading") GradingStatus grading,
                            @Param("manualReview") GradingStatus manualReview,
                            @Param("passThreshold") float passThreshold);

    @Query("""
        select coalesce(sum(case when r.status = :done and r.score is not null and r.score < 2 then 1 else 0 end), 0),
               coalesce(sum(case when r.status = :done and r.score >= 2 and r.score < 4 then 1 else 0 end), 0),
               coalesce(sum(case when r.status = :done and r.score >= 4 and r.score < 6 then 1 else 0 end), 0),
               coalesce(sum(case when r.status = :done and r.score >= 6 and r.score < 8 then 1 else 0 end), 0),
               coalesce(sum(case when r.status = :done and r.score >= 8 then 1 else 0 end), 0)
        from ExamResult r
        where (:examId is null or r.examId = :examId)
          and (r.mode is null or lower(r.mode) = 'submit')
    """)
    Object[] scoreBuckets(@Param("examId") String examId, @Param("done") GradingStatus done);

    @Query("select new com.example.grader.dto.ResultStat(r.studentId, r.mode, r.score, r.status, " +
           "r.submittedAt, r.updatedAt) from ExamResult r " +
           "where (:examId is null or r.examId = :examId) " +
           "and (r.mode is null or lower(r.mode) = 'submit') " +
           "and (r.updatedAt >= :since or (r.updatedAt is null and r.submittedAt >= :since))")
    List<com.example.grader.dto.ResultStat> findTrendStatsSince(@Param("examId") String examId,
                                                                 @Param("since") Instant since);

    // Lấy toàn bộ bài của 1 đề thi — dùng cho thống kê
    List<ExamResult> findByExamId(String examId);

    /** Dọn lịch sử chấm khi xoá đề — không xoá thì trang Lịch sử vẫn còn nguyên bảng điểm cũ. */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    long deleteByExamId(String examId);

    // Lịch sử chấm theo đề (chỉ bài nộp chính thức), mới nhất lên đầu
    List<ExamResult> findByExamIdAndModeOrderByUpdatedAtDesc(String examId, String mode);

    // Projection nhẹ cho trang lịch sử: không kéo result_json LONGTEXT.
    @Query("""
        select new com.example.grader.dto.ExamHistoryRow(
            r.id, r.studentId, r.studentName, r.score, r.manualScore, r.status,
            r.batchId, r.submittedAt, r.updatedAt, r.details, r.errorLog,
            r.diagnosticCode, r.diagnosticOrigin, r.diagnosticStage, r.requiresManualReview,
            case when r.resultJson is null then false else true end,
            r.gradingStartedAt, r.gradingFinishedAt,
            r.manualJson, r.previousScore,
            case when r.feedbackJson is null then false else true end
        )
        from ExamResult r
        where r.examId = :examId and r.mode = :mode
        order by r.updatedAt desc
    """)
    List<ExamHistoryRow> findHistoryRowsByExamIdAndMode(@Param("examId") String examId,
                                                        @Param("mode") String mode);

    // Tìm kiếm (thanh search header): theo mã SV / tên SV / mã đề
    @Query("SELECT r FROM ExamResult r WHERE (r.mode IS NULL OR r.mode = 'submit') AND (" +
           "LOWER(r.studentId)   LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(r.studentName) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(r.examId)      LIKE LOWER(CONCAT('%', :q, '%'))) ORDER BY r.updatedAt DESC")
    List<ExamResult> searchSubmissions(@Param("q") String q, Pageable pageable);

    // Danh sách examId đã từng được chấm — dùng để lọc dropdown thống kê
    @Query("select distinct r.examId from ExamResult r where r.examId is not null")
    List<String> findDistinctExamIds();

    // Danh sách mã SV đã NỘP của 1 đề (nhẹ — không kéo resultJson). Dùng cho trang Kho đề + chấm lại cả đề.
    @Query("select distinct r.studentId from ExamResult r " +
           "where r.examId = :examId and (r.mode is null or r.mode = 'submit')")
    List<String> findSubmitStudentIds(@Param("examId") String examId);

    // Tìm 1 bài cụ thể trong batch
    Optional<ExamResult> findByStudentIdAndBatchId(String studentId, String batchId);

    // Đếm theo trạng thái — dùng cho progress bar
    long countByBatchIdAndStatus(String batchId, GradingStatus status);

    // Job đang chờ/đang chấm — để khôi phục hàng đợi sau restart
    List<ExamResult> findByStatusIn(java.util.Collection<GradingStatus> statuses);

    // Kiểm tra đã nộp chính thức chưa
    boolean existsByStudentIdAndExamIdAndMode(String studentId, String examId, String mode);

    // Tìm bản ghi cũ để ghi đè khi chấm lại (cùng SV + đề + mode)
    Optional<ExamResult> findByStudentIdAndExamIdAndMode(String studentId, String examId, String mode);
}
