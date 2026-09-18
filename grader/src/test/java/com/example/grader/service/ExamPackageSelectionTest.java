package com.example.grader.service;

import com.example.grader.repository.ExamRepository;
import com.example.grader.entity.Exam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;

class ExamPackageSelectionTest {
    @TempDir Path temp;
    private Exam exam;
    private final Map<String, Exam> records = new HashMap<>();

    private ExamService service() throws Exception {
        Path base = temp.resolve("grader-base");
        Files.createDirectories(base);
        Files.writeString(base.resolve("Dockerfile.base"), "FROM scratch\n");
        Files.writeString(base.resolve("pubspec.base.yaml"), """
                name: exam_project
                environment:
                  sdk: '>=3.0.0 <4.0.0'
                dependencies:
                  flutter:
                    sdk: flutter
                  sqflite: '>=2.4.2+1 <2.4.3'
                  custom:
                    git:
                      url: https://example.org/custom.git
                      ref: release-1
                  intl: ^0.20.2
                dev_dependencies:
                  flutter_test:
                    sdk: flutter
                  developer_tool: ^9.0.0
                flutter:
                  uses-material-design: true
                """);
        ExamService s = new ExamService();
        ReflectionTestUtils.setField(s, "templateDir", base.toString());
        ReflectionTestUtils.setField(s, "examsDir", temp.resolve("exams").toString());
        ExamRepository repo = mock(ExamRepository.class);
        exam = new Exam();
        exam.setExamId("PE");
        records.put("PE", exam);
        when(repo.findByExamId(anyString())).thenAnswer(call -> java.util.Optional.ofNullable(records.get(call.getArgument(0))));
        when(repo.save(any(Exam.class))).thenAnswer(call -> {
            Exam saved = call.getArgument(0);
            records.put(saved.getExamId(), saved);
            return saved;
        });
        ReflectionTestUtils.setField(s, "examRepository", repo);
        ReflectionTestUtils.setField(s, "baseImage", "grading-base-khong-ton-tai:test");
        return s;
    }

    private void selection(String json) throws Exception {
        exam.setAllowedPackages(new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get("allowed_packages").toString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deps(String yaml) {
        return (Map<String, Object>) ((Map<?, ?>) new Yaml().load(yaml)).get("dependencies");
    }

    @Test void savedStarterUsesSelectedBaseDependenciesAndPreservesSources() throws Exception {
        ExamService s = service();
        selection("{\"allowed_packages\":[\"sqflite\",\"custom\"]}");
        s.saveStarterFiles("PE", List.of(Map.of("name", "lib/main.dart", "content", "void main() {}"),
                Map.of("name", "pubspec.yaml", "content", "dependencies:\n  unapproved: any\n")));
        Map<String, Object> actual = deps(Files.readString(temp.resolve("exams/PE/handout/starter/pubspec.yaml")));
        assertEquals(Map.of("flutter", Map.of("sdk", "flutter"), "sqflite", ">=2.4.2+1 <2.4.3",
                "custom", Map.of("git", Map.of("url", "https://example.org/custom.git", "ref", "release-1"))), actual);
    }

    @Test void downloadingExistingStarterRefreshesHandWrittenPubspec() throws Exception {
        ExamService s = service();
        selection("{\"allowed_packages\":[]}");
        Path starter = temp.resolve("exams/PE/handout/starter");
        Files.createDirectories(starter);
        Files.writeString(starter.resolve("pubspec.yaml"), "dependencies:\n  intl: any\n");
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(s.zipStarter("PE")))) {
            String yaml = null;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("pubspec.yaml")) yaml = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            assertNotNull(yaml);
            assertEquals(Map.of("flutter", Map.of("sdk", "flutter")), deps(yaml));
        }
    }

    @Test void downloadingSolutionPreservesGoldenPubspecForHonestSync() throws Exception {
        ExamService s = service();
        selection("{\"allowed_packages\":[]}");
        Path solution = temp.resolve("exams/PE/handout/solution");
        Files.createDirectories(solution);
        String original = "dependencies:\n  unapproved: ^8.0.0\n";
        Files.writeString(solution.resolve("pubspec.yaml"), original);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(s.zipSolution("PE")))) {
            String yaml = null;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("pubspec.yaml")) yaml = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            assertEquals(original, yaml);
        }
    }

    @Test void savesSelectionInExamAndInvalidatesOldSyncOnlyWhenPackagesChange() throws Exception {
        ExamService s = service();
        exam.setStarterCheckRequired(true);
        exam.setStarterCheckedGoldenSha("previous-golden");
        exam.setStarterCheckedAt(java.time.Instant.parse("2026-09-17T00:00:00Z"));
        assertEquals(List.of("flutter", "sqflite"), s.saveExamAllowedPackages("PE", List.of("sqflite", "sqflite")));
        assertEquals("[\"flutter\",\"sqflite\"]", exam.getAllowedPackages());
        assertEquals(List.of("flutter", "sqflite"), s.getExamAllowedPackages("PE"));
        assertNull(exam.getStarterCheckedGoldenSha());
        assertNull(exam.getStarterCheckedAt());
        exam.setStarterCheckedGoldenSha("checked-again");
        s.saveExamAllowedPackages("PE", List.of("sqflite", "flutter"));
        assertEquals("checked-again", exam.getStarterCheckedGoldenSha());
        assertFalse(Files.exists(temp.resolve("exams/PE/allowed_packages.json")));
    }

    @Test void defaultIsBaseRuntimeDependenciesAndEmptySelectionIsOnlyFlutter() throws Exception {
        ExamService s = service();
        assertEquals(java.util.Set.of("flutter", "sqflite", "custom", "intl"), java.util.Set.copyOf(s.getExamAllowedPackages("PE")));
        assertEquals(List.of("flutter"), s.saveExamAllowedPackages("PE", List.of()));
        assertEquals(List.of("flutter"), s.getExamAllowedPackages("PE"));
    }

    @Test void rejectsUnknownPackageWithoutReplacingPreviousExamSelection() throws Exception {
        ExamService s = service();
        selection("{\"allowed_packages\":[\"sqflite\"]}");
        assertThrows(IllegalArgumentException.class, () -> s.saveExamAllowedPackages("PE", List.of("unapproved")));
        assertEquals(List.of("flutter", "sqflite"), s.getExamAllowedPackages("PE"));
    }

    @Test void cloningHandoutPreservesExamSelection() throws Exception {
        ExamService s = service();
        selection("{\"allowed_packages\":[\"sqflite\"]}");
        Path handout = temp.resolve("exams/PE/handout");
        Files.createDirectories(handout);
        Files.writeString(handout.resolve("de_bai.md"), "Đề mẫu");
        s.cloneExamHandout("PE", "PE_COPY", "Bản sao", "local");
        assertEquals(List.of("flutter", "sqflite"), s.getExamAllowedPackages("PE_COPY"));
        assertEquals("Đề mẫu", Files.readString(temp.resolve("exams/PE_COPY/handout/de_bai.md")));
    }

    @SuppressWarnings("unchecked")
    @Test void packageSelectorShowsOnlyBaseRuntimeDependenciesAsDirect() throws Exception {
        ExamService s = service();
        List<Map<String, Object>> direct = (List<Map<String, Object>>) s.goiChoManSoanDe().get("direct");
        assertEquals(java.util.Set.of("flutter", "sqflite", "custom", "intl"),
                direct.stream().map(p -> String.valueOf(p.get("name"))).collect(java.util.stream.Collectors.toSet()));
    }
}
