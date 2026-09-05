package com.mewcode.tool;

import java.util.Map;

/** MewCode 本地工具的统一能力契约。 */
public interface Tool {

  /** 返回发送给模型并用于路由调用的稳定名称。 */
  String name();

  /** 返回给模型看的用途和参数语义说明。 */
  String description();

  /** 返回工具的风险分类，用于展示和调度策略。 */
  ToolCategory category();

  /** 返回 provider 无关的 JSON Schema。 */
  Map<String, Object> inputSchema();

  /** 返回可选的输出 JSON Schema；本地工具默认没有声明。 */
  default Map<String, Object> outputSchema() {
    return Map.of();
  }

  /** 在执行上下文中执行工具，结果必须是完整、可回灌模型的文本。 */
  ToolResult execute(ToolExecutionContext context, Map<String, Object> input);

  /** 是否只读；Plan Mode 只允许此类工具。 */
  boolean isReadOnly();

  /** 是否可能破坏数据或改变外部状态。 */
  boolean isDestructive();

  /** 当前输入是否可以与相邻安全调用并发执行。 */
  boolean isConcurrencySafe(Map<String, Object> input);

  /** 是否默认从模型工具列表中延迟隐藏；本地工具默认立即可见。 */
  default boolean shouldDefer() {
    return false;
  }

  /** 系统工具不受 Skill 白名单和 Agent 模式裁剪。 */
  default boolean isSystem() {
    return false;
  }

  /** 是否只应在某个 Skill 激活并列入白名单时出现。 */
  default boolean isSkillTool() {
    return false;
  }

  /** 返回 null 表示校验通过，否则返回面向模型的调整提示。 */
  String validateInput(Map<String, Object> input);

  /** 在共享执行上下文中校验参数。 默认委托旧接口，保证外部自定义工具仍然兼容；需要项目根目录的工具覆盖此方法。 */
  default String validateInput(ToolExecutionContext context, Map<String, Object> input) {
    return validateInput(input);
  }
}
