package com.mewcode.tool;

import java.util.Objects;

/** 集中维护全局工具规则及其在具体工具描述中的局部强化文本。 */
public final class ToolPromptRules {

  private static final String EDITING_RULE = "编辑前必须先调用 ReadFile 读取并确认目标文件内容；只能编辑已读取且未变化的文件。";
  private static final String DEDICATED_TOOL_RULE =
      "读取、查找和搜索优先使用 ReadFile、Glob 和 Grep 等专用工具，不要用 Bash 代替。";

  private ToolPromptRules() {}

  /** 返回应进入全局系统提示的工具使用约束。 */
  public static String globalInstructions() {
    return "优先使用专用工具完成读取、查找、搜索、编辑和命令执行。"
        + "\n"
        + EDITING_RULE
        + "\n"
        + DEDICATED_TOOL_RULE
        + "\n工具出错后必须根据结构化错误调整参数，不要声称操作已经成功。";
  }

  /** 返回发送给模型的工具描述，并按工具职责补充局部规则。 */
  public static String descriptionFor(Tool tool) {
    Objects.requireNonNull(tool, "tool");
    String description = Objects.requireNonNullElse(tool.description(), "");
    return switch (tool.name()) {
      case "EditFile", "WriteFile" -> appendIfMissing(description, EDITING_RULE);
      case "Bash" -> appendIfMissing(description, DEDICATED_TOOL_RULE);
      default -> description;
    };
  }

  public static String editingRule() {
    return EDITING_RULE;
  }

  public static String dedicatedToolRule() {
    return DEDICATED_TOOL_RULE;
  }

  private static String appendIfMissing(String description, String rule) {
    return description.contains(rule) ? description : description + "\n" + rule;
  }
}
