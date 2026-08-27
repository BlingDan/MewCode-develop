package com.mewcode.permission;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** 交给 ProcessBuilder 的参数化沙箱进程描述。 */
public record SandboxedProcess(List<String> argv, Path workingDirectory) {
  public SandboxedProcess {
    argv = List.copyOf(Objects.requireNonNull(argv, "argv"));
    if (argv.isEmpty() || argv.stream().anyMatch(value -> value == null || value.isBlank())) {
      throw new IllegalArgumentException("argv must contain non-blank values");
    }
    workingDirectory =
        Objects.requireNonNull(workingDirectory, "workingDirectory").toAbsolutePath().normalize();
  }
}
