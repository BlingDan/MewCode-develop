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

class EditFileToolTest {

    @TempDir Path tempDir;

    @Test
    void replacesOnlyAnExactlyOnceMatchingString() throws Exception {
        Path file = tempDir.resolve("config.txt");
        Files.writeString(file, "before\nneedle\nafter\n");
        var cache = new FileStateCache();
        cache.recordRead(file);

        var result = new EditFileTool().execute(
                new ToolExecutionContext(tempDir, Duration.ofSeconds(2), cache),
                Map.of("path", file.toString(), "old_string", "needle", "new_string", "changed"));

        assertFalse(result.isError());
        assertEquals("before\nchanged\nafter\n", Files.readString(file));
    }

    @Test
    void leavesFileUntouchedWhenMatchIsMissingOrAmbiguous() throws Exception {
        Path file = tempDir.resolve("config.txt");
        Files.writeString(file, "needle\nneedle\n");
        var cache = new FileStateCache();
        cache.recordRead(file);
        var context = new ToolExecutionContext(tempDir, Duration.ofSeconds(2), cache);
        var tool = new EditFileTool();

        var multiple = tool.execute(context, Map.of(
                "path", file.toString(), "old_string", "needle", "new_string", "changed"));
        assertTrue(multiple.isError());
        assertTrue(multiple.content().contains("多次"));
        assertEquals("needle\nneedle\n", Files.readString(file));

        var missing = tool.execute(context, Map.of(
                "path", file.toString(), "old_string", "absent", "new_string", "changed"));
        assertTrue(missing.isError());
        assertTrue(missing.content().contains("未找到"));
        assertEquals("needle\nneedle\n", Files.readString(file));
    }

    @Test
    void rejectsAnUnreadOrStaleFileBeforeLookingForReplacement() throws Exception {
        Path file = tempDir.resolve("stale.txt");
        Files.writeString(file, "needle\n");
        var tool = new EditFileTool();
        var cache = new FileStateCache();
        var context = new ToolExecutionContext(tempDir, Duration.ofSeconds(2), cache);

        var unread = tool.execute(context, Map.of(
                "path", file.toString(), "old_string", "needle", "new_string", "changed"));
        assertTrue(unread.isError());
        assertTrue(unread.content().contains("未读取"));

        cache.recordRead(file);
        Files.setLastModifiedTime(file,
                java.nio.file.attribute.FileTime.fromMillis(
                        Files.getLastModifiedTime(file).toMillis() + 10_000));
        var stale = tool.execute(context, Map.of(
                "path", file.toString(), "old_string", "needle", "new_string", "changed"));
        assertTrue(stale.isError());
        assertTrue(stale.content().contains("发生变化"));
        assertEquals("needle\n", Files.readString(file));
    }
}
