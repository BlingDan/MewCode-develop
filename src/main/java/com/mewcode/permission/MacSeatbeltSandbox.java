package com.mewcode.permission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** macOS sandbox-exec/seatbelt 适配器。 */
public final class MacSeatbeltSandbox implements BashSandbox {
  private static final Path EXECUTABLE = Path.of("/usr/bin/sandbox-exec");

  @Override
  public boolean isAvailable() {
    return Files.isExecutable(EXECUTABLE);
  }

  @Override
  public SandboxedProcess prepare(BashSandboxRequest request) throws IOException {
    if (!isAvailable()) throw unavailable();
    String profile = profile(request);
    List<String> argv = new ArrayList<>();
    argv.add(EXECUTABLE.toString());
    argv.add("-p");
    argv.add(profile);
    argv.add("/bin/sh");
    argv.add("-c");
    argv.add(request.command());
    return new SandboxedProcess(argv, request.projectRoot());
  }

  private static String profile(BashSandboxRequest request) {
    var profile =
        new StringBuilder("(version 1)\n(deny default)\n")
            .append("(allow process*)\n")
            .append("(allow file-read*)\n")
            .append("(allow file-write* (literal \"/dev/null\") ");
    for (Path scope : request.writableScopes()) {
      profile.append("(subpath \"").append(escape(realScope(scope).toString())).append("\") ");
    }
    profile.append(")\n(allow network-outbound)\n(allow network-inbound)\n");
    return profile.toString();
  }

  private static Path realScope(Path scope) {
    try {
      return scope.toRealPath();
    } catch (IOException ignored) {
      return scope.toAbsolutePath().normalize();
    }
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static IOException unavailable() {
    return new IOException("macOS seatbelt 沙箱不可用，Bash 已安全拒绝执行");
  }
}
