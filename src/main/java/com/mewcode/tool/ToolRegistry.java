package com.mewcode.tool;

import com.mewcode.tool.impl.BashTool;
import com.mewcode.tool.impl.EditFileTool;
import com.mewcode.tool.impl.GlobTool;
import com.mewcode.tool.impl.GrepTool;
import com.mewcode.tool.impl.ReadFileTool;
import com.mewcode.tool.impl.WriteFileTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 集中注册工具并生成 provider 无关的 API 定义。
 *
 * <p>注册顺序单独保存，保证每轮发给模型的工具列表稳定；协议转换只改变外层字段
 * （Anthropic 的 input_schema / OpenAI 的 function.parameters），不改变工具自身 schema。</p>
 */
public final class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final List<String> registrationOrder = new ArrayList<>();

    /** 注册或替换工具；首次注册顺序保持不变。 */
    public synchronized void register(Tool tool) {
        if (tool == null) throw new IllegalArgumentException("tool must not be null");
        if (!tools.containsKey(tool.name())) registrationOrder.add(tool.name());
        tools.put(tool.name(), tool);
    }

    /** 按模型返回的工具名查找实现。 */
    public Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /** 返回稳定注册顺序的不可变工具快照。 */
    public synchronized List<Tool> getAll() {
        var result = new ArrayList<Tool>();
        for (String name : registrationOrder) {
            Tool tool = tools.get(name);
            if (tool != null) result.add(tool);
        }
        return List.copyOf(result);
    }

    /** 兼容既有方案中的方法名，生成当前 provider 所需的工具定义。 */
    public List<Map<String, Object>> toAPIFormate(ToolApiProtocol protocol) {
        return toAPIFormate(protocol, tool -> true);
    }

    /** 按策略过滤工具，并转换为目标 provider 的工具声明格式。 */
    public List<Map<String, Object>> toAPIFormate(ToolApiProtocol protocol,
                                                  Predicate<Tool> filter) {
        var result = new ArrayList<Map<String, Object>>();
        for (Tool tool : getAll()) {
            if (filter != null && !filter.test(tool)) continue;
            if (protocol == ToolApiProtocol.ANTHROPIC) {
                result.add(anthropicDefinition(tool));
            } else {
                result.add(openAiDefinition(tool));
            }
        }
        return List.copyOf(result);
    }

    public List<Map<String, Object>> toApiFormat(ToolApiProtocol protocol) {
        return toAPIFormate(protocol);
    }

    /** 创建 MewCode 内置的文件、搜索和命令工具集合。 */
    public static ToolRegistry createDefault() {
        var registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        registry.register(new EditFileTool());
        registry.register(new BashTool());
        registry.register(new GlobTool());
        registry.register(new GrepTool());
        return registry;
    }

    private static Map<String, Object> anthropicDefinition(Tool tool) {
        var definition = new LinkedHashMap<String, Object>();
        definition.put("name", tool.name());
        definition.put("description", tool.description());
        definition.put("input_schema", tool.inputSchema());
        return Map.copyOf(definition);
    }   

    private static Map<String, Object> openAiDefinition(Tool tool) {
        var function = new LinkedHashMap<String, Object>();
        function.put("name", tool.name());
        function.put("description", tool.description());
        function.put("parameters", tool.inputSchema());
        var definition = new LinkedHashMap<String, Object>();
        definition.put("type", "function");
        definition.put("function", function);
        return Map.copyOf(definition);
    }
}
