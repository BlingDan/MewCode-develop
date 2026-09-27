package com.mewcode.worktree;

import com.mewcode.agent.CancellationToken;
import com.mewcode.tool.FileStateCache;
import java.nio.file.Path;

/** 子任务隔离适配层；创建和删除都复用管理器，不写主会话现场。 */
public final class AgentWorktree {
  public record Result(Path worktreePath, String worktreeBranch, String headCommit, Path gitRoot) {}

  private final WorktreeManager manager;
  private final String agentId;
  private final AgentWorkspace workspace;
  private WorktreeSessionStore.Resource resource;

  public AgentWorktree(WorktreeManager manager, String agentId, Path userHome, String sessionId) {
    this.manager = manager;
    this.agentId = agentId;
    workspace =
        new AgentWorkspace(
            manager.repositoryRoot(), userHome, sessionId, agentId, new FileStateCache(), manager);
  }

  public Result create(Path parentCwd, String slug, String headCommit, CancellationToken token) {
    if (resource != null) throw new IllegalStateException("子任务已分配工作树");
    resource = manager.createResource(parentCwd, slug, headCommit, true, agentId, token);
    if (!resource.temporary || !resource.createdByAgentId.equals(agentId))
      throw WorktreeManager.failure("子任务启动", "资源临时归属不符", resource.path, resource.branch);
    manager.bindChild(workspace, resource);
    return new Result(
        resource.path, resource.branch, resource.baseCommit, manager.repositoryRoot());
  }

  public AgentWorkspace workspace() {
    return workspace;
  }

  public void release() {
    if (resource != null) manager.releaseChild(resource.slug, resource.recordId, agentId);
  }

  public boolean remove(Result result, CancellationToken token) {
    if (resource == null
        || !resource.path.equals(result.worktreePath())
        || !resource.branch.equals(result.worktreeBranch())
        || !resource.baseCommit.equals(result.headCommit()))
      throw new IllegalArgumentException("子任务结果归属不符");
    return manager.removeKnown(resource.slug, resource.recordId, token);
  }

  public String buildNotice(Path parentCwd, Path worktreeCwd) {
    return "父目录："
        + parentCwd.toAbsolutePath().normalize()
        + "\n子任务目录："
        + worktreeCwd.toAbsolutePath().normalize()
        + "\n父目录内的相对路径应转换为子任务目录下同一相对路径。所有文件操作使用子任务目录；编辑前必须重新读取，父 Agent 的已读记录不适用。";
  }
}
