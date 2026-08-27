package com.mewcode.permission;

import java.util.LinkedHashMap;
import java.util.Map;

/** 发给 TUI 的一次确认请求。 */
public record PermissionRequest(
    String requestId,
    String toolName,
    Map<String, Object> arguments,
    String displayOperation,
    String reason,
    String authorizationKey) {
  public PermissionRequest {
    requestId = requireText(requestId, "requestId");
    toolName = requireText(toolName, "toolName");
    arguments = arguments == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(arguments));
    displayOperation = requireText(displayOperation, "displayOperation");
    reason = requireText(reason, "reason");
    authorizationKey = requireText(authorizationKey, "authorizationKey");
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(field + " must not be blank");
    return value;
  }
}
