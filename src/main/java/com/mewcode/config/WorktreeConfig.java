package com.mewcode.config;

import java.nio.file.Path;
import java.util.List;

/** 工作树初始化与过期扫描配置；默认不共享任何依赖目录。 */
public final class WorktreeConfig {
  private int cleanupIntervalMinutes = 30;
  private int staleCutoffHours = 24;
  private List<String> symlinkDirectories = List.of();
  private List<String> requiredFiles = List.of();

  public int getCleanupIntervalMinutes() {
    return cleanupIntervalMinutes;
  }

  public void setCleanupIntervalMinutes(int value) {
    cleanupIntervalMinutes = value;
  }

  public int getStaleCutoffHours() {
    return staleCutoffHours;
  }

  public void setStaleCutoffHours(int value) {
    staleCutoffHours = value;
  }

  public List<String> getSymlinkDirectories() {
    return symlinkDirectories;
  }

  public void setSymlinkDirectories(List<String> value) {
    symlinkDirectories = value == null ? List.of() : List.copyOf(value);
  }

  public List<String> getRequiredFiles() {
    return requiredFiles;
  }

  public void setRequiredFiles(List<String> value) {
    requiredFiles = value == null ? List.of() : List.copyOf(value);
  }

  public void validate() {
    if (cleanupIntervalMinutes <= 0 || staleCutoffHours <= 0) {
      throw new IllegalArgumentException("worktree 时间配置必须为正数");
    }
    symlinkDirectories.forEach(WorktreeConfig::validatePath);
    requiredFiles.forEach(WorktreeConfig::validatePath);
  }

  public static void validatePath(String value) {
    if (value == null || value.isBlank() || value.contains("\\") || Path.of(value).isAbsolute()) {
      throw new IllegalArgumentException("worktree 文件规则必须为安全相对路径");
    }
    for (String part : value.split("/", -1)) {
      if (part.isEmpty() || part.equals(".") || part.equals("..") || part.equals(".git")) {
        throw new IllegalArgumentException("worktree 文件规则包含禁止的路径段");
      }
    }
    if (value.equals(".mewcode")
        || value.startsWith(".mewcode/worktrees")
        || value.startsWith(".mewcode/worktree-state")
        || value.startsWith(".mewcode/sessions")
        || value.startsWith(".mewcode/memory")
        || value.startsWith(".mewcode/context")) {
      throw new IllegalArgumentException("worktree 文件规则不能覆盖管理区域");
    }
  }
}
