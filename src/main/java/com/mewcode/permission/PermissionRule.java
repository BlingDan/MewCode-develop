package com.mewcode.permission;

/** 一条工具名加目标模式的权限规则。 */
public record PermissionRule(String pattern, RuleDecision decision, RuleSource source) {
  public PermissionRule {
    if (pattern == null || pattern.isBlank())
      throw new IllegalArgumentException("pattern must not be blank");
    if (decision == null) throw new IllegalArgumentException("decision must not be null");
    if (source == null) throw new IllegalArgumentException("source must not be null");
    parse(pattern);
  }

  public String toolName() {
    return parse(pattern).toolName();
  }

  public String targetPattern() {
    return parse(pattern).targetPattern();
  }

  public static PermissionRule of(String pattern, String decision, RuleSource source) {
    RuleDecision parsed;
    try {
      parsed = RuleDecision.valueOf(decision.trim().toUpperCase());
    } catch (RuntimeException error) {
      throw new IllegalArgumentException("decision must be allow or deny", error);
    }
    return new PermissionRule(pattern, parsed, source);
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
