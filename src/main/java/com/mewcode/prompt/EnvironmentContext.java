package com.mewcode.prompt;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 会话级稳定环境上下文。 */
public record EnvironmentContext(Path projectRoot, Map<String, String> attributes) {

  public EnvironmentContext {
    projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }

  /** 渲染为独立的 system 片段，属性按名称排序以保证输出稳定。 */
  public String render() {
    StringBuilder result =
        new StringBuilder().append("The current project root is: ").append(projectRoot);
    String extra =
        attributes.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
            .map(entry -> entry.getKey() + ": " + entry.getValue())
            .collect(Collectors.joining("\n"));
    if (!extra.isEmpty()) result.append('\n').append(extra);
    return result.toString();
  }
}
