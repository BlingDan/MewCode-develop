package com.mewcode.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 当前进程内的先读再写状态缓存。 */
public final class FileStateCache {

    private final Map<Path, FileTime> readStates = new ConcurrentHashMap<>();

    public void recordRead(Path path) throws IOException {
        Path normalized = normalize(path);
        recordRead(normalized, Files.getLastModifiedTime(normalized));
    }

    public void recordRead(Path path, FileTime modifiedTime) {
        readStates.put(normalize(path), modifiedTime);
    }

    public boolean wasRead(Path path) {
        return readStates.containsKey(normalize(path));
    }

    public boolean canModify(Path path) {
        Path normalized = normalize(path);
        FileTime recorded = readStates.get(normalized);
        if (recorded == null || !Files.exists(normalized)) return false;
        try {
            return recorded.equals(Files.getLastModifiedTime(normalized));
        } catch (IOException error) {
            return false;
        }
    }

    public void update(Path path) throws IOException {
        Path normalized = normalize(path);
        if (Files.exists(normalized)) {
            readStates.put(normalized, Files.getLastModifiedTime(normalized));
        } else {
            readStates.remove(normalized);
        }
    }

    public void clear(Path path) {
        readStates.remove(normalize(path));
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
