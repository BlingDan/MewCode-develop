package com.mewcode.worktree;

import java.nio.file.Path;

/** 面向用户的安全失败；只携带已审查原因，禁止拼入配置正文或 Git 原始错误输出。 */
public final class WorktreeException extends RuntimeException {
  private final String stage;
  private final Path path;
  private final String branch;

  public WorktreeException(String stage, String reason, Path path, String branch) {
    super(
        "工作树 "
            + stage
            + " 失败："
            + reason
            + (path == null ? "" : "；目录=" + path)
            + (branch == null ? "" : "；分支=" + branch));
    this.stage = stage;
    this.path = path;
    this.branch = branch;
  }

  public String stage() {
    return stage;
  }

  public Path path() {
    return path;
  }

  public String branch() {
    return branch;
  }
}
