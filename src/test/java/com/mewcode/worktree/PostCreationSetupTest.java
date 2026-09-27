package com.mewcode.worktree;

import static org.junit.jupiter.api.Assertions.*;

import com.mewcode.agent.CancellationToken;
import com.mewcode.config.WorktreeConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PostCreationSetupTest {
  @TempDir Path root;

  @Test
  void copiesIgnoredRuntimeFilesAndUsesGitIncludeNegation() throws Exception {
    var repo = new GitRepositoryFixture(root);
    Files.writeString(
        root.resolve(".gitignore"),
        Files.readString(root.resolve(".gitignore")) + "runtime/\nnode_modules/\n");
    Files.writeString(root.resolve(".worktreeinclude"), "runtime/**\n!runtime/private.txt\n");
    repo.git("add", ".gitignore", ".worktreeinclude");
    repo.git("commit", "-m", "运行规则");
    Files.createDirectories(root.resolve(".mewcode"));
    Files.writeString(root.resolve(".mewcode/config.yaml"), "private-local-config");
    Files.createDirectories(root.resolve("runtime"));
    Files.writeString(root.resolve("runtime/a file.txt"), "needed");
    Files.writeString(root.resolve("runtime/private.txt"), "excluded");
    Files.createDirectories(root.resolve("runtime/deep"));
    Files.writeString(root.resolve("runtime/deep/line\nbreak.txt"), "nested-special");
    Files.createDirectories(root.resolve(".mewcode/sessions"));
    Files.writeString(root.resolve(".mewcode/sessions/private.jsonl"), "private-history");
    Files.writeString(
        root.resolve(".worktreeinclude"),
        "runtime/**\n!runtime/private.txt\n.mewcode/sessions/**\n");
    Files.setPosixFilePermissions(
        root.resolve(".mewcode/config.yaml"),
        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    Files.createDirectory(root.resolve("node_modules"));
    var config = new WorktreeConfig();
    config.setSymlinkDirectories(java.util.List.of("node_modules"));
    var manager = new WorktreeManager(root, config, "session");
    var child = manager.create(root, "child", new CancellationToken()).path();
    assertEquals("private-local-config", Files.readString(child.resolve(".mewcode/config.yaml")));
    assertEquals("needed", Files.readString(child.resolve("runtime/a file.txt")));
    assertFalse(Files.exists(child.resolve("runtime/private.txt")));
    assertEquals("nested-special", Files.readString(child.resolve("runtime/deep/line\nbreak.txt")));
    assertFalse(Files.exists(child.resolve(".mewcode/sessions/private.jsonl")));
    assertEquals(
        Files.getPosixFilePermissions(root.resolve(".mewcode/config.yaml")),
        Files.getPosixFilePermissions(child.resolve(".mewcode/config.yaml")));
    assertEquals(
        root.resolve("node_modules").toRealPath(), child.resolve("node_modules").toRealPath());
  }

  @Test
  void initializationCannotOverwriteCommittedCodeOrFollowRuntimeLinks() throws Exception {
    new GitRepositoryFixture(root);
    var config = new WorktreeConfig();
    config.setRequiredFiles(java.util.List.of("notes.txt"));
    Files.writeString(root.resolve("notes.txt"), "uncommitted-code");
    var manager = new WorktreeManager(root, config, "session");
    assertThrows(
        WorktreeException.class, () -> manager.create(root, "overwrite", new CancellationToken()));
    assertEquals("uncommitted-code", Files.readString(root.resolve("notes.txt")));
    Path outside = Files.createTempDirectory("mew-worktree-outside");
    try {
      Files.writeString(outside.resolve("config"), "fake-linked-secret");
      Files.createSymbolicLink(root.resolve("runtime"), outside);
      config.setRequiredFiles(java.util.List.of("runtime/config"));
      var error =
          assertThrows(
              WorktreeException.class,
              () ->
                  new WorktreeManager(root, config, "session")
                      .create(root, "linked", new CancellationToken()));
      assertFalse(error.toString().contains("fake-linked-secret"));
      assertEquals("fake-linked-secret", Files.readString(outside.resolve("config")));
    } finally {
      Files.deleteIfExists(outside.resolve("config"));
      Files.delete(outside);
    }
  }

  @Test
  void hooksDoNotChangeSharedConfigWhenWorktreeExtensionIsDisabled() throws Exception {
    var repo = new GitRepositoryFixture(root);
    Files.createDirectories(root.resolve("hooks"));
    repo.git("config", "core.hooksPath", "hooks");
    String shared = Files.readString(root.resolve(".git/config"));
    var child =
        new WorktreeManager(root, new WorktreeConfig(), "session")
            .create(root, "child", new CancellationToken())
            .path();
    assertEquals(shared, Files.readString(root.resolve(".git/config")));
    var record = new WorktreeSessionStore().loadResource(root, "child").orElseThrow();
    assertEquals("ENV", record.hooksConfigurationMode);
    assertEquals(root.resolve("hooks").toRealPath().toString(), record.hooksPath);
    assertFalse(Files.exists(child.resolve(".git/config")));
  }

  @Test
  void environmentOverridePreservesEntriesAndRejectsMalformedCounts() throws Exception {
    var environment = new java.util.HashMap<String, String>();
    environment.put("GIT_CONFIG_COUNT", "1");
    environment.put("GIT_CONFIG_KEY_0", "test.existing");
    environment.put("GIT_CONFIG_VALUE_0", "preserved");
    PostCreationSetup.addHooksEnvironment(environment, "/safe/hooks", "ENV");
    assertEquals("2", environment.get("GIT_CONFIG_COUNT"));
    assertEquals("preserved", environment.get("GIT_CONFIG_VALUE_0"));
    assertEquals("core.hooksPath", environment.get("GIT_CONFIG_KEY_1"));
    environment.put("GIT_CONFIG_COUNT", "invalid");
    assertThrows(
        IllegalArgumentException.class,
        () -> PostCreationSetup.addHooksEnvironment(environment, "/safe/hooks", "ENV"));
  }

  @Test
  void configuredHookRunsInTheChildWithoutChangingTheParent() throws Exception {
    var repo = new GitRepositoryFixture(root);
    repo.git("config", "extensions.worktreeConfig", "true");
    repo.git("config", "core.hooksPath", "hooks");
    Files.createDirectory(root.resolve("hooks"));
    Path hook = root.resolve("hooks/pre-commit");
    Files.writeString(
        hook, "#!/bin/sh\nmkdir -p .mewcode/hook-events\npwd > .mewcode/hook-events/current\n");
    Files.setPosixFilePermissions(
        hook, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    var child =
        new WorktreeManager(root, new WorktreeConfig(), "session")
            .create(root, "child", new CancellationToken())
            .path();
    repo.gitAt(child, "commit", "--allow-empty", "-m", "触发 Hooks");
    assertEquals(
        child.toString(), Files.readString(child.resolve(".mewcode/hook-events/current")).strip());
    assertFalse(Files.exists(root.resolve(".mewcode/hook-events/current")));
  }
}
