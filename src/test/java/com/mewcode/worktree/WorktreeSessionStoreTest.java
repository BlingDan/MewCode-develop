package com.mewcode.worktree;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorktreeSessionStoreTest {
  @TempDir Path root;

  @Test
  void sessionsAreAtomicAndIndependent() throws Exception {
    var store = new WorktreeSessionStore();
    var first = session("first");
    var second = session("second");
    store.save(root, first);
    store.save(root, second);
    assertEquals(first, store.load(root, "first").orElseThrow());
    store.clear(root, "first");
    assertTrue(store.load(root, "first").isEmpty());
    assertEquals(second, store.load(root, "second").orElseThrow());
  }

  @Test
  void damagedOrLinkedRecordsAreNotTrusted() throws Exception {
    var store = new WorktreeSessionStore();
    store.save(root, session("one"));
    Path record = root.resolve(".mewcode/worktree-state/sessions/one.json");
    Files.writeString(record, "{broken");
    assertThrows(java.io.IOException.class, () -> store.load(root, "one"));
    Files.delete(record);
    Files.createSymbolicLink(record, root.resolve("victim"));
    assertThrows(java.io.IOException.class, () -> store.save(root, session("one")));
    assertFalse(Files.exists(root.resolve("victim")));
  }

  @Test
  void unsafeManagementPathsAreRejected() throws Exception {
    Path outside = Files.createDirectory(root.resolve("outside"));
    Path repo = Files.createDirectory(root.resolve("repo"));
    Files.createSymbolicLink(repo.resolve(".mewcode"), outside);
    assertThrows(
        java.io.IOException.class, () -> new WorktreeSessionStore().save(repo, session("one")));
    assertEquals(0, Files.list(outside).count());
  }

  @Test
  void recoveryValidatesGitPointersWithoutChangingFiles() throws Exception {
    var repo = new GitRepositoryFixture(root);
    var record = resource(repo);
    var store = new WorktreeSessionStore();
    store.saveResource(root, record);
    Path head = record.gitDir.resolve("HEAD");
    var before = Files.getLastModifiedTime(head);
    store.verifyReady(root, store.loadResource(root, "child").orElseThrow());
    assertEquals(before, Files.getLastModifiedTime(head));
    Path fake = root.resolve("fake-head");
    Files.writeString(fake, Files.readString(head));
    Files.delete(head);
    Files.createSymbolicLink(head, fake);
    assertThrows(java.io.IOException.class, () -> store.verifyReady(root, record));
  }

  @Test
  void rejectsMissingBaselineObjectWithoutExecutingGit() throws Exception {
    var repo = new GitRepositoryFixture(root);
    var record = resource(repo);
    var store = new WorktreeSessionStore();
    record.baseCommit = "a".repeat(40);
    assertThrows(java.io.IOException.class, () -> store.verifyReady(root, record));
  }

  @Test
  void rejectsCorruptLooseBaselineObject() throws Exception {
    var repo = new GitRepositoryFixture(root);
    var record = resource(repo);
    Path object =
        root.resolve(".git/objects")
            .resolve(record.baseCommit.substring(0, 2))
            .resolve(record.baseCommit.substring(2));
    Files.delete(object);
    Files.writeString(object, "not-a-git-object");
    assertThrows(
        java.io.IOException.class, () -> new WorktreeSessionStore().verifyReady(root, record));
  }

  @Test
  void verifiesPackedBaselineWithFilesystemReadsOnly() throws Exception {
    var repo = new GitRepositoryFixture(root);
    var record = resource(repo);
    var store = new WorktreeSessionStore();
    repo.git("repack", "-ad");
    repo.git("prune-packed");
    assertDoesNotThrow(() -> store.verifyReady(root, record));
  }

  private WorktreeSessionStore.Resource resource(GitRepositoryFixture repo) throws Exception {
    Path child = root.resolve(".mewcode/worktrees/child");
    String base = repo.git("rev-parse", "HEAD");
    repo.git("worktree", "add", "-b", SlugValidator.branch("child"), child.toString(), base);
    var resource = new WorktreeSessionStore.Resource();
    resource.slug = "child";
    resource.path = child.toRealPath();
    resource.branch = SlugValidator.branch("child");
    resource.sourceCwd = root.toRealPath();
    resource.baseCommit = base;
    resource.gitCommonDir = root.resolve(".git").toRealPath();
    resource.gitDir = Path.of(repo.gitAt(child, "rev-parse", "--absolute-git-dir")).toRealPath();
    resource.createdBySessionId = "session";
    resource.createdByAgentId = "agent";
    resource.createdAt = java.time.Instant.now();
    resource.lastUsedAt = resource.createdAt;
    resource.state = WorktreeSessionStore.State.READY;
    Path lock = WorktreeSessionStore.lockPath(root, "child");
    Files.createDirectories(lock.getParent());
    Files.createFile(lock);
    return resource;
  }

  private WorktreeSession session(String id) {
    return new WorktreeSession(
        root.toAbsolutePath(),
        root.resolve("child").toAbsolutePath(),
        "child",
        SlugValidator.branch("child"),
        "",
        "a".repeat(40),
        id,
        "main",
        5);
  }
}
