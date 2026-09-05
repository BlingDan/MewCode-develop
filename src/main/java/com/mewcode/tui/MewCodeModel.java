package com.mewcode.tui;

import com.mewcode.agent.AgentEvent;
import com.mewcode.agent.AgentEventStream;
import com.mewcode.agent.AgentLoopConfig;
import com.mewcode.agent.AgentMode;
import com.mewcode.agent.AgentRun;
import com.mewcode.agent.AgentTurnCoordinator;
import com.mewcode.agent.PromptAdditions;
import com.mewcode.agent.PromptRequestFactory;
import com.mewcode.command.CommandContext;
import com.mewcode.command.CommandRegistry;
import com.mewcode.compact.ContextManager;
import com.mewcode.config.McpServerConfig;
import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.instructions.InstructionLoadResult;
import com.mewcode.instructions.InstructionLoader;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.LlmClients;
import com.mewcode.mcp.McpManager;
import com.mewcode.memory.MemoryManager;
import com.mewcode.permission.BashSandbox;
import com.mewcode.permission.BashSandboxFactory;
import com.mewcode.permission.PathAuthorizationStore;
import com.mewcode.permission.PermissionGate;
import com.mewcode.permission.PermissionMode;
import com.mewcode.permission.PermissionRequest;
import com.mewcode.permission.PermissionResponse;
import com.mewcode.permission.PermissionRuleEngine;
import com.mewcode.permission.PermissionRuntime;
import com.mewcode.prompt.PromptBuilder;
import com.mewcode.prompt.SystemPromptBundle;
import com.mewcode.session.HistoryStore;
import com.mewcode.session.ResumeResult;
import com.mewcode.session.SessionInfo;
import com.mewcode.session.SessionManager;
import com.mewcode.skill.ProviderRouter;
import com.mewcode.skill.ScriptTool;
import com.mewcode.skill.SkillCatalog;
import com.mewcode.skill.SkillDefinition;
import com.mewcode.skill.SkillExecutor;
import com.mewcode.skill.SkillRun;
import com.mewcode.tool.FileStateCache;
import com.mewcode.tool.ToolApiProtocol;
import com.mewcode.tool.ToolExecutor;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import com.mewcode.tool.impl.LoadSkillTool;
import com.mewcode.tui.tea.Command;
import com.mewcode.tui.tea.KeyPressMessage;
import com.mewcode.tui.tea.Message;
import com.mewcode.tui.tea.Model;
import com.mewcode.tui.tea.QuitMessage;
import com.mewcode.tui.tea.UpdateResult;
import com.mewcode.tui.tea.WindowSizeMessage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * MewCode 终端交互模型，负责把 AgentEvent 投影为可重绘的 UI 状态。
 *
 * <p>模型不直接调用 provider 或工具：提交消息时启动 {@link AgentRun}，后续由定时 {@link StreamPollMessage} 消费事件。流式 buffer
 * 只用于实时预览和完整响应收尾， 最终历史由 Agent 协调器维护，取消时不会把半截 assistant 文本写入会话。
 */
public final class MewCodeModel implements Model, CommandContext.UIController, AutoCloseable {

  public static final String VERSION = "0.1.0";
  private static final Duration POLL_INTERVAL = Duration.ofMillis(50);
  private static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
  // streaming view 还要保留状态栏、输入框和 spinner，正文只能占剩余行数。
  private static final int STREAMING_FIXED_LINES = 10;

  private final List<ProviderConfig> providers;
  private final Path projectRoot;
  private final SystemPromptBundle systemPromptBundle;
  private final BiFunction<ProviderConfig, String, LlmClient> clientFactory;
  private final AgentLoopConfig loopConfig;
  private final List<McpServerConfig> mcpServerConfigs;
  private final PermissionRuntime permissionRuntime;
  private final CommandRegistry commandRegistry;
  private SkillCatalog skillCatalog;
  private final PathAuthorizationStore pathAuthorizationStore;
  private final BashSandbox bashSandbox;
  private final Path userHome;
  private final ConversationManager conversation = new ConversationManager();
  private final SessionManager sessionManager;
  private final MemoryManager memoryManager;
  private final List<ChatMessage> chatMessages = new ArrayList<>();
  private final StringBuilder inputBuffer = new StringBuilder();
  private final StringBuilder streamBuffer = new StringBuilder();

  private AppState state;
  private ProviderConfig selectedProvider;
  private LlmClient client;
  private AgentRun activeRun;
  private AgentEventStream streamEvents;
  private ToolExecutor toolExecutor;
  private ToolRegistry toolRegistry;
  private AgentTurnCoordinator coordinator;
  private volatile McpManager mcpManager;
  private ContextManager contextManager;
  private ProviderRouter providerRouter;
  private final PermissionGate permissionGate = new PermissionGate();
  private SkillRun activeSkillRun;
  private int providerCursor;
  private int inputCursor;
  private int spinnerFrame;
  private int width = 80;
  private int height = 24;
  private long requestStartMillis;
  private String spinnerVerb = "Imagining";
  private boolean streaming;
  private boolean ready;
  private boolean bannerPrinted;
  private boolean singleProviderPending;
  private String initializationError;
  private AgentMode agentMode = AgentMode.EXECUTE;
  private int currentIteration;
  private String pendingStreamError;
  private String usageLabel = "Token 用量：unknown";
  private PermissionRequest pendingPermission;
  private volatile boolean closed;
  private boolean compactionRun;
  private volatile String backgroundDiagnostic;
  private volatile Thread mcpInitializationThread;
  private volatile boolean mcpInitializing;
  private String pendingPrompt;
  private String pendingCompactFocus;
  private Command pendingUiCommand;
  private String confirmationText;
  private Runnable confirmationAction;
  private List<com.mewcode.command.Command> completionCandidates = List.of();
  private int completionCursor;

  public record StreamPollMessage() implements Message {}

  public record McpInitializationPollMessage() implements Message {}

  public MewCodeModel(List<ProviderConfig> providers) {
    this(providers, currentProjectRoot(), LlmClients::create);
  }

  public MewCodeModel(List<ProviderConfig> providers, Path projectRoot) {
    this(providers, projectRoot, LlmClients::create);
  }

  MewCodeModel(
      List<ProviderConfig> providers, BiFunction<ProviderConfig, String, LlmClient> clientFactory) {
    this(providers, currentProjectRoot(), clientFactory, new AgentLoopConfig());
  }

  MewCodeModel(
      List<ProviderConfig> providers,
      Path projectRoot,
      BiFunction<ProviderConfig, String, LlmClient> clientFactory) {
    this(providers, projectRoot, clientFactory, new AgentLoopConfig());
  }

  MewCodeModel(
      List<ProviderConfig> providers,
      Path projectRoot,
      BiFunction<ProviderConfig, String, LlmClient> clientFactory,
      Path userHome) {
    this(
        providers,
        projectRoot,
        clientFactory,
        new AgentLoopConfig(),
        PermissionMode.DEFAULT,
        new PermissionRuleEngine(),
        new PathAuthorizationStore(projectRoot),
        BashSandboxFactory.create(),
        List.of(),
        userHome);
  }

  public MewCodeModel(
      List<ProviderConfig> providers,
      Path projectRoot,
      BiFunction<ProviderConfig, String, LlmClient> clientFactory,
      AgentLoopConfig loopConfig) {
    this(
        providers,
        projectRoot,
        clientFactory,
        loopConfig,
        PermissionMode.DEFAULT,
        new PermissionRuleEngine(),
        new PathAuthorizationStore(projectRoot),
        BashSandboxFactory.create(),
        List.of());
  }

