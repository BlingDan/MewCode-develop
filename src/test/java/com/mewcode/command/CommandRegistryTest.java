package com.mewcode.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.command.Command.CommandType;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CommandRegistryTest {

  @Test
  void rejectsEveryNormalizedNameAndAliasCollisionBeforeDispatch() {
    CommandRegistry registry = new CommandRegistry();
    registry.register(command("Compact", List.of("C")), context -> "ok");

    IllegalArgumentException nameCollision =
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.register(command("compact", List.of()), context -> "duplicate"));
    IllegalArgumentException aliasCollision =
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.register(command("clear", List.of("c")), context -> "duplicate"));

    assertTrue(nameCollision.getMessage().contains("compact"));
    assertTrue(aliasCollision.getMessage().contains("c"));
  }

  @Test
  void parsesCaseInsensitiveNameAtFirstAsciiSpaceAndKeepsArguments() {
    CommandRegistry registry = new CommandRegistry();
    Command compact = command("compact", List.of("c"));
    registry.register(compact, context -> context.args());

    CommandRegistry.CommandCall call = registry.parse("/C Keep DB  ").orElseThrow();

    assertEquals(compact, call.command());
    assertEquals("Keep DB  ", call.args());
    assertTrue(registry.parse("").isEmpty());
    assertTrue(registry.parse(" /compact").isEmpty());
    assertTrue(registry.parse("/missing").isEmpty());
  }

  @Test
  void searchesVisibleCanonicalCommandsByNameOrAliasWithoutDuplicates() {
    CommandRegistry registry = new CommandRegistry();
    registry.register(command("compact", List.of("c", "cmp")), context -> "ok");
    registry.register(command("clear", List.of("cls")), context -> "ok");
    registry.register(
        new Command("concealed", List.of("co"), "隐藏", "/concealed", CommandType.LOCAL, "", true),
        context -> "ok");

    assertEquals(
        List.of("compact", "clear"), registry.search("c").stream().map(Command::name).toList());
    assertEquals(List.of("clear"), registry.search("cl").stream().map(Command::name).toList());
    assertEquals(
        List.of("compact", "clear"), registry.listVisible().stream().map(Command::name).toList());
  }

  @Test
  void createsTheNineApprovedBuiltInsAndLeavesRetiredCommandsUnknown() {
    CommandRegistry registry = CommandRegistry.createDefault();

    assertEquals(
        List.of(
            "help",
            "compact",
            "clear",
            "plan",
            "session",
            "memory",
            "permission",
            "status",
            "review"),
        registry.listVisible().stream().map(Command::name).toList());
    assertEquals(CommandType.LOCAL_UI, registry.find("CLS").orElseThrow().type());
    assertEquals(CommandType.LOCAL_UI, registry.find("p").orElseThrow().type());
    assertEquals(CommandType.PROMPT, registry.find("r").orElseThrow().type());
    for (String retired : List.of("do", "d", "exit", "sessions", "resume")) {
      assertTrue(registry.find(retired).isEmpty());
    }
  }

  @Test
  void reviewSendsExpandedPromptAndPlanTogglesThroughUiOnly() {
    CommandRegistry registry = CommandRegistry.createDefault();
    FakeUi ui = new FakeUi();

    registry.execute(registry.parse("/review 特别注意并发安全").orElseThrow(), context("特别注意并发安全", ui));
    registry.execute(registry.parse("/plan").orElseThrow(), context("", ui));

    assertTrue(ui.sent.get().contains("git diff"));
    assertTrue(ui.sent.get().contains("缺陷、回归、安全风险和测试缺口"));
    assertTrue(ui.sent.get().contains("特别注意并发安全"));
    assertTrue(ui.planMode.get());
    assertTrue(ui.refreshed.get());
  }

  @Test
  void compactClearAndStatusUseOnlyTheirDeclaredCapabilities() {
    CommandRegistry registry = CommandRegistry.createDefault();
    FakeUi ui = new FakeUi();
    AtomicReference<String> focus = new AtomicReference<>();
    CommandContext context = context("保留数据库", ui, focus::set);

    registry.execute(registry.parse("/compact 保留数据库").orElseThrow(), context);
    registry.execute(registry.parse("/clear").orElseThrow(), context("", ui));
    String status = registry.execute(registry.parse("/status").orElseThrow(), context("", ui));

    assertEquals("保留数据库", focus.get());
    assertTrue(ui.startedNewConversation.get());
    assertEquals("状态", status);
  }

  @Test
  void permissionAddTakesTheEffectFromTheEndAndKeepsSpacesInRule() {
    CommandRegistry registry = CommandRegistry.createDefault();
    AtomicReference<String> rule = new AtomicReference<>();
    AtomicReference<String> effect = new AtomicReference<>();
    CommandContext base = context("add Bash(git status --short) deny", new FakeUi());
    CommandContext context =
        new CommandContext(
            base.args(),
            base.workDir(),
            base.model(),
            base.ui(),
            base.status(),
            base.compact(),
            base.sessionInfo(),
            base.sessionList(),
            base.sessionResume(),
            base.memorySummary(),
            base.memoryList(),
            base.memoryAdd(),
            base.memoryClear(),
            base.permissionSummary(),
            base.permissionRules(),
            base.permissionMode(),
            (value, decision) -> {
              rule.set(value);
              effect.set(decision);
              return "已添加";
            },
            base.permissionReset());

    assertEquals(
        "已添加",
        registry.execute(
            registry.parse("/permission add Bash(git status --short) deny").orElseThrow(),
            context));
    assertEquals("Bash(git status --short)", rule.get());
    assertEquals("deny", effect.get());
  }

  @Test
  void helpListsAliasesAndUsageAndAliasDetailMatchesCanonicalDetail() {
    CommandRegistry registry = CommandRegistry.createDefault();

    String list =
        registry.execute(registry.parse("/help").orElseThrow(), context("", new FakeUi()));
    String canonical =
        registry.execute(
            registry.parse("/help compact").orElseThrow(), context("compact", new FakeUi()));
    String alias =
        registry.execute(registry.parse("/help c").orElseThrow(), context("c", new FakeUi()));

    assertTrue(list.contains("/compact（/c）"));
    assertTrue(list.contains("用法：/compact [保留重点]"));
    assertEquals(canonical, alias);
  }

  private static CommandContext context(String args, FakeUi ui) {
    return context(args, ui, ignored -> {});
  }

  private static CommandContext context(
      String args, FakeUi ui, java.util.function.Consumer<String> compact) {
    return new CommandContext(
        args,
        ".",
        "model",
        ui,
        () -> "状态",
        compact,
        () -> "当前会话",
        () -> List.of("会话"),
        id -> "已恢复 " + id,
        () -> "记忆概要",
        () -> List.of("记忆"),
        (type, content) -> "已添加",
        () -> {},
        () -> "权限概要",
        () -> List.of("规则"),
        mode -> "已切换",
        (rule, effect) -> "已添加",
        () -> {});
  }

  private static final class FakeUi implements CommandContext.UIController {
    private final AtomicReference<String> sent = new AtomicReference<>("");
    private final AtomicBoolean planMode = new AtomicBoolean();
    private final AtomicBoolean refreshed = new AtomicBoolean();
    private final AtomicBoolean startedNewConversation = new AtomicBoolean();

    @Override
    public void addSystemMessage(String text) {}

    @Override
    public void sendUserMessage(String text) {
      sent.set(text);
    }

    @Override
    public boolean isPlanMode() {
      return planMode.get();
    }

    @Override
    public void setPlanMode(boolean enabled) {
      planMode.set(enabled);
    }

    @Override
    public long getTokenCount() {
      return 0;
    }

    @Override
    public void refreshStatus() {
      refreshed.set(true);
    }

    @Override
    public void startNewConversation() {
      startedNewConversation.set(true);
    }

    @Override
    public void requestConfirmation(String text, Runnable onConfirm) {}
  }

  private static Command command(String name, List<String> aliases) {
    return new Command(name, aliases, "描述", "/" + name, CommandType.LOCAL, "", false);
  }
}
