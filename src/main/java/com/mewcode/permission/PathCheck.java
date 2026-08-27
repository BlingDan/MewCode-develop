package com.mewcode.permission;

import java.nio.file.Path;
import java.util.Objects;

/** 路径沙箱解析结果。 */
public record PathCheck(
    PathBoundary boundary,
    Path normalizedPath,
    Path resolvedPath,
    String reason,
    String authorizationKey) {
  public PathCheck {
    boundary = Objects.requireNonNull(boundary, "boundary");
    normalizedPath = Objects.requireNonNull(normalizedPath, "normalizedPath");
    resolvedPath = Objects.requireNonNull(resolvedPath, "resolvedPath");
    reason = Objects.requireNonNullElse(reason, "");
    authorizationKey = Objects.requireNonNullElse(authorizationKey, "");
  }
}
