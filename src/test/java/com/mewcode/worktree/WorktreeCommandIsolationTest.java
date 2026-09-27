package com.mewcode.worktree;

import static org.junit.jupiter.api.Assertions.*;

import com.mewcode.agent.CancellationToken;
import com.mewcode.config.WorktreeConfig;
import com.mewcode.permission.*;
import com.mewcode.tool.*;
import com.mewcode.tool.support.CommandRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorktreeCommandIsolationTest {
  @TempDir Path root;

  @Test
  void actualMacSandboxAllowsOwnCommitButRejectsParentDependencyAndSharedConfigWrites()
      throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").contains("Mac"));
    var repo = new GitRepositoryFixture(root);
    Files.createDirectory(root.resolve("node_modules"));
    Files.writeString(root.resolve("node_modules/library"), "read-only");
    Path executable = root.resolve("node_modules/check.sh");
    Files.writeString(executable, "#!/bin/sh\nprintf shared-ok\n");
    Files.setPosixFilePermissions(
        executable, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    var config = new WorktreeConfig();
    config.setSymlinkDirectories(List.of("node_modules"));
    var manager = new WorktreeManager(root, config, "session");
    Path child = manager.create(root, "child", new CancellationToken()).path();
    var workspace = new AgentWorkspace(root, "session", "main", new FileStateCache(), manager);
    manager.enter(workspace, "child");
    var context = workspace.capture(new CancellationToken());
    var commands = new CommandRunner(new MacSeatbeltSandbox());
    var commit =
        commands.run(
            "printf child > notes.txt && git add notes.txt && git commit -m child", context);
    assertEquals(0, commit.exitCode(), commit.output());
    assertEquals("child", Files.readString(child.resolve("notes.txt")));
    for (String command :
        List.of(
            "printf bad > '" + repo.root.resolve("notes.txt") + "'",
            "printf bad > node_modules/library",
            "git config --local core.test unsafe",
            "cat '" + repo.root.resolve("notes.txt") + "'",
            "git branch unauthorized")) {
      assertNotEquals(0, commands.run(command, context).exitCode(), command);
    }
    assertEquals("baseline\n", Files.readString(root.resolve("notes.txt")));
    assertEquals("read-only", Files.readString(root.resolve("node_modules/library")));
    assertEquals(0, commands.run("cat node_modules/library", context).exitCode());
    var executed = commands.run("./node_modules/check.sh", context);
    assertEquals(0, executed.exitCode(), executed.output());
    assertEquals("shared-ok", executed.output());
    var hookWrite = commands.runHook("printf bad > node_modules/library", "{}", context);
    assertNotEquals(0, hookWrite.exitCode());
    Path script = child.resolve("write-shared.sh");
    Files.writeString(script, "#!/bin/sh\nprintf bad > node_modules/library\n");
    Files.setPosixFilePermissions(
        script, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    assertNotEquals(0, commands.runScript(script, child, "{}", context).exitCode());
    assertEquals("read-only", Files.readString(root.resolve("node_modules/library")));
    manager.exit(workspace, false, new CancellationToken());
  }

  @Test
  void dependencyDirectoryWithoutSharingIsWritableOnlyInItsOwnCopy() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").contains("Mac"));
    var repo = new GitRepositoryFixture(root);
    Files.createDirectories(root.resolve("node_modules"));
    Files.writeString(root.resolve("node_modules/library"), "parent-copy");
    repo.git("add", "node_modules/library");
    repo.git("commit", "-m", "独立依赖副本");
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    Path child = manager.create(root, "local-copy", new CancellationToken()).path();
    var workspace = new AgentWorkspace(root, "session", "main", new FileStateCache(), manager);
    manager.enter(workspace, "local-copy");
    assertFalse(Files.isSymbolicLink(child.resolve("node_modules")));
    var result =
        new CommandRunner(new MacSeatbeltSandbox())
            .run(
                "printf own-copy > node_modules/library",
                workspace.capture(new CancellationToken()));
    assertEquals(0, result.exitCode(), result.output());
    assertEquals("own-copy", Files.readString(child.resolve("node_modules/library")));
    assertEquals("parent-copy", Files.readString(root.resolve("node_modules/library")));
    manager.exit(workspace, false, new CancellationToken());
  }

  @Test
  void parentCommandsCannotWriteIntoManagedCopies() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").contains("Mac"));
    new GitRepositoryFixture(root);
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    Path child = manager.create(root, "child", new CancellationToken()).path();
    var workspace = new AgentWorkspace(root, "session", "main", new FileStateCache(), manager);
    var result =
        new CommandRunner(new MacSeatbeltSandbox())
            .run(
                "printf bad > '" + child.resolve("notes.txt") + "'",
                workspace.capture(new CancellationToken()));
    assertNotEquals(0, result.exitCode());
    assertEquals("baseline\n", Files.readString(child.resolve("notes.txt")));
  }

  @Test
  void explicitDetachedCommandRetainsTheResourceAsUnknownEvenAfterShellReturns() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").contains("Mac"));
    new GitRepositoryFixture(root);
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    Path child = manager.create(root, "background", new CancellationToken()).path();
    var workspace = new AgentWorkspace(root, "session", "main", new FileStateCache(), manager);
    manager.enter(workspace, "background");
    Files.createDirectories(child.resolve(".mewcode/hook-events"));
    long pid = -1;
    try {
      var result =
          new CommandRunner(new MacSeatbeltSandbox())
              .run(
                  "/usr/bin/nohup /bin/sleep 2 </dev/null >/dev/null 2>&1 & echo $! > .mewcode/hook-events/pid",
                  workspace.capture(new CancellationToken()));
      assertEquals(0, result.exitCode(), result.output());
      pid = Long.parseLong(Files.readString(child.resolve(".mewcode/hook-events/pid")).strip());
      var failure =
          assertThrows(
              WorktreeException.class,
              () -> manager.exit(workspace, true, new CancellationToken()));
      assertTrue(failure.getMessage().contains("停止"), failure.getMessage());
      assertEquals(child, workspace.currentCwd());
      assertTrue(Files.isDirectory(child));
    } finally {
      if (pid > 0) ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
      if (workspace.currentSession().isPresent())
        manager.exit(workspace, false, new CancellationToken());
    }
  }

  @Test
  void unicodeWhitespaceDoesNotTurnAnOpaqueShellNameIntoABuiltinExemption() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").contains("Mac"));
    new GitRepositoryFixture(root);
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    Path child = manager.create(root, "unicode", new CancellationToken()).path();
    var workspace = new AgentWorkspace(root, "session", "main", new FileStateCache(), manager);
    manager.enter(workspace, "unicode");
    new CommandRunner(new MacSeatbeltSandbox())
        .run("true\u2003", workspace.capture(new CancellationToken()));
    var failure =
        assertThrows(
            WorktreeException.class, () -> manager.exit(workspace, true, new CancellationToken()));
    assertTrue(failure.getMessage().contains("停止"), failure.getMessage());
    assertEquals(child, workspace.currentCwd());
    manager.exit(workspace, false, new CancellationToken());
  }

  @Test
  void failedPersistenceOfUnknownProcessStatePreventsTheCommandFromStarting() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").contains("Mac"));
    new GitRepositoryFixture(root);
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    Path child = manager.create(root, "persist", new CancellationToken()).path();
    var workspace = new AgentWorkspace(root, "session", "main", new FileStateCache(), manager);
    manager.enter(workspace, "persist");
    Files.createDirectories(child.resolve(".mewcode/hook-events"));
    Path records = root.resolve(".mewcode/worktree-state/resources");
    Path backup = records.resolveSibling("resources-saved");
    BashSandbox fault =
        new BashSandbox() {
          public boolean isAvailable() {
            return true;
          }

          public SandboxedProcess prepare(BashSandboxRequest request) throws java.io.IOException {
            var prepared = new MacSeatbeltSandbox().prepare(request);
            Files.move(records, backup);
            Files.writeString(records, "模拟存储不可写");
            return prepared;
          }
        };
    try {
      assertThrows(
          WorktreeException.class,
          () ->
              new CommandRunner(fault)
                  .run(
                      "/usr/bin/touch .mewcode/hook-events/started",
                      workspace.capture(new CancellationToken())));
      assertFalse(Files.exists(child.resolve(".mewcode/hook-events/started")));
    } finally {
      if (Files.exists(backup)) {
        Files.delete(records);
        Files.move(backup, records);
      }
      manager.exit(workspace, false, new CancellationToken());
    }
  }

  @Test
  void opaqueScriptIsRetainedEvenWhenItsInvocationHasNoBackgroundSyntax() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").contains("Mac"));
    new GitRepositoryFixture(root);
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    Path child = manager.create(root, "script", new CancellationToken()).path();
    var workspace = new AgentWorkspace(root, "session", "main", new FileStateCache(), manager);
    manager.enter(workspace, "script");
    Path assets = Files.createDirectory(root.resolve("assets"));
    Path script = assets.resolve("worker.sh");
    Files.writeString(script, "#!/bin/sh\ntrue\n");
    Files.setPosixFilePermissions(
        script, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    var result =
        new CommandRunner(new MacSeatbeltSandbox())
            .runScript(script, assets, "{}", workspace.capture(new CancellationToken()));
    assertEquals(0, result.exitCode(), result.stderr());
    var failure =
        assertThrows(
            WorktreeException.class, () -> manager.exit(workspace, true, new CancellationToken()));
    assertTrue(failure.getMessage().contains("停止"), failure.getMessage());
    assertEquals(child, workspace.currentCwd());
    manager.exit(workspace, false, new CancellationToken());
    assertThrows(WorktreeException.class, () -> manager.remove("script", new CancellationToken()));
  }

  @Test
  void hooksEnvironmentAndSkillAssetsUseTheCapturedChildDirectory() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").contains("Mac"));
    var repo = new GitRepositoryFixture(root);
    Files.createDirectory(root.resolve("hooks"));
    Path hook = root.resolve("hooks/pre-commit");
    Files.writeString(
        hook, "#!/bin/sh\nmkdir -p .mewcode/hook-events\npwd > .mewcode/hook-events/current\n");
    Files.setPosixFilePermissions(
        hook, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    repo.git("config", "core.hooksPath", "hooks");
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    Path child = manager.create(root, "child", new CancellationToken()).path();
    var workspace = new AgentWorkspace(root, "session", "main", new FileStateCache(), manager);
    manager.enter(workspace, "child");
    var context = workspace.capture(new CancellationToken());
    var commands = new CommandRunner(new MacSeatbeltSandbox());
    var commit = commands.run("git commit --allow-empty -m hook", context);
    assertEquals(0, commit.exitCode(), commit.output());
    assertEquals(
        child.toString(), Files.readString(child.resolve(".mewcode/hook-events/current")).strip());
    assertEquals("hooks", repo.gitAt(child, "config", "--get", "core.hooksPath"));
    Path assets = Files.createDirectory(root.resolve("assets"));
    Files.writeString(assets.resolve("message"), "resource");
    Path script = assets.resolve("script.sh");
    Files.writeString(
        script, "#!/bin/sh\ncat \"$MEWCODE_SKILL_DIR/message\" > skill-output\npwd\n");
    Files.setPosixFilePermissions(
        script, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    var result = commands.runScript(script.toAbsolutePath(), assets.toRealPath(), "{}", context);
    assertEquals(0, result.exitCode(), result.stderr());
    assertEquals(child.toString(), result.stdout().strip());
    assertEquals("resource", Files.readString(child.resolve("skill-output")));
    assertFalse(Files.exists(assets.resolve("skill-output")));
    manager.exit(workspace, false, new CancellationToken());
  }
}
