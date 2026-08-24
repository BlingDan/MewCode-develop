package com.mewcode.tool.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 文本文件读取和二进制检测。 */
public final class TextFileSupport {

    public static final int BINARY_PROBE_BYTES = 512;

    private TextFileSupport() {
    }

    public static boolean isBinary(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = input.readNBytes(BINARY_PROBE_BYTES);
            for (byte value : buffer) {
                if (value == 0) return true;
            }
            return false;
        }
    }

    public static List<String> readLines(Path path, int offset, int limit) throws IOException {
        var result = new ArrayList<String>(Math.min(limit, 256));
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (lineNumber < offset) continue;
                if (result.size() >= limit) break;
                result.add(lineNumber + "\t" + line);
            }
        }
        return result;
    }

    public static String readString(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
