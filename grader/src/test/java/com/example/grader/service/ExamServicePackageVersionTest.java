package com.example.grader.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExamServicePackageVersionTest {

    @Test
    void chiNhanRangBuocVersionMotDongAnToanChoPubspec() {
        assertEquals("^5.7.0", ExamService.validatePackageVersion(" ^5.7.0 "));
        assertEquals("^5.7.0", ExamService.validatePackageVersion("\"^5.7.0\""));
        assertEquals(">=2.1.0 <3.0.0", ExamService.validatePackageVersion(">=2.1.0 <3.0.0"));
        assertThrows(IllegalArgumentException.class,
                () -> ExamService.validatePackageVersion("^5.7.0\nother_package: ^1.0.0"));
        assertThrows(IllegalArgumentException.class,
                () -> ExamService.validatePackageVersion("^5.7.0 # sửa YAML"));
    }

    @Test
    void ghiRangBuocKhoangThanhYamlScalarHopLe() {
        String yaml = "dependencies:\n"
                + ExamService.formatDependencyLine("sqflite", ">=2.4.2+1 <2.4.3") + "\n"
                + ExamService.formatDependencyLine("dio", "^5.7.0") + "\n";

        assertEquals(java.util.Map.of("sqflite", ">=2.4.2+1 <2.4.3", "dio", "^5.7.0"),
                PubspecDependencies.parse(yaml));
    }
}
