package com.mewcode.worktree;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 只操作 JUnit 临时目录的真实 Git 仓库，不读取用户 Git 配置或访问网络。 */
public final class GitRepositoryFixture {
  public final Path root;

  public GitRepositoryFixture(Path directory) throws Exception {
    root = Files.createDirectories(directory).toRealPath();
    git("init", "--initial-branch=main");
    git("config", "user.name", "Worktree Test");
    git("config", "user.email", "worktree-test@example.invalid");
    git("config", "commit.gpgSign", "false");
    Files.writeString(root.resolve("notes.txt"), "baseline\n");
    Files.writeString(
        root.resolve(".gitignore"),
        ".mewcode/config.yaml\n.mewcode/permissions.local.yaml\n.mewcode/hooks.yaml\n"
            + ".mewcode/sessions/\n.mewcode/memory/\n.mewcode/hook-events/\n");
    git("add", ".");
    git("commit", "-m", "测试基线");
    knownRemote();
  }

  public String git(String... arguments) throws Exception {
    return gitAt(root, arguments);
  }

  public String gitAt(Path cwd, String... arguments) throws Exception {
    var argv = new ArrayList<String>(List.of("git"));
    argv.addAll(List.of(arguments));
    var builder = new ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true);
    builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
    builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
    builder.environment().put("GIT_TERMINAL_PROMPT", "0");
    Process process = builder.start();
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IOException("测试 Git 超时");
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.exitValue() != 0) throw new IOException("测试 Git 失败：" + output);
    return output.strip();
  }

  public void knownRemote() throws Exception {
    git("update-ref", "refs/remotes/fixture/main", git("rev-parse", "HEAD"));
  }
}
