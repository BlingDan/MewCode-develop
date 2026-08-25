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
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 写入文本文件，并对已有文件执行先读再写保护。 */
public final class WriteFileTool implements Tool {

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwxr-xr-x");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-r--r--");

    @Override
    public String name() {
        return "WriteFile";
    }

    @Override
    public String description() {
        return "写入项目根目录内的文本文件；新文件自动创建父目录，已有文件必须先成功读取。";
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
                        "content", Map.of("type", "string", "description", "要写入的完整文本内容")),
                "required", List.of("path", "content"),
                "additionalProperties", false);
    }

    @Override
    public String validateInput(Map<String, Object> input) {
        String pathError = ToolInput.requireString(input, "path", " 请传入项目根目录内的绝对路径。");
        if (pathError != null) return pathError;
        String contentError = ToolInput.requireString(input, "content", " 即使要写入空文件，也请传入空字符串。");
        if (contentError != null && input != null && input.get("content") instanceof String) {
            contentError = null;
        }
        if (contentError != null) return contentError;
        try {
            return Path.of(ToolInput.requiredString(input, "path")).isAbsolute()
                    ? null
                    : "参数 path 必须是绝对路径，请传入项目根目录内的绝对路径。";
        } catch (RuntimeException error) {
            return "参数 path 不是合法路径，请传入合法的绝对路径。";
        }
    }

    @Override
    public String validateInput(ToolExecutionContext context, Map<String, Object> input) {
        String pathError = ToolInput.requireString(input, "path", " 请传入项目根目录内的绝对路径。");
        if (pathError != null) return pathError;
        String contentError = ToolInput.requireString(input, "content", " 即使要写入空文件，也请传入空字符串。");
        if (contentError != null && input != null && input.get("content") instanceof String) {
            contentError = null;
        }
        if (contentError != null) return contentError;
        return PathGuard.validatePathArgument(input.get("path"), context.projectRoot());
    }

    /** 校验路径和已有文件快照后写入文本，并返回实际写入摘要。 */
    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
        String pathError = PathGuard.validatePath(input.get("path"), context.projectRoot(), false);
        if (pathError != null) return ToolResult.error(pathError);
        Path path = PathGuard.path(input.get("path"));
        String content = (String) input.get("content");
        try {
            if (Files.exists(path) && Files.isDirectory(path)) {
                return ToolResult.error("目标是目录而不是文件：" + path + "。请传入文件路径。");
            }
            boolean existing = Files.exists(path);
            if (existing) {
                if (TextFileSupport.isBinary(path)) {
                    return ToolResult.error("拒绝覆盖二进制文件：" + path
                            + "。请使用 Bash 处理二进制内容。");
                }
                if (!context.fileStateCache().wasRead(path)) {
                    return ToolResult.error("拒绝覆盖未读取的已有文件：" + path
                            + "。请先调用 ReadFile，确认内容后再重试。");
                }
                if (!context.fileStateCache().canModify(path)) {
                    return ToolResult.error("文件在读取后已发生变化：" + path
                            + "。为避免覆盖外部修改，请先重新调用 ReadFile 后再重试。");
                }
            }

            Path parent = path.getParent();
            if (parent != null) {
                List<Path> missingParents = missingParents(parent);
                Files.createDirectories(parent);
                for (Path missingParent : missingParents) {
                    setPosixPermissions(missingParent, DIRECTORY_PERMISSIONS);
                }
            }
            if (existing) {
                Files.writeString(path, content, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE);
            } else {
                Files.createFile(path);
                Files.writeString(path, content, StandardOpenOption.WRITE);
            }
            setPosixPermissions(path, FILE_PERMISSIONS);
            context.fileStateCache().update(path);
            return ToolResult.success("已写入文件：" + path);
        } catch (AccessDeniedException error) {
            return ToolResult.error("没有写入权限：" + path + "。请检查文件和父目录权限。");
        } catch (java.nio.file.FileAlreadyExistsException error) {
            return ToolResult.error("文件在写入过程中已被创建：" + path
                    + "。请重新读取文件状态后再重试。");
        } catch (UnsupportedOperationException error) {
            return ToolResult.error("当前文件系统不支持设置所需的文件权限：" + path + "。请检查运行环境。");
        } catch (IOException error) {
            return ToolResult.error("写入文件失败：" + path + "。请确认路径和权限后重试。");
        }
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public boolean isDestructive() {
        return false;
    }

    @Override
    public boolean isConcurrencySafe(Map<String, Object> input) {
        return false;
    }

    private static void setPosixPermissions(Path path, Set<PosixFilePermission> permissions)
            throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows 等非 POSIX 文件系统使用系统默认权限。
        }
    }

    private static List<Path> missingParents(Path parent) {
        var missing = new ArrayList<Path>();
        Path current = parent;
        while (current != null && !Files.exists(current)) {
            missing.add(current);
            current = current.getParent();
        }
        return missing;
    }
}
