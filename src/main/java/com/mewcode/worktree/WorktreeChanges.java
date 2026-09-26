package com.mewcode.worktree;

import com.mewcode.agent.CancellationToken;
import java.nio.file.Path;

/** 成果检查只使用本地引用；检测失败或未知远端一律保留。 */
public final class WorktreeChanges {
  public record ChangeSummary(int changedFiles, int commits) {}

  private final GitCommandRunner git;

  public WorktreeChanges() {
    this(new GitCommandRunner());
  }

  WorktreeChanges(GitCommandRunner git) {
    this.git = git;
  }

  public ChangeSummary countChanges(Path worktreePath, String headCommit, CancellationToken token) {
    if (headCommit == null || !headCommit.matches("[0-9a-f]{40,64}"))
      throw WorktreeManager.failure("成果检查", "创建基线无效", worktreePath, null);
    String status =
        git.run(worktreePath, token, "status", "--porcelain=v1", "-z", "--untracked-files=all");
    if (!status.isEmpty() && !status.endsWith("\0"))
      throw WorktreeManager.failure("成果检查", "状态输出不完整", worktreePath, null);
    String[] entries = status.split("\0", -1);
    int changed = 0;
    for (int i = 0; i < entries.length; i++) {
      if (entries[i].isEmpty()) continue;
      if (entries[i].length() < 4 || entries[i].charAt(2) != ' ')
        throw WorktreeManager.failure("成果检查", "状态输出无法解析", worktreePath, null);
      changed++;
      if (entries[i].charAt(0) == 'R'
          || entries[i].charAt(0) == 'C'
          || entries[i].charAt(1) == 'R'
          || entries[i].charAt(1) == 'C') {
        if (++i >= entries.length || entries[i].isEmpty())
          throw WorktreeManager.failure("成果检查", "重命名输出不完整", worktreePath, null);
      }
    }
    var ancestor =
        git.execute(worktreePath, token, "merge-base", "--is-ancestor", headCommit, "HEAD");
    if (ancestor.exitCode() != 0)
      throw WorktreeManager.failure("成果检查", "HEAD 已偏离创建基线，须保留", worktreePath, null);
    String count =
        git.run(worktreePath, token, "rev-list", "--count", headCommit + "..HEAD").strip();
    try {
      return new ChangeSummary(changed, Integer.parseInt(count));
    } catch (NumberFormatException error) {
      throw WorktreeManager.failure("成果检查", "提交计数无法解析", worktreePath, null);
    }
  }

  public boolean hasChanges(Path path, String base, CancellationToken token) {
    try {
      ChangeSummary summary = countChanges(path, base, token);
      return summary.changedFiles() > 0 || summary.commits() > 0;
    } catch (RuntimeException error) {
      return true;
    }
  }

  public boolean hasUnpushedCommits(Path path, CancellationToken token) {
    try {
      requireKnownRemoteContainment(path, token);
      return false;
    } catch (RuntimeException error) {
      return true;
    }
  }

  void requireKnownRemoteContainment(Path path, CancellationToken token) {
    if (countUnpushedCommits(path, token) > 0)
      throw WorktreeManager.failure("成果检查", "HEAD 有本地未推送提交，须保留", path, null);
  }

  int countUnpushedCommits(Path path, CancellationToken token) {
    String all =
        git.run(path, token, "for-each-ref", "--format=%(refname)", "refs/remotes/").strip();
    if (all.isEmpty()) throw WorktreeManager.failure("成果检查", "没有本地已知远端引用，无法确认提交安全", path, null);
    try {
      return Integer.parseInt(
          git.run(path, token, "rev-list", "--count", "HEAD", "--not", "--remotes").strip());
    } catch (NumberFormatException error) {
      throw WorktreeManager.failure("成果检查", "本地远端包含关系无法确认", path, null);
    }
  }
}
