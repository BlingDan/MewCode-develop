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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 集中注册工具并生成 provider 无关的 API 定义。
 *
 * <p>注册顺序单独保存，保证每轮发给模型的工具列表稳定；协议转换只改变外层字段 （Anthropic 的 input_schema / OpenAI 的
 * function.parameters），不改变工具自身 schema。
 */
public final class ToolRegistry {

  private final Map<String, Tool> tools = new ConcurrentHashMap<>();
  private final List<String> registrationOrder = new ArrayList<>();
  private final Set<String> discoveredTools = ConcurrentHashMap.newKeySet();
  private final Set<String> skillTools = ConcurrentHashMap.newKeySet();

  /** 注册或替换工具；首次注册顺序保持不变。 */
  public synchronized void register(Tool tool) {
    if (tool == null) throw new IllegalArgumentException("tool must not be null");
    if (!tools.containsKey(tool.name())) registrationOrder.add(tool.name());
    tools.put(tool.name(), tool);
    if (!tool.shouldDefer()) discoveredTools.remove(tool.name());
  }

  /** 原子替换当前 Catalog 提供的脚本工具；冲突时不改变旧集合并返回冲突名称。 */
  public synchronized List<String> replaceSkillTools(List<? extends Tool> replacements) {
    List<? extends Tool> incoming = replacements == null ? List.of() : List.copyOf(replacements);
    var names = new java.util.LinkedHashSet<String>();
    var conflicts = new ArrayList<String>();
    for (Tool tool : incoming) {
      if (tool == null || !names.add(tool.name())) {
        if (tool != null) conflicts.add(tool.name());
        continue;
      }
      if (tools.containsKey(tool.name()) && !skillTools.contains(tool.name())) {
        conflicts.add(tool.name());
      }
    }
    if (!conflicts.isEmpty()) return List.copyOf(conflicts.stream().distinct().toList());

    for (String name : List.copyOf(skillTools)) {
      tools.remove(name);
      registrationOrder.remove(name);
      discoveredTools.remove(name);
    }
    skillTools.clear();
    for (Tool tool : incoming) {
      tools.put(tool.name(), tool);
      registrationOrder.add(tool.name());
      skillTools.add(tool.name());
    }
    return List.of();
  }

  /** 当前非 Skill 工具名快照，供 Catalog 白名单校验。 */
  public synchronized Set<String> ordinaryToolNames() {
    var result = new java.util.LinkedHashSet<>(registrationOrder);
    result.removeAll(skillTools);
    return Set.copyOf(result);
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

  /** 判断工具是否可以在当前轮进入模型工具列表。 */
  public boolean modelVisible(Tool tool) {
    if (tool == null) return false;
    if ("ToolSearch".equals(tool.name())) return hasUndiscoveredDeferredTools();
    return !tool.shouldDefer() || discoveredTools.contains(tool.name());
  }

  /** 返回当前轮可以发送给模型的工具快照。 */
  public List<Tool> getModelVisibleTools() {
    return getAll().stream().filter(this::modelVisible).toList();
  }

  /** 返回尚未被 ToolSearch 发现的延迟工具名，保持注册顺序。 */
  public synchronized List<String> deferredToolNames() {
    return registrationOrder.stream()
        .map(tools::get)
        .filter(tool -> tool != null && tool.shouldDefer())
        .filter(tool -> !discoveredTools.contains(tool.name()))
        .map(Tool::name)
        .toList();
  }

  /** 当前是否仍有未发现的延迟工具。 */
  public boolean hasUndiscoveredDeferredTools() {
    return !deferredToolNames().isEmpty();
  }

  /** 在本地精确查找并标记一个延迟工具；查找失败不改变状态。 */
  public synchronized Optional<Tool> findAndDiscover(String name) {
    if (name == null) return Optional.empty();
    Tool tool = tools.get(name);
    if (tool == null || !tool.shouldDefer()) return Optional.empty();
    discoveredTools.add(name);
    return Optional.of(tool);
  }

  /** 标记一个已注册的延迟工具已被发现。 */
  public synchronized boolean markDiscovered(String name) {
    return findAndDiscover(name).isPresent();
  }

  /** 返回当前 Registry 中延迟工具的本地状态。 */
  public boolean isDiscovered(String name) {
    return name != null && discoveredTools.contains(name);
  }

  /** 按 Agent 当前可见性过滤并生成 provider 工具定义。 */
  public List<Map<String, Object>> toAPIFormateForModel(
      ToolApiProtocol protocol, Predicate<Tool> filter) {
    return toAPIFormate(
        protocol, tool -> modelVisible(tool) && (filter == null || filter.test(tool)));
  }

  /** 兼容既有方案中的方法名，生成当前 provider 所需的工具定义。 */
  public List<Map<String, Object>> toAPIFormate(ToolApiProtocol protocol) {
    return toAPIFormate(protocol, tool -> true);
  }

  /** 按策略过滤工具，并转换为目标 provider 的工具声明格式。 */
  public List<Map<String, Object>> toAPIFormate(ToolApiProtocol protocol, Predicate<Tool> filter) {
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
    definition.put("description", ToolPromptRules.descriptionFor(tool));
    definition.put("input_schema", tool.inputSchema());
    return Map.copyOf(definition);
  }

  private static Map<String, Object> openAiDefinition(Tool tool) {
    var function = new LinkedHashMap<String, Object>();
    function.put("name", tool.name());
    function.put("description", ToolPromptRules.descriptionFor(tool));
    function.put("parameters", tool.inputSchema());
    var definition = new LinkedHashMap<String, Object>();
    definition.put("type", "function");
    definition.put("function", function);
    return Map.copyOf(definition);
  }
}