  public MewCodeModel(
      List<ProviderConfig> providers,
      Path projectRoot,
      BiFunction<ProviderConfig, String, LlmClient> clientFactory,
      AgentLoopConfig loopConfig,
      PermissionMode permissionMode,
      PermissionRuleEngine permissionRuleEngine,
      PathAuthorizationStore pathAuthorizationStore,
      BashSandbox bashSandbox) {
    this(
        providers,
        projectRoot,
        clientFactory,
        loopConfig,
        permissionMode,
        permissionRuleEngine,
        pathAuthorizationStore,
        bashSandbox,
        List.of());
  }

  /** 创建模型并在 provider 启动前注入已校验的 MCP Server 配置。 */
  public MewCodeModel(
      List<ProviderConfig> providers,
      Path projectRoot,
      BiFunction<ProviderConfig, String, LlmClient> clientFactory,
      AgentLoopConfig loopConfig,
      PermissionMode permissionMode,
      PermissionRuleEngine permissionRuleEngine,
      PathAuthorizationStore pathAuthorizationStore,
      BashSandbox bashSandbox,
      List<McpServerConfig> mcpServerConfigs) {
    this(
        providers,
        projectRoot,
        clientFactory,
        loopConfig,
        permissionMode,
        permissionRuleEngine,
        pathAuthorizationStore,
        bashSandbox,
        mcpServerConfigs,
        currentUserHome());
  }

  MewCodeModel(
      List<ProviderConfig> providers,
      Path projectRoot,
      BiFunction<ProviderConfig, String, LlmClient> clientFactory,
      AgentLoopConfig loopConfig,
      PermissionMode permissionMode,
      PermissionRuleEngine permissionRuleEngine,
      PathAuthorizationStore pathAuthorizationStore,
      BashSandbox bashSandbox,
      List<McpServerConfig> mcpServerConfigs,
      Path userHome) {
    this.providers = providers == null ? List.of() : List.copyOf(providers);
    this.projectRoot =
        Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
    this.userHome = Objects.requireNonNull(userHome, "userHome").toAbsolutePath().normalize();
    InstructionLoadResult instructions =
        new InstructionLoader(this.projectRoot, this.userHome).load();
    this.systemPromptBundle = PromptBuilder.buildBundle(this.projectRoot, instructions.text());
    this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
    this.loopConfig = Objects.requireNonNull(loopConfig, "loopConfig").copy();
    this.mcpServerConfigs = mcpServerConfigs == null ? List.of() : List.copyOf(mcpServerConfigs);
    this.permissionRuntime =
        new PermissionRuntime(
            Objects.requireNonNull(permissionMode, "permissionMode"),
            Objects.requireNonNull(permissionRuleEngine, "permissionRuleEngine"));
    this.commandRegistry = CommandRegistry.createDefault();
    this.skillCatalog = SkillCatalog.load(this.projectRoot, this.userHome);
    SkillCatalog.RefreshResult initialSkills =
        this.skillCatalog.refresh(
            java.util.Set.of(
                "ReadFile", "WriteFile", "EditFile", "Bash", "Glob", "Grep", LoadSkillTool.NAME),
            this.commandRegistry.reservedNames());
    this.commandRegistry.replaceSkillCommands(initialSkills.skills());
    initialSkills.diagnostics().forEach(this::recordDiagnostic);
    this.pathAuthorizationStore =
        Objects.requireNonNull(pathAuthorizationStore, "pathAuthorizationStore");
    this.bashSandbox = Objects.requireNonNull(bashSandbox, "bashSandbox");
    this.sessionManager =
        new SessionManager(this.projectRoot, this.userHome, conversation, this::recordDiagnostic);
    this.memoryManager = new MemoryManager(this.projectRoot, this.userHome, this::recordDiagnostic);
    for (String diagnostic : instructions.diagnostics()) recordDiagnostic(diagnostic);
    Thread.startVirtualThread(
        () -> {
          try {
            HistoryStore.deleteExpired(
                this.projectRoot.resolve(".mewcode/sessions"), Duration.ofDays(30));
          } catch (java.io.IOException error) {
            recordDiagnostic("session 过期清理失败。");
          }
        });
    this.agentMode = permissionMode == PermissionMode.PLAN ? AgentMode.PLAN : AgentMode.EXECUTE;
    if (this.providers.size() == 1) {
      selectedProvider = this.providers.getFirst();
      state = AppState.CHAT;
      singleProviderPending = true;
    } else {
      state = AppState.PROVIDER_SELECT;
    }
  }

  /** 初始化阶段请求一次窗口尺寸，随后再创建 provider。 */
  @Override
  public Command init() {
    return Command.checkWindowSize();
  }

  /** 处理单条 TUI 消息。 Ctrl+C 在流式态只取消当前 Loop，在空闲态才产生 Quit；Esc 只取消当前 Loop。 */
  @Override
  public UpdateResult<MewCodeModel> update(Message message) {
    if (message instanceof KeyPressMessage key && "ctrl+c".equals(key.key())) {
      if (streaming) return cancelStream();
      return UpdateResult.from(this, QuitMessage::new);
    }
    if (message instanceof KeyPressMessage key && "escape".equals(key.key())) {
      if (streaming) return cancelStream();
      completionCandidates = List.of();
      confirmationText = null;
      confirmationAction = null;
      return UpdateResult.from(this);
    }

    if (message instanceof WindowSizeMessage size) {
      width = Math.max(size.width(), 1);
      height = Math.max(size.height(), 3);
      ready = true;
      if (singleProviderPending) {
        singleProviderPending = false;
        initializeProvider();
      }
      if (state == AppState.CHAT && !bannerPrinted) {
        bannerPrinted = true;
        return UpdateResult.from(this, withMcpInitializationPoll(Command.println(renderBanner())));
      }
      return UpdateResult.from(this);
    }

    if (message instanceof StreamPollMessage) {
      return pollStream();
    }

    if (message instanceof McpInitializationPollMessage) {
      return pollMcpInitialization();
    }

    if (!(message instanceof KeyPressMessage key)) {
      return UpdateResult.from(this);
    }
    return state == AppState.PROVIDER_SELECT ? handleProviderSelection(key) : handleChatKey(key);
  }

  /** 返回当前模式的完整视图；流式长文本由动态区预览限制在终端高度内。 */
  @Override
  public String view() {
    if (!ready) return "";
    return state == AppState.PROVIDER_SELECT ? viewProviderSelection() : viewChat();
  }

  private UpdateResult<MewCodeModel> handleProviderSelection(KeyPressMessage message) {
    return switch (message.key()) {
      case "up" -> {
        if (providerCursor > 0) providerCursor--;
        yield UpdateResult.from(this);
      }
      case "down" -> {
        if (providerCursor < providers.size() - 1) providerCursor++;
        yield UpdateResult.from(this);
      }
      case "enter" -> {
        if (providers.isEmpty()) yield UpdateResult.from(this);
        selectedProvider = providers.get(providerCursor);
        initializeProvider();
        state = AppState.CHAT;
        bannerPrinted = true;
        yield UpdateResult.from(this, withMcpInitializationPoll(Command.println(renderBanner())));
      }
      default -> UpdateResult.from(this);
    };
  }

