package com.example.grader.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PubspecDependenciesTest {
    @TempDir Path temp;

    @Test void readsOnlyRuntimeConstraintsWithoutResolvingVersions() {
        assertEquals(Map.of("flutter", Map.of("sdk", "flutter"), "path", "^1.9.0"),
                PubspecDependencies.parse("""
                    name: exam_project
                    dependencies:
                      flutter: {sdk: flutter}
                      path: '^1.9.0' # giu rang buoc
                    dev_dependencies:
                      flutter_lints: ^4.0.0
                    """));
    }

    @Test void missingPubspecIsUnknownAndCannotBecomeAnEmptyPolicy() {
        assertThrows(IllegalArgumentException.class, () -> PubspecDependencies.read(temp));
    }

    @Test void malformedAndDuplicateDependenciesCannotPass() {
        assertThrows(IllegalArgumentException.class, () -> PubspecDependencies.parse("dependencies: [path]"));
        assertThrows(IllegalArgumentException.class, () -> PubspecDependencies.parse("dependencies:\n  path: ^1.8.0\n  path: ^1.9.0\n"));
        assertThrows(IllegalArgumentException.class, () -> PubspecDependencies.parse("name: exam_project"));
    }

    @Test void readsProjectPubspecFromDisk() throws Exception {
        Files.writeString(temp.resolve("pubspec.yaml"), "dependencies:\n  path: any\n");
        assertEquals(Map.of("path", "any"), PubspecDependencies.read(temp));
    }
}
