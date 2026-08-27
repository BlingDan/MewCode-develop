package com.mewcode.tool.impl;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolExecutionContext;
import com.mewcode.tool.ToolResult;
import com.mewcode.tool.support.PathGuard;
import com.mewcode.tool.support.SearchSupport;
import com.mewcode.tool.support.TextFileSupport;
import com.mewcode.tool.support.ToolInput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** 在文本文件中按正则表达式搜索。 */
public final class GrepTool implements Tool {

  @Override
  public String name() {
    return "Grep";
  }

  @Override
  public String description() {
    return "在项目根目录内按正则表达式搜索文本，跳过二进制和无意义目录，结果带路径与行号。";
  }

  @Override
  public ToolCategory category() {
    return ToolCategory.SEARCH;
  }

  @Override
  public Map<String, Object> inputSchema() {
    return Map.of(
        "type",
        "object",
        "properties",
        Map.of(
            "pattern", Map.of("type", "string", "description", "要搜索的正则表达式"),
            "path", Map.of("type", "string", "description", "项目根目录内的绝对搜索根目录"),
            "include", Map.of("type", "string", "description", "可选文件名 glob，例如 *.java")),
        "required",
        List.of("pattern", "path"),
        "additionalProperties",
        false);
  }

  @Override
  public String validateInput(Map<String, Object> input) {
    String patternError = ToolInput.requireString(input, "pattern", " 请传入要搜索的正则表达式。");
    if (patternError != null) return patternError;
    String pathError = ToolInput.requireString(input, "path", " 请传入项目根目录内的绝对搜索根目录。");
    if (pathError != null) return pathError;
    try {
      Pattern.compile((String) input.get("pattern"));
    } catch (PatternSyntaxException error) {
      return "参数 pattern 不是合法正则表达式：" + error.getDescription() + "。请修正后重试。";
    }
    try {
      if (!Path.of((String) input.get("path")).isAbsolute()) {
        return "参数 path 必须是绝对路径，请传入项目根目录内的搜索根目录。";
      }
    } catch (RuntimeException error) {
      return "参数 path 不是合法路径，请传入合法的绝对路径。";
    }
    if (input.containsKey("include") && !(input.get("include") instanceof String)) {
      return "参数 include 必须是文件名 glob 字符串。";
    }
    return null;
  }

  @Override
  public String validateInput(ToolExecutionContext context, Map<String, Object> input) {
    String patternError = ToolInput.requireString(input, "pattern", " 请传入要搜索的正则表达式。");
    if (patternError != null) return patternError;
    String pathError = ToolInput.requireString(input, "path", " 请传入项目根目录内的绝对搜索根目录。");
    if (pathError != null) return pathError;
    try {
      Pattern.compile((String) input.get("pattern"));
    } catch (PatternSyntaxException error) {
      return "参数 pattern 不是合法正则表达式：" + error.getDescription() + "。请修正后重试。";
    }
    String boundaryError =
        PathGuard.validatePathArgument(
            input.get("path"), context.projectRoot(), context.externalPathAuthorized());
    if (boundaryError != null) return boundaryError;
    if (input.containsKey("include") && !(input.get("include") instanceof String)) {
      return "参数 include 必须是文件名 glob 字符串。";
    }
    return null;
  }

  /** 在允许的路径范围内执行正则搜索，并以带行号的分组文本返回匹配结果。 */
  @Override
  public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
    String pathError =
        PathGuard.validatePath(
            input.get("path"), context.projectRoot(), true, context.externalPathAuthorized());
    if (pathError != null) return ToolResult.error(pathError);
    Path root = Path.of((String) input.get("path")).toAbsolutePath().normalize();
    if (!Files.isDirectory(root)) {
      return ToolResult.error("Grep 搜索根目录不是目录：" + root + "。请传入目录的绝对路径。");
    }
    Pattern pattern = Pattern.compile((String) input.get("pattern"));
    PathMatcher include =
        input.get("include") instanceof String value && !value.isBlank()
            ? FileSystems.getDefault().getPathMatcher("glob:" + value)
            : null;
    var groups = new ArrayList<MatchGroup>();
    int[] skippedBinary = {0};
    try {
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
              return directory.equals(root) || !SearchSupport.shouldSkipDirectory(directory)
                  ? FileVisitResult.CONTINUE
                  : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              if (!attrs.isRegularFile()
                  || include != null && !include.matches(file.getFileName())) {
                return FileVisitResult.CONTINUE;
              }
              try {
                if (TextFileSupport.isBinary(file)) {
                  skippedBinary[0]++;
                  return FileVisitResult.CONTINUE;
                }
                var lines = new ArrayList<String>();
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                  String line;
                  int lineNumber = 0;
                  while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    if (pattern.matcher(line).find()) {
                      lines.add(lineNumber + "\t" + line);
                    }
                  }
                }
                if (!lines.isEmpty()) groups.add(new MatchGroup(file, lines));
              } catch (IOException ignored) {
                // 单个文件不可读时跳过，不影响其他搜索结果。
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) {
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException error) {
      return ToolResult.error("Grep 搜索失败：" + error.getMessage() + "。请检查搜索根目录权限后重试。");
    }

    groups.sort(
        Comparator.comparing((MatchGroup group) -> SearchSupport.modifiedTime(group.file()))
            .reversed()
            .thenComparing(group -> group.file().toString()));
    var output = new ArrayList<String>();
    for (MatchGroup group : groups) {
      String prefix = SearchSupport.relative(root, group.file()) + ":";
      for (String line : group.lines()) {
        if (output.size() >= SearchSupport.MAX_RESULTS) break;
        output.add(prefix + line);
      }
      if (output.size() >= SearchSupport.MAX_RESULTS) break;
    }
    return new ToolResult(
        String.join("\n", output),
        false,
        Map.of("skippedBinaryCount", skippedBinary[0], "resultCount", output.size()));
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

  private record MatchGroup(Path file, List<String> lines) {}
}
