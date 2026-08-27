package com.mewcode.permission;

import com.mewcode.tool.ToolCall;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** 文件工具的应用层路径沙箱；先解引用，再进行组件边界检查。 */
public final class PathSandbox {
  public PathCheck inspect(ToolCall call, Path projectRoot) {
    Objects.requireNonNull(call, "call");
    Path root = normalizeRoot(projectRoot);
    String raw = target(call);
    if (raw == null || raw.isBlank()) {
      return invalid(root, "工具没有提供可检查的路径参数");
    }
    Path normalized;
    try {
      Path candidate = Path.of(raw);
      normalized = (candidate.isAbsolute() ? candidate : root.resolve(candidate)).normalize();
    } catch (RuntimeException error) {
      return invalid(root, "路径格式无效：" + raw);
    }

    try {
      Path realRoot = root.toRealPath();
      Path resolved = resolveForBoundary(normalized, realRoot);
      boolean inside = resolved.startsWith(realRoot);
      PathBoundary boundary = inside ? PathBoundary.INSIDE_PROJECT : PathBoundary.OUTSIDE_PROJECT;
      String reason = inside ? "路径位于当前项目目录内" : "路径解析后超出当前项目目录或通过符号链接逃逸";
      return new PathCheck(
          boundary, normalized, resolved, reason, authorizationKey(call, resolved));
    } catch (IOException | RuntimeException error) {
      return new PathCheck(
          PathBoundary.INVALID,
          normalized,
          normalized,
          "无法解析路径或符号链接：" + raw,
          authorizationKey(call, normalized));
    }
  }

  private static Path resolveForBoundary(Path normalized, Path realRoot) throws IOException {
    Path current = normalized.getRoot();
    if (current == null) current = realRoot;
    for (Path component : normalized) {
      current = current.resolve(component.toString());
      if (Files.exists(current) || Files.isSymbolicLink(current)) {
        current = current.toRealPath();
      }
    }
    return current.normalize();
  }

  private static String target(ToolCall call) {
    Object path = call.arguments().get("path");
    if (path instanceof String value) return value;
    Object pattern = call.arguments().get("pattern");
    return pattern instanceof String value ? value : null;
  }

  private static String authorizationKey(ToolCall call, Path resolved) {
    return call.toolName() + "(path:" + resolved.toAbsolutePath().normalize() + ")";
  }

  private static PathCheck invalid(Path root, String reason) {
    return new PathCheck(PathBoundary.INVALID, root, root, reason, "invalid-path");
  }

  private static Path normalizeRoot(Path root) {
    return Objects.requireNonNull(root, "projectRoot").toAbsolutePath().normalize();
  }
}
