package com.mewcode.tool.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件工具的绝对路径和项目根目录边界校验。
 *
 * <p>先校验规范化路径，再检查符号链接的真实路径，避免模型通过相对路径、{@code ..}
 * 或链接把读写范围带出项目根目录。</p>
 */
public final class PathGuard {

    private PathGuard() {
    }

    /** 校验路径格式、项目边界、目标存在性和符号链接逃逸。 */
    public static String validatePath(Object raw, Path projectRoot, boolean mustExist) {
        Path root = normalizeRoot(projectRoot);
        if (!(raw instanceof String value) || value.isBlank()) {
            return "参数 path 必须是非空绝对路径。当前项目根目录：" + root
                    + "。请传入类似 " + root + "/src/main.java 的路径。";
        }
        final Path path = parse(value);
        if (path == null) return "参数 path 不是合法路径。当前项目根目录：" + root + "。请传入合法的绝对路径。";
        String boundaryError = validateAbsolutePath(path, value, root, "path");
        if (boundaryError != null) return boundaryError;

        Path normalized = path.normalize();
        if (mustExist && !Files.exists(normalized)) {
            if (Files.notExists(normalized)) {
                return "文件或目录不存在：" + normalized + "。当前项目根目录：" + root
                        + "。请先用 Glob 检查路径。";
            }
            return "无法访问文件或目录：" + normalized + "。当前项目根目录：" + root
                    + "。请检查权限后重试，或改用可访问的路径。";
        }
        try {
            Path realRoot = root.toRealPath();
            if (Files.exists(normalized)) {
                if (!normalized.toRealPath().startsWith(realRoot)) {
                    return "路径通过符号链接逃逸项目根目录：" + normalized
                            + "。当前项目根目录：" + root + "。请改用项目根目录内的路径。";
                }
            } else {
                Path existingParent = normalized;
                while (existingParent != null && !Files.exists(existingParent)) {
                    existingParent = existingParent.getParent();
                }
                if (existingParent == null || !existingParent.toRealPath().startsWith(realRoot)) {
                    return "目标父目录不在项目根目录内：" + normalized
                            + "。当前项目根目录：" + root
                            + "。请改用项目根目录内的绝对路径。";
                }
            }
        } catch (IOException error) {
            return "无法检查路径权限或符号链接：" + normalized + "。当前项目根目录：" + root
                    + "。请确认路径可访问后重试。";
        }
        return null;
    }

    /** 校验写入类工具的路径参数；目标本身可以尚不存在。 */
    public static String validatePathArgument(Object raw, Path projectRoot) {
        Path root = normalizeRoot(projectRoot);
        if (!(raw instanceof String value) || value.isBlank()) {
            return "参数 path 必须是非空绝对路径。当前项目根目录：" + root
                    + "。请传入项目根目录内的绝对路径。";
        }
        Path path = parse(value);
        if (path == null) return "参数 path 不是合法路径。当前项目根目录：" + root + "。请传入合法的绝对路径。";
        return validateAbsolutePath(path, value, root, "path");
    }

    /** 校验搜索模式的绝对路径和项目边界，不执行文件系统访问。 */
    public static String validatePattern(Object raw, Path projectRoot) {
        Path root = normalizeRoot(projectRoot);
        if (!(raw instanceof String value) || value.isBlank()) {
            return "参数 pattern 必须是非空的绝对 glob 模式。当前项目根目录：" + root
                    + "。请传入项目根目录内的绝对模式。";
        }
        Path pattern = parse(value);
        if (pattern == null) {
            return "参数 pattern 不是合法路径模式。当前项目根目录：" + root
                    + "。请传入项目根目录内的绝对模式。";
        }
        return validateAbsolutePath(pattern, value, root, "pattern");
    }

    /** Glob 工具的执行前校验别名，语义上强调此时不访问文件系统。 */
    public static String validatePatternArgument(Object raw, Path projectRoot) {
        return validatePattern(raw, projectRoot);
    }

    private static Path parse(String value) {
        try {
            return Path.of(value);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static String validateAbsolutePath(Path path, String value, Path root, String parameterName) {
        if (!path.isAbsolute()) {
            Path suggestion = root.resolve(value).normalize();
            String expected = "pattern".equals(parameterName) ? "绝对 glob 模式" : "绝对路径";
            return "参数 " + parameterName + " 必须是" + expected + "。当前项目根目录：" + root
                    + "。请将相对值 " + value + " 解析为 " + suggestion + " 后重试。";
        }
        Path normalized = path.normalize();
        if (!normalized.startsWith(root)) {
            return "参数 " + parameterName + " 位于项目根目录之外：" + normalized
                    + "。当前项目根目录：" + root
                    + "。请改用项目根目录内的绝对路径或绝对 glob 模式。";
        }
        return null;
    }

    private static Path normalizeRoot(Path projectRoot) {
        return projectRoot.toAbsolutePath().normalize();
    }

    public static Path path(Object raw) {
        return Path.of((String) raw).normalize();
    }
}