  /** 创建 provider、默认工具注册表和 Agent 协调器；失败只阻塞当前会话而不崩溃 TUI。 */
  private void initializeProvider() {
    closeContextManager();
    closeToolExecutor();
    try {
      client = clientFactory.apply(selectedProvider, systemPromptBundle.flattenedText());
      providerRouter =
          new ProviderRouter(
              providers,
              selectedProvider,
              client,
              clientFactory,
              systemPromptBundle.flattenedText());
      sessionManager.attachTitleClient(client, selectedProvider.getModel());
      memoryManager.attachClient(client, selectedProvider.getModel());
      if (toolRegistry == null) {
        toolRegistry = ToolRegistry.createDefault();
        toolRegistry.register(new LoadSkillTool());
        refreshSkills();
      }
      toolExecutor =
          new ToolExecutor(toolRegistry, projectRoot, new FileStateCache(), permissionGate);
      if (mcpManager == null) mcpManager = new McpManager(toolRegistry);
      contextManager =
          new ContextManager(projectRoot, client, selectedProvider.getContextWindowTokens());
      contextManager.resetForSession(sessionManager.currentSessionDirectory());
      ToolApiProtocol protocol =
          "anthropic".equalsIgnoreCase(selectedProvider.getProtocol())
              ? ToolApiProtocol.ANTHROPIC
              : ToolApiProtocol.OPENAI;
      coordinator =
          new AgentTurnCoordinator(
              client,
              toolRegistry,
              toolExecutor,
              conversation,
              protocol,
              loopConfig,
              new PromptRequestFactory(systemPromptBundle),
              contextManager,
              permissionGate,
              permissionRuntime,
              pathAuthorizationStore,
              bashSandbox);
      coordinator.setPromptAdditionsSupplier(
          () ->
              new PromptAdditions(
                  memoryManager.indexText(), sessionManager.consumeResumeReminder()));
      coordinator.configureSkills(
          skillCatalog, this::refreshSkills, providerRouter, this::runForkSkill);
      coordinator.setCompletionListener(
          completedTurn -> {
            sessionManager.onCompletedTurn(completedTurn);
            memoryManager.updateAsync(completedTurn);
          });
      if (mcpManager.connectedServers().isEmpty() && mcpManager.errors().isEmpty()) {
        startMcpInitialization();
      }
      initializationError = null;
    } catch (RuntimeException error) {
      closeContextManager();
      closeToolExecutor();
      client = null;
      coordinator = null;
      initializationError = "Provider initialization failed.";
    }
  }

  /** 使用入口在 TUI 出现前已发现并校验的 Skill/MCP 工具集合。 */
  public void useSkillBootstrap(SkillCatalog catalog, ToolRegistry registry, McpManager manager) {
    if (ready || client != null) throw new IllegalStateException("TUI 已开始初始化");
    this.skillCatalog = Objects.requireNonNull(catalog, "catalog");
    this.toolRegistry = Objects.requireNonNull(registry, "registry");
    this.mcpManager = Objects.requireNonNull(manager, "manager");
    commandRegistry.replaceSkillCommands(catalog.list());
  }

  /** 将耗时的 MCP 握手和工具发现移出 TUI 主事件循环。 */
  private void startMcpInitialization() {
    if (mcpServerConfigs.isEmpty() || mcpManager == null) return;
    McpManager manager = mcpManager;
    mcpInitializing = true;
    mcpInitializationThread =
        Thread.startVirtualThread(
            () -> {
              try {
                McpManager.ConnectionReport report = manager.connectAll(mcpServerConfigs);
                if (!report.errors().isEmpty())
                  recordDiagnostic(String.join("\n", report.errors()));
                refreshSkills();
              } catch (RuntimeException error) {
                if (!closed) recordDiagnostic("MCP 初始化失败。");
              } finally {
                if (mcpManager == manager) mcpInitializing = false;
                if (closed || mcpManager != manager) manager.close();
                if (Thread.currentThread() == mcpInitializationThread) {
                  mcpInitializationThread = null;
                }
              }
            });
  }

  private UpdateResult<MewCodeModel> pollMcpInitialization() {
    if (!mcpInitializing) return UpdateResult.from(this);
    return UpdateResult.from(
        this, Command.tick(POLL_INTERVAL, ignored -> new McpInitializationPollMessage()));
  }

  private Command withMcpInitializationPoll(Command command) {
    if (!mcpInitializing) return command;
    return sequence(
        List.of(
            command, Command.tick(POLL_INTERVAL, ignored -> new McpInitializationPollMessage())));
  }

  private void closeMcpManager() {
    mcpInitializing = false;
    McpManager manager = mcpManager;
    mcpManager = null;
    if (manager == null) return;
    Thread initializationThread = mcpInitializationThread;
    mcpInitializationThread = null;
    if (initializationThread != null && initializationThread.isAlive()) {
      initializationThread.interrupt();
      return;
    }
    manager.close();
  }

  private void closeToolExecutor() {
    if (toolExecutor == null) return;
    toolExecutor.close();
    toolExecutor = null;
  }

  private void closeContextManager() {
    if (contextManager == null) return;
    contextManager.close();
    contextManager = null;
  }

  /** 关闭当前 Agent 和所有 MCP/工具执行资源；重复调用安全无副作用。 */
  @Override
  public void close() {
    if (closed) return;
    closed = true;
    if (activeRun != null) activeRun.cancel();
    closeContextManager();
    closeMcpManager();
    closeToolExecutor();
    memoryManager.close();
    sessionManager.close();
    activeRun = null;
    streamEvents = null;
    coordinator = null;
    client = null;
  }

  private UpdateResult<MewCodeModel> handleChatKey(KeyPressMessage message) {
    if (streaming) {
      return pendingPermission == null ? UpdateResult.from(this) : handlePermissionKey(message);
    }

    if (confirmationAction != null) return handleConfirmationKey(message);
    if (!completionCandidates.isEmpty()) return handleCompletionKey(message);

    String key = message.key();
    return switch (key) {
      case "enter" -> submit();
      case "tab" -> completeCommand();
      case "alt+enter" -> {
        inputBuffer.insert(inputCursor, '\n');
        inputCursor++;
        yield UpdateResult.from(this);
      }
      case "backspace", "ctrl+h" -> {
        if (inputCursor > 0) {
          inputBuffer.deleteCharAt(inputCursor - 1);
          inputCursor--;
        }
        completionCandidates = List.of();
        yield UpdateResult.from(this);
      }
      case "left" -> {
        if (inputCursor > 0) inputCursor--;
        yield UpdateResult.from(this);
      }
      case "right" -> {
        if (inputCursor < inputBuffer.length()) inputCursor++;
        yield UpdateResult.from(this);
      }
      case "home", "ctrl+a" -> {
        inputCursor = lineStart(inputBuffer, inputCursor);
        yield UpdateResult.from(this);
      }
      case "end", "ctrl+e" -> {
        inputCursor = lineEnd(inputBuffer, inputCursor);
        yield UpdateResult.from(this);
      }
      default -> insertCharacters(message);
    };
  }

  private UpdateResult<MewCodeModel> handleConfirmationKey(KeyPressMessage message) {
    if ("y".equalsIgnoreCase(message.key())) {
      Runnable action = confirmationAction;
      confirmationAction = null;
      confirmationText = null;
      action.run();
      return UpdateResult.from(this, Command.println(Styles.DIM.render("操作已确认")));
    }
    if ("n".equalsIgnoreCase(message.key())) {
      confirmationAction = null;
      confirmationText = null;
      return UpdateResult.from(this, Command.println(Styles.DIM.render("操作已取消")));
    }
    return UpdateResult.from(this);
  }

