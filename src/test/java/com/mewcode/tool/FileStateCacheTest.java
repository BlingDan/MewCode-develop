package com.mewcode.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

class FileStateCacheTest {

    @TempDir Path tempDir;

    @Test
    void recordsReadStateAndDetectsExternalModification() throws Exception {
        Path file = tempDir.resolve("main.java");
        Files.writeString(file, "class Main {}\n");
        var cache = new FileStateCache();

        assertFalse(cache.wasRead(file));
        cache.recordRead(file);
        assertTrue(cache.wasRead(file));
        assertTrue(cache.canModify(file));

        Files.setLastModifiedTime(file,
                FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 10_000));
        assertFalse(cache.canModify(file));

        cache.update(file);
        assertTrue(cache.canModify(file));
        cache.clear(file);
        assertFalse(cache.wasRead(file));
    }
}
