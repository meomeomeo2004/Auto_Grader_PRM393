package com.example.grader.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Bộ chấm hành vi. File Dart theo từng đề không còn là nguồn sự thật của bộ này. */
@Getter
@Setter
@Entity
@Table(name = "behavior_suites", uniqueConstraints = {
        @UniqueConstraint(name = "uq_behavior_suite_code", columnNames = "suite_code")
}, indexes = {
        @Index(name = "idx_behavior_suite_exam", columnList = "exam_id"),
        @Index(name = "idx_behavior_suite_status", columnList = "status")
})
public class BehaviorSuite {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "suite_code", length = 80, nullable = false)
    private String suiteCode;

    @Column(name = "exam_id", length = 50)
    private String examId;

    @Column(name = "golden_app_id", length = 36, nullable = false)
    private String goldenAppId;

    @Column(name = "name", length = 200, nullable = false)
    private String name;

    @Lob
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "schema_version", length = 20, nullable = false)
    private String schemaVersion;

    @Column(name = "revision", nullable = false)
    private Integer revision;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private BehaviorSuiteStatus status;

    /** Semantic/action contract công bố cho sinh viên; không chứa dữ liệu oracle bí mật. */
    @Lob
    @Column(name = "public_contract_json", columnDefinition = "LONGTEXT")
    private String publicContractJson;

    /** Schema, bảng/cột và quy tắc chuẩn hoá DB mà observer được phép đối chiếu. */
    @Lob
    @Column(name = "database_contract_json", columnDefinition = "LONGTEXT")
    private String databaseContractJson;

    @Lob
    @Column(name = "runtime_config_json", columnDefinition = "LONGTEXT")
    private String runtimeConfigJson;

    /**
     * Luật chấm tĩnh (nhóm Kiến trúc, lint) dạng {"rules":[...]} — nguồn sự thật để
     * materializer sinh dòng `runner: STATIC_ANALYSIS`; xem StaticRuleService.
     */
    @Lob
    @Column(name = "static_rules_json", columnDefinition = "LONGTEXT")
    private String staticRulesJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (schemaVersion == null || schemaVersion.isBlank()) schemaVersion = "1.0";
        if (revision == null) revision = 1;
        if (status == null) status = BehaviorSuiteStatus.DRAFT;
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