  private UpdateResult<MewCodeModel> handleCompletionKey(KeyPressMessage message) {
    switch (message.key()) {
      case "up" ->
          completionCursor = Math.floorMod(completionCursor - 1, completionCandidates.size());
      case "down", "tab" -> completionCursor = (completionCursor + 1) % completionCandidates.size();
      case "enter" -> applyCompletion(completionCandidates.get(completionCursor));
      default -> completionCandidates = List.of();
    }
    return UpdateResult.from(this);
  }

  private UpdateResult<MewCodeModel> completeCommand() {
    refreshSkills();
    String input = inputBuffer.toString();
    if (!input.startsWith("/") || inputCursor != input.length() || input.indexOf(' ') >= 0) {
      return UpdateResult.from(this);
    }
    List<com.mewcode.command.Command> matches = commandRegistry.search(input.substring(1));
    if (matches.size() == 1) applyCompletion(matches.getFirst());
    else if (matches.size() > 1) {
      completionCandidates = matches;
      completionCursor = 0;
    }
    return UpdateResult.from(this);
  }

  private void applyCompletion(com.mewcode.command.Command command) {
    inputBuffer.setLength(0);
    inputBuffer.append('/').append(command.name()).append(' ');
    inputCursor = inputBuffer.length();
    completionCandidates = List.of();
  }

  private UpdateResult<MewCodeModel> handlePermissionKey(KeyPressMessage message) {
    PermissionResponse response =
        switch (message.key()) {
          case "y" -> PermissionResponse.ALLOW_ONCE;
          case "s" -> PermissionResponse.ALLOW_SESSION;
          case "a" -> PermissionResponse.ALLOW_ALWAYS;
          case "n" -> PermissionResponse.DENY;
          default -> null;
        };
    if (response == null || activeRun == null || pendingPermission == null) {
      return UpdateResult.from(this);
    }
    boolean resolved = activeRun.resolvePermission(pendingPermission.requestId(), response);
    if (!resolved) return UpdateResult.from(this);
    String messageText =
        switch (response) {
          case ALLOW_ONCE -> "已允许本次操作";
          case ALLOW_SESSION -> "已允许本会话中的同类操作";
          case ALLOW_ALWAYS -> "已永久允许同类操作";
          case DENY -> "已拒绝本次操作";
        };
    pendingPermission = null;
    return UpdateResult.from(this, Command.println(Styles.DIM.render(messageText)));
  }

  private UpdateResult<MewCodeModel> insertCharacters(KeyPressMessage message) {
    if (message.runes() == null) return UpdateResult.from(this);
    for (char character : message.runes()) {
      if (character >= 32) {
        inputBuffer.insert(inputCursor, character);
        inputCursor++;
      }
    }
    completionCandidates = List.of();
    return UpdateResult.from(this);
  }

  /** 处理一条输入：slash 命令本地生效，普通文本启动异步 Agent Loop。 */
  private UpdateResult<MewCodeModel> submit() {
    String text = inputBuffer.toString();
    if (text.isBlank()) return UpdateResult.from(this);
    inputBuffer.setLength(0);
    inputCursor = 0;

    if (text.charAt(0) == '/') return dispatchCommand(text);
    return startAgentRequest(text);
  }

  private UpdateResult<MewCodeModel> dispatchCommand(String text) {
    refreshSkills();
    var call = commandRegistry.parse(text);
    if (call.isEmpty()) {
      return UpdateResult.from(
          this, Command.println(Styles.DIM.render("未知命令：" + text.strip() + "，输入 /help 查看可用命令")));
    }
    if (call.get().command().type() == com.mewcode.command.Command.CommandType.SKILL) {
      return startSkillRequest(text, call.get());
    }
    pendingPrompt = null;
    pendingCompactFocus = null;
    pendingUiCommand = null;
    String output = commandRegistry.execute(call.get(), commandContext(call.get().args()));
    if (pendingPrompt != null) return startAgentRequest(pendingPrompt);
    if (pendingCompactFocus != null) return startManualCompaction(pendingCompactFocus);
    var commands = new ArrayList<Command>();
    if (pendingUiCommand != null) commands.add(pendingUiCommand);
    if (output != null && !output.isBlank()) {
      commands.add(Command.println(Styles.DIM.render(output)));
    }
    return UpdateResult.from(this, sequence(commands));
  }

  private UpdateResult<MewCodeModel> startAgentRequest(String text) {
    return startAgentRequest(text, null);
  }

  private UpdateResult<MewCodeModel> startAgentRequest(String text, SkillRun skills) {
    if (client == null || coordinator == null) {
      String message =
          initializationError != null ? initializationError : "No provider is available.";
      chatMessages.add(new ChatMessage("error", message, 0));
      return UpdateResult.from(this, Command.println(renderError(message, 0)));
    }

    chatMessages.add(new ChatMessage("user", text, 0));
    streamBuffer.setLength(0);
    requestStartMillis = System.currentTimeMillis();
    spinnerVerb = SpinnerVerbs.random();
    spinnerFrame = 0;
    currentIteration = 0;
    pendingStreamError = null;
    usageLabel = "Token 用量：unknown";
    compactionRun = false;
    streaming = true;

    try {
      activeSkillRun = skills;
      activeRun =
          skills == null
              ? coordinator.startRun(text, agentMode)
              : coordinator.startRun(text, agentMode, skills);
      streamEvents = activeRun.events();
    } catch (RuntimeException error) {
      activeRun = null;
      streamEvents = null;
      String detail =
          error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
      return failStream("无法启动 Agent Loop：" + safeTerminalText(detail));
    }

    return UpdateResult.from(
        this,
        Command.batch(
            Command.println(renderUser(text)),
            Command.tick(POLL_INTERVAL, ignored -> new StreamPollMessage())));
  }

  private UpdateResult<MewCodeModel> startSkillRequest(
      String original, CommandRegistry.CommandCall call) {
    SkillDefinition skill = skillCatalog.find(call.command().name()).orElse(null);
    if (skill == null) {
      return UpdateResult.from(this, Command.println(Styles.DIM.render("Skill 已被删除或更新，请重新补全后再试。")));
    }
    if (skill.meta().mode() == SkillDefinition.Mode.FORK) {
      return startForkRequest(original, skill, call.args());
    }
    SkillRun skills = new SkillRun();
    skills.activate(skill, call.args());
    return startAgentRequest(original, skills);
  }

