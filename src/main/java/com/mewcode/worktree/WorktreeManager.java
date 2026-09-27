package com.mewcode.worktree;

import com.mewcode.agent.CancellationToken;
import com.mewcode.config.WorktreeConfig;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** 固定存储根、每资源锁与显式 cwd 的生命周期入口；Git 等待不占全局状态锁。 */
public final class WorktreeManager {
  public record WorktreeInfo(Path path, String branch, Instant createdAt) {}

  private final Path root;
  private final WorktreeConfig config;
  private final String sessionId;
  final GitCommandRunner git;
  final WorktreeSessionStore store = new WorktreeSessionStore();
  private final ConcurrentHashMap<String, ReentrantLock> operations = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, List<String>> warnings = new ConcurrentHashMap<>();

  public WorktreeManager(Path root, WorktreeConfig config, String sessionId) {
    this(root, config, sessionId, new GitCommandRunner());
  }

  WorktreeManager(Path root, WorktreeConfig config, String sessionId, GitCommandRunner git) {
    try {
      this.root = root.toRealPath();
    } catch (IOException error) {
      throw new IllegalArgumentException("项目目录不存在");
    }
    config.validate();
    WorktreeSessionStore.validateId(sessionId);
    this.config = config;
    this.sessionId = sessionId;
    this.git = git;
  }

  public Path repositoryRoot() {
    return root;
  }

  public List<String> warnings(String slug) {
    return warnings.getOrDefault(slug, List.of());
  }

  public WorktreeInfo create(Path sourceCwd, String slug, CancellationToken token) {
    return info(createResource(sourceCwd, slug, null, false, "main", token));
  }

