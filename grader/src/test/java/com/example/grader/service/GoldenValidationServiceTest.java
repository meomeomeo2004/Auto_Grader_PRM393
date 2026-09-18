package com.example.grader.service;

import com.example.grader.entity.GoldenValidationRun;
import com.example.grader.entity.GoldenValidationStatus;
import com.example.grader.repository.GoldenValidationRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoldenValidationServiceTest {
    private GoldenValidationService validation;
    private Map<String, Object> golden;
    private Map<String, Object> plan;
    private GoldenValidationRun passed;

    @BeforeEach
    void setUp() {
        var authoring = mock(BehaviorAuthoringService.class);
        var artifacts = mock(BehaviorArtifactService.class);
        var runs = mock(GoldenValidationRunRepository.class);
        golden = new LinkedHashMap<>(Map.of("artifact_sha256", "golden-v1", "status", "REGISTERED",
                "runtime_url", "", "metadata", Map.of(), "created_at", "2026-09-18", "updated_at", "before-build"));
        plan = new LinkedHashMap<>(Map.of("golden_app", golden, "suite", Map.of("id", "suite-1"),
                "scenarios", List.of(Map.of("scenario_code", "ADD", "checkpoints", List.of(Map.of("weight", 100))))));
        when(authoring.previewExecutionPlan("suite-1")).thenAnswer(invocation -> plan);
        when(artifacts.activeManifest("suite-1")).thenReturn(Map.of("GOLDEN_SOLUTION", Map.of("sha256", "golden-v1")));
        validation = new GoldenValidationService(authoring, artifacts, mock(BehaviorSuiteMaterializer.class), runs);
        passed = new GoldenValidationRun();
        passed.setStatus(GoldenValidationStatus.PASSED);
        passed.setTotalCheckpoints(79);
        passed.setPassedCheckpoints(79);
        passed.setPlanSha256(ReflectionTestUtils.invokeMethod(validation, "currentPlanSha", "suite-1"));
        when(runs.findFirstBySuiteIdOrderByCreatedAtDesc("suite-1")).thenReturn(Optional.of(passed));
    }

    @Test
    void rebuildingTheSameGoldenKeepsPassedPreflightCurrent() {
        golden.put("status", "READY");
        golden.put("runtime_url", "/api/behavior-authoring/runtime/suite-1/");
        golden.put("updated_at", "after-build");
        golden.put("metadata", Map.of("build_state", "cache-hit", "built_at", "after-build"));

        assertThat(validation.latest("suite-1")).containsEntry("current", true);
        assertThatCode(() -> validation.requirePassed("suite-1")).doesNotThrowAnyException();
    }

    @Test
    void changedCheckpointsStillRequireAnotherPreflight() {
        plan.put("scenarios", List.of(Map.of("scenario_code", "ADD", "checkpoints", List.of(Map.of("weight", 50)))));
        assertThat(validation.latest("suite-1")).containsEntry("current", false);
        assertThatThrownBy(() -> validation.requirePassed("suite-1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void publishingWithoutChangingThePlanKeepsItsPreflightCurrent() {
        plan.put("suite", Map.of("id", "suite-1", "status", "PUBLISHED", "revision", 2,
                "published_at", "after-preflight", "updated_at", "after-publish"));
        assertThat(validation.latest("suite-1")).containsEntry("current", true);
        assertThatCode(() -> validation.requirePassed("suite-1")).doesNotThrowAnyException();
    }

    @Test
    void changedGoldenStillRequiresAnotherPreflight() {
        golden.put("artifact_sha256", "golden-v2");
        assertThat(validation.latest("suite-1")).containsEntry("current", false);
        assertThatThrownBy(() -> validation.requirePassed("suite-1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void passedStatusCannotPublishAnIncompleteCheckpointCount() {
        passed.setPassedCheckpoints(78);
        assertThatThrownBy(() -> validation.requirePassed("suite-1")).isInstanceOf(IllegalStateException.class);
    }
}
