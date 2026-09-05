package com.mewcode.command;

import com.mewcode.skill.SkillDefinition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** 斜杠命令的注册、查找、解析与补全中心。 */
public final class CommandRegistry {

  private final Map<String, Command> commands = new LinkedHashMap<>();
  private final Map<String, Command> aliases = new HashMap<>();
  private final Map<String, Function<CommandContext, String>> handlers = new HashMap<>();
  private volatile Map<String, Command> skillCommands = Map.of();

  public static CommandRegistry createDefault() {
    CommandRegistry registry = new CommandRegistry();
    registry.register(
        command(
            "help", List.of("h", "?"), "显示命令帮助", "/help [命令]", Command.CommandType.LOCAL, "[命令]"),
        context -> registry.help(context.args()));
    registry.register(
        command(
            "compact",
            List.of("c"),
            "压缩当前上下文",
            "/compact [保留重点]",
            Command.CommandType.LOCAL,
            "[保留重点]"),
        context -> {
          context.compact().accept(context.args());
          return "";
        });
    registry.register(
        command("clear", List.of("cls"), "开启新对话", "/clear", Command.CommandType.LOCAL_UI, ""),
        context -> {
          context.ui().startNewConversation();
          return "已开启新对话";
        });
    registry.register(
        command("plan", List.of("p"), "切换计划模式", "/plan", Command.CommandType.LOCAL_UI, ""),
        context -> {
          boolean enabled = !context.ui().isPlanMode();
          context.ui().setPlanMode(enabled);
          context.ui().refreshStatus();
          return enabled ? "已进入计划模式" : "已退出计划模式";
        });
    registry.register(
        command(
            "session",
            List.of("s"),
            "管理会话",
            "/session [list|resume <id>]",
            Command.CommandType.LOCAL,
            "[list|resume <id>]"),
        CommandRegistry::session);
    registry.register(
        command(
            "memory",
            List.of("m"),
            "管理记忆",
            "/memory [list|add <类别> <内容>|clear]",
            Command.CommandType.LOCAL,
            "[list|add <类别> <内容>|clear]"),
        CommandRegistry::memory);
    registry.register(
        command(
            "permission",
            List.of("perm"),
            "管理权限",
            "/permission [rules|mode <模式>|add <规则> <效果>|reset]",
            Command.CommandType.LOCAL,
            "[rules|mode <模式>|add <规则> <效果>|reset]"),
        CommandRegistry::permission);
    registry.register(
        command("status", List.of("st"), "显示运行状态", "/status", Command.CommandType.LOCAL, ""),
        context -> context.status().get());
    return registry;
  }

