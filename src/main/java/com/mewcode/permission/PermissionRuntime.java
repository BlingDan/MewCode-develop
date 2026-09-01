package com.mewcode.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 当前进程内可变的权限模式与临时规则；每次 Agent Run 使用不可变快照。 */
public final class PermissionRuntime {
  private final PermissionMode startupMode;
  private final PermissionRuleEngine configuredEngine;
  private final List<PermissionRule> temporaryRules = new ArrayList<>();
  private PermissionMode mode;

  public PermissionRuntime(PermissionMode startupMode, PermissionRuleEngine configuredEngine) {
    this.startupMode = Objects.requireNonNull(startupMode, "startupMode");
    this.mode = startupMode;
    this.configuredEngine = Objects.requireNonNull(configuredEngine, "configuredEngine");
  }

  public synchronized PermissionMode mode() {
    return mode;
  }

  public synchronized List<PermissionRule> temporaryRules() {
    return List.copyOf(temporaryRules);
  }

  public synchronized void setMode(String value) {
    PermissionMode next = PermissionMode.parse(value);
    if (next == PermissionMode.PLAN) {
      throw new IllegalArgumentException("计划模式请使用 /plan 切换");
    }
    mode = next;
  }

  public synchronized PermissionRule addRule(String pattern, String effect) {
    PermissionRule rule = PermissionRule.of(pattern, effect, RuleSource.SESSION);
    temporaryRules.add(rule);
    return rule;
  }

  public synchronized void reset() {
    mode = startupMode;
    temporaryRules.clear();
  }

  public synchronized Snapshot snapshot() {
    var rules =
        new ArrayList<PermissionRule>(temporaryRules.size() + configuredEngine.rules().size());
    rules.addAll(temporaryRules);
    rules.addAll(configuredEngine.rules());
    return new Snapshot(mode, configuredEngine.withRules(rules));
  }

  public record Snapshot(PermissionMode mode, PermissionRuleEngine ruleEngine) {}
}
