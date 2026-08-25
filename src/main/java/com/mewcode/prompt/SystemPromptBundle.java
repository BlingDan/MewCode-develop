package com.mewcode.prompt;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** 稳定系统提示和环境片段的不可变快照。 */
public record SystemPromptBundle(List<PromptModule> modules, EnvironmentContext environment) {

  public SystemPromptBundle {
    modules = modules == null ? List.of() : List.copyOf(modules);
    environment = Objects.requireNonNull(environment, "environment");
  }

  /** 返回稳定模块和环境上下文两个独立 system 片段。 */
  public List<String> systemSegments() {
    List<PromptModule> ordered = new ArrayList<>(modules);
    ordered.sort(Comparator.comparingInt(PromptModule::priority));
    String stable =
        ordered.stream()
            .map(PromptModule::content)
            .filter(content -> !content.isBlank())
            .collect(Collectors.joining("\n\n"));
    String environmentText = environment.render();
    if (stable.isEmpty()) return List.of(environmentText);
    return List.of(stable, environmentText);
  }

  /** 提供给旧字符串接口的兼容文本。 */
  public String flattenedText() {
    return String.join("\n\n", systemSegments());
  }
}
