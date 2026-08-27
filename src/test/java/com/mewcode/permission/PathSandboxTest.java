package com.mewcode.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.mewcode.tool.ToolCall;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathSandboxTest {
  @TempDir Path projectRoot;

  @Test
  void resolvesExistingSymlinkBeforeCheckingTheProjectBoundary() throws Exception {
    Path outside = projectRoot.resolveSibling("mewcode-permission-outside");
    Files.createDirectories(outside);
    Path link = projectRoot.resolve("link");
    try {
      Files.createSymbolicLink(link, outside);
    } catch (UnsupportedOperationException | java.nio.file.FileSystemException error) {
      return;
    }

    PathCheck check =
        new PathSandbox()
            .inspect(
                new ToolCall(
                    "call-1", "ReadFile", Map.of("path", link.resolve("file.txt").toString())),
                projectRoot);

    assertEquals(PathBoundary.OUTSIDE_PROJECT, check.boundary());
    assertEquals(outside.toRealPath().resolve("file.txt"), check.resolvedPath());
    assertNotNull(check.authorizationKey());
  }

  @Test
  void distinguishesAdjacentProjectNamesAndMissingChildren() {
    Path adjacent = projectRoot.resolveSibling(projectRoot.getFileName() + "-other");
    PathCheck outside =
        new PathSandbox()
            .inspect(
                new ToolCall(
                    "call-2", "WriteFile", Map.of("path", adjacent.resolve("new.txt").toString())),
                projectRoot);
    PathCheck inside =
        new PathSandbox()
            .inspect(
                new ToolCall(
                    "call-3",
                    "WriteFile",
                    Map.of("path", projectRoot.resolve("new.txt").toString())),
                projectRoot);

    assertEquals(PathBoundary.OUTSIDE_PROJECT, outside.boundary());
    assertEquals(PathBoundary.INSIDE_PROJECT, inside.boundary());
  }
}
