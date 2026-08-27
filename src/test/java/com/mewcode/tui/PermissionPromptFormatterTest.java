package com.mewcode.tui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.permission.PermissionRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PermissionPromptFormatterTest {
  @Test
  void rendersTheBashOperationAndDecisionOptions() {
    String prompt =
        PermissionPromptFormatter.format(
            new PermissionRequest(
                "call-1",
                "Bash",
                Map.of("command", "git commit -m \"fix\""),
                "[Bash] git commit -m \"fix\"",
                "当前权限模式要求用户确认操作",
                "Bash(git *)"));

    assertTrue(prompt.contains("MewCode 想要执行以下操作："));
    assertTrue(prompt.contains("[Bash] git commit -m \"fix\""));
    assertTrue(prompt.contains("(y)是 / (n)否"));
    assertTrue(prompt.contains("(a)始终允许此类操作"));
  }
}
