package com.mewcode.tool.impl;

import com.mewcode.tool.FileStateCache;
import com.mewcode.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlobAndGrepToolTest {

    @TempDir Path tempDir;

    @Test
    void globRecursesReturnsRelativePathsAndSkipsNoiseDirectories() throws Exception {
        Files.createDirectories(tempDir.resolve("src/nested"));
        Files.createDirectories(tempDir.resolve(".git/objects"));
        Files.createDirectories(tempDir.resolve("node_modules/pkg"));
        Files.writeString(tempDir.resolve("src/nested/Main.java"), "class Main {}\n");
        Files.writeString(tempDir.resolve(".git/objects/Hidden.java"), "hidden\n");
        Files.writeString(tempDir.resolve("node_modules/pkg/Dependency.java"), "dependency\n");

        var result = new GlobTool().execute(context(), Map.of(
                "pattern", tempDir.resolve("**/*.java").toString()));

        assertFalse(result.isError(), result.content());
        assertEquals("src/nested/Main.java", result.content());
        assertFalse(result.content().contains("Hidden.java"));
        assertFalse(result.content().contains("Dependency.java"));
    }

    @Test
    void grepReturnsRelativePathLineAndSkipsBinaryFiles() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.createDirectories(tempDir.resolve("vendor"));
        Files.writeString(tempDir.resolve("src/Main.java"), "class Main {}\nneedle here\n");
        Files.writeString(tempDir.resolve("vendor/Hidden.java"), "needle hidden\n");
        Files.write(tempDir.resolve("src/image.bin"), new byte[]{'n', 0, 'e'});

        var result = new GrepTool().execute(context(), Map.of(
                "path", tempDir.toString(), "pattern", "needle", "include", "*.java"));

        assertFalse(result.isError());
        assertEquals("src/Main.java:2\tneedle here", result.content());
        assertEquals(0, result.metadata().get("skippedBinaryCount"));

        var allFiles = new GrepTool().execute(context(), Map.of(
                "path", tempDir.toString(), "pattern", "needle"));
        assertEquals(1, allFiles.metadata().get("skippedBinaryCount"));
        assertFalse(allFiles.content().contains("Hidden.java"));
    }

    @Test
    void capsGlobAndGrepResultsAtTwoHundred() throws Exception {
        for (int i = 0; i < 205; i++) {
            Files.writeString(tempDir.resolve("file" + i + ".txt"), "needle " + i + "\n");
        }

        var glob = new GlobTool().execute(context(), Map.of(
                "pattern", tempDir.resolve("*.txt").toString()));
        assertFalse(glob.isError(), glob.content());
        assertEquals(200, glob.content().lines().count());

        var grep = new GrepTool().execute(context(), Map.of(
                "path", tempDir.toString(), "pattern", "needle", "include", "*.txt"));
        assertFalse(grep.isError(), grep.content());
        assertEquals(200, grep.content().lines().count());
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext(tempDir, Duration.ofSeconds(2), new FileStateCache());
    }
}
