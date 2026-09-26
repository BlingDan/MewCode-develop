package com.mewcode.worktree;

import com.mewcode.agent.CancellationToken;
import com.mewcode.instructions.InstructionLoader;
import com.mewcode.prompt.PromptBuilder;
import com.mewcode.prompt.SystemPromptBundle;
import com.mewcode.skill.SkillCatalog;
import com.mewcode.subagent.AgentCatalog;
import com.mewcode.tool.FileStateCache;
import com.mewcode.tool.ToolExecutionContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** 每个 Agent 的目录绑定；缓存以绝对路径区分，已发出的调用不跟随之后的切换。 */
public final class AgentWorkspace {
  private final Path initialCwd;
  private final Path userHome;
  private final String sessionId;
  private final String agentId;
  private final FileStateCache fileCache;
  private final WorktreeManager manager;
  private final Map<Path, SystemPromptBundle> prompts = new ConcurrentHashMap<>();
  private final Map<Path, SkillCatalog> skills = new ConcurrentHashMap<>();
  private final Map<Path, AgentCatalog> agents = new ConcurrentHashMap<>();
  private final ReentrantLock transition = new ReentrantLock();
  private Path cwd;
  private WorktreeSession session;
  private WorktreeSessionStore.Resource resource;
  private Duration timeout = ToolExecutionContext.DEFAULT_TIMEOUT;

  public AgentWorkspace(
      Path cwd,
      String sessionId,
      String agentId,
      FileStateCache fileCache,
      WorktreeManager manager) {
    this(cwd, Path.of(System.getProperty("user.home")), sessionId, agentId, fileCache, manager);
  }

  public AgentWorkspace(
      Path cwd,
      Path userHome,
      String sessionId,
      String agentId,
      FileStateCache fileCache,
      WorktreeManager manager) {
    try {
      this.initialCwd = cwd.toRealPath();
    } catch (java.io.IOException error) {
      throw new IllegalArgumentException("工作目录不存在");
    }
    this.cwd = initialCwd;
    this.userHome = userHome.toAbsolutePath().normalize();
    WorktreeSessionStore.validateId(sessionId);
    WorktreeSessionStore.validateId(agentId);
    this.sessionId = sessionId;
    this.agentId = agentId;
    this.fileCache = java.util.Objects.requireNonNull(fileCache);
    this.manager = java.util.Objects.requireNonNull(manager);
  }

  public synchronized Path currentCwd() {
    return cwd;
  }

  public synchronized Optional<WorktreeSession> currentSession() {
    return Optional.ofNullable(session);
  }

  public String sessionId() {
    return sessionId;
  }

  public String agentId() {
    return agentId;
  }

  public Path initialCwd() {
    return initialCwd;
  }

  public WorktreeManager manager() {
    return manager;
  }

  public synchronized ToolExecutionContext capture(CancellationToken token) {
    return new ToolExecutionContext(
        cwd,
        timeout,
        fileCache,
        token,
        null,
        false,
        new Scope(manager, manager.repositoryRoot(), cwd, agentId, resource));
  }

  public SystemPromptBundle systemPrompt() {
    return promptAt(currentCwd());
  }

  SystemPromptBundle promptAt(Path path) {
    Path key = path.toAbsolutePath().normalize();
    return prompts.computeIfAbsent(
        key, p -> PromptBuilder.buildBundle(p, new InstructionLoader(p, userHome).load().text()));
  }

  public SkillCatalog skillCatalog() {
    Path key = currentCwd();
    return skills.computeIfAbsent(key, p -> SkillCatalog.load(p, userHome));
  }

  public AgentCatalog agentCatalog(int defaultMaxTurns) {
    Path key = currentCwd();
    return agents.computeIfAbsent(
        key, p -> AgentCatalog.load(p, userHome, List.of(), defaultMaxTurns));
  }

  void prepare(Path path) {
    promptAt(path);
  }

  void beginTransition() {
    if (!transition.tryLock())
      throw WorktreeManager.failure("切换", "此 Agent 正在切换目录", currentCwd(), null);
  }

  void endTransition() {
    transition.unlock();
  }

  synchronized void bind(WorktreeSession next, WorktreeSessionStore.Resource nextResource) {
    session = next;
    resource = nextResource;
    cwd = next.worktreePath();
  }

  synchronized void restore(Path original) {
    cwd = original;
    session = null;
    resource = null;
  }

  synchronized void bindChild(WorktreeSessionStore.Resource nextResource) {
    cwd = nextResource.path;
    resource = nextResource;
  }

  /** 运行时推导的范围；模型参数和外部路径授权不能修改。 */
  public static final class Scope {
    private final WorktreeManager manager;
    private final Path storageRoot;
    private final Path cwd;
    private final String owner;
    private final String slug;
    private final String recordId;
    private final Path gitDir;
    private final Path common;
    private final List<Path> shared;
    private final String hooks;
    private final String hooksMode;

    private Scope(
        WorktreeManager manager,
        Path root,
        Path cwd,
        String owner,
        WorktreeSessionStore.Resource resource) {
      this.manager = manager;
      this.storageRoot = root;
      this.cwd = cwd;
      this.owner = owner;
      this.slug = resource == null ? null : resource.slug;
      this.recordId = resource == null ? null : resource.recordId;
      this.gitDir = resource == null ? null : resource.gitDir;
      this.common = resource == null ? null : resource.gitCommonDir;
      this.shared = resource == null ? List.of() : List.copyOf(resource.sharedDirectories);
      this.hooks = resource == null ? "" : resource.hooksPath;
      this.hooksMode = resource == null ? "NONE" : resource.hooksConfigurationMode;
    }

    public boolean isolated() {
      return slug != null;
    }

    public Path storageRoot() {
      return storageRoot;
    }

    public List<Path> sharedDirectories() {
      return shared;
    }

    public Path gitDirectory() {
      return gitDir;
    }

    public Path gitCommonDirectory() {
      return common;
    }

    public Path cwd() {
      return cwd;
    }

    public AutoCloseable retain() {
      return slug == null ? () -> {} : manager.retain(slug, recordId, owner);
    }

    public void applyEnvironment(Map<String, String> environment) {
      PostCreationSetup.addHooksEnvironment(environment, hooks, hooksMode);
    }

    public List<Path> writableScopes() {
      if (!isolated()) return List.of(cwd);
      String prefix = "codex/worktree/" + SlugValidator.validate(slug);
      return List.of(
          cwd,
          gitDir,
          common.resolve("objects"),
          common.resolve("refs/heads/" + prefix),
          common.resolve("logs/refs/heads/" + prefix));
    }
  }
}