  private UpdateResult<MewCodeModel> startForkRequest(
      String original, SkillDefinition skill, String arguments) {
    if (client == null || coordinator == null || providerRouter == null) {
      return UpdateResult.from(
          this, Command.println(renderError("Provider initialization failed.", 0)));
    }
    chatMessages.add(new ChatMessage("user", original, 0));
    streamBuffer.setLength(0);
    requestStartMillis = System.currentTimeMillis();
    spinnerVerb = "Forking";
    spinnerFrame = 0;
    currentIteration = 0;
    pendingStreamError = null;
    usageLabel = "Token 用量：unknown";
    compactionRun = false;
    streaming = true;
    AgentRun parent = new AgentRun();
    activeSkillRun = null;
    activeRun = parent;
    streamEvents = parent.events();
    List<com.mewcode.conversation.Message> history = conversation.getMessages();
    String sessionId = sessionManager.currentSessionId();
    Thread.startVirtualThread(
        () -> {
          ToolResult result =
              runForkSkill(
                  new SkillExecutor.ForkRequest(skill, arguments, history, agentMode, parent));
          if (!closed && sessionId.equals(sessionManager.currentSessionId())) {
            conversation.addExchange(original, result.content());
          }
          parent.events().publish(new AgentEvent.StreamText(result.content()));
          parent.events().publish(new AgentEvent.LoopComplete(1));
          parent.complete();
        });
    return UpdateResult.from(
        this,
        Command.batch(
            Command.println(renderUser(original)),
            Command.tick(POLL_INTERVAL, ignored -> new StreamPollMessage())));
  }

  /** 重扫 Skill，并把脚本工具和动态命令整体切换到同一 Catalog 结果。 */
  private synchronized SkillCatalog.RefreshResult refreshSkills() {
    java.util.Set<String> known =
        toolRegistry == null
            ? java.util.Set.of(
                "ReadFile", "WriteFile", "EditFile", "Bash", "Glob", "Grep", LoadSkillTool.NAME)
            : toolRegistry.ordinaryToolNames();
    SkillCatalog.RefreshResult result =
        skillCatalog.refreshHot(known, commandRegistry.reservedNames());
    if (toolRegistry != null) {
      var scripts = new ArrayList<com.mewcode.tool.Tool>();
      for (SkillDefinition skill : result.skills()) {
        for (SkillDefinition.ToolSpec spec : skill.tools()) {
          scripts.add(new ScriptTool(spec, skill.directory()));
        }
      }
      List<String> conflicts = toolRegistry.replaceSkillTools(scripts);
      if (!conflicts.isEmpty()) recordDiagnostic("Skill 工具名称冲突：" + String.join(", ", conflicts));
    }
    commandRegistry.replaceSkillCommands(result.skills());
    result.diagnostics().forEach(this::recordDiagnostic);
    if (!result.missingTools().isEmpty()) {
      recordDiagnostic(
          "Skill 引用了未知工具："
              + result.missingTools().stream()
                  .map(item -> item.skill() + "/" + item.tool())
                  .reduce((left, right) -> left + ", " + right)
                  .orElse(""));
    }
    return result;
  }

  private ToolResult runForkSkill(SkillExecutor.ForkRequest request) {
    if (providerRouter == null || toolExecutor == null) {
      return ToolResult.error("fork Skill 运行环境未初始化。");
    }
    var temporary = new ConversationManager();
    temporary.loadMessages(
        SkillExecutor.selectHistory(
            request.mainHistory(),
            request.skill().meta().context(),
            request.skill().meta().contextCount()));
    var temporaryContext =
        new ContextManager(
            projectRoot,
            providerRouter.main().client(),
            providerRouter.main().config().getContextWindowTokens());
    try {
      var child =
          new AgentTurnCoordinator(
              providerRouter.main().client(),
              toolRegistry,
              toolExecutor,
              temporary,
              providerRouter.main().protocol(),
              loopConfig,
              new PromptRequestFactory(systemPromptBundle),
              temporaryContext,
              permissionGate,
              permissionRuntime,
              pathAuthorizationStore,
              bashSandbox);
      child.setPromptAdditionsSupplier(PromptAdditions::empty);
      child.configureSkills(
          skillCatalog,
          this::refreshSkills,
          providerRouter,
          ignored -> ToolResult.error("fork Skill 不支持嵌套 fork。"));
      SkillRun skills = new SkillRun();
      skills.activate(request.skill(), request.arguments());
      return SkillExecutor.runFork(request, child, temporary, skills);
    } catch (RuntimeException error) {
      return ToolResult.error("fork Skill 执行失败。");
    } finally {
      temporaryContext.close();
    }
  }

  /** 启动 /compact；该路径不写入用户消息，也不产生正常 Agent 轮次。 */
  private UpdateResult<MewCodeModel> startManualCompaction(String focus) {
    if (client == null || coordinator == null || contextManager == null) {
      String message = initializationError != null ? initializationError : "上下文管理未初始化。";
      chatMessages.add(new ChatMessage("error", message, 0));
      return UpdateResult.from(this, Command.println(renderError(message, 0)));
    }
    if (coordinator.estimateManualCompactionTokens(agentMode) < 5_000) {
      return UpdateResult.from(
          this, Command.println(Styles.DIM.render("当前上下文不足 5000 token，无需压缩。")));
    }

    streamBuffer.setLength(0);
    requestStartMillis = System.currentTimeMillis();
    spinnerVerb = "Compacting";
    spinnerFrame = 0;
    currentIteration = 0;
    pendingStreamError = null;
    usageLabel = "Token 用量：unknown";
    compactionRun = true;
    streaming = true;
    try {
      activeRun = coordinator.startManualCompaction(agentMode, focus);
      streamEvents = activeRun.events();
    } catch (RuntimeException error) {
      activeRun = null;
      streamEvents = null;
      compactionRun = false;
      streaming = false;
      String detail =
          error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
      return UpdateResult.from(this, Command.println(renderError("无法启动上下文压缩：" + detail, 0)));
    }
    return UpdateResult.from(this, Command.tick(POLL_INTERVAL, ignored -> new StreamPollMessage()));
  }

  private CommandContext commandContext(String args) {
    return new CommandContext(
        args,
        projectRoot.toString(),
        selectedProvider == null ? "" : selectedProvider.getModel(),
        this,
        this::statusSummary,
        focus -> pendingCompactFocus = focus,
        () -> "当前 session：" + sessionManager.currentSessionId(),
        this::sessionLines,
        this::resumeSession,
        this::memorySummary,
        this::memoryLines,
        this::addMemory,
        memoryManager::clearAll,
        this::permissionSummary,
        this::permissionLines,
        this::setPermissionMode,
        this::addPermissionRule,
        permissionRuntime::reset);
  }

  private List<String> sessionLines() {
    List<SessionInfo> sessions = sessionManager.listSessions();
    if (sessions.isEmpty()) return List.of("暂无已保存的 session。");
    return sessions.stream()
        .map(
            session ->
                "%s  %s  %s  %s  messages=%d  size=%d"
                    .formatted(
                        session.id(),
                        safeTerminalText(session.title()),
                        session.modifiedAt(),
                        safeTerminalText(session.model()),
                        countMessages(session),
                        session.size()))
        .toList();
  }

  private static int countMessages(SessionInfo session) {
    try {
      return HistoryStore.countMessages(session.dir());
    } catch (java.io.IOException error) {
      return 0;
    }
  }

  private String resumeSession(String id) {
    ResumeResult result = sessionManager.resume(id);
    if (contextManager != null) contextManager.resetForSession(result.sessionDir());
    return "已恢复 session " + result.sessionId() + (result.stale() ? "（已插入过期提醒）" : "");
  }

  private String memorySummary() {
    var summary = memoryManager.summary();
    var text =
        new StringBuilder(
            "记忆概要：user %d 条，project %d 条"
                .formatted(summary.user().size(), summary.project().size()));
    if (!summary.user().isEmpty()) {
      text.append("\nuser：")
          .append(summary.user().stream().map(com.mewcode.memory.MemoryNote::title).toList());
    }
    if (!summary.project().isEmpty()) {
      text.append("\nproject：")
          .append(summary.project().stream().map(com.mewcode.memory.MemoryNote::title).toList());
    }
    return text.toString();
  }

