package com.mewcode.tool.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PathGuardTest {

    @TempDir Path projectRoot;

    @Test
    void relativeFilePathReportsRootAndAbsoluteSuggestion() {
        String error = PathGuard.validatePathArgument(
                ".trae/skills/mew-spec/SKILL.md", projectRoot);

        assertNotNull(error);
        assertTrue(error.contains("必须是绝对路径"), error);
        assertTrue(error.contains(projectRoot.toAbsolutePath().normalize().toString()), error);
        assertTrue(error.contains(projectRoot.resolve(".trae/skills/mew-spec/SKILL.md")
                .toAbsolutePath().normalize().toString()), error);
    }

    @Test
    void relativeGlobReportsRootAndAbsoluteSuggestion() {
        String error = PathGuard.validatePatternArgument("**/*.java", projectRoot);

        assertNotNull(error);
        assertTrue(error.contains("pattern"), error);
        assertTrue(error.contains("绝对"), error);
        assertTrue(error.contains(projectRoot.resolve("**/*.java")
                .toAbsolutePath().normalize().toString()), error);
    }

    @Test
    void externalAbsolutePathIsRejectedWithoutRewriting() {
        Path outside = projectRoot.resolveSibling("outside-file.txt").toAbsolutePath().normalize();
        String error = PathGuard.validatePathArgument(outside.toString(), projectRoot);

        assertNotNull(error);
        assertTrue(error.contains(outside.toString()), error);
        assertTrue(error.contains("项目根目录之外"), error);
        assertTrue(error.contains(projectRoot.toAbsolutePath().normalize().toString()), error);
    }

    @Test
    void externalAbsoluteGlobAndParentEscapeAreRejected() {
        Path outside = projectRoot.resolveSibling("outside").toAbsolutePath().normalize();
        String patternError = PathGuard.validatePatternArgument(outside.resolve("**/*.java").toString(), projectRoot);
        String parentError = PathGuard.validatePathArgument(
                projectRoot.resolve("../outside/file.txt").toString(), projectRoot);

        assertNotNull(patternError);
        assertTrue(patternError.contains("项目根目录之外"), patternError);
        assertNotNull(parentError);
        assertTrue(parentError.contains("项目根目录之外"), parentError);
    }

    @Test
    void symlinkEscapingRootIsRejectedAtExecutionValidation() throws Exception {
        Path outside = projectRoot.resolveSibling("outside-dir");
        Files.createDirectories(outside);
        Path link = projectRoot.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException error) {
            return;
        }
        String error = PathGuard.validatePath(link.resolve("file.txt").toString(), projectRoot, false);

        assertNotNull(error);
        assertTrue(error.contains("符号链接") || error.contains("父目录"), error);
    }
}
