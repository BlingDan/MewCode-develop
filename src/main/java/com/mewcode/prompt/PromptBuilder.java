package com.mewcode.prompt;

import com.mewcode.agent.AgentMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 构建工具可用阶段的系统提示词。
 *
 * <p>提示词会随本轮模式生成：Plan Mode 告诉模型只做调查并输出计划，Execute Mode 告诉模型完成并验证修改。真正的工具过滤仍由 {@code ToolPolicy}
 * 执行，这里的文字是 行为引导而不是权限系统。
 */
public final class PromptBuilder {

  private static final int IDENTITY_PRIORITY = 10;
  private static final int SYSTEM_CONSTRAINTS_PRIORITY = 20;
  private static final int TASK_MODE_PRIORITY = 30;
  private static final int ACTION_EXECUTION_PRIORITY = 40;
  private static final int TOOL_USAGE_PRIORITY = 50;
  private static final int TONE_PRIORITY = 60;
  private static final int TEXT_OUTPUT_PRIORITY = 70;

  private PromptBuilder() {}

  /** 返回固定模块和预留的可选模块，调用方可在此基础上追加局部模块。 */
  public static List<PromptModule> modules() {
    List<PromptModule> modules = new ArrayList<>(fixedModules());
    modules.addAll(optionalModules());
    return List.copyOf(modules);
  }

  /** 返回按职责拆分的七个稳定系统提示模块。 */
  public static List<PromptModule> fixedModules() {
    return List.of(
        new PromptModule(
            "identity",
            IDENTITY_PRIORITY,
            "You are MewCode, a helpful coding assistant running in a terminal."),
        new PromptModule(
            "system-constraints",
            SYSTEM_CONSTRAINTS_PRIORITY,
            "Follow the system instructions and the project's safety boundaries.\n"
                + "If a tool returns an error, adjust the parameters based on the structured error instead of claiming success."),
        new PromptModule(
            "task-mode",
            TASK_MODE_PRIORITY,
            "Respect the active task mode and its tool restrictions. Runtime mode reminders are provided separately when the mode or round changes."),
        new PromptModule(
            "action-execution",
            ACTION_EXECUTION_PRIORITY,
            "Inspect the relevant project context before acting, make the smallest change that solves the request, and verify important changes before reporting completion.\n"
                + "Read large files in ranges with offset and limit. Continue the task across multiple tool rounds when results require further investigation or action."),
        new PromptModule(
            "tool-usage",
            TOOL_USAGE_PRIORITY,
            "Prefer dedicated tools for reading, searching, editing, and running commands.\n"
                + "Read the target file before editing it.\n"
                + "Use ReadFile, Glob, and Grep instead of assembling their behavior with Bash."),
        new PromptModule(
            "tone",
            TONE_PRIORITY,
            "Be clear, accurate, direct, and proportionate to the task. Avoid unnecessary speculation or unrelated changes."),
        new PromptModule(
            "text-output",
            TEXT_OUTPUT_PRIORITY,
            "Use Markdown when it improves readability. Summarize completed work and include relevant verification results in the final response."));
  }

  /** 返回后续章节可填充的自定义指令、Skill 和长期记忆插槽。 */
  public static List<PromptModule> optionalModules() {
    return List.of(
        new PromptModule("custom-instructions", 80, ""),
        new PromptModule("activated-skills", 90, ""),
        new PromptModule("long-term-memory", 100, ""));
  }

  /** 构建稳定模块和环境上下文分离的系统提示 bundle。 */
  public static SystemPromptBundle buildBundle(Path projectRoot) {
    Path root = projectRoot.toAbsolutePath().normalize();
    return new SystemPromptBundle(modules(), new EnvironmentContext(root, Map.of()));
  }

  /** 使用当前目录和 Execute Mode 生成默认提示词。 */
  public static String buildSystemPrompt() {
    return buildSystemPrompt(Path.of(".").toAbsolutePath().normalize(), AgentMode.EXECUTE);
  }

  /** 使用指定项目根目录生成 Execute Mode 提示词。 */
  public static String buildSystemPrompt(Path projectRoot) {
    return buildSystemPrompt(projectRoot, AgentMode.EXECUTE);
  }

  /** 生成包含项目根目录和本轮 Agent 模式的完整系统提示词。 */
  public static String buildSystemPrompt(Path projectRoot, AgentMode mode) {
    String common = buildBundle(projectRoot).flattenedText();
    String modeHint =
        mode == AgentMode.PLAN
            ? """
                  You are currently in planning mode. Use only safe read-only tools for investigation.
                  Do not modify files, run destructive commands, or claim that changes were made.
                  Finish with a concrete, ordered implementation plan.
                  """
            : """
                  You are currently in execution mode. Use the supplied tools to complete the user's task.
                  Verify important changes with the available tools before giving the final response.
                  """;
    return (common + "\n" + modeHint).strip();
  }
}
