package com.mewcode.config;

import com.mewcode.hook.HookAction;
import com.mewcode.hook.HookEvent;
import com.mewcode.hook.HookRule;
import com.mewcode.permission.RuleMatcher;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 加载项目级和用户级 Hook 配置，并隔离文件及单条规则错误。 */
public final class HookConfigLoader {
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
  private static final Pattern TIMEOUT = Pattern.compile("([0-9]+)(ms|s|m)");

  private HookConfigLoader() {}

  public static LoadedHooks load(Path projectRoot, Path userHome, Consumer<String> diagnostics) {
    if (projectRoot == null || userHome == null) {
      throw new IllegalArgumentException("projectRoot and userHome must not be null");
    }
    Consumer<String> report = diagnostics == null ? ignored -> {} : diagnostics;
    var rules = new ArrayList<HookRule>();
    var sources = new ArrayList<Path>();
    Set<String> names = new HashSet<>();
    loadFile(projectRoot.resolve(".mewcode/hooks.yaml"), rules, sources, names, report);
    loadFile(userHome.resolve(".mewcode/hooks.yaml"), rules, sources, names, report);
    return new LoadedHooks(rules, sources);
  }

  private static void loadFile(
      Path file,
      List<HookRule> rules,
      List<Path> sources,
      Set<String> names,
      Consumer<String> diagnostics) {
    file = file.toAbsolutePath().normalize();
    if (!Files.exists(file)) return;
    if (!Files.isRegularFile(file)) {
      diagnostics.accept("Hook 配置无法解析：" + file.getFileName() + " 不是普通文件");
      return;
    }

    Object parsed;
    try {
      var options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      parsed = new Yaml(new SafeConstructor(options)).load(Files.readString(file));
    } catch (IOException | RuntimeException error) {
      diagnostics.accept("Hook 配置无法解析：" + file.getFileName());
      return;
    }
    if (!(parsed instanceof Map<?, ?> document)) {
      diagnostics.accept("Hook 配置无法解析：" + file.getFileName() + " 顶层必须是对象");
      return;
    }
    Object rawHooks = document.get("hooks");
    if (rawHooks == null) {
      sources.add(file);
      return;
    }
    if (!(rawHooks instanceof List<?> hookList)) {
      diagnostics.accept("Hook 配置无法解析：" + file.getFileName() + " hooks 必须是列表");
      return;
    }
    sources.add(file);
    for (int index = 0; index < hookList.size(); index++) {
      Object rawRule = hookList.get(index);
      String name =
          rawRule instanceof Map<?, ?> map && map.get("name") instanceof String text
              ? text
              : "<unnamed>";
      try {
        HookRule rule = parseRule(rawRule, file);
        if (!names.add(rule.name())) {
          diagnostics.accept(
              "Hook 规则跳过："
                  + file.getFileName()
                  + " hooks["
                  + index
                  + "] "
                  + rule.name()
                  + " 与先加载规则重名");
          continue;
        }
        rules.add(rule);
      } catch (IllegalArgumentException error) {
        diagnostics.accept(
            "Hook 规则跳过："
                + file.getFileName()
                + " hooks["
                + index
                + "] "
                + name
                + "："
                + safeReason(error));
      }
    }
  }

