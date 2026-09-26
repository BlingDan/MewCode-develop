package com.mewcode.worktree;

import static org.junit.jupiter.api.Assertions.*;

import com.mewcode.agent.CancellationToken;
import com.mewcode.config.WorktreeConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorktreeManagerTest {
  @TempDir Path root;

  @Test
  void createsIndependentCommittedCopiesAndRecoversWithoutGitOrWrites() throws Exception {
    var repo = new GitRepositoryFixture(root);
    String base = repo.git("rev-parse", "HEAD");
    Files.writeString(root.resolve("notes.txt"), "parent dirty");
    var count = new AtomicInteger();
    var runner =
        new GitCommandRunner(
            java.time.Duration.ofSeconds(10),
            builder -> {
              count.incrementAndGet();
              return builder.start();
            });
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session", runner);
    var first = manager.create(root, "one/task", new CancellationToken());
    var second = manager.create(root, "two", new CancellationToken());
    assertEquals("baseline\n", Files.readString(first.path().resolve("notes.txt")));
    assertNotEquals(first.branch(), second.branch());
    assertEquals(base, repo.gitAt(first.path(), "rev-parse", "HEAD"));
    assertEquals("parent dirty", Files.readString(root.resolve("notes.txt")));
    Files.writeString(first.path().resolve("notes.txt"), "child dirty");
    Path record = root.resolve(".mewcode/worktree-state/resources/one+task.json");
    var modified = Files.getLastModifiedTime(record);
    int calls = count.get();
    var recovered = manager.create(root, "one/task", new CancellationToken());
    assertEquals(first, recovered);
    assertEquals(calls, count.get());
    assertEquals(modified, Files.getLastModifiedTime(record));
    assertEquals("child dirty", Files.readString(first.path().resolve("notes.txt")));
    assertFalse(repo.git("status", "--porcelain").contains("worktrees"));
    assertEquals(2, manager.list().size());
  }

  @Test
  void foreignOrPartiallyInitializedDirectoriesAreRejected() throws Exception {
    var repo = new GitRepositoryFixture(root);
    var config = new WorktreeConfig();
    config.setRequiredFiles(java.util.List.of("runtime/missing"));
    var manager = new WorktreeManager(root, config, "session");
    assertThrows(
        WorktreeException.class, () -> manager.create(root, "broken", new CancellationToken()));
    assertThrows(
        WorktreeException.class, () -> manager.create(root, "broken", new CancellationToken()));
    Files.createDirectories(root.resolve(".mewcode/worktrees/foreign"));
    assertThrows(
        WorktreeException.class, () -> manager.create(root, "foreign", new CancellationToken()));
    assertEquals("baseline", repo.git("show", "HEAD:notes.txt"));
  }

  @Test
  void branchConflictDoesNotMoveExistingBranch() throws Exception {
    var repo = new GitRepositoryFixture(root);
    repo.git("branch", SlugValidator.branch("taken"));
    String before = repo.git("rev-parse", SlugValidator.branch("taken"));
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    assertThrows(
        WorktreeException.class, () -> manager.create(root, "taken", new CancellationToken()));
    assertEquals(before, repo.git("rev-parse", SlugValidator.branch("taken")));
  }

  @Test
  void dirtyExitDeletePreservesSessionAndTrustedDiscardRequiresCurrentIdentity() throws Exception {
    var repo = new GitRepositoryFixture(root);
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    var child = manager.create(root, "child", new CancellationToken()).path();
    var workspace =
        new AgentWorkspace(root, "session", "main", new com.mewcode.tool.FileStateCache(), manager);
    manager.enter(workspace, "child");
    Files.writeString(child.resolve("notes.txt"), "dirty");
    assertThrows(
        WorktreeException.class, () -> manager.exit(workspace, true, new CancellationToken()));
    assertEquals(child, workspace.currentCwd());
    assertTrue(workspace.currentSession().isPresent());
    assertTrue(new WorktreeSessionStore().load(root, "session").isPresent());
    manager.exit(workspace, false, new CancellationToken());
    assertThrows(
        WorktreeException.class,
        () -> manager.discardFromUserCommand("child", "old-resource", new CancellationToken()));
    repo.gitAt(child, "add", "notes.txt");
    repo.gitAt(child, "commit", "-m", "未推送成果");
    assertThrows(WorktreeException.class, () -> manager.remove("child", new CancellationToken()));
    assertTrue(
        manager.discardFromUserCommand(
            "child", manager.resourceIdentityForUserCommand("child"), new CancellationToken()));
  }

  @Test
  void worktreeConfigExtensionOnlyChangesChildConfig() throws Exception {
    var repo = new GitRepositoryFixture(root);
    repo.git("config", "extensions.worktreeConfig", "true");
    repo.git("config", "core.hooksPath", "hooks");
    String original = Files.readString(root.resolve(".git/config"));
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    var child = manager.create(root, "child", new CancellationToken()).path();
    assertEquals(original, Files.readString(root.resolve(".git/config")));
    assertEquals(
        root.toRealPath().resolve("hooks").toString(),
        repo.gitAt(child, "config", "--worktree", "--get", "core.hooksPath"));
  }

  @Test
  void restartRestoresOnlyTheSavedSessionAndHonorsAnotherProcessLock() throws Exception {
    new GitRepositoryFixture(root);
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    var child = manager.create(root, "child", new CancellationToken()).path();
    var workspace =
        new AgentWorkspace(root, "session", "main", new com.mewcode.tool.FileStateCache(), manager);
    manager.enter(workspace, "child");
    var saved = workspace.currentSession().orElseThrow();
    manager.exit(workspace, false, new CancellationToken());
    new WorktreeSessionStore().save(root, saved);
    var restarted =
        new AgentWorkspace(root, "session", "main", new com.mewcode.tool.FileStateCache(), manager);
    assertTrue(manager.restore(restarted));
    assertEquals(child, restarted.currentCwd());
    manager.exit(restarted, false, new CancellationToken());
    assertFalse(manager.restore(restarted));
    Path source = root.resolve("LockProbe.java");
    Files.writeString(
        source,
        """
        import java.nio.channels.*;
        import java.nio.file.*;
        class LockProbe { public static void main(String[] args) throws Exception {
          try (var c = FileChannel.open(Path.of(args[0]), StandardOpenOption.READ, StandardOpenOption.WRITE);
               var l = c.lock()) { System.out.println("locked"); System.out.flush(); System.in.read(); }
        }}
        """);
    Path javaExecutable = Path.of(System.getProperty("java.home"), "bin/java");
    Process holder =
        new ProcessBuilder(
                javaExecutable.toString(),
                source.toString(),
                WorktreeSessionStore.lockPath(root, "child").toString())
            .start();
    try {
      var ready =
          new java.io.BufferedReader(new java.io.InputStreamReader(holder.getInputStream()));
      try (var reader = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
        assertEquals(
            "locked",
            reader.submit(ready::readLine).get(10, java.util.concurrent.TimeUnit.SECONDS));
      }
      assertThrows(
          WorktreeException.class, () -> manager.create(root, "child", new CancellationToken()));
      assertThrows(WorktreeException.class, () -> manager.remove("child", new CancellationToken()));
    } finally {
      holder.getOutputStream().close();
      if (!holder.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) holder.destroyForcibly();
      Files.delete(source);
    }
  }

  @Test
  void branchDeleteFailurePersistsActualPartialResidue() throws Exception {
    new GitRepositoryFixture(root);
    var runner =
        new GitCommandRunner(
            java.time.Duration.ofSeconds(10),
            builder -> {
              if (builder.command().size() > 2
                  && builder.command().get(1).equals("branch")
                  && builder.command().get(2).equals("-d"))
                return new ProcessBuilder("/bin/sh", "-c", "exit 1").start();
              return builder.start();
            });
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session", runner);
    Path child = manager.create(root, "child", new CancellationToken()).path();
    assertThrows(WorktreeException.class, () -> manager.remove("child", new CancellationToken()));
    assertFalse(Files.exists(child));
    var resource = new WorktreeSessionStore().loadResource(root, "child").orElseThrow();
    assertEquals(WorktreeSessionStore.State.PARTIAL, resource.state);
    assertEquals("NO", resource.directoryPresent);
    assertEquals("YES", resource.branchPresent);
    assertThrows(
        WorktreeException.class, () -> manager.create(root, "child", new CancellationToken()));
  }

  @Test
  void necessaryInitializationFailureRollsBackOnlyItsNewCleanResource() throws Exception {
    var repo = new GitRepositoryFixture(root);
    var config = new WorktreeConfig();
    config.setRequiredFiles(java.util.List.of("absent"));
    var manager = new WorktreeManager(root, config, "session");
    assertThrows(
        WorktreeException.class, () -> manager.create(root, "failed", new CancellationToken()));
    assertFalse(Files.exists(root.resolve(".mewcode/worktrees/failed")));
    assertFalse(new WorktreeSessionStore().loadResource(root, "failed").isPresent());
    assertEquals("", repo.git("branch", "--list", SlugValidator.branch("failed")));
  }

  @Test
  void frozenBaseSurvivesLaterParentCommitsAndCurrentChildCanBeTheSource() throws Exception {
    var repo = new GitRepositoryFixture(root);
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    String frozen = manager.freezeHead(repo.root, new CancellationToken());
    Files.writeString(root.resolve("notes.txt"), "later parent");
    repo.git("add", "notes.txt");
    repo.git("commit", "-m", "父分支前进");
    var resource =
        manager.createResource(
            root, "frozen", frozen, true, "child-agent", new CancellationToken());
    assertEquals(frozen, resource.baseCommit);
    assertEquals("baseline\n", Files.readString(resource.path.resolve("notes.txt")));
    Files.writeString(resource.path.resolve("notes.txt"), "child commit");
    repo.gitAt(resource.path, "add", "notes.txt");
    repo.gitAt(resource.path, "commit", "-m", "子目录前进");
    Path grandchild = manager.create(resource.path, "grandchild", new CancellationToken()).path();
    assertEquals("child commit", Files.readString(grandchild.resolve("notes.txt")));
  }

  @Test
  void invalidRecoveryDoesNotRewriteResourceMetadata() throws Exception {
    new GitRepositoryFixture(root);
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    Path child = manager.create(root, "child", new CancellationToken()).path();
    Path record = root.resolve(".mewcode/worktree-state/resources/child.json");
    String before = Files.readString(record);
    var time = Files.getLastModifiedTime(record);
    Files.writeString(child.resolve(".git"), "gitdir: /invalid");
    assertThrows(
        WorktreeException.class, () -> manager.create(root, "child", new CancellationToken()));
    assertEquals(before, Files.readString(record));
    assertEquals(time, Files.getLastModifiedTime(record));
  }

  @Test
  void trackedOrUnignoredManagementRegionsAreRejected() throws Exception {
    var repo = new GitRepositoryFixture(root);
    Files.createDirectories(root.resolve(".mewcode/worktrees"));
    Files.writeString(root.resolve(".mewcode/worktrees/tracked"), "old");
    repo.git("add", ".mewcode/worktrees/tracked");
    repo.git("commit", "-m", "管理冲突");
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session");
    assertThrows(
        WorktreeException.class, () -> manager.create(root, "refused", new CancellationToken()));
    assertEquals("old", Files.readString(root.resolve(".mewcode/worktrees/tracked")));
  }

  @Test
  void simultaneousSameNameRequestsHaveOnlyOneCreator() throws Exception {
    new GitRepositoryFixture(root);
    var started = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var runner =
        new GitCommandRunner(
            java.time.Duration.ofSeconds(10),
            builder -> {
              if (builder.command().contains("worktree") && builder.command().contains("add")) {
                started.countDown();
                try {
                  if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                    throw new java.io.IOException("测试等待超时");
                } catch (InterruptedException error) {
                  Thread.currentThread().interrupt();
                  throw new java.io.IOException("测试中断");
                }
              }
              return builder.start();
            });
    var manager = new WorktreeManager(root, new WorktreeConfig(), "session", runner);
    try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var first = threads.submit(() -> manager.create(root, "race", new CancellationToken()));
      try {
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertThrows(
            WorktreeException.class, () -> manager.create(root, "race", new CancellationToken()));
      } finally {
        release.countDown();
      }
      assertTrue(Files.isDirectory(first.get(5, java.util.concurrent.TimeUnit.SECONDS).path()));
    }
    assertEquals(1, manager.list().size());
  }
}
