package com.mewcode.tool.impl;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolExecutionContext;
import com.mewcode.tool.ToolResult;
import com.mewcode.tool.support.PathGuard;
import com.mewcode.tool.support.TextFileSupport;
import com.mewcode.tool.support.ToolInput;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 分页读取文本文件并为每行附加行号。 */
public final class ReadFileTool implements Tool {

    public static final int DEFAULT_LIMIT = 2_000;

    @Override
    public String name() {
        return "ReadFile";
    }

    @Override
    public String description() {
        return "读取项目根目录内的文本文件，返回带行号的内容。使用 offset 和 limit 分段读取大文件。";
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.FILE;
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "项目根目录内的绝对文件路径"),
                        "offset", Map.of("type", "integer", "minimum", 1, "default", 1),
                        "limit", Map.of("type", "integer", "minimum", 1, "default", DEFAULT_LIMIT)),
                "required", List.of("path"),
                "additionalProperties", false);
    }

    @Override
    public String validateInput(Map<String, Object> input) {
        String pathError = ToolInput.requireString(input, "path", " 请传入项目根目录内的绝对路径。");
        if (pathError != null) return pathError;
        try {
            if (!Path.of(ToolInput.requiredString(input, "path")).isAbsolute()) {
                return "参数 path 必须是绝对路径，请传入项目根目录内的绝对路径。";
            }
        } catch (RuntimeException error) {
            return "参数 path 不是合法路径，请传入合法的绝对路径。";
        }
        String numberError = validatePositiveInteger(input, "offset");
        if (numberError != null) return numberError;
        return validatePositiveInteger(input, "limit");
    }

    @Override
    public String validateInput(ToolExecutionContext context, Map<String, Object> input) {
        String pathError = ToolInput.requireString(input, "path", " 请传入项目根目录内的绝对路径。");
        if (pathError != null) return pathError;
        String boundaryError = PathGuard.validatePathArgument(input.get("path"), context.projectRoot());
        if (boundaryError != null) return boundaryError;
        String numberError = validatePositiveInteger(input, "offset");
        if (numberError != null) return numberError;
        return validatePositiveInteger(input, "limit");
    }

    /** 分页读取 UTF-8 文本；二进制文件和超出项目边界的路径会在执行前拒绝。 */
    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
        String pathError = PathGuard.validatePath(input.get("path"), context.projectRoot(), true);
        if (pathError != null) return ToolResult.error(pathError);
        Path path = PathGuard.path(input.get("path"));
        if (!Files.isRegularFile(path)) {
            if (Files.exists(path) && !Files.isReadable(path)) {
                return ToolResult.error("没有读取权限：" + path
                        + "。请检查文件权限或改用有权限的路径。");
            }
            return ToolResult.error("目标不是普通文件：" + path + "。请传入文件路径。 ");
        }
        try {
            if (TextFileSupport.isBinary(path)) {
                return ToolResult.error("拒绝读取二进制文件：" + path
                        + "。请改用 Bash 等命令行工具处理，不要把二进制内容直接返回给模型。");
            }
            int offset = valueOrDefault(input, "offset", 1);
            int limit = valueOrDefault(input, "limit", DEFAULT_LIMIT);
            List<String> lines = TextFileSupport.readLines(path, offset, limit);
            context.fileStateCache().recordRead(path);
            return ToolResult.success(String.join("\n", lines));
        } catch (AccessDeniedException error) {
            return ToolResult.error("没有读取权限：" + path + "。请检查文件权限或改用有权限的路径。");
        } catch (IOException error) {
            return ToolResult.error("读取文件失败：" + path + "。请确认文件存在且可访问后重试。");
        }
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public boolean isDestructive() {
        return false;
    }

    @Override
    public boolean isConcurrencySafe(Map<String, Object> input) {
        return true;
    }

    private static String validatePositiveInteger(Map<String, Object> input, String name) {
        if (input == null || !input.containsKey(name)) return null;
        Integer value = ToolInput.integer(input, name);
        return value == null || value < 1
                ? "参数 " + name + " 必须是大于 0 的整数。"
                : null;
    }

    private static int valueOrDefault(Map<String, Object> input, String name, int fallback) {
        Integer value = ToolInput.integer(input, name);
        return value == null ? fallback : value;
    }
}