  /** 用最新 Catalog 整体替换动态 Skill 命令。 */
  public synchronized List<String> replaceSkillCommands(List<SkillDefinition> skills) {
    var next = new LinkedHashMap<String, Command>();
    var conflicts = new ArrayList<String>();
    if (skills != null) {
      for (SkillDefinition skill : skills) {
        String name = normalize(skill.meta().name());
        if (commands.containsKey(name) || aliases.containsKey(name) || next.containsKey(name)) {
          conflicts.add(name);
          continue;
        }
        next.put(
            name,
            command(
                name,
                List.of(),
                skill.meta().description(),
                "/" + name + " [参数]",
                Command.CommandType.SKILL,
                "[参数]"));
      }
    }
    skillCommands = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(next));
    return List.copyOf(conflicts);
  }

  /** 静态命令名和别名均为 Skill 保留标识。 */
  public synchronized Set<String> reservedNames() {
    var result = new LinkedHashSet<String>(commands.keySet());
    result.addAll(aliases.keySet());
    return Set.copyOf(result);
  }

  public void register(Command command, Function<CommandContext, String> handler) {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(handler, "handler");

    String name = normalize(command.name());
    assertAvailable(name);
    Set<String> normalizedAliases = new LinkedHashSet<>();
    for (String alias : command.aliases()) {
      String normalized = normalize(alias);
      if (normalized.isBlank()) throw new IllegalArgumentException("命令别名不能为空");
      assertAvailable(normalized);
      if (normalized.equals(name) || !normalizedAliases.add(normalized)) {
        throw new IllegalArgumentException("命令标识冲突: " + normalized);
      }
    }

    commands.put(name, command);
    handlers.put(name, handler);
    normalizedAliases.forEach(alias -> aliases.put(alias, command));
  }

  public Optional<Command> find(String name) {
    String normalized = normalize(name);
    Command command = commands.get(normalized);
    if (command == null) command = aliases.get(normalized);
    if (command == null) command = skillCommands.get(normalized);
    return Optional.ofNullable(command);
  }

  public List<Command> listVisible() {
    var result = new ArrayList<Command>();
    commands.values().stream().filter(command -> !command.hidden()).forEach(result::add);
    skillCommands.values().stream().filter(command -> !command.hidden()).forEach(result::add);
    return List.copyOf(result);
  }

  public List<Command> search(String prefix) {
    String normalized = normalize(prefix);
    var result = new ArrayList<Command>();
    for (Command command : commands.values()) {
      if (command.hidden()) continue;
      boolean matches = normalize(command.name()).startsWith(normalized);
      if (!matches) {
        matches =
            command.aliases().stream()
                .map(CommandRegistry::normalize)
                .anyMatch(alias -> alias.startsWith(normalized));
      }
      if (matches) result.add(command);
    }
    for (Command command : skillCommands.values()) {
      if (!command.hidden() && normalize(command.name()).startsWith(normalized))
        result.add(command);
    }
    return List.copyOf(result);
  }

  public Optional<CommandCall> parse(String input) {
    if (input == null || input.isBlank() || input.charAt(0) != '/') return Optional.empty();
    int space = input.indexOf(' ');
    String name = input.substring(1, space < 0 ? input.length() : space);
    String args = space < 0 ? "" : input.substring(space + 1);
    return find(name).map(command -> new CommandCall(command, args));
  }

  public String execute(CommandCall call, CommandContext context) {
    Objects.requireNonNull(call, "call");
    if (call.command().type() == Command.CommandType.SKILL) return "";
    Function<CommandContext, String> handler = handlers.get(normalize(call.command().name()));
    if (handler == null) throw new IllegalArgumentException("命令未注册: " + call.command().name());
    try {
      return handler.apply(context);
    } catch (IllegalArgumentException exception) {
      return exception.getMessage();
    }
  }

  private String help(String args) {
    String name = args.strip();
    if (name.startsWith("/")) name = name.substring(1);
    if (!name.isEmpty()) {
      return find(name)
          .filter(command -> !command.hidden())
          .map(CommandRegistry::helpDetail)
          .orElse("未知命令：/" + name + "，输入 /help 查看可用命令");
    }
    return listVisible().stream()
        .map(CommandRegistry::helpDetail)
        .reduce("可用命令：", (left, right) -> left + "\n" + right);
  }

  private static String helpDetail(Command command) {
    String aliases =
        command.aliases().isEmpty()
            ? ""
            : "（"
                + command.aliases().stream()
                    .map(alias -> "/" + alias)
                    .reduce((a, b) -> a + "、" + b)
                    .orElse("")
                + "）";
    return "/"
        + command.name()
        + aliases
        + "  "
        + command.description()
        + "\n  用法："
        + command.usage();
  }

  private static String session(CommandContext context) {
    String args = context.args().strip();
    if (args.isEmpty()) return context.sessionInfo().get();
    if (args.equalsIgnoreCase("list")) return String.join("\n", context.sessionList().get());
    if (args.regionMatches(true, 0, "resume ", 0, 7) && !args.substring(7).isBlank()) {
      return context.sessionResume().apply(args.substring(7).strip());
    }
    return "用法：/session [list|resume <id>]";
  }

  private static String memory(CommandContext context) {
    String args = context.args().strip();
    if (args.isEmpty()) return context.memorySummary().get();
    if (args.equalsIgnoreCase("list")) return String.join("\n", context.memoryList().get());
    if (args.equalsIgnoreCase("clear")) {
      context.ui().requestConfirmation("确认清空全部记忆？", context.memoryClear());
      return "";
    }
    if (args.regionMatches(true, 0, "add ", 0, 4)) {
      String value = args.substring(4).strip();
      int space = value.indexOf(' ');
      if (space > 0 && !value.substring(space + 1).isBlank()) {
        return context
            .memoryAdd()
            .apply(value.substring(0, space), value.substring(space + 1).strip());
      }
    }
    return "用法：/memory [list|add <类别> <内容>|clear]";
  }

  private static String permission(CommandContext context) {
    String args = context.args().strip();
    if (args.isEmpty()) return context.permissionSummary().get();
    if (args.equalsIgnoreCase("rules")) return String.join("\n", context.permissionRules().get());
    if (args.equalsIgnoreCase("reset")) {
      context.permissionReset().run();
      return "权限已重置";
    }
    if (args.regionMatches(true, 0, "mode ", 0, 5) && !args.substring(5).isBlank()) {
      return context.permissionMode().apply(args.substring(5).strip());
    }
    if (args.regionMatches(true, 0, "add ", 0, 4)) {
      String value = args.substring(4).strip();
      int space = value.lastIndexOf(' ');
      if (space > 0) {
        String effect = value.substring(space + 1).toLowerCase(Locale.ROOT);
        if (effect.equals("allow") || effect.equals("deny")) {
          return context.permissionAdd().apply(value.substring(0, space).strip(), effect);
        }
      }
    }
    return "用法：/permission [rules|mode <模式>|add <规则> <效果>|reset]";
  }

  private static Command command(
      String name,
      List<String> aliases,
      String description,
      String usage,
      Command.CommandType type,
      String argumentHint) {
    return new Command(name, aliases, description, usage, type, argumentHint, false);
  }

  private void assertAvailable(String identifier) {
    if (commands.containsKey(identifier) || aliases.containsKey(identifier)) {
      throw new IllegalArgumentException("命令标识冲突: " + identifier);
    }
  }

  private static String normalize(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT);
  }

  public record CommandCall(Command command, String args) {}
}
