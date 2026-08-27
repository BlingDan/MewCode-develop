package com.mewcode.permission;

import java.util.Objects;

/** 规则引擎实际命中的规则和目标。 */
public record RuleMatch(PermissionRule rule, String matchedSubject) {
  public RuleMatch {
    Objects.requireNonNull(rule, "rule");
    if (matchedSubject == null) matchedSubject = "";
  }
}
