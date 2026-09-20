package com.example.grader.controller;

import com.example.grader.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class GoldenPublishReadinessTest {
    @Test
    void recordedInputValueCannotBeEditedThroughTheApi() {
        var authoring = mock(BehaviorAuthoringService.class);
        var controller = new BehaviorAuthoringController(authoring, mock(BehaviorSuiteMaterializer.class),
                mock(BehaviorArtifactService.class), mock(GoldenValidationService.class),
                mock(GoldenRuntimeService.class), mock(GoldenOracleCaptureService.class),
                mock(StaticRuleService.class), mock(ExamService.class));

        var response = controller.updateEvent("recording-1", 2, Map.of("value", "changed by hand"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(response.getBody())).contains("không thể sửa tay");
        verifyNoInteractions(authoring);
    }

    @ParameterizedTest
    @CsvSource({"REGISTERED,false,409", "BUILDING,false,409", "FAILED,true,409", "READY,false,409", "READY,true,200"})
    void runtimeReadinessGatesBothValidateAndPublish(String status, boolean available, int expectedStatus) {
        var authoring = mock(BehaviorAuthoringService.class);
        var materializer = mock(BehaviorSuiteMaterializer.class);
        var artifacts = mock(BehaviorArtifactService.class);
        var validation = mock(GoldenValidationService.class);
        var runtime = mock(GoldenRuntimeService.class);
        when(runtime.status("suite-1")).thenReturn(Map.of("status", status, "available", available));
        when(validation.validate("suite-1")).thenReturn(Map.of("status", "PASSED"));
        when(authoring.publish("suite-1")).thenReturn(Map.of("status", "PUBLISHED"));
        when(materializer.materialize("suite-1")).thenReturn(Map.of("exam_id", "DE_1"));
        var controller = new BehaviorAuthoringController(authoring, materializer, artifacts,
                validation, runtime, mock(GoldenOracleCaptureService.class),
                mock(StaticRuleService.class), mock(ExamService.class));

        assertThat(controller.validateGolden("suite-1").getStatusCode().value()).isEqualTo(expectedStatus);
        assertThat(controller.publish("suite-1").getStatusCode().value()).isEqualTo(expectedStatus);
        if (expectedStatus == 409) {
            verifyNoInteractions(validation, authoring, materializer);
        } else {
            verify(validation).validate("suite-1");
            verify(validation).requirePassed("suite-1");
            verify(authoring).publish("suite-1");
            verify(materializer).materialize("suite-1");
        }
    }
}
