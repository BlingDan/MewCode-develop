package com.mewcode.tool.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;

/** Glob 和 Grep 共用的搜索边界和结果规则。 */
public final class SearchSupport {

    public static final int MAX_RESULTS = 200;
    public static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "vendor", ".idea", "__pycache__", ".gradle", "build");

    private SearchSupport() {
    }

    public static boolean shouldSkipDirectory(Path path) {
        Path fileName = path.getFileName();
        return fileName != null && SKIP_DIRS.contains(fileName.toString());
    }

    public static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    public static FileTime modifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException error) {
            return FileTime.fromMillis(0);
        }
    }

    public static Comparator<Path> newestFirst() {
        return Comparator.comparing(SearchSupport::modifiedTime).reversed()
                .thenComparing(Path::toString);
    }

    public static Set<String> copySkipDirs() {
        return new LinkedHashSet<>(SKIP_DIRS);
    }
}