  private List<String> memoryLines() {
    var summary = memoryManager.summary();
    var lines = new ArrayList<String>();
    summary.user().forEach(note -> lines.add(memoryLine("user", note)));
    summary.project().forEach(note -> lines.add(memoryLine("project", note)));
    return lines.isEmpty() ? List.of("暂无记忆。") : List.copyOf(lines);
  }

  private static String memoryLine(String level, com.mewcode.memory.MemoryNote note) {
    return "[%s/%s] %s\n%s".formatted(level, note.type().wire(), note.title(), note.content());
  }

  private String addMemory(String type, String content) {
    var note = memoryManager.addManual(type, content);
    return "已添加记忆：" + note.title();
  }

  private String permissionSummary() {
    return "权限模式：%s，规则数量：%d"
        .formatted(
            permissionRuntime.mode().configValue(),
            permissionRuntime.snapshot().ruleEngine().rules().size());
  }

  private List<String> permissionLines() {
    List<com.mewcode.permission.PermissionRule> rules =
        permissionRuntime.snapshot().ruleEngine().rules();
    return rules.isEmpty()
        ? List.of("暂无生效规则。")
        : rules.stream()
            .map(rule -> "%s %s（%s）".formatted(rule.pattern(), rule.decision(), rule.source()))
            .toList();
  }

  private String setPermissionMode(String mode) {
    permissionRuntime.setMode(mode);
    return "权限模式已切换为 " + permissionRuntime.mode().configValue();
  }

  private String addPermissionRule(String rule, String effect) {
    permissionRuntime.addRule(rule, effect);
    return "已添加临时权限规则";
  }

  private String statusSummary() {
    var memory = memoryManager.summary();
    long tokens = getTokenCount();
    int window = selectedProvider == null ? 0 : selectedProvider.getContextWindowTokens();
    long percent = window <= 0 ? 0 : Math.min(100, tokens * 100 / window);
    int tools = toolRegistry == null ? 0 : toolRegistry.getAll().size();
    String mcp =
        mcpInitializing
            ? "连接中"
            : mcpManager == null
                ? "未初始化"
                : "%d 个已连接，%d 个错误"
                    .formatted(mcpManager.connectedServers().size(), mcpManager.errors().size());
    return """
        MewCode 状态
        ─────────────
        Provider：%s / %s
        模式：%s
        权限：%s
        Session：%s
        Token：%,d / %,d（%d%%）
        工具：%d 个已启用
        记忆：user %d 条，project %d 条
        MCP：%s
        工作目录：%s
        版本：v%s
        """
        .formatted(
            selectedProvider == null ? "none" : selectedProvider.getName(),
            selectedProvider == null ? "none" : selectedProvider.getModel(),
            agentMode == AgentMode.PLAN ? "plan" : "default",
            permissionRuntime.mode().configValue(),
            sessionManager.currentSessionId(),
            tokens,
            window,
            percent,
            tools,
            memory.user().size(),
            memory.project().size(),
            mcp,
            projectRoot,
            VERSION)
        .stripTrailing();
  }

  @Override
  public void addSystemMessage(String text) {
    pendingUiCommand = Command.println(Styles.DIM.render(text));
  }

  @Override
  public void sendUserMessage(String text) {
    pendingPrompt = text;
  }

  @Override
  public boolean isPlanMode() {
    return agentMode == AgentMode.PLAN;
  }

  @Override
  public void setPlanMode(boolean enabled) {
    agentMode = enabled ? AgentMode.PLAN : AgentMode.EXECUTE;
  }

  @Override
  public long getTokenCount() {
    return coordinator == null ? 0 : coordinator.estimateManualCompactionTokens(agentMode);
  }

  @Override
  public void refreshStatus() {
    // 状态栏直接读取当前字段，下一次 render 即生效。
  }

  @Override
  public void startNewConversation() {
    if (activeSkillRun != null) activeSkillRun.clear();
    var session = sessionManager.startNewSession();
    if (contextManager != null) contextManager.resetForSession(session.sessionDirectory());
    chatMessages.clear();
    usageLabel = "Token 用量：unknown";
    pendingUiCommand = Command.clearScreen();
  }

  @Override
  public void requestConfirmation(String text, Runnable onConfirm) {
    confirmationText = text;
    confirmationAction = onConfirm;
  }

