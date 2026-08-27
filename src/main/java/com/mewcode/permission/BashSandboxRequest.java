package com.mewcode.permission;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** 构造 Bash OS 沙箱进程所需的稳定输入。 */
public record BashSandboxRequest(String command, Path projectRoot, List<Path> writableScopes) {
  public BashSandboxRequest {
    if (command == null || command.isBlank())
      throw new IllegalArgumentException("command must not be blank");
    projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
    writableScopes =
        writableScopes == null
            ? List.of(projectRoot)
            : writableScopes.stream()
                .map(
                    path ->
                        Objects.requireNonNull(path, "writable scope").toAbsolutePath().normalize())
                .distinct()
                .toList();
    if (writableScopes.isEmpty()) writableScopes = List.of(projectRoot);
  }
}
