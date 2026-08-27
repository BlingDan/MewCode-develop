package com.mewcode.permission;

import java.util.Objects;

/** 权限闸门的可解释结果。 */
public record PermissionCheck(
    PermissionDecision decision,
    PermissionReason reason,
    String message,
    String matchedPattern,
    String authorizationKey,
    boolean pathOutside) {
  public PermissionCheck {
    decision = Objects.requireNonNull(decision, "decision");
    reason = Objects.requireNonNull(reason, "reason");
    message = Objects.requireNonNullElse(message, "");
    matchedPattern = Objects.requireNonNullElse(matchedPattern, "");
    authorizationKey = Objects.requireNonNullElse(authorizationKey, "");
  }

  public PermissionCheck(
      PermissionDecision decision,
      PermissionReason reason,
      String message,
      String matchedPattern,
      String authorizationKey) {
    this(decision, reason, message, matchedPattern, authorizationKey, false);
  }
}
