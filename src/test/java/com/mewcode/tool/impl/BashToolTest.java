package com.mewcode.tool.impl;

import com.mewcode.tool.FileStateCache;
import com.mewcode.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BashToolTest {

    @TempDir Path tempDir;

    @Test
    void mergesOutputAndReportsExitCodeWithDestructiveMetadata() {
        var result = new BashTool().execute(context(Duration.ofSeconds(2)), Map.of(
                "command", "printf out; printf err >&2"));

        assertFalse(result.isError());
        assertTrue(result.content().contains("out"));
        assertTrue(result.content().contains("err"));
        assertTrue(result.content().contains("<exit_code>0</exit_code>"));
        assertTrue(result.metadata().containsKey("exitCode"));
        assertTrue(new BashTool().isDestructive());
        assertFalse(new BashTool().isReadOnly());
    }

    @Test
    void runsInTheSameProjectRootAsFileTools() {
        var result = new BashTool().execute(context(Duration.ofSeconds(2)), Map.of(
                "command", "pwd"));

        assertFalse(result.isError(), result.content());
        assertTrue(result.content().contains(tempDir.toAbsolutePath().normalize().toString()),
                result.content());
    }

    @Test
    void appliesCommandSpecificExitCodeSemanticsAndTimeout() throws Exception {
        var grepNoMatch = new BashTool().execute(context(Duration.ofSeconds(2)), Map.of(
                "command", "grep -q needle /dev/null"));
        assertFalse(grepNoMatch.isError());
        assertTrue(grepNoMatch.content().contains("<exit_code>1</exit_code>"));

        Path left = tempDir.resolve("left.txt");
        Path right = tempDir.resolve("right.txt");
        java.nio.file.Files.writeString(left, "left\n");
        java.nio.file.Files.writeString(right, "right\n");
        var diff = new BashTool().execute(context(Duration.ofSeconds(2)), Map.of(
                "command", "diff -q " + left + " " + right));
        assertFalse(diff.isError());
        assertTrue(diff.content().contains("<exit_code>1</exit_code>"));

        var find = new BashTool().execute(context(Duration.ofSeconds(2)), Map.of(
                "command", "find " + tempDir.resolve("missing") + " -maxdepth 0"));
        assertFalse(find.isError());
        assertTrue(find.content().contains("<exit_code>1</exit_code>"));

        var ordinaryFailure = new BashTool().execute(context(Duration.ofSeconds(2)), Map.of(
                "command", "sh -c 'exit 1'"));
        assertTrue(ordinaryFailure.isError());

        var timeout = new BashTool().execute(context(Duration.ofMillis(50)), Map.of(
                "command", "sleep 2"));
        assertTrue(timeout.isError());
        assertTrue(timeout.content().contains("超时"));
    }

    @Test
    void truncatesLongOutputAndKeepsTheLeadingPart() {
        var result = new BashTool().execute(context(Duration.ofSeconds(2)), Map.of(
                "command", "yes x | head -c 21001"));

        assertFalse(result.isError());
        assertTrue(result.content().contains("[output truncated"));
        assertEquals(true, result.metadata().get("truncated"));
        assertTrue(result.content().contains("<exit_code>0</exit_code>"));
    }

    private ToolExecutionContext context(Duration timeout) {
        return new ToolExecutionContext(tempDir, timeout, new FileStateCache());
    }
}