  /** 批量消费事件并转换为 UI 命令；定时 tick 保证没有事件时 spinner 仍会刷新。 */
  private UpdateResult<MewCodeModel> pollStream() {
    if (!streaming || streamEvents == null) return UpdateResult.from(this);

    spinnerFrame++;
    var printCommands = new ArrayList<Command>();
    AgentEvent event;
    try {
      while ((event = streamEvents.poll(10, TimeUnit.MILLISECONDS)) != null) {
        switch (event) {
          case AgentEvent.StreamText text -> streamBuffer.append(text.text());
          case AgentEvent.ToolUse started -> printCommands.add(renderToolStarted(started));
          case AgentEvent.ToolResult completed -> printCommands.add(renderToolCompleted(completed));
          case AgentEvent.PermissionRequested request -> {
            pendingPermission = request.request();
            printCommands.add(
                Command.println(
                    Styles.ERROR.render(PermissionPromptFormatter.format(pendingPermission))));
          }
          case AgentEvent.TurnComplete turn -> {
            currentIteration = turn.round();
            printCommands.add(
                Command.println(Styles.DIM.render("  Agent 正在推进第 %d 轮…".formatted(turn.round()))));
          }
          case AgentEvent.Usage usage -> usageLabel = formatUsage(usage);
          case AgentEvent.CompactionStarted started -> {
            if (started.trigger() == com.mewcode.compact.ContextTrigger.EMERGENCY) {
              streamBuffer.setLength(0);
            }
            printCommands.add(Command.println(Styles.DIM.render(compactionStartedText(started))));
          }
          case AgentEvent.CompactionComplete complete ->
              printCommands.add(
                  Command.println(Styles.DIM.render(compactionCompleteText(complete))));
          case AgentEvent.ProviderFallback fallback -> {
            streamBuffer.setLength(0);
            printCommands.add(
                Command.println(
                    Styles.DIM.render(
                        "  Provider %s 不可用，已回退到 %s。".formatted(fallback.from(), fallback.to()))));
          }
          case AgentEvent.LoopComplete ignored -> {
            if (compactionRun) {
              return withLeadingCommands(
                  printCommands,
                  pendingStreamError == null
                      ? completeCompaction()
                      : failCompaction(pendingStreamError));
            }
            return withLeadingCommands(
                printCommands,
                pendingStreamError == null ? completeStream() : failStream(pendingStreamError));
          }
          case AgentEvent.Error error -> {
            pendingStreamError = error.message();
          }
        }
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return withLeadingCommands(printCommands, failStream("Streaming was interrupted."));
    }
    printCommands.add(Command.tick(POLL_INTERVAL, ignored -> new StreamPollMessage()));
    return UpdateResult.from(this, sequence(printCommands));
  }

  private Command renderToolStarted(AgentEvent.ToolUse event) {
    String line =
        ToolDisplayFormatter.invocation(event.toolName(), event.input(), toolDisplayColumns());
    return Command.println(Styles.TOOL.render(line));
  }

  private Command renderToolCompleted(AgentEvent.ToolResult event) {
    var summary =
        ToolDisplayFormatter.result(
            new com.mewcode.tool.ToolResult(
                event.result(),
                event.isError(),
                java.util.Map.of("durationMs", event.durationMillis())),
            toolDisplayColumns());
    var style = summary.isError() ? Styles.ERROR : Styles.TOOL_RESULT;
    return Command.println(style.render(summary.text()));
  }

  private int toolDisplayColumns() {
    return Math.max(8, Math.min(ToolDisplayFormatter.DEFAULT_MAX_COLUMNS, Math.max(width - 4, 8)));
  }

  private UpdateResult<MewCodeModel> withLeadingCommands(
      List<Command> leading, UpdateResult<MewCodeModel> terminal) {
    if (leading.isEmpty()) return terminal;
    var commands = new ArrayList<>(leading);
    if (terminal.command() != null) commands.add(terminal.command());
    return UpdateResult.from(this, sequence(commands));
  }

  private static Command sequence(List<Command> commands) {
    if (commands.isEmpty()) return null;
    if (commands.size() == 1) return commands.getFirst();
    return Command.batch(commands.toArray(Command[]::new));
  }

  /** 正常收到 LoopComplete 后一次性渲染完整响应，并把它放入 UI 历史。 */
  private UpdateResult<MewCodeModel> completeStream() {
    String rawText = streamBuffer.toString();
    double elapsed = elapsedSeconds();
    String finalUsage = usageLabel;
    chatMessages.add(new ChatMessage("assistant", rawText, elapsed));
    String rendered = MarkdownRenderer.render(rawText, Math.max(width - 4, 20));
    resetStream();
    String output =
        Styles.ASSISTANT.render("● ")
            + rendered.stripTrailing()
            + "\n"
            + Styles.DIM.render("  Completed in %.1fs · %s".formatted(elapsed, finalUsage));
    return UpdateResult.from(this, Command.println(output));
  }

  private UpdateResult<MewCodeModel> completeCompaction() {
    resetStream();
    return UpdateResult.from(this);
  }

  private UpdateResult<MewCodeModel> failCompaction(String message) {
    String safeMessage = message == null ? "上下文压缩失败。" : message;
    double elapsed = elapsedSeconds();
    chatMessages.add(new ChatMessage("error", safeMessage, elapsed));
    resetStream();
    return UpdateResult.from(this, Command.println(renderError(safeMessage, elapsed)));
  }

  /** 取消当前 AgentRun；已显示的部分文本仅保留在终端提示，不写入会话历史。 */
  private UpdateResult<MewCodeModel> cancelStream() {
    if (!streaming) return UpdateResult.from(this);
    if (activeRun != null) activeRun.cancel();

    double elapsed = elapsedSeconds();
    String finalUsage = usageLabel;
    var output = new StringBuilder();
    if (!streamBuffer.isEmpty()) {
      output
          .append(Styles.ASSISTANT.render("● "))
          .append(safeTerminalText(streamBuffer.toString()))
          .append("\n")
          .append(Styles.DIM.render("  本轮已取消，部分响应未写入历史"))
          .append("\n");
    }
    output.append(
        Styles.DIM.render("  已取消本轮 Agent Loop（耗时 %.1fs · %s）".formatted(elapsed, finalUsage)));
    resetStream();
    return UpdateResult.from(this, Command.println(output.toString()));
  }

  /** provider/Loop 出错时展示安全错误文本，并丢弃未完成的流式响应。 */
  private UpdateResult<MewCodeModel> failStream(String safeMessage) {
    double elapsed = elapsedSeconds();
    String finalUsage = usageLabel;
    var output = new StringBuilder();
    if (!streamBuffer.isEmpty()) {
      output
          .append(Styles.ASSISTANT.render("● "))
          .append(safeTerminalText(streamBuffer.toString()))
          .append("\n")
          .append(Styles.DIM.render("  Partial response (not added to history)"))
          .append("\n");
    }
    chatMessages.add(new ChatMessage("error", safeMessage, elapsed));
    output.append(renderError(safeMessage, elapsed));
    output.append("\n").append(Styles.DIM.render("  " + finalUsage));
    resetStream();
    return UpdateResult.from(this, Command.println(output.toString()));
  }

  /** 清空本轮临时状态，使取消或完成后可以继续输入下一条消息。 */
  private void resetStream() {
    if (activeSkillRun != null) activeSkillRun.clear();
    activeSkillRun = null;
    streaming = false;
    activeRun = null;
    streamEvents = null;
    streamBuffer.setLength(0);
    spinnerFrame = 0;
    currentIteration = 0;
    pendingStreamError = null;
    usageLabel = "Token 用量：unknown";
    pendingPermission = null;
    compactionRun = false;
  }

  private String viewProviderSelection() {
    var view =
        new StringBuilder(renderBanner())
            .append("\n\n")
            .append(Styles.SELECTED.render("Select a provider"))
            .append("\n\n");
    for (int i = 0; i < providers.size(); i++) {
      ProviderConfig provider = providers.get(i);
      String label = provider.getName() + " (" + provider.getModel() + ")";
      view.append(i == providerCursor ? Styles.SELECTED.render("  ❯ " + label) : "    " + label);
      view.append('\n');
    }
    view.append('\n').append(Styles.DIM.render("↑/↓ select · Enter confirm · Ctrl+C quit"));
    return view.toString();
  }

  /** 渲染聊天态；动态区和输入框必须保持有界，避免终端 scrollback 重复追加。 */
  private String viewChat() {
    var view = new StringBuilder();
    view.append(Styles.DIM.render("● Ready for conversation and tools · " + modeLabel()));
    view.append('\n');

    if (streaming) {
      if (!streamBuffer.isEmpty()) {
        view.append('\n')
            .append(Styles.ASSISTANT.render("● "))
            .append(streamingPreview())
            .append('\n');
      }
      String frame = SPINNER[spinnerFrame % SPINNER.length];
      String iteration = currentIteration == 0 ? "准备第 1 轮" : "第 %d 轮".formatted(currentIteration);
      view.append('\n')
          .append(
              Styles.DIM.render(
                  "%s %s… · %s · (%.0fs)"
                      .formatted(frame, spinnerVerb, iteration, elapsedSeconds())));
      view.append('\n').append(Styles.DIM.render("  " + usageLabel));
      view.append('\n');
    } else if (mcpInitializing) {
      view.append('\n').append(Styles.DIM.render("  MCP 正在连接…")).append('\n');
    } else if (initializationError != null) {
      view.append('\n').append(Styles.ERROR.render("✖ " + initializationError)).append('\n');
    }
    if (!streaming && backgroundDiagnostic != null) {
      view.append('\n')
          .append(Styles.ERROR.render("✖ " + safeTerminalText(backgroundDiagnostic)))
          .append('\n');
    }
    if (confirmationText != null) {
      view.append('\n')
          .append(Styles.ERROR.render("  " + safeTerminalText(confirmationText) + "（y 确认 / n 取消）"))
          .append('\n');
    }
    if (!completionCandidates.isEmpty()) {
      view.append('\n');
      for (int index = 0; index < completionCandidates.size(); index++) {
        var candidate = completionCandidates.get(index);
        String line = "  /" + candidate.name() + "  " + candidate.description();
        view.append(index == completionCursor ? Styles.SELECTED.render("❯" + line) : " " + line)
            .append('\n');
      }
    }

    int boxWidth = Math.max(width - 2, 20);
    String border = "─".repeat(boxWidth);
    view.append(Styles.SEPARATOR.render("╭" + border + "╮")).append('\n');
    if (streaming) {
      view.append("│ ").append(Styles.DIM.render("Waiting for response…"));
      view.append(" ".repeat(Math.max(boxWidth - 21, 0))).append("│\n");
    } else {
      appendInput(view);
    }
    view.append(Styles.SEPARATOR.render("╰" + border + "╯")).append('\n');
    view.append(renderStatusBar());
    return view.toString();
  }

  private void appendInput(StringBuilder view) {
    if (inputBuffer.isEmpty()) {
      view.append("│ ")
          .append(Styles.PROMPT.render("❯ "))
          .append(Styles.DIM.render("Send a message..."));
      int used = 2 + 2 + "Send a message...".length();
      view.append(" ".repeat(Math.max(Math.max(width - 2, 20) - used, 0))).append("│\n");
      return;
    }

    String withCursor =
        inputBuffer.substring(0, inputCursor) + "█" + inputBuffer.substring(inputCursor);
    String[] lines = withCursor.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      view.append("│ ");
      if (i == 0) view.append(Styles.PROMPT.render("❯ "));
      else view.append("  ");
      view.append(lines[i]);
      int used = 2 + 2 + com.mewcode.tui.tea.Program.displayWidth(lines[i]);
      view.append(" ".repeat(Math.max(Math.max(width - 2, 20) - used, 0))).append("│\n");
    }
  }

