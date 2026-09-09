package com.mewcode.permission;

import java.util.Map;

/** 一条工具名加目标匹配器的权限规则。 */
public record PermissionRule(
    String pattern,
    RuleDecision decision,
    RuleSource source,
    String toolName,
    RuleMatcher matcher) {
  public PermissionRule {
    if (pattern == null || pattern.isBlank())
      throw new IllegalArgumentException("pattern must not be blank");
    if (decision == null) throw new IllegalArgumentException("decision must not be null");
    if (source == null) throw new IllegalArgumentException("source must not be null");
    if (toolName == null || toolName.isBlank())
      throw new IllegalArgumentException("toolName must not be blank");
    if (matcher == null) throw new IllegalArgumentException("matcher must not be null");
  }

  public PermissionRule(String pattern, RuleDecision decision, RuleSource source) {
    this(
        pattern,
        decision,
        source,
        parse(pattern).toolName(),
        RuleMatcher.glob(parse(pattern).targetPattern()));
  }

  public static PermissionRule of(String pattern, String decision, RuleSource source) {
    return new PermissionRule(pattern, parseDecision(decision), source);
  }

  public static PermissionRule of(
      String toolName, Map<?, ?> match, String decision, RuleSource source) {
    if (toolName == null || toolName.isBlank()) {
      throw new IllegalArgumentException("toolName must not be blank");
    }
    if (match == null) throw new IllegalArgumentException("match must not be null");
    RuleMatcher matcher = RuleMatcher.parse(match);
    return new PermissionRule(
        toolName + "(" + String.valueOf(match.get("type")) + ")",
        parseDecision(decision),
        source,
        toolName,
        matcher);
  }

  public String targetPattern() {
    return matcher instanceof RuleMatcher.Glob glob ? glob.value() : pattern;
  }

  private static RuleDecision parseDecision(String decision) {
    RuleDecision parsed;
    try {
      parsed = RuleDecision.valueOf(decision.trim().toUpperCase());
    } catch (RuntimeException error) {
      throw new IllegalArgumentException("decision must be allow or deny", error);
    }
    return parsed;
  }

  private static ParsedPattern parse(String value) {
    int open = value.indexOf('(');
    if (open <= 0 || !value.endsWith(")") || open == value.length() - 2) {
      throw new IllegalArgumentException("permission pattern must look like Tool(target)");
    }
    String tool = value.substring(0, open).trim();
    String target = value.substring(open + 1, value.length() - 1);
    if (tool.isBlank() || target.isBlank() || target.contains("\n")) {
      throw new IllegalArgumentException("permission pattern contains an invalid tool or target");
    }
    return new ParsedPattern(tool, target);
  }

  private record ParsedPattern(String toolName, String targetPattern) {}
}
