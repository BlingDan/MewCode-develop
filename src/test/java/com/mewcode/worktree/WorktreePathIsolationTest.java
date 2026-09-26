package com.mewcode.worktree;

import static org.junit.jupiter.api.Assertions.*;

import com.mewcode.agent.CancellationToken;
import com.mewcode.config.WorktreeConfig;
import com.mewcode.tool.*;
import com.mewcode.tool.impl.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorktreePathIsolationTest {
  @TempDir Path root;

  @Test
  void externalAuthorizationCannotGrantParentSharedOrMetadataWrites() throws Exception {
    new GitRepositoryFixture(root);
    Files.createDirectory(root.resolve("node_modules"));
    Files.writeString(root.resolve("node_modules/library.txt"), "shared");
    var config = new WorktreeConfig();
    config.setSymlinkDirectories(List.of("node_modules"));
    var manager = new WorktreeManager(root, config, "session");
    Path child = manager.create(root, "child", new CancellationToken()).path();
    var workspace = new AgentWorkspace(root, "session", "main", new FileStateCache(), manager);
    manager.enter(workspace, "child");
    var context =
        workspace
            .capture(new CancellationToken())
            .withPermissionContext(null, new CancellationToken(), true);
    var read = new ReadFileTool();
    var write = new WriteFileTool();
    assertFalse(
        read.execute(context, Map.of("path", child.resolve("node_modules/library.txt").toString()))
            .isError());
    for (Path path :
        List.of(
            root.resolve("notes.txt"),
            child.resolve("node_modules/library.txt"),
            root.resolve(".mewcode/worktree-state/resources/child.json"))) {
      assertNotNull(
          write.validateInput(
              context, Map.of("path", path.toAbsolutePath().toString(), "content", "bad")));
      assertTrue(
          write
              .execute(context, Map.of("path", path.toAbsolutePath().toString(), "content", "bad"))
              .isError());
    }
    assertEquals("shared", Files.readString(root.resolve("node_modules/library.txt")));
    assertEquals("baseline\n", Files.readString(root.resolve("notes.txt")));
    manager.exit(workspace, false, new CancellationToken());
  }

  @Test
  void parentSearchExcludesManagedCopiesWhileEnteredSearchSeesOwnCode() throws Exception {
    new GitRepositoryFixture(root);
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    Path child = manager.create(root, "child", new CancellationToken()).path();
    Files.writeString(child.resolve("only-child.txt"), "child-marker");
    var workspace = new AgentWorkspace(root, "session", "main", new FileStateCache(), manager);
    var grep = new GrepTool();
    var parent =
        grep.execute(
            workspace.capture(new CancellationToken()),
            Map.of("path", root.toRealPath().toString(), "pattern", "child-marker"));
    assertFalse(parent.isError(), parent.content());
    assertFalse(parent.content().contains("only-child"));
    manager.enter(workspace, "child");
    var own =
        grep.execute(
            workspace.capture(new CancellationToken()),
            Map.of("path", child.toString(), "pattern", "child-marker"));
    assertFalse(own.isError(), own.content());
    assertTrue(own.content().contains("only-child"));
    manager.exit(workspace, false, new CancellationToken());
  }
}
