package com.mewcode.worktree;

import com.mewcode.agent.CancellationToken;
import com.mewcode.config.WorktreeConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 创建后的必要初始化；复制不覆盖检出源码，配置内容不进入错误消息。 */
public final class PostCreationSetup {
  private final WorktreeConfig config;
  private final GitCommandRunner git;
  private List<Path> sharedDirectories = List.of();
  private String hooksPath = "";
  private String hooksMode = "NONE";

  public PostCreationSetup(WorktreeConfig config) {
    this(config, new GitCommandRunner());
  }

  PostCreationSetup(WorktreeConfig config, GitCommandRunner git) {
    this.config = config;
    this.git = git;
  }

  List<Path> sharedDirectories() {
    return sharedDirectories;
  }

  String hooksPath() {
    return hooksPath;
  }

  String hooksConfigurationMode() {
    return hooksMode;
  }

  public List<String> perform(Path sourceCwd, Path worktreePath, CancellationToken token) {
    var warnings = new ArrayList<String>();
    try {
      Path source = sourceCwd.toRealPath();
      Path target = worktreePath.toRealPath();
      for (String file :
          List.of(
              ".mewcode/config.yaml", ".mewcode/permissions.local.yaml", ".mewcode/hooks.yaml")) {
        token.throwIfCancelled();
        Path from = safe(source, file);
        if (Files.exists(from, LinkOption.NOFOLLOW_LINKS)) copy(source, target, file);
        else warnings.add("可选本地配置不存在：" + file);
      }
      for (String file : config.getRequiredFiles()) {
        token.throwIfCancelled();
        if (!Files.isRegularFile(safe(source, file), LinkOption.NOFOLLOW_LINKS))
          throw new IOException("必要运行文件缺失");
        copy(source, target, file);
      }
      configureHooks(source, target, token);
      var shared = new ArrayList<Path>();
      for (String directory : config.getSymlinkDirectories()) {
        token.throwIfCancelled();
        Path from = safe(source, directory);
        if (!Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
          warnings.add("可选共享目录不存在：" + directory);
          continue;
        }
        if (!Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) throw new IOException("共享目标不是目录");
        Path to = safe(target, directory);
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) throw new IOException("共享目标与检出内容冲突");
        Files.createDirectories(to.getParent());
        Path real = from.toRealPath();
        Files.createSymbolicLink(to, real);
        shared.add(real);
      }
      sharedDirectories = List.copyOf(shared);
      includeFiles(source, target, token);
      return List.copyOf(warnings);
    } catch (IOException | IllegalArgumentException error) {
      throw WorktreeManager.failure("初始化", "必要文件、Hooks 或共享目录校验失败", worktreePath, null);
    }
  }

  private void configureHooks(Path source, Path target, CancellationToken token)
      throws IOException {
    var setting = git.execute(source, token, "config", "--get", "core.hooksPath");
    if (setting.exitCode() != 0 && setting.exitCode() != 1) throw new IOException("无法确认 Hooks 配置");
    if (setting.exitCode() == 1) {
      hooksPath =
          Path.of(
                  git.run(
                          source,
                          token,
                          "rev-parse",
                          "--path-format=absolute",
                          "--git-path",
                          "hooks")
                      .strip())
              .normalize()
              .toString();
      return;
    }
    String value = setting.output().strip();
    if (value.isEmpty() || value.contains("\n")) throw new IOException("Hooks 路径无效");
    Path configured = Path.of(value);
    Path resolved = configured.isAbsolute() ? configured : source.resolve(configured);
    // 可保留不存在的 hooks 目录，但已有父路径不能通过链接越界。
    resolved = resolved.toAbsolutePath().normalize();
    Path ancestor = resolved;
    while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS))
      ancestor = ancestor.getParent();
    if (ancestor == null || !ancestor.toRealPath().equals(ancestor))
      throw new IOException("Hooks 路径含符号链接");
    hooksPath = resolved.toString();
    var extension =
        git.execute(source, token, "config", "--bool", "--get", "extensions.worktreeConfig");
    if (extension.exitCode() != 0 && extension.exitCode() != 1)
      throw new IOException("无法确认 Git 扩展");
    if (extension.exitCode() == 0 && extension.output().strip().equals("true")) {
      git.run(target, token, "config", "--worktree", "core.hooksPath", hooksPath);
      hooksMode = "WORKTREE";
    } else hooksMode = "ENV";
  }

  /** 追加受信条目；合法既有条目不丢弃，畸形环境明确拒绝。 */
  static void addHooksEnvironment(Map<String, String> environment, String hooksPath, String mode) {
    if (!"ENV".equals(mode)) return;
    int count;
    try {
      count = Integer.parseInt(environment.getOrDefault("GIT_CONFIG_COUNT", "0"));
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException("Git 配置环境无效");
    }
    if (count < 0 || count > 1024) throw new IllegalArgumentException("Git 配置环境无效");
    for (int i = 0; i < count; i++) {
      if (environment.get("GIT_CONFIG_KEY_" + i) == null
          || environment.get("GIT_CONFIG_VALUE_" + i) == null)
        throw new IllegalArgumentException("Git 配置环境不完整");
    }
    environment.put("GIT_CONFIG_KEY_" + count, "core.hooksPath");
    environment.put("GIT_CONFIG_VALUE_" + count, hooksPath);
    environment.put("GIT_CONFIG_COUNT", Integer.toString(count + 1));
  }

  private void includeFiles(Path source, Path target, CancellationToken token) throws IOException {
    Path rules = safe(source, ".worktreeinclude");
    if (!Files.exists(rules, LinkOption.NOFOLLOW_LINKS)) return;
    if (!Files.isRegularFile(rules, LinkOption.NOFOLLOW_LINKS)) throw new IOException("包含规则不是普通文件");
    Set<String> ignored =
        nulPaths(
            git.run(
                source, token, "ls-files", "--others", "--ignored", "--exclude-standard", "-z"));
    Set<String> included =
        nulPaths(
            git.run(
                source,
                token,
                "ls-files",
                "--others",
                "--ignored",
                "--exclude-from=" + rules,
                "-z"));
    ignored.retainAll(included);
    for (String file : ignored) {
      token.throwIfCancelled();
      // 即使 include 命中也不遍历管理区域、Git 数据或会话历史。
      if (forbidden(file)) continue;
      boolean dependency =
          config.getSymlinkDirectories().stream()
              .anyMatch(d -> file.equals(d) || file.startsWith(d + "/"));
      if (!dependency) copy(source, target, file);
    }
  }

  private static Set<String> nulPaths(String text) throws IOException {
    if (!text.isEmpty() && !text.endsWith("\0")) throw new IOException("Git 文件列表不完整");
    var paths = new LinkedHashSet<String>();
    for (String path : text.split("\0", -1)) if (!path.isEmpty()) paths.add(path);
    return paths;
  }

  private static boolean forbidden(String path) {
    if (path.equals(".git") || path.startsWith(".git/") || path.contains("/.git/")) return true;
    return List.of(
            ".mewcode/worktrees",
            ".mewcode/worktree-state",
            ".mewcode/sessions",
            ".mewcode/memory",
            ".mewcode/context")
        .stream()
        .anyMatch(p -> path.equals(p) || path.startsWith(p + "/"));
  }

  private static Path safe(Path root, String relative) throws IOException {
    WorktreeConfig.validatePath(relative);
    Path target = root.resolve(relative).normalize();
    if (!target.startsWith(root)) throw new IOException("初始化路径越界");
    WorktreeSessionStore.noLinks(root, target);
    return target;
  }

  private static void copy(Path source, Path target, String relative) throws IOException {
    Path from = safe(source, relative);
    Path to = safe(target, relative);
    if (!Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS)) throw new IOException("复制来源不是普通文件");
    if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
      if (Files.isRegularFile(to, LinkOption.NOFOLLOW_LINKS) && Files.mismatch(from, to) == -1)
        return;
      throw new IOException("禁止覆盖检出内容");
    }
    Files.createDirectories(to.getParent());
    Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
    try {
      Files.setPosixFilePermissions(to, Files.getPosixFilePermissions(from));
    } catch (UnsupportedOperationException ignored) {
    }
  }
}
