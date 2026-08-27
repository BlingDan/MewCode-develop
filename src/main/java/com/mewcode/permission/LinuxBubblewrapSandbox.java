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
    var argv =
        new ArrayList<String>(
            List.of(
                EXECUTABLE,
                "--die-with-parent",
                "--new-session",
                "--ro-bind",
                "/",
                "/",
                "--dev",
                "/dev",
                "--proc",
                "/proc"));
    for (Path scope : request.writableScopes()) {
      argv.add("--bind");
      argv.add(scope.toString());
      argv.add(scope.toString());
    }
    argv.add("--chdir");
    argv.add(request.projectRoot().toString());
    argv.add("/bin/sh");
    argv.add("-c");
    argv.add(request.command());
    return new SandboxedProcess(argv, request.projectRoot());
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
