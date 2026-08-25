package com.mewcode.tool.impl;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolExecutionContext;
import com.mewcode.tool.ToolPromptRules;
import com.mewcode.tool.ToolResult;
import com.mewcode.tool.support.PathGuard;
import com.mewcode.tool.support.TextFileSupport;
import com.mewcode.tool.support.ToolInput;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/** 对文本文件执行大小写敏感的原文唯一匹配替换。 */
public final class EditFileTool implements Tool {

  @Override
  public String name() {
    return "EditFile";
  }

  @Override
  public String description() {
    return "将文件中的 old_string 唯一精确替换为 new_string；" + ToolPromptRules.editingRule();
  }

  @Override
  public ToolCategory category() {
    return ToolCategory.FILE;
  }

  @Override
  public Map<String, Object> inputSchema() {
    return Map.of(
        "type",
        "object",
        "properties",
        Map.of(
            "path", Map.of("type", "string", "description", "项目根目录内的绝对文件路径"),
            "old_string", Map.of("type", "string", "description", "必须唯一出现的原文"),
            "new_string", Map.of("type", "string", "description", "替换后的文本")),
        "required",
        List.of("path", "old_string", "new_string"),
        "additionalProperties",
        false);
  }

  @Override
  public String validateInput(Map<String, Object> input) {
    String pathError = ToolInput.requireString(input, "path", " 请传入项目根目录内的绝对路径。");
    if (pathError != null) return pathError;
    if (input == null || !(input.get("old_string") instanceof String)) {
      return "参数 old_string 必须是字符串。请传入要精确匹配的原文。";
    }
    if (input == null || !(input.get("new_string") instanceof String)) {
      return "参数 new_string 必须是字符串。请传入替换后的文本。";
    }
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
    if (input == null || !(input.get("old_string") instanceof String)) {
      return "参数 old_string 必须是字符串。请传入要精确匹配的原文。";
    }
    if (input == null || !(input.get("new_string") instanceof String)) {
      return "参数 new_string 必须是字符串。请传入替换后的文本。";
    }
    return PathGuard.validatePathArgument(input.get("path"), context.projectRoot());
  }

  /** 校验文件快照后执行唯一原文替换，防止覆盖模型未读取或已变化的文件。 */
  @Override
  public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
    String pathError = PathGuard.validatePath(input.get("path"), context.projectRoot(), true);
    if (pathError != null) return ToolResult.error(pathError);
    Path path = PathGuard.path(input.get("path"));
    try {
      if (!Files.isRegularFile(path)) {
        return ToolResult.error("目标不是普通文件：" + path + "。请传入文件路径。");
      }
      if (TextFileSupport.isBinary(path)) {
        return ToolResult.error("拒绝编辑二进制文件：" + path + "。请使用 Bash 处理二进制内容。");
      }
      if (!context.fileStateCache().wasRead(path)) {
        return ToolResult.error("拒绝编辑未读取的文件：" + path + "。请先调用 ReadFile 后再重试。");
      }
      if (!context.fileStateCache().canModify(path)) {
        return ToolResult.error("文件在读取后已发生变化：" + path + "。请先重新调用 ReadFile 后再重试。");
      }

      String content = TextFileSupport.readString(path);
      String oldString = (String) input.get("old_string");
      String newString = (String) input.get("new_string");
      int occurrences = countOccurrences(content, oldString);
      if (occurrences == 0) {
        return ToolResult.error("未找到 old_string，文件未修改：" + path + "。请先 ReadFile 核对原文和空白字符。");
      }
      if (occurrences > 1) {
        return ToolResult.error("old_string 在文件中出现多次，替换不唯一，文件未修改：" + path + "。请扩大匹配文本使其只出现一次。");
      }
      Files.writeString(
          path,
          content.replace(oldString, newString),
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE);
      context.fileStateCache().update(path);
      return ToolResult.success("已编辑文件：" + path);
    } catch (AccessDeniedException error) {
      return ToolResult.error("没有编辑权限：" + path + "。请检查文件权限。");
    } catch (IOException error) {
      return ToolResult.error("编辑文件失败：" + path + "。请重新读取并确认文件可访问。");
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

  private static int countOccurrences(String text, String target) {
    if (target.isEmpty()) return text.isEmpty() ? 1 : text.length() + 1;
    int count = 0;
    int from = 0;
    while ((from = text.indexOf(target, from)) >= 0) {
      count++;
      from += target.length();
    }
    return count;
  }
}