  private String renderStatusBar() {
    String left = agentMode == AgentMode.PLAN ? "[PLAN]" : "[DEFAULT]";
    String right = selectedProvider == null ? "" : selectedProvider.getModel();
    int spaces = Math.max(width - left.length() - right.length(), 1);
    return Styles.STATUS.render(left + " ".repeat(spaces) + right);
  }

  private String modeLabel() {
    return agentMode == AgentMode.PLAN ? "Plan Mode（只读）" : "Execute Mode";
  }

  /** 动态区只显示长响应的尾部，避免整个 view 超过终端高度后无法回退光标， 进而把每次刷新都追加到 scrollback。完整响应仍由 completeStream 一次性输出。 */
  private String streamingPreview() {
    String text = safeTerminalText(streamBuffer.toString());
    int maxLines = Math.max(1, height - STREAMING_FIXED_LINES);
    int textWidth = Math.max(width - 2, 8);
    return tailByPhysicalLines(text, textWidth, maxLines);
  }

  private static String tailByPhysicalLines(String text, int width, int maxLines) {
    if (text.isEmpty()) return "";
    if (physicalLines(text, width) <= maxLines) return text;
    if (maxLines <= 1) return tailCharacters(lastLogicalLine(text), width, 1);

    String[] logicalLines = text.split("\\n", -1);
    int budget = maxLines - 1; // 预留一行给省略标记
    var suffix = new ArrayList<String>();
    for (int index = logicalLines.length - 1; index >= 0 && budget > 0; index--) {
      String line = logicalLines[index];
      int lineCount = physicalLines(line, width);
      if (lineCount <= budget) {
        suffix.add(line);
        budget -= lineCount;
      } else {
        suffix.add(tailCharacters(line, width, budget));
        budget = 0;
      }
    }
    java.util.Collections.reverse(suffix);
    return "…\n" + String.join("\n", suffix);
  }

  private static String lastLogicalLine(String text) {
    int newline = text.lastIndexOf('\n');
    return newline < 0 ? text : text.substring(newline + 1);
  }

  private static String tailCharacters(String value, int width, int maxLines) {
    int budget = Math.max(1, width * Math.max(1, maxLines));
    int start = value.length();
    int used = 0;
    for (int index = value.length(); index > 0; ) {
      int codePoint = value.codePointBefore(index);
      int characterWidth =
          com.mewcode.tui.tea.Program.displayWidth(new String(Character.toChars(codePoint)));
      if (used + characterWidth > budget) break;
      used += characterWidth;
      index -= Character.charCount(codePoint);
      start = index;
    }
    return value.substring(start);
  }

  private static int physicalLines(String value, int width) {
    int total = 0;
    for (String line : value.split("\\n", -1)) {
      total +=
          Math.max(
              1, (int) Math.ceil((double) com.mewcode.tui.tea.Program.displayWidth(line) / width));
    }
    return Math.max(1, total);
  }

  private static String formatUsage(AgentEvent.Usage usage) {
    String input =
        usage.inputTokens().isPresent()
            ? Long.toString(usage.inputTokens().getAsLong())
            : "unknown";
    String output =
        usage.outputTokens().isPresent()
            ? Long.toString(usage.outputTokens().getAsLong())
            : "unknown";
    return "Token 用量：输入 %s · 输出 %s".formatted(input, output);
  }

  private static String compactionStartedText(AgentEvent.CompactionStarted event) {
    return switch (event.trigger()) {
      case AUTO -> "  正在压缩上下文…";
      case MANUAL -> "  正在执行 /compact…";
      case EMERGENCY -> "  上下文过长，正在压缩并重试…";
    };
  }

  private static String compactionCompleteText(AgentEvent.CompactionComplete event) {
    var result = event.result();
    if (!result.changed()) return "  上下文无需压缩";
    return "  上下文压缩完成：约 %d → %d tokens".formatted(result.beforeTokens(), result.afterTokens());
  }

  private String renderBanner() {
    String model = selectedProvider == null ? "" : selectedProvider.getModel();
    return Styles.BANNER.render(" /\\_/\\    MewCode " + VERSION)
        + "\n"
        + Styles.BANNER.render("( o.o )   " + model)
        + "\n"
        + Styles.BANNER.render(" > ^ <    " + projectRoot);
  }

  private static Path currentProjectRoot() {
    return Path.of(".").toAbsolutePath().normalize();
  }

  private static Path currentUserHome() {
    return Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
  }

  private void recordDiagnostic(String message) {
    if (message != null && !message.isBlank()) backgroundDiagnostic = message;
  }

  private static String renderUser(String text) {
    String[] lines = text.split("\n", -1);
    var result = new StringBuilder();
    for (int i = 0; i < lines.length; i++) {
      if (i > 0) result.append('\n');
      result.append(i == 0 ? Styles.PROMPT.render("❯ ") : "  ").append(safeTerminalText(lines[i]));
    }
    return result.toString();
  }

  private static String renderError(String message, double elapsed) {
    return Styles.ERROR.render("✖ " + safeTerminalText(message))
        + "\n"
        + Styles.DIM.render("  Failed in %.1fs".formatted(elapsed));
  }

  private double elapsedSeconds() {
    return Math.max(0, System.currentTimeMillis() - requestStartMillis) / 1000.0;
  }

  private static int lineStart(StringBuilder input, int cursor) {
    int newline = input.lastIndexOf("\n", Math.max(cursor - 1, 0));
    return newline < 0 ? 0 : newline + 1;
  }

  private static int lineEnd(StringBuilder input, int cursor) {
    int newline = input.indexOf("\n", cursor);
    return newline < 0 ? input.length() : newline;
  }

  private static String safeTerminalText(String text) {
    if (text == null) return "";
    var safe = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\n' || c == '\t' || c >= 32 && c != 127) safe.append(c);
    }
    return safe.toString().replace("\033", "");
  }
}
