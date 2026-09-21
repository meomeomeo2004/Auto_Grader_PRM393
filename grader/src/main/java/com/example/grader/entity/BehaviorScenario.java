package com.example.grader.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Scenario có thể thực thi, được abstract từ một recording của Golden App. */
@Getter
@Setter
@Entity
@Table(name = "behavior_scenarios", uniqueConstraints = {
        @UniqueConstraint(name = "uq_behavior_scenario_code", columnNames = {"suite_id", "scenario_code"})
}, indexes = {
        @Index(name = "idx_behavior_scenario_suite", columnList = "suite_id")
})
public class BehaviorScenario {

    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;

    @Column(name = "suite_id", length = 36, nullable = false)
    private String suiteId;

    @Column(name = "source_recording_id", length = 36)
    private String sourceRecordingId;

    @Column(name = "scenario_code", length = 100, nullable = false)
    private String scenarioCode;

    /**
     * Mã nhóm do giảng viên đặt (FILTER, UI, CRUD…). ĐỂ TRỐNG nghĩa là luồng không thuộc nhóm nào.
     *
     * <p>Trước 21/9/2026 nhóm được máy tự suy từ `ui_group` của bảng tick thành phần giao diện,
     * lấy tên màn làm nhãn. Hai màn cùng đặt tên "Màn hình" là gộp chung một rọ, kéo tiêu chí của
     * nhiều luồng khác nhau vào một dòng điểm — mà không ai yêu cầu gộp. Nay nhóm chỉ đến từ ô
     * này: người soạn gõ thì có nhóm, không gõ thì không.
     */
    @Column(name = "group_code", length = 50)
    private String groupCode;

    @Column(name = "name", length = 240, nullable = false)
    private String name;

    @Column(name = "skill_code", length = 100, nullable = false)
    private String skillCode;

    @Lob
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(name = "weight", nullable = false)
    private Double weight;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Lob
    @Column(name = "variables_json", columnDefinition = "LONGTEXT", nullable = false)
    private String variablesJson;

    @Lob
    @Column(name = "initial_state_json", columnDefinition = "LONGTEXT", nullable = false)
    private String initialStateJson;

    @Lob
    @Column(name = "steps_json", columnDefinition = "LONGTEXT", nullable = false)
    private String stepsJson;

    @Lob
    @Column(name = "checkpoints_json", columnDefinition = "LONGTEXT", nullable = false)
    private String checkpointsJson;

    @Lob
    @Column(name = "viewports_json", columnDefinition = "LONGTEXT", nullable = false)
    private String viewportsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (displayOrder == null) displayOrder = 0;
        if (skillCode == null || skillCode.isBlank()) skillCode = "UI_BUTTONS_SELECTION";
        if (weight == null) weight = 1.0;
        if (enabled == null) enabled = true;
        if (variablesJson == null) variablesJson = "{}";
        if (initialStateJson == null) initialStateJson = "{}";
        if (stepsJson == null) stepsJson = "[]";
        if (checkpointsJson == null) checkpointsJson = "[]";
        if (viewportsJson == null) viewportsJson = "[]";
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
