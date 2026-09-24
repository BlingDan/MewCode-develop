package com.mewcode.subagent;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** 一份已经解析并校验的不可变 Agent 定义。 */
public record SubAgentSpec(
    String name,
    String description,
    Set<String> tools,
    Set<String> disallowedTools,
    String systemPrompt,
    int maxTurns,
    String model,
    PermissionMode permissionMode,
    Source source,
    Path sourcePath) {

  public SubAgentSpec {
    name = requireText(name, "Agent 名称").toLowerCase(Locale.ROOT);
    if (!name.matches("[a-z0-9][a-z0-9_-]*")) {
      throw new IllegalArgumentException("Agent 名称只能包含小写字母、数字、下划线和连字符");
    }
    description = requireText(description, "Agent 说明");
    tools = tools == null ? null : Set.copyOf(new LinkedHashSet<>(tools));
    disallowedTools = immutableNames(disallowedTools);
    systemPrompt = requireText(systemPrompt, "Agent 正文");
    if (maxTurns <= 0) throw new IllegalArgumentException("maxTurns 必须是正整数");
    model = normalizeModel(model);
    permissionMode = permissionMode == null ? PermissionMode.DEFAULT : permissionMode;
    source = Objects.requireNonNull(source, "source");
    sourcePath = Objects.requireNonNull(sourcePath, "sourcePath").toAbsolutePath().normalize();
  }

  public enum PermissionMode {
    DEFAULT,
    DONT_ASK
  }

  public enum Source {
    PLUGIN,
    BUILTIN,
    USER,
    PROJECT
  }

  private static Set<String> immutableNames(Set<String> values) {
    if (values == null || values.isEmpty()) return Set.of();
    var result = new LinkedHashSet<String>();
    for (String value : values) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("工具名称不能为空");
      }
      result.add(value);
    }
    return Set.copyOf(result);
  }

  private static String normalizeModel(String value) {
    String model = value == null || value.isBlank() ? "inherit" : value.toLowerCase(Locale.ROOT);
    if (!Set.of("inherit", "haiku", "sonnet", "opus").contains(model)) {
      throw new IllegalArgumentException("model 的值无效：" + value);
    }
    return model;
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + "不能为空");
    return value.strip();
  }
}
