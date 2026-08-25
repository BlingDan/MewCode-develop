package com.mewcode.tool.impl;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolExecutionContext;
import com.mewcode.tool.ToolPromptRules;
import com.mewcode.tool.ToolResult;
import com.mewcode.tool.support.CommandRunner;
import com.mewcode.tool.support.ToolInput;
import java.util.List;
import java.util.Map;

/** 通过系统 shell 执行命令。 */
public final class BashTool implements Tool {

  private final CommandRunner runner;

  public BashTool() {
    this(new CommandRunner());
  }

  BashTool(CommandRunner runner) {
    this.runner = runner;
  }

  @Override
  public String name() {
    return "Bash";
  }

  @Override
  public String description() {
    return "在项目根目录通过系统 shell 执行命令，返回合并后的 stdout/stderr 和退出码。\n"
        + ToolPromptRules.dedicatedToolRule();
  }

  @Override
  public ToolCategory category() {
    return ToolCategory.SHELL;
  }

  @Override
  public Map<String, Object> inputSchema() {
    return Map.of(
        "type",
        "object",
        "properties",
        Map.of("command", Map.of("type", "string", "description", "要执行的 shell 命令")),
        "required",
        List.of("command"),
        "additionalProperties",
        false);
  }

  @Override
  public String validateInput(Map<String, Object> input) {
    return ToolInput.requireString(input, "command", " 请传入要执行的 shell 命令。");
  }

  /** 在项目根目录执行命令，并把退出码、超时和截断信息转成工具结果。 */
  @Override
  public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
    String command = (String) input.get("command");
    try {
      CommandRunner.Result result = runner.run(command, context);
      String output =
          "<output>\n"
              + result.output()
              + "\n</output>\n"
              + "<exit_code>"
              + result.exitCode()
              + "</exit_code>";
      if (result.timedOut()) {
        return new ToolResult(
            output + "\n命令执行超时，已强制终止。",
            true,
            Map.of("timedOut", true, "exitCode", result.exitCode()));
      }
      boolean error = CommandRunner.isErrorExit(command, result.exitCode());
      return new ToolResult(
          output,
          error,
          Map.of(
              "exitCode", result.exitCode(),
              "truncated", result.truncated()));
    } catch (Exception error) {
      return ToolResult.error("执行 shell 命令失败：" + error.getMessage() + "。请检查命令语法后重试。");
    }
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @Override
  public boolean isDestructive() {
    return true;
  }

  @Override
  public boolean isConcurrencySafe(Map<String, Object> input) {
    return false;
  }
}
