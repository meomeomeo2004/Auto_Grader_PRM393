package com.example.grader.service;

import com.example.grader.dto.ExamSetupResponse;
import com.example.grader.entity.Exam;
import com.example.grader.entity.ExamStatus;
import com.example.grader.repository.ExamRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExamServiceSandboxTest {

    @TempDir
    Path tempDir;

    @Test
    void exportsByteIdenticalZipForUnchangedTestcaseFiles() throws Exception {
        Path testcaseDir = tempDir.resolve("exams/PE_DETERMINISTIC/testcase");
        Files.createDirectories(testcaseDir);
        Files.writeString(testcaseDir.resolve("exam_test.dart"), "void main() {}\n");
        Files.writeString(testcaseDir.resolve("grader.dart"), "void main() {}\n");
        Files.writeString(testcaseDir.resolve("skills_matrix.json"), "{}\n");
        Files.writeString(testcaseDir.resolve("contract.json"), "{\"require_keys\":false}\n");

        ExamService service = serviceWithReadyBaseImage(mock(ExamRepository.class));
        byte[] first = service.zipTestcase("PE_DETERMINISTIC");
        byte[] second = service.zipTestcase("PE_DETERMINISTIC");

        assertArrayEquals(first, second);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(first))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                assertEquals(0L, entry.getTime(), entry.getName());
            }
        }
    }

    @Test
    void buildsSandboxFromExistingDirectoryWithoutCreatingZip() throws Exception {
        Path testcaseDir = tempDir.resolve("exams/PE_01/testcase");
        Files.createDirectories(testcaseDir);
        Files.writeString(testcaseDir.resolve("exam_test.dart"), "void main() {}\n");
        Files.writeString(testcaseDir.resolve("grader.dart"), "void main() {}\n");
        Files.writeString(testcaseDir.resolve("skills_matrix.json"), "{}\n");

        Exam exam = new Exam();
        exam.setExamId("PE_01");
        exam.setTestcasePath(testcaseDir.toString());
        exam.setTestcaseStatus("PUBLISHED");

        ExamRepository repository = mock(ExamRepository.class);
        when(repository.findByExamId("PE_01")).thenReturn(Optional.of(exam));

        ExamService service = serviceWithReadyBaseImage(repository);
        ExamSetupResponse response = service.buildSandbox("PE_01");

        assertEquals("READY", response.getStatus());
        assertEquals(ExamStatus.READY, exam.getStatus());
        assertEquals("grading-base:test", exam.getImageName());
        verify(repository).save(exam);
        try (var files = Files.walk(tempDir)) {
            assertFalse(files.anyMatch(path -> path.toString().endsWith(".zip")));
        }
    }

    @Test
    void rejectsDraftBeforeBuildingSandbox() {
        Exam exam = new Exam();
        exam.setExamId("PE_DRAFT");
        exam.setTestcaseStatus("DRAFT");

        ExamRepository repository = mock(ExamRepository.class);
        when(repository.findByExamId("PE_DRAFT")).thenReturn(Optional.of(exam));

        ExamService service = serviceWithReadyBaseImage(repository);
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service.buildSandbox("PE_DRAFT"));

        assertEquals(
                "Bộ testcase chưa được lưu chính thức. Hãy bấm Lưu trước khi Build Sandbox.",
                error.getMessage());
    }

    @Test
    void importsManualZipUsingFilenameAndKeepsOnlyExtractedFolder() throws Exception {
        ExamRepository repository = mock(ExamRepository.class);
        when(repository.findByExamId("DE_PE_01")).thenReturn(Optional.empty());
        when(repository.save(any(Exam.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ExamService service = serviceWithReadyBaseImage(repository);
        Map<String, Object> result = service.importManualTestcase(
                "Đề PE 01.zip", validTestcaseZip(), "local-user");

        assertEquals("DE_PE_01", result.get("examId"));
        assertEquals("Đề PE 01", result.get("examName"));
        assertEquals("BUILDING", result.get("status"));
        verify(repository).save(argThat(exam -> exam.getTestcaseConfigJson() == null
                && exam.getStatus() == ExamStatus.BUILDING
                && "PUBLISHED".equals(exam.getTestcaseStatus())));

        Path testcaseDir = tempDir.resolve("exams/DE_PE_01/testcase");
        assertEquals("void main() {}\n", Files.readString(testcaseDir.resolve("exam_test.dart")));
        assertEquals("void main() {}\n", Files.readString(testcaseDir.resolve("grader.dart")));
        assertEquals("{}\n", Files.readString(testcaseDir.resolve("skills_matrix.json")));
        try (var files = Files.walk(tempDir.resolve("exams/DE_PE_01"))) {
            assertFalse(files.anyMatch(path -> path.toString().endsWith(".zip")));
        }
    }

    private byte[] validTestcaseZip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> file : Map.of(
                    "exam_test.dart", "void main() {}\n",
                    "grader.dart", "void main() {}\n",
                    "skills_matrix.json", "{}\n").entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private ExamService serviceWithReadyBaseImage(ExamRepository repository) {
        ExamService service = new ExamService();
        ReflectionTestUtils.setField(service, "examRepository", repository);
        ReflectionTestUtils.setField(service, "templateDir", tempDir.resolve("grader-base").toString());
        ReflectionTestUtils.setField(service, "examsDir", tempDir.resolve("exams").toString());
        ReflectionTestUtils.setField(service, "baseImage", "grading-base:test");
        ReflectionTestUtils.setField(service, "runnerProcessTimeoutSeconds", 60);
        AtomicBoolean ready = (AtomicBoolean) ReflectionTestUtils.getField(service, "baseImageReady");
        ready.set(true);
        return service;
    }
}
