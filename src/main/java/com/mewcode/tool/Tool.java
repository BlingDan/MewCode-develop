package com.mewcode.tool;

import java.util.Map;

/** MewCode 本地工具的统一能力契约。 */
public interface Tool {

    String name();

    String description();

    ToolCategory category();

    Map<String, Object> inputSchema();

    ToolResult execute(ToolExecutionContext context, Map<String, Object> input);

    boolean isReadOnly();

    boolean isDestructive();

    /** 返回 true 表示该输入可以和其他安全调用并发执行。 */
    boolean isConcurrencySafe(Map<String, Object> input);

    /** 返回 null 表示校验通过，否则返回面向模型的调整提示。 */
    String validateInput(Map<String, Object> input);

    /**
     * 在共享执行上下文中校验参数。
     * 默认委托旧接口，保证外部自定义工具仍然兼容；需要项目根目录的工具覆盖此方法。
     */
    default String validateInput(ToolExecutionContext context, Map<String, Object> input) {
        return validateInput(input);
    }
}
