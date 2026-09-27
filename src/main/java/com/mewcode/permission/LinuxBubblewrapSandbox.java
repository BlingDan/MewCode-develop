package com.mewcode.permission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Linux bubblewrap 适配器。 */
public final class LinuxBubblewrapSandbox implements BashSandbox {
  private static final String EXECUTABLE = "bwrap";

  @Override
  public boolean isAvailable() {
    return executableOnPath(EXECUTABLE);
  }

  @Override
  public SandboxedProcess prepare(BashSandboxRequest request) throws IOException {
    if (!isAvailable()) throw new IOException("Linux bubblewrap 沙箱不可用，Bash 已安全拒绝执行");
    return new SandboxedProcess(arguments(request), request.projectRoot());
  }

  static List<String> arguments(BashSandboxRequest request) throws IOException {
    var argv =
        new ArrayList<String>(
            List.of(
                EXECUTABLE,
                "--die-with-parent",
                "--unshare-pid",
                "--new-session",
                "--ro-bind",
                "/",
                "/",
                "--dev",
                "/dev",
                "--proc",
                "/proc"));
    if (request.excludedReadRoot() != null) {
      argv.addAll(List.of("--tmpfs", request.excludedReadRoot().toString()));
      for (Path scope : request.readableExceptions()) {
        if (!Files.exists(scope)) throw new IOException("隔离读取范围不存在，Linux 命令已拒绝");
        argv.addAll(List.of("--ro-bind", scope.toString(), scope.toString()));
      }
    }
    for (Path scope : request.writableScopes()) {
      argv.add("--bind");
      argv.add(scope.toString());
      argv.add(scope.toString());
    }
    // 只读覆盖在可写绑定之后，避免 broad cwd 重新开放管理区域。
    for (Path scope : request.readOnlyScopes()) {
      if (Files.exists(scope))
        argv.addAll(List.of("--ro-bind", scope.toString(), scope.toString()));
      else
        // 未出现的管理目录也必须遮蔽，避免运行中创建后落入 broad cwd 的可写挂载。
        argv.addAll(List.of("--tmpfs", scope.toString(), "--remount-ro", scope.toString()));
    }
    argv.add("--chdir");
    argv.add(request.projectRoot().toString());
    argv.add("/bin/sh");
    argv.add("-c");
    argv.add(request.command());
    return List.copyOf(argv);
  }

  private static boolean executableOnPath(String name) {
    String path = System.getenv("PATH");
    if (path == null) return false;
    for (String directory : path.split(java.io.File.pathSeparator)) {
      if (Files.isExecutable(Path.of(directory, name))) return true;
    }
    return false;
  }
}
