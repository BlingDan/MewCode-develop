package com.mewcode.worktree;

import java.nio.file.Path;
import java.util.Objects;

/** 进入前的现场；原 HEAD 仅说明进入来源，不替代资源创建基线。 */
public record WorktreeSession(
    Path originalCwd,
    Path worktreePath,
    String worktreeName,
    String worktreeBranch,
    String originalBranch,
    String originalHeadCommit,
    String sessionId,
    String agentId,
    long creationDurationMs) {
  public WorktreeSession {
    originalCwd = Objects.requireNonNull(originalCwd).toAbsolutePath().normalize();
    worktreePath = Objects.requireNonNull(worktreePath).toAbsolutePath().normalize();
    SlugValidator.validate(worktreeName);
    if (!SlugValidator.branch(worktreeName).equals(worktreeBranch))
      throw new IllegalArgumentException("工作树分支与名称不符");
    WorktreeSessionStore.validateId(sessionId);
    WorktreeSessionStore.validateId(agentId);
    if (originalHeadCommit == null || !originalHeadCommit.matches("[0-9a-f]{40,64}"))
      throw new IllegalArgumentException("无效提交基线");
    originalBranch = originalBranch == null ? "" : originalBranch;
    if (creationDurationMs < 0) throw new IllegalArgumentException("无效创建耗时");
  }
}
