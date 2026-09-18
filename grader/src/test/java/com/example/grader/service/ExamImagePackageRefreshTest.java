package com.example.grader.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExamImagePackageRefreshTest {
    @TempDir Path temp;

    @Test void successfulSameTagCommitRefreshesAvailableImagePackages() throws Exception {
        ExamService service = new ExamService();
        ReflectionTestUtils.setField(service, "baseImage", "grading-base:test");
        Path pubspec = temp.resolve("pubspec.yaml");
        Files.writeString(pubspec, "dependencies:\n  added: ^1.0.0\n");
        AtomicBoolean committed = new AtomicBoolean();
        try (var ignored = mockConstruction(ProcessBuilder.class, (builder, context) -> {
            Object argument = context.arguments().get(0);
            List<?> command = argument instanceof List<?> list ? list : List.of((String[]) argument);
            when(builder.environment()).thenReturn(new HashMap<>());
            when(builder.redirectErrorStream(true)).thenReturn(builder);
            when(builder.start()).thenAnswer(call -> {
                if (command.contains("commit")) committed.set(true);
                String output = command.contains("/app/pubspec.lock")
                        ? "packages:\n  flutter:\n    dependency: direct main\n  path:\n    dependency: direct main\n  intl:\n    dependency: direct main\n"
                            + (committed.get() ? "  added:\n    dependency: direct main\n" : "")
                        : "";
                return process(output);
            });
        })) {
            assertFalse(service.goiCoTrongAnhCham().contains("added"));
            ReflectionTestUtils.invokeMethod(service, "updateBaseImageInPlace", pubspec, new StringBuilder());
            assertTrue(service.goiCoTrongAnhCham().contains("added"), "Cùng tag ảnh mới vẫn phải đọc lại lock sau commit");
        }
    }

    private Process process(String output) {
        return new Process() {
            private final InputStream input = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
            @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
            @Override public InputStream getInputStream() { return input; }
            @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
            @Override public int waitFor() { return 0; }
            @Override public int exitValue() { return 0; }
            @Override public void destroy() { }
        };
    }
}
