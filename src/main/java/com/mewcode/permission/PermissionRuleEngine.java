package com.mewcode.permission;

import com.mewcode.tool.ToolCall;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** 按会话、本地、项目、用户顺序执行的规则引擎。 */
public final class PermissionRuleEngine {
  private final List<PermissionRule> rules;
  private final Set<String> sessionGrants = ConcurrentHashMap.newKeySet();

  public PermissionRuleEngine() {
    this(List.of());
  }

  public PermissionRuleEngine(List<PermissionRule> rules) {
    this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
  }

  public List<PermissionRule> rules() {
    return rules;
  }

  /** 匹配完整工具目标，规则模式是全字符串匹配而不是子串匹配。 */
  public Optional<RuleMatch> match(ToolCall call) {
    return match(call, null);
  }

  /** 匹配目标；文件路径会先按项目根目录规范化，Grep 同时包含表达式和搜索范围。 */
  public Optional<RuleMatch> match(ToolCall call, Path projectRoot) {
    Objects.requireNonNull(call, "call");
    String subject = target(call, projectRoot);
    return rules.stream()
        .filter(rule -> rule.toolName().equals(call.toolName()))
        .filter(rule -> globMatches(rule.targetPattern(), subject))
        .map(rule -> new RuleMatch(rule, subject))
        .findFirst();
  }

  public void addSessionGrant(String authorizationKey) {
    if (authorizationKey == null || authorizationKey.isBlank()) {
      throw new IllegalArgumentException("authorizationKey must not be blank");
    }
    sessionGrants.add(authorizationKey);
  }

  public boolean isSessionGranted(String authorizationKey) {
    return authorizationKey != null && sessionGrants.contains(authorizationKey);
  }

  public Set<String> sessionGrants() {
    return Set.copyOf(sessionGrants);
  }

  /** 将工具调用转换成规则和授权共用的稳定目标文本。 */
  public static String target(ToolCall call) {
    return target(call, null);
  }

  /** 将工具调用转换成规则目标；路径和搜索范围使用稳定的规范化文本。 */
  public static String target(ToolCall call, Path projectRoot) {
    if ("Bash".equals(call.toolName())) {
      return stringValue(call.arguments().get("command"));
    }
    if ("Grep".equals(call.toolName())) {
      String pattern = stringValue(call.arguments().get("pattern"));
      String path = normalizePath(stringValue(call.arguments().get("path")), projectRoot);
      return pattern + " @ " + path;
    }
    Object path = call.arguments().get("path");
    if (path instanceof String value) {
      return normalizePath(value, projectRoot);
    }
    return stringValue(call.arguments().get("pattern"));
  }

  public static String authorizationKey(ToolCall call) {
    return call.toolName() + "(" + target(call) + ")";
  }

  static boolean globMatches(String glob, String value) {
    if (glob.indexOf('*') < 0 && glob.indexOf('?') < 0) return glob.equals(value);
    return Pattern.compile(globToRegex(glob), Pattern.DOTALL).matcher(value).matches();
  }

  private static String globToRegex(String glob) {
    var regex = new StringBuilder("^");
    for (int i = 0; i < glob.length(); i++) {
      char character = glob.charAt(i);
      if (character == '*') {
        regex.append(".*");
      } else if (character == '?') {
        regex.append('.');
      } else {
        if ("\\.^$|()[]{}+".indexOf(character) >= 0) regex.append('\\');
        regex.append(character);
      }
    }
    return regex.append('$').toString();
  }

  private static String stringValue(Object value) {
    return value instanceof String text ? text : "";
  }

  private static String normalizePath(String value, Path projectRoot) {
    if (value.isBlank()) return value;
    try {
      Path path = Path.of(value);
      if (projectRoot != null && !path.isAbsolute()) path = projectRoot.resolve(path);
      return path.normalize().toString();
    } catch (RuntimeException ignored) {
      return value;
    }
  }
}
