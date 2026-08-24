package com.mewcode.tool.impl;

import com.mewcode.tool.FileStateCache;
import com.mewcode.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WriteFileToolTest {

    @TempDir Path tempDir;

    @Test
    void createsParentDirectoriesAndUsesStandardPosixPermissions() throws Exception {
        Path file = tempDir.resolve("src/nested/Main.java");
        var result = new WriteFileTool().execute(context(), Map.of(
                "path", file.toString(), "content", "class Main {}\n"));

        assertFalse(result.isError());
        assertEquals("class Main {}\n", Files.readString(file));
        try {
            assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"),
                    Files.getPosixFilePermissions(file.getParent()));
            assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"),
                    Files.getPosixFilePermissions(file.getParent().getParent()));
            assertEquals(PosixFilePermissions.fromString("rw-r--r--"),
                    Files.getPosixFilePermissions(file));
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统没有该权限视图。
        }
    }

    @Test
    void existingFileRequiresReadAndUnchangedModificationTime() throws Exception {
        Path file = tempDir.resolve("existing.txt");
        Files.writeString(file, "old");
        var cache = new FileStateCache();
        var context = new ToolExecutionContext(tempDir, Duration.ofSeconds(2), cache);
        var tool = new WriteFileTool();

        var withoutRead = tool.execute(context, Map.of("path", file.toString(), "content", "new"));
        assertTrue(withoutRead.isError());
        assertEquals("old", Files.readString(file));

        cache.recordRead(file);
        var written = tool.execute(context, Map.of("path", file.toString(), "content", "new"));
        assertFalse(written.isError());
        assertEquals("new", Files.readString(file));

        cache.recordRead(file);
        Files.setLastModifiedTime(file,
                FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 10_000));
        var stale = tool.execute(context, Map.of("path", file.toString(), "content", "stale"));
        assertTrue(stale.isError());
        assertTrue(stale.content().contains("发生变化"));
        assertEquals("new", Files.readString(file));
    }

    @Test
    void acceptsAnEmptyFileContent() {
        var tool = new WriteFileTool();
        assertNull(tool.validateInput(Map.of("path", tempDir.resolve("empty.txt").toString(),
                "content", "")));
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext(tempDir, Duration.ofSeconds(2), new FileStateCache());
    }
}