  private static HookRule parseRule(Object rawRule, Path source) {
    if (!(rawRule instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("rule must be an object");
    }
    String name = text(map.get("name"), "name");
    HookEvent event = HookEvent.parse(text(map.get("event"), "event"));
    HookAction action = parseAction(map.get("action"));
    Optional<HookRule.HookCondition> condition =
        map.containsKey("if") ? Optional.of(parseCondition(map.get("if"))) : Optional.empty();
    boolean onlyOnce = booleanValue(map.get("only_once"), false, "only_once");
    boolean async = booleanValue(map.get("async"), false, "async");
    Duration timeout = parseTimeout(map.get("timeout"));
    if (event.blocking() && async) {
      throw new IllegalArgumentException("blocking events cannot be async");
    }
    return new HookRule(name, event, condition, action, onlyOnce, async, timeout, source);
  }

  private static HookRule.HookCondition parseCondition(Object rawCondition) {
    if (!(rawCondition instanceof Map<?, ?> map) || map.size() != 1) {
      throw new IllegalArgumentException("if must contain exactly one condition group");
    }
    Object rawFields;
    HookRule.HookCondition.Combination combination;
    if (map.containsKey("all_of")) {
      combination = HookRule.HookCondition.Combination.ALL_OF;
      rawFields = map.get("all_of");
    } else if (map.containsKey("any_of")) {
      combination = HookRule.HookCondition.Combination.ANY_OF;
      rawFields = map.get("any_of");
    } else {
      throw new IllegalArgumentException("if must use all_of or any_of");
    }
    if (!(rawFields instanceof List<?> list) || list.isEmpty()) {
      throw new IllegalArgumentException("condition group must not be empty");
    }
    var fields = new ArrayList<HookRule.HookCondition.FieldMatch>();
    for (Object rawField : list) {
      if (!(rawField instanceof Map<?, ?> field)) {
        throw new IllegalArgumentException("condition must be an object");
      }
      Object name = field.get("field");
      Object match = field.get("match");
      if (!(name instanceof String fieldName) || !(match instanceof Map<?, ?> matchDefinition)) {
        throw new IllegalArgumentException("condition requires field and match");
      }
      fields.add(
          new HookRule.HookCondition.FieldMatch(fieldName, RuleMatcher.parse(matchDefinition)));
    }
    return new HookRule.HookCondition(combination, fields);
  }

  private static HookAction parseAction(Object rawAction) {
    if (!(rawAction instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("action must be an object");
    }
    String type = text(map.get("type"), "action.type");
    return switch (type) {
      case "shell" -> new HookAction.Shell(text(map.get("command"), "action.command"));
      case "prompt" -> new HookAction.Prompt(text(map.get("text"), "action.text"));
      case "subagent" ->
          new HookAction.Subagent(
              text(map.get("agent_name"), "action.agent_name"),
              text(map.get("prompt"), "action.prompt"));
      case "http" -> parseHttp(map);
      default -> throw new IllegalArgumentException("unknown action type");
    };
  }

  private static HookAction.Http parseHttp(Map<?, ?> map) {
    URI url;
    try {
      url = URI.create(text(map.get("url"), "action.url"));
    } catch (RuntimeException error) {
      throw new IllegalArgumentException("action.url is invalid");
    }
    String method = map.containsKey("method") ? text(map.get("method"), "action.method") : "POST";
    Map<String, String> headers = parseHeaders(map.get("headers"));
    Optional<String> body =
        map.containsKey("body")
            ? Optional.of(text(map.get("body"), "action.body"))
            : Optional.empty();
    return new HookAction.Http(url, method, headers, body);
  }

  private static Map<String, String> parseHeaders(Object rawHeaders) {
    if (rawHeaders == null) return Map.of();
    if (!(rawHeaders instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("action.headers must be an object");
    }
    var headers = new LinkedHashMap<String, String>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)
          || !(entry.getValue() instanceof String value)
          || key.isBlank()) {
        throw new IllegalArgumentException("action.headers must contain strings");
      }
      headers.put(key, value);
    }
    return headers;
  }

  private static Duration parseTimeout(Object rawTimeout) {
    if (rawTimeout == null) return DEFAULT_TIMEOUT;
    if (!(rawTimeout instanceof String text)) {
      throw new IllegalArgumentException("timeout must use ms, s, or m");
    }
    Matcher matcher = TIMEOUT.matcher(text);
    if (!matcher.matches()) throw new IllegalArgumentException("timeout must use ms, s, or m");
    try {
      long amount = Long.parseLong(matcher.group(1));
      if (amount <= 0) throw new IllegalArgumentException("timeout must be positive");
      return switch (matcher.group(2)) {
        case "ms" -> Duration.ofMillis(amount);
        case "s" -> Duration.ofSeconds(amount);
        case "m" -> Duration.ofMinutes(amount);
        default -> throw new IllegalArgumentException("timeout unit is invalid");
      };
    } catch (ArithmeticException | NumberFormatException error) {
      throw new IllegalArgumentException("timeout is out of range");
    }
  }

  private static boolean booleanValue(Object value, boolean fallback, String field) {
    if (value == null) return fallback;
    if (value instanceof Boolean booleanValue) return booleanValue;
    throw new IllegalArgumentException(field + " must be boolean");
  }

  private static String text(Object value, String field) {
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return text;
  }

  private static String safeReason(IllegalArgumentException error) {
    String message = error.getMessage();
    return message == null || message.isBlank() ? "字段无效" : message;
  }

  public record LoadedHooks(List<HookRule> rules, List<Path> sources) {
    public LoadedHooks {
      rules = List.copyOf(rules == null ? List.of() : rules);
      sources = List.copyOf(sources == null ? List.of() : sources);
    }
  }
}
