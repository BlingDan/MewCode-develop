package com.mewcode.instructions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 加载三层 MEWCODE.md，并安全展开文件内的 @include。 */
public final class InstructionLoader {

    private static final int MAX_INCLUDE_DEPTH = 5;
    private static final Pattern INCLUDE = Pattern.compile("^\\s*@include\\s+(.+?)\\s*$");

    private final Path projectRoot;
    private final Path userHome;

    public InstructionLoader(Path projectRoot, Path userHome) {
        this.projectRoot = absolute(projectRoot);
        this.userHome = absolute(userHome);
    }

    /** 按项目根、项目配置目录、用户目录的优先级拼接指令。 */
    public InstructionLoadResult load() {
        var diagnostics = new ArrayList<String>();
        var layers = new ArrayList<String>();
        loadLayer("项目根", projectRoot.resolve("MEWCODE.md"), projectRoot, layers, diagnostics);
        loadLayer(
                "项目 .mewcode",
                projectRoot.resolve(".mewcode/MEWCODE.md"),
                projectRoot,
                layers,
                diagnostics);
        Path userBoundary = userHome.resolve(".mewcode").normalize();
        loadLayer(
                "用户 .mewcode",
                userBoundary.resolve("MEWCODE.md"),
                userBoundary,
                layers,
                diagnostics);
        return new InstructionLoadResult(String.join("\n\n", layers), diagnostics);
    }

    private static void loadLayer(
            String layer,
            Path file,
            Path boundary,
            List<String> layers,
            List<String> diagnostics) {
        try {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
            String text = expand(file, boundary, 0, new HashSet<>());
            if (!text.isBlank()) layers.add(text);
        } catch (IOException | RuntimeException error) {
            diagnostics.add("MEWCODE.md " + layer + "加载失败：已跳过该层。");
        }
    }

    private static String expand(Path file, Path boundary, int depth, Set<Path> visited)
            throws IOException {
        Path boundaryPath = absolute(boundary);
        Path normalized = absolute(file);
        requireWithin(normalized, boundaryPath);
        if (!Files.exists(normalized)) {
            return missingMarker(normalized);
        }
        if (!Files.isRegularFile(normalized)) throw new InstructionFailure();

        Path realBoundary = realOrNormalized(boundaryPath);
        Path realFile = normalized.toRealPath();
        requireWithin(realFile, realBoundary);
        if (!visited.add(realFile)) throw new InstructionFailure();
        try {
            String content = Files.readString(realFile, StandardCharsets.UTF_8);
            String[] lines = content.split("\\R", -1);
            var expanded = new StringBuilder(content.length());
            for (int index = 0; index < lines.length; index++) {
                Matcher matcher = INCLUDE.matcher(lines[index]);
                if (matcher.matches()) {
                    if (depth >= MAX_INCLUDE_DEPTH) throw new InstructionFailure();
                    String includeText = stripQuotes(matcher.group(1).trim());
                    if (includeText.isEmpty()) throw new InstructionFailure();
                    Path include = realFile.getParent().resolve(includeText).normalize();
                    requireWithin(include, realBoundary);
                    expanded.append(expand(include, realBoundary, depth + 1, visited));
                } else {
                    expanded.append(lines[index]);
                }
                if (index < lines.length - 1) expanded.append('\n');
            }
            return expanded.toString();
        } finally {
            visited.remove(realFile);
        }
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    private static String missingMarker(Path file) {
        String safe = file.toString().replace("--", "- -");
        return "<!-- @include " + safe + " 未找到 -->";
    }

    private static void requireWithin(Path path, Path boundary) {
        Path normalized = absolute(path);
        if (!normalized.equals(boundary) && !normalized.startsWith(boundary)) {
            throw new InstructionFailure();
        }
    }

    private static Path realOrNormalized(Path path) {
        Path normalized = absolute(path);
        try {
            return normalized.toRealPath();
        } catch (IOException ignored) {
            return normalized;
        }
    }

    private static Path absolute(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static final class InstructionFailure extends RuntimeException {}
}
