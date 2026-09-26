package com.mewcode.permission;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** 构造 Bash OS 沙箱进程所需的稳定输入。 */
public record BashSandboxRequest(
    String command,
    Path projectRoot,
    List<Path> writableScopes,
    List<Path> readOnlyScopes,
    Path excludedReadRoot,
    List<Path> readableExceptions) {
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
    readOnlyScopes =
        readOnlyScopes == null
            ? List.of()
            : readOnlyScopes.stream().map(p -> p.toAbsolutePath().normalize()).distinct().toList();
    excludedReadRoot =
        excludedReadRoot == null ? null : excludedReadRoot.toAbsolutePath().normalize();
    readableExceptions =
        readableExceptions == null
            ? List.of()
            : readableExceptions.stream()
                .map(p -> p.toAbsolutePath().normalize())
                .distinct()
                .toList();
  }

  public BashSandboxRequest(String command, Path projectRoot, List<Path> writableScopes) {
    this(command, projectRoot, writableScopes, List.of(), null, List.of());
  }
}
