package com.mewcode.tool.impl;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolExecutionContext;
import com.mewcode.tool.ToolResult;
import com.mewcode.tool.support.PathGuard;
import com.mewcode.tool.support.SearchSupport;
import com.mewcode.tool.support.ToolInput;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 在项目根目录内按 glob 模式查找文件。 */
public final class GlobTool implements Tool {

  @Override
  public String name() {
    return "Glob";
  }

  @Override
  public String description() {
    return "按绝对 glob 模式递归查找文件，支持 **，自动排除无意义目录并按修改时间倒序返回。";
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
        Map.of("pattern", Map.of("type", "string", "description", "项目根目录内的绝对 glob 模式")),
        "required",
        List.of("pattern"),
        "additionalProperties",
        false);
  }

  @Override
  public String validateInput(Map<String, Object> input) {
    return ToolInput.requireString(input, "pattern", " 请传入例如 /项目根目录/**/*.java 的绝对模式。");
  }

  @Override
  public String validateInput(ToolExecutionContext context, Map<String, Object> input) {
    String patternError =
        ToolInput.requireString(input, "pattern", " 请传入例如 /项目根目录/**/*.java 的绝对模式。");
    if (patternError != null) return patternError;
    return PathGuard.validatePatternArgument(
        input.get("pattern"), context.projectRoot(), context.externalPathAuthorized());
  }

  /** 在项目根目录内遍历 glob 结果，并过滤构建产物和超出数量上限的结果。 */
  @Override
  public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
    String patternText = (String) input.get("pattern");
    String patternError =
        PathGuard.validatePattern(
            patternText, context.projectRoot(), context.externalPathAuthorized());
    if (patternError != null) return ToolResult.error(patternError);
    try {
      Path pattern = Path.of(patternText).toAbsolutePath().normalize();
      Path searchRoot = searchRoot(pattern);
      String rootError =
          PathGuard.validatePath(
              searchRoot.toString(), context.projectRoot(), true, context.externalPathAuthorized());
      if (rootError != null) return ToolResult.error("glob 搜索根目录不可用：" + rootError);
      if (!Files.isDirectory(searchRoot)) {
        return ToolResult.error("glob 搜索根目录不存在或不是目录：" + searchRoot + "。请调整绝对模式后重试。");
      }
      PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
      var matches = new ArrayList<Path>();
      Files.walkFileTree(
          searchRoot,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
              return directory.equals(searchRoot) || !SearchSupport.shouldSkipDirectory(directory)
                  ? FileVisitResult.CONTINUE
                  : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              if (attrs.isRegularFile() && matcher.matches(file)) matches.add(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) {
              return FileVisitResult.CONTINUE;
            }
          });
      matches.sort(SearchSupport.newestFirst());
      return ToolResult.success(
          matches.stream()
              .limit(SearchSupport.MAX_RESULTS)
              .map(path -> SearchSupport.relative(searchRoot, path))
              .reduce((left, right) -> left + "\n" + right)
              .orElse(""));
    } catch (RuntimeException | IOException error) {
      return ToolResult.error("glob 搜索失败：" + error.getMessage() + "。请检查模式和搜索根目录后重试。");
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

  private static Path searchRoot(Path pattern) {
    Path current = pattern.getRoot();
    for (int i = 0; i < pattern.getNameCount(); i++) {
      String component = pattern.getName(i).toString();
      if (component.indexOf('*') >= 0
          || component.indexOf('?') >= 0
          || component.indexOf('[') >= 0
          || component.indexOf('{') >= 0) {
        break;
      }
      current = current.resolve(component);
    }
    if (current.equals(pattern) && !Files.isDirectory(current)) {
      current = current.getParent();
    }
    return current == null ? pattern.getRoot() : current;
  }
}
