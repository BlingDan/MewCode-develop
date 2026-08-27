package com.mewcode.tui;

import com.mewcode.agent.AgentEvent;
import com.mewcode.agent.AgentEventStream;
import com.mewcode.agent.AgentLoopConfig;
import com.mewcode.agent.AgentMode;
import com.mewcode.agent.AgentRun;
import com.mewcode.agent.AgentTurnCoordinator;
import com.mewcode.agent.PromptRequestFactory;
import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.LlmClients;
import com.mewcode.permission.BashSandbox;
import com.mewcode.permission.BashSandboxFactory;
import com.mewcode.permission.PathAuthorizationStore;
import com.mewcode.permission.PermissionGate;
import com.mewcode.permission.PermissionMode;
import com.mewcode.permission.PermissionRequest;
import com.mewcode.permission.PermissionResponse;
import com.mewcode.permission.PermissionRuleEngine;
import com.mewcode.prompt.PromptBuilder;
import com.mewcode.prompt.SystemPromptBundle;
import com.mewcode.tool.FileStateCache;
import com.mewcode.tool.ToolApiProtocol;
import com.mewcode.tool.ToolExecutor;
import com.mewcode.tool.ToolRegistry;
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
public final class MewCodeModel implements Model {

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
  private final PermissionMode configuredPermissionMode;
  private final PermissionRuleEngine permissionRuleEngine;
  private final PathAuthorizationStore pathAuthorizationStore;
  private final BashSandbox bashSandbox;
  private final ConversationManager conversation = new ConversationManager();
  private final List<ChatMessage> chatMessages = new ArrayList<>();
  private final StringBuilder inputBuffer = new StringBuilder();
  private final StringBuilder streamBuffer = new StringBuilder();

  private AppState state;
  private ProviderConfig selectedProvider;
  private LlmClient client;
  private AgentRun activeRun;
  private AgentEventStream streamEvents;
  private ToolExecutor toolExecutor;
  private AgentTurnCoordinator coordinator;
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

  public record StreamPollMessage() implements Message {}

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
        BashSandboxFactory.create());
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
    this.providers = providers == null ? List.of() : List.copyOf(providers);
    this.projectRoot =
        Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
    this.systemPromptBundle = PromptBuilder.buildBundle(this.projectRoot);
    this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
    this.loopConfig = Objects.requireNonNull(loopConfig, "loopConfig").copy();
    this.configuredPermissionMode = Objects.requireNonNull(permissionMode, "permissionMode");
    this.permissionRuleEngine =
        Objects.requireNonNull(permissionRuleEngine, "permissionRuleEngine");
    this.pathAuthorizationStore =
        Objects.requireNonNull(pathAuthorizationStore, "pathAuthorizationStore");
    this.bashSandbox = Objects.requireNonNull(bashSandbox, "bashSandbox");
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
      return streaming ? cancelStream() : UpdateResult.from(this);
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
        return UpdateResult.from(this, Command.println(renderBanner()));
      }
      return UpdateResult.from(this);
    }

    if (message instanceof StreamPollMessage) {
      return pollStream();
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
        yield UpdateResult.from(this, Command.println(renderBanner()));
      }
      default -> UpdateResult.from(this);
    };
  }

  /** 创建 provider、默认工具注册表和 Agent 协调器；失败只阻塞当前会话而不崩溃 TUI。 */
  private void initializeProvider() {
    try {
      client = clientFactory.apply(selectedProvider, systemPromptBundle.flattenedText());
      if (toolExecutor != null) toolExecutor.close();
      var registry = ToolRegistry.createDefault();
      var gate = new PermissionGate();
      toolExecutor = new ToolExecutor(registry, projectRoot, new FileStateCache(), gate);
      ToolApiProtocol protocol =
          "anthropic".equalsIgnoreCase(selectedProvider.getProtocol())
              ? ToolApiProtocol.ANTHROPIC
              : ToolApiProtocol.OPENAI;
      coordinator =
          new AgentTurnCoordinator(
              client,
              registry,
              toolExecutor,
              conversation,
              protocol,
              loopConfig,
              new PromptRequestFactory(systemPromptBundle),
              gate,
              configuredPermissionMode,
              permissionRuleEngine,
              pathAuthorizationStore,
              bashSandbox);
      initializationError = null;
    } catch (RuntimeException error) {
      client = null;
      coordinator = null;
      initializationError = "Provider initialization failed.";
    }
  }

  private UpdateResult<MewCodeModel> handleChatKey(KeyPressMessage message) {
    if (streaming) {
      return pendingPermission == null ? UpdateResult.from(this) : handlePermissionKey(message);
    }

    String key = message.key();
    return switch (key) {
      case "enter" -> submit();
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
    return UpdateResult.from(this);
  }

  /** 处理一条输入：slash 命令本地生效，普通文本启动异步 Agent Loop。 */
  private UpdateResult<MewCodeModel> submit() {
    String text = inputBuffer.toString();
    if (text.isBlank()) return UpdateResult.from(this);
    inputBuffer.setLength(0);
    inputCursor = 0;

    if ("/exit".equals(text.trim())) {
      return UpdateResult.from(this, QuitMessage::new);
    }
    if ("/plan".equals(text.trim())) {
      agentMode = AgentMode.PLAN;
      return UpdateResult.from(
          this, Command.println(Styles.DIM.render("已切换到 Plan Mode：模型仅应执行只读操作，写操作仍需确认。")));
    }
    if ("/do".equals(text.trim())) {
      agentMode = AgentMode.EXECUTE;
      return UpdateResult.from(
          this, Command.println(Styles.DIM.render("已切换到 Execute Mode：允许全部工具。")));
    }
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
    streaming = true;

    try {
      activeRun = coordinator.startRun(text, agentMode);
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
          case AgentEvent.LoopComplete ignored -> {
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
    streaming = false;
    activeRun = null;
    streamEvents = null;
    streamBuffer.setLength(0);
    spinnerFrame = 0;
    currentIteration = 0;
    pendingStreamError = null;
    usageLabel = "Token 用量：unknown";
    pendingPermission = null;
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
      if (pendingPermission != null) {
        view.append('\n').append(Styles.ERROR.render("  等待权限确认：y 本次 / s 本会话 / a 永久 / n 拒绝"));
      }
      view.append('\n');
    } else if (initializationError != null) {
      view.append('\n').append(Styles.ERROR.render("✖ " + initializationError)).append('\n');
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
    String left = selectedProvider == null ? "no provider" : selectedProvider.getName();
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
