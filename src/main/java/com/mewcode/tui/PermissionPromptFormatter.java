package com.mewcode.tui;

import com.mewcode.permission.PermissionRequest;

/** 将权限请求渲染成便于用户判断的确认文案。 */
public final class PermissionPromptFormatter {
  private PermissionPromptFormatter() {}

  public static String format(PermissionRequest request) {
    String operation;
    if ("Bash".equals(request.toolName())
        && request.arguments().get("command") instanceof String command) {
      operation = "[Bash] " + command;
    } else {
      operation = "[" + request.toolName() + "] " + request.arguments();
    }
    return "MewCode 想要执行以下操作：\n\n"
        + operation
        + "\n\n"
        + "原因："
        + request.reason()
        + "\n\n"
        + "允许执行？(y)是 / (n)否 / (s)本会话允许 / (a)始终允许此类操作";
  }
}