  WorktreeSessionStore.Resource createResource(
      Path sourceCwd,
      String slug,
      String frozenHead,
      boolean temporary,
      String agentId,
      CancellationToken token) {
    String flat = SlugValidator.validate(slug);
    ReentrantLock operation = begin(slug);
    WorktreeSessionStore.Resource resource = null;
    boolean createdHere = false;
    try {
      Path target = SlugValidator.safePath(root, ".mewcode/worktrees/" + flat);
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        resource = required(slug);
        store.verifyReady(root, resource);
        probeUnused(resource);
        return resource;
      }
      if (store.loadResource(root, slug).isPresent())
        throw failure("创建", "同名资源有未处理的残留", target, SlugValidator.branch(slug));
      Path source = sourceCwd.toRealPath();
      // 只在新建入口固定提交，已有目录恢复绝不执行 Git。
      String base = frozenHead == null ? freezeHead(source, token) : frozenHead;
      if (!base.matches("[0-9a-f]{40,64}")) throw failure("创建", "提交基线无效", target, null);
      Path common = commonFor(source, token);
      if (!common.equals(WorktreeSessionStore.commonDirectory(root)))
        throw failure("创建", "来源不属于初始仓库", target, null);
      if (!git.run(source, token, "rev-parse", "--verify", base + "^{commit}").strip().equals(base))
        throw failure("创建", "提交基线不是有效提交", target, null);
      String branch = SlugValidator.branch(slug);
      var reference =
          git.execute(source, token, "show-ref", "--verify", "--quiet", "refs/heads/" + branch);
      if (reference.exitCode() == 0) throw failure("创建", "同名分支已存在", target, branch);
      if (reference.exitCode() != 1) throw failure("创建", "不能确认分支是否存在", target, branch);
      registerExcludes(source, common, token);
      resource = new WorktreeSessionStore.Resource();
      resource.slug = slug;
      resource.path = target;
      resource.branch = branch;
      resource.sourceCwd = source;
      resource.baseCommit = base;
      resource.gitCommonDir = common;
      resource.temporary = temporary;
      resource.createdBySessionId = sessionId;
      resource.createdByAgentId = agentId;
      resource.createdAt = Instant.now();
      resource.lastUsedAt = resource.createdAt;
      resource.directoryPresent = "NO";
      resource.branchPresent = "NO";
      Path lockPath = WorktreeSessionStore.lockPath(root, slug);
      Files.createDirectories(lockPath.getParent());
      // 不抢占旧的锁文件；同名残留需要明确处理。
      try (FileChannel channel =
              FileChannel.open(
                  lockPath,
                  StandardOpenOption.CREATE_NEW,
                  StandardOpenOption.READ,
                  StandardOpenOption.WRITE);
          FileLock held = channel.tryLock()) {
        if (held == null) throw failure("创建", "资源被另一个进程占用", target, branch);
        store.saveResource(root, resource);
        createdHere = true;
        git.run(source, token, "worktree", "add", "-b", branch, target.toString(), base);
        resource.gitDir =
            Path.of(git.run(target, token, "rev-parse", "--absolute-git-dir").strip()).toRealPath();
        resource.directoryPresent = "YES";
        resource.branchPresent = "YES";
        store.saveResource(root, resource);
        var setup = new PostCreationSetup(config, git);
        warnings.put(slug, setup.perform(source, target, token));
        resource.sharedDirectories = setup.sharedDirectories();
        resource.hooksPath = setup.hooksPath();
        resource.hooksConfigurationMode = setup.hooksConfigurationMode();
        resource.state = WorktreeSessionStore.State.READY;
        store.verifyReady(root, resource);
        store.saveResource(root, resource);
      }
      return resource;
    } catch (IOException | OverlappingFileLockException error) {
      if (createdHere && !rollbackNewResource(resource)) recordPartial(resource, "文件系统或资源锁操作失败");
      throw failure(
          "创建",
          "文件系统或资源锁操作失败，保留实际残留",
          resource == null ? null : resource.path,
          resource == null ? null : resource.branch);
    } catch (RuntimeException error) {
      if (createdHere && resource != null && resource.state != WorktreeSessionStore.State.READY) {
        if (rollbackNewResource(resource))
          throw failure("创建", "必要初始化失败，新建资源已安全回滚", resource.path, resource.branch);
        recordPartial(resource, "创建或必要初始化未完成");
      }
      throw error;
    } finally {
      operation.unlock();
    }
  }

  public String freezeHead(Path source, CancellationToken token) {
    String head = git.run(source, token, "rev-parse", "--verify", "HEAD^{commit}").strip();
    if (!head.matches("[0-9a-f]{40,64}")) throw failure("基线", "无法确认已提交 HEAD", source, null);
    return head;
  }

  private Path commonFor(Path source, CancellationToken token) throws IOException {
    Path common =
        Path.of(
                git.run(source, token, "rev-parse", "--path-format=absolute", "--git-common-dir")
                    .strip())
            .toRealPath();
    return common;
  }

  public List<WorktreeInfo> list() {
    try {
      var result = new ArrayList<WorktreeInfo>();
      for (var resource : store.resources(root)) {
        if (resource.state == WorktreeSessionStore.State.READY) {
          store.verifyReady(root, resource);
          result.add(info(resource));
        }
      }
      return List.copyOf(result);
    } catch (IOException error) {
      throw failure("列表", "资源记录或归属无法验证", root, null);
    }
  }

  WorktreeSessionStore.Resource required(String slug) throws IOException {
    return store.loadResource(root, slug).orElseThrow(() -> new IOException("资源身份记录缺失"));
  }

  private WorktreeInfo info(WorktreeSessionStore.Resource resource) {
    return new WorktreeInfo(resource.path, resource.branch, resource.createdAt);
  }

  private void registerExcludes(Path source, Path common, CancellationToken token)
      throws IOException {
    List<String> regions =
        List.of(".mewcode/worktrees", ".mewcode/worktree-state", ".mewcode/context");
    for (String region : regions) {
      if (!git.run(source, token, "ls-files", "-z", "--", region).isEmpty())
        throw failure("忽略规则", "管理区域已被 Git 跟踪，请先调整仓库", source, null);
    }
    Path exclude = common.resolve("info/exclude");
    WorktreeSessionStore.noLinks(common, exclude);
    Files.createDirectories(exclude.getParent());
    String content = Files.exists(exclude) ? Files.readString(exclude) : "";
    StringBuilder updated = new StringBuilder(content);
    if (!content.isEmpty() && !content.endsWith("\n")) updated.append('\n');
    for (String region : regions) {
      String rule = "/" + region + "/";
      if (!content.lines().anyMatch(rule::equals)) updated.append(rule).append('\n');
    }
    if (!updated.toString().equals(content)) Files.writeString(exclude, updated);
    for (String region : regions) {
      var ignored =
          git.execute(
              source, token, "check-ignore", "--no-index", "-q", region + "/__mewcode_probe__");
      if (ignored.exitCode() != 0) throw failure("忽略规则", "仓库否定规则使管理区域可追踪，请调整规则", source, null);
    }
  }

  private void probeUnused(WorktreeSessionStore.Resource resource) throws IOException {
    Path path = WorktreeSessionStore.lockPath(root, resource.slug);
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        FileLock held = channel.tryLock(0, Long.MAX_VALUE, true)) {
      if (held == null) throw new IOException("工作树被占用");
    } catch (OverlappingFileLockException error) {
      throw new IOException("工作树被当前进程占用");
    }
  }

  ReentrantLock begin(String slug) {
    synchronized (active) {
      Active slot = active.get(slug);
      if (slot != null && slot.prepared != null)
        throw failure("占用", "当前目录生命周期动作尚未提交", slot.resource.path, slot.resource.branch);
    }
    return beginReserved(slug);
  }

  private ReentrantLock beginReserved(String slug) {
    SlugValidator.validate(slug);
    ReentrantLock lock = operations.computeIfAbsent(slug, ignored -> new ReentrantLock());
    if (!lock.tryLock()) throw failure("占用", "资源正有其他操作", null, SlugValidator.branch(slug));
    return lock;
  }

  private boolean rollbackNewResource(WorktreeSessionStore.Resource resource) {
    if (resource == null
        || resource.gitDir == null
        || !Files.isDirectory(resource.path, LinkOption.NOFOLLOW_LINKS)) return false;
    Active slot = null;
    WorktreeSessionStore.State original = resource.state;
    try {
      var saved = required(resource.slug);
      if (!saved.recordId.equals(resource.recordId)) return false;
      resource.state = WorktreeSessionStore.State.READY;
      store.verifyReady(root, resource);
      slot = acquireOwner(resource, "init-rollback");
      reserveDelete(slot);
      return removeHeld(resource, false, new CancellationToken());
    } catch (IOException | RuntimeException error) {
      return false;
    } finally {
      if (resource.state == WorktreeSessionStore.State.READY) resource.state = original;
      if (slot != null) releaseOwner(slot);
    }
  }

  private void recordPartial(WorktreeSessionStore.Resource resource, String reason) {
    if (resource == null) return;
    resource.state = WorktreeSessionStore.State.PARTIAL;
    resource.lastError = reason;
    resource.directoryPresent =
        Files.exists(resource.path, LinkOption.NOFOLLOW_LINKS) ? "YES" : "NO";
    // 未运行完整 Git 检查时不伪造分支是否存在。
    resource.branchPresent = "UNKNOWN";
    try {
      store.saveResource(root, resource);
    } catch (IOException ignored) {
    }
  }

  private final java.util.Map<String, Active> active = new java.util.LinkedHashMap<>();

  private static final class Active {
    final WorktreeSessionStore.Resource resource;
    final String owner;
    final FileChannel channel;
    final FileLock fileLock;
    int uses;
    boolean ownerPresent = true;
    boolean deleting;
    PreparedExit prepared;

    Active(
        WorktreeSessionStore.Resource resource,
        String owner,
        FileChannel channel,
        FileLock fileLock) {
      this.resource = resource;
      this.owner = owner;
      this.channel = channel;
      this.fileLock = fileLock;
    }
  }

  private Active acquireOwner(WorktreeSessionStore.Resource resource, String owner)
      throws IOException {
    synchronized (active) {
      if (active.containsKey(resource.slug)) throw new IOException("资源已有使用者");
    }
    Path lockPath = WorktreeSessionStore.lockPath(root, resource.slug);
    FileChannel channel =
        FileChannel.open(lockPath, StandardOpenOption.READ, StandardOpenOption.WRITE);
    FileLock lock = null;
    try {
      lock = channel.tryLock();
      if (lock == null) throw new IOException("资源被另一进程占用");
      Active slot = new Active(resource, owner, channel, lock);
      synchronized (active) {
        if (active.putIfAbsent(resource.slug, slot) != null) throw new IOException("资源竞争");
      }
      return slot;
    } catch (IOException | OverlappingFileLockException error) {
      if (lock != null) lock.release();
      channel.close();
      throw new IOException("无法独占资源");
    }
  }

  void markUnconfirmedProcess(String slug, String recordId) {
    Active slot;
    synchronized (active) {
      slot = active.get(slug);
      if (slot == null || !slot.resource.recordId.equals(recordId)) return;
      slot.resource.lastError = "命令进程停止状态未知";
    }
    try {
      store.saveResource(root, slot.resource);
    } catch (IOException ignored) {
    }
  }

  void verifyCommandLayout(String slug, String recordId) {
    try {
      var resource = required(slug);
      if (!resource.recordId.equals(recordId)) throw new IOException("资源身份变化");
      store.verifyReady(root, resource);
      for (Path scope :
          List.of(
              resource.gitDir,
              resource.gitCommonDir.resolve("objects"),
              resource.gitCommonDir.resolve(
                  "refs/heads/codex/worktree/" + SlugValidator.validate(slug)),
              resource.gitCommonDir.resolve(
                  "logs/refs/heads/codex/worktree/" + SlugValidator.validate(slug)))) {
        WorktreeSessionStore.noLinks(resource.gitCommonDir, scope);
        if (!Files.isDirectory(scope, LinkOption.NOFOLLOW_LINKS))
          throw new IOException("Git 写入范围不能确认");
      }
    } catch (IOException error) {
      throw failure("命令隔离", "Git 布局无法验证，未扩大写入范围", root, SlugValidator.branch(slug));
    }
  }

  AutoCloseable retain(String slug, String recordId, String owner) {
    Active slot;
    synchronized (active) {
      slot = active.get(slug);
      if (slot == null
          || !slot.resource.recordId.equals(recordId)
          || !slot.owner.equals(owner)
          || slot.deleting) throw failure("占用", "调用所属资源已不可用", null, SlugValidator.branch(slug));
      slot.uses++;
    }
    var released = new java.util.concurrent.atomic.AtomicBoolean();
    return () -> {
      if (!released.compareAndSet(false, true)) return;
      boolean close;
      synchronized (active) {
        slot.uses--;
        if (slot.uses == 0 && slot.prepared != null && !slot.prepared.committing)
          slot.prepared = null;
        close = !slot.ownerPresent && slot.uses == 0;
        if (close) {
          slot.deleting = true;
        }
      }
      if (close) closeActive(slot);
    };
  }

  private void releaseOwner(Active slot) {
    boolean close;
    synchronized (active) {
      slot.ownerPresent = false;
      close = slot.uses == 0;
      if (close) slot.deleting = true;
    }
    if (close) closeActive(slot);
  }

  private void closeActive(Active slot) {
    try {
      if (Files.isDirectory(slot.resource.path)) {
        slot.resource.lastUsedAt = Instant.now();
        store.saveResource(root, slot.resource);
      }
    } catch (IOException ignored) {
      /* 不影响占用释放；旧时间不能绕过清理成果检查。 */
    }
    try {
      slot.fileLock.release();
    } catch (IOException ignored) {
    }
    try {
      slot.channel.close();
    } catch (IOException ignored) {
    }
    synchronized (active) {
      active.remove(slot.resource.slug, slot);
    }
  }

  void bindChild(AgentWorkspace workspace, WorktreeSessionStore.Resource resource) {
    Active slot = null;
    try {
      store.verifyReady(root, resource);
      workspace.prepare(resource.path);
      slot = acquireOwner(resource, workspace.agentId());
      workspace.bindChild(resource);
    } catch (IOException | RuntimeException error) {
      if (slot != null) releaseOwner(slot);
      throw failure("子任务启动", "子目录资源加载或占用失败", resource.path, resource.branch);
    }
  }

  void releaseChild(String slug, String recordId, String owner) {
    Active slot;
    synchronized (active) {
      slot = active.get(slug);
      if (slot == null || !slot.resource.recordId.equals(recordId) || !slot.owner.equals(owner))
        return;
    }
    releaseOwner(slot);
  }

  boolean removeKnown(String slug, String recordId, CancellationToken token) {
    return removeInternal(slug, null, recordId, token);
  }

  public WorktreeSession enter(AgentWorkspace workspace, String slug) {
    workspace.beginTransition();
    ReentrantLock operation = null;
    Active slot = null;
    try {
      if (workspace.currentSession().isPresent())
        throw failure("进入", "已在工作树内，请先退出", workspace.currentCwd(), null);
      operation = begin(slug);
      var resource = required(slug);
      store.verifyReady(root, resource);
      slot = acquireOwner(resource, workspace.agentId());
      Path original = workspace.currentCwd();
      CancellationToken token = new CancellationToken();
      String head = freezeHead(original, token);
      var branch = git.execute(original, token, "symbolic-ref", "--quiet", "--short", "HEAD");
      if (branch.exitCode() != 0 && branch.exitCode() != 1)
        throw failure("进入", "无法确认原分支", original, null);
      workspace.prepare(resource.path);
      var session =
          new WorktreeSession(
              original,
              resource.path,
              slug,
              resource.branch,
              branch.exitCode() == 0 ? branch.output().strip() : "",
              head,
              workspace.sessionId(),
              workspace.agentId(),
              0);
      resource.lastUsedAt = Instant.now();
      store.saveResource(root, resource);
      store.save(root, session);
      workspace.bind(session, resource);
      return session;
    } catch (IOException error) {
      throw failure(
          "进入", "记录或独占使用验证失败，原目录保持不变", workspace.currentCwd(), SlugValidator.branch(slug));
    } finally {
      if (slot != null && workspace.currentSession().isEmpty()) releaseOwner(slot);
      if (operation != null) operation.unlock();
      workspace.endTransition();
    }
  }

  public boolean restore(AgentWorkspace workspace) {
    workspace.beginTransition();
    ReentrantLock operation = null;
    Active slot = null;
    try {
      if (workspace.currentSession().isPresent())
        throw failure("恢复", "会话已经进入工作树", workspace.currentCwd(), null);
      var saved = store.load(root, workspace.sessionId());
      if (saved.isEmpty()) return false;
      var session = saved.orElseThrow();
      if (!session.agentId().equals(workspace.agentId())
          || !session.originalCwd().equals(workspace.initialCwd())) throw new IOException("会话归属不符");
      operation = begin(session.worktreeName());
      var resource = required(session.worktreeName());
      if (!session.worktreePath().equals(resource.path)
          || !session.worktreeBranch().equals(resource.branch)) throw new IOException("会话资源不符");
      store.verifyReady(root, resource);
      slot = acquireOwner(resource, workspace.agentId());
      workspace.prepare(resource.path);
      workspace.bind(session, resource);
      return true;
    } catch (IOException error) {
      throw failure("恢复", "保存现场无效或被占用，未切换目录", workspace.currentCwd(), null);
    } finally {
      if (slot != null && workspace.currentSession().isEmpty()) releaseOwner(slot);
      if (operation != null) operation.unlock();
      workspace.endTransition();
    }
  }

  public void exit(AgentWorkspace workspace, boolean delete, CancellationToken token) {
    workspace.beginTransition();
    ReentrantLock operation = null;
    try {
      WorktreeSession session =
          workspace
              .currentSession()
              .orElseThrow(() -> failure("退出", "当前未进入工作树", workspace.currentCwd(), null));
      operation = begin(session.worktreeName());
      Active slot;
      synchronized (active) {
        slot = active.get(session.worktreeName());
        if (slot == null || !slot.owner.equals(workspace.agentId()))
          throw failure("退出", "使用者归属不符", session.worktreePath(), session.worktreeBranch());
      }
      workspace.prepare(session.originalCwd());
      if (delete) {
        reserveDelete(slot);
        try {
          removeHeld(slot.resource, false, token);
        } finally {
          if (!Files.exists(session.worktreePath(), LinkOption.NOFOLLOW_LINKS)) {
            try {
              store.clear(root, workspace.sessionId());
            } finally {
              workspace.restore(session.originalCwd());
              releaseOwner(slot);
            }
          } else
            synchronized (active) {
              slot.deleting = false;
            }
        }
      } else {
        store.clear(root, workspace.sessionId());
        workspace.restore(session.originalCwd());
        releaseOwner(slot);
      }
    } catch (IOException error) {
      throw failure("退出", "现场记录操作失败", workspace.currentCwd(), null);
    } finally {
      if (operation != null) operation.unlock();
      workspace.endTransition();
    }
  }

  private void reserveDelete(Active slot) {
    synchronized (active) {
      if (slot.uses != 0 || slot.deleting)
        throw failure("删除", "仍有工具或 Hook 尚未实际结束", slot.resource.path, slot.resource.branch);
      slot.deleting = true;
    }
  }

  /** 结果中的内部对象只由受信工具构造，模型的 JSON 参数不能构造或授权此对象。 */
  public static final String FINALIZATION_KEY = "worktree_finalization";

  public final class PreparedExit {
    private final AgentWorkspace workspace;
    private final WorktreeSession session;
    private final Active slot;
    private final boolean delete;
    private final CancellationToken token;
    private final java.util.concurrent.atomic.AtomicBoolean finished =
        new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean committing;

    private PreparedExit(
        AgentWorkspace workspace,
        WorktreeSession session,
        Active slot,
        boolean delete,
        CancellationToken token) {
      this.workspace = workspace;
      this.session = session;
      this.slot = slot;
      this.delete = delete;
      this.token = token;
    }

    public boolean deletesDirectory() {
      return delete;
    }

    public Path currentCwd() {
      return workspace.currentCwd();
    }

    public Path target() {
      return session.worktreePath();
    }

    public void beginCommit() {
      committing = true;
    }

    public void commit() {
      if (!finished.compareAndSet(false, true))
        throw failure("提交", "生命周期动作已结束", session.worktreePath(), session.worktreeBranch());
      ReentrantLock operation = null;
      boolean transitionHeld = false;
      try {
        workspace.beginTransition();
        transitionHeld = true;
        operation = beginReserved(session.worktreeName());
        if (token.isCancelled())
          throw failure("提交", "操作已取消，保留目录", session.worktreePath(), session.worktreeBranch());
        if (!workspace.currentSession().filter(session::equals).isPresent())
          throw failure("提交", "当前会话已变化", session.worktreePath(), session.worktreeBranch());
        synchronized (active) {
          if (slot.prepared != this || !slot.ownerPresent || slot.deleting)
            throw failure("提交", "生命周期准备状态无效", session.worktreePath(), session.worktreeBranch());
          if (delete && slot.uses != 0)
            throw failure(
                "删除",
                "Post Hook 或其他调用尚未实际结束，须保留",
                session.worktreePath(),
                session.worktreeBranch());
          if (delete) slot.deleting = true;
        }
        workspace.prepare(session.originalCwd());
        if (delete) {
          try {
            removeHeld(slot.resource, false, token);
          } finally {
            if (!Files.exists(session.worktreePath(), LinkOption.NOFOLLOW_LINKS)) {
              try {
                store.clear(root, workspace.sessionId());
              } finally {
                workspace.restore(session.originalCwd());
                releaseOwner(slot);
              }
            }
          }
        } else {
          store.clear(root, workspace.sessionId());
          workspace.restore(session.originalCwd());
          releaseOwner(slot);
        }
      } catch (IOException error) {
        throw failure("提交", "现场记录操作失败；以实际目录状态为准", session.worktreePath(), session.worktreeBranch());
      } finally {
        synchronized (active) {
          if (slot.prepared == this) slot.prepared = null;
          slot.deleting = false;
        }
        if (operation != null) operation.unlock();
        if (transitionHeld) workspace.endTransition();
      }
    }
  }

  public PreparedExit prepareExit(
      AgentWorkspace workspace, boolean delete, CancellationToken token) {
    workspace.beginTransition();
    ReentrantLock operation = null;
    try {
      var session =
          workspace
              .currentSession()
              .orElseThrow(() -> failure("准备退出", "当前未进入工作树", workspace.currentCwd(), null));
      operation = begin(session.worktreeName());
      Active slot;
      synchronized (active) {
        slot = active.get(session.worktreeName());
        if (slot == null
            || !slot.owner.equals(workspace.agentId())
            || !slot.ownerPresent
            || slot.deleting)
          throw failure("准备退出", "当前使用者无法验证", session.worktreePath(), session.worktreeBranch());
        if (delete && slot.uses > 2)
          throw failure(
              "删除", "仍有其他调用或 Hook 使用目录", session.worktreePath(), session.worktreeBranch());
      }
      store.verifyReady(root, slot.resource);
      workspace.prepare(session.originalCwd());
      if (delete) assertProtectedResults(slot.resource, token);
      PreparedExit prepared = new PreparedExit(workspace, session, slot, delete, token);
      synchronized (active) {
        if (delete && slot.uses > 2)
          throw failure(
              "删除", "仍有其他调用或 Hook 使用目录", session.worktreePath(), session.worktreeBranch());
        slot.prepared = prepared;
      }
      return prepared;
    } catch (IOException error) {
      throw failure("准备退出", "资源身份无法验证", workspace.currentCwd(), null);
    } finally {
      if (operation != null) operation.unlock();
      workspace.endTransition();
    }
  }

  private void assertProtectedResults(
      WorktreeSessionStore.Resource resource, CancellationToken token) {
    if (resource.lastError.equals("命令进程停止状态未知"))
      throw failure("删除", "无法确认此前命令停止，须保留目录", resource.path, resource.branch);
    var changes = new WorktreeChanges(git);
    var summary = changes.countChanges(resource.path, resource.baseCommit, token);
    int unpushed = changes.countUnpushedCommits(resource.path, token);
    if (summary.changedFiles() > 0 || summary.commits() > 0 || unpushed > 0)
      throw failure("删除", "有文件修改或新增提交，须保留", resource.path, resource.branch);
  }

  public boolean remove(String slug, CancellationToken token) {
    return removeInternal(slug, null, null, token);
  }

  /** 仅供明确用户命令调用；授权必须绑定当前资源身份，工具模型参数不得调用此入口。 */
  public boolean discardFromUserCommand(String slug, String recordId, CancellationToken token) {
    if (recordId == null) throw new IllegalArgumentException("用户丢弃请求缺少资源身份");
    return removeInternal(slug, recordId, recordId, token);
  }

  public String resourceIdentityForUserCommand(String slug) {
    try {
      return required(slug).recordId;
    } catch (IOException error) {
      throw failure("授权", "资源身份无法验证", root, SlugValidator.branch(slug));
    }
  }

  private boolean removeInternal(
      String slug, String discardId, String expectedId, CancellationToken token) {
    ReentrantLock operation = begin(slug);
    Active slot = null;
    try {
      var resource = required(slug);
      store.verifyReady(root, resource);
      if (expectedId != null && !resource.recordId.equals(expectedId))
        throw failure("删除", "资源身份已变化", resource.path, resource.branch);
      boolean discard = discardId != null;
      if (discard && !resource.recordId.equals(discardId))
        throw failure("删除", "丢弃授权已过期或目标不符", resource.path, resource.branch);
      slot = acquireOwner(resource, "cleanup");
      reserveDelete(slot);
      return removeHeld(resource, discard, token);
    } catch (IOException error) {
      throw failure("删除", "资源归属或占用无法验证", null, SlugValidator.branch(slug));
    } finally {
      if (slot != null) releaseOwner(slot);
      operation.unlock();
    }
  }

  private boolean removeHeld(
      WorktreeSessionStore.Resource resource, boolean discard, CancellationToken token)
      throws IOException {
    store.verifyReady(root, resource);
    if (resource.lastError.equals("命令进程停止状态未知"))
      throw failure("删除", "无法确认此前命令停止，须保留目录", resource.path, resource.branch);
    var changes = new WorktreeChanges(git);
    var summary = changes.countChanges(resource.path, resource.baseCommit, token);
    int unpushed = changes.countUnpushedCommits(resource.path, token);
    if (!discard && (summary.changedFiles() > 0 || summary.commits() > 0 || unpushed > 0))
      throw failure("删除", "有未提交文件或相对创建基线的新增提交，须保留", resource.path, resource.branch);
    try {
      if (discard) git.run(root, token, "worktree", "remove", "--force", resource.path.toString());
      else git.run(root, token, "worktree", "remove", resource.path.toString());
      resource.directoryPresent =
          Files.exists(resource.path, LinkOption.NOFOLLOW_LINKS) ? "YES" : "NO";
      if (!resource.directoryPresent.equals("NO"))
        throw failure("删除", "目录仍然存在", resource.path, resource.branch);
      git.run(root, token, "branch", discard ? "-D" : "-d", resource.branch);
      var ref =
          git.execute(
              root, token, "show-ref", "--verify", "--quiet", "refs/heads/" + resource.branch);
      if (ref.exitCode() != 1) throw failure("删除", "无法确认分支已经删除", resource.path, resource.branch);
      resource.branchPresent = "NO";
      store.clearResource(root, resource.slug);
      Files.deleteIfExists(WorktreeSessionStore.lockPath(root, resource.slug));
      warnings.remove(resource.slug);
      return true;
    } catch (RuntimeException | IOException error) {
      resource.state = WorktreeSessionStore.State.PARTIAL;
      resource.lastError = "删除未全部完成";
      resource.directoryPresent =
          Files.exists(resource.path, LinkOption.NOFOLLOW_LINKS) ? "YES" : "NO";
      try {
        var branch =
            git.execute(
                root,
                new CancellationToken(),
                "show-ref",
                "--verify",
                "--quiet",
                "refs/heads/" + resource.branch);
        resource.branchPresent =
            branch.exitCode() == 0 ? "YES" : branch.exitCode() == 1 ? "NO" : "UNKNOWN";
      } catch (RuntimeException ignored) {
        resource.branchPresent = "UNKNOWN";
      }
      try {
        store.saveResource(root, resource);
      } catch (IOException ignored) {
      }
      throw failure("删除", "删除未全部完成，保留资源记录供后续处理", resource.path, resource.branch);
    }
  }

  static WorktreeException failure(String stage, String reason, Path path, String branch) {
    return new WorktreeException(stage, reason, path, branch);
  }
}
