package com.mewcode.skill;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 一份已经解析并校验的不可变 Skill 定义。 */
public record SkillDefinition(
    Meta meta, String body, Source source, Path entry, Path directory, List<ToolSpec> tools) {

  public SkillDefinition {
    meta = Objects.requireNonNull(meta, "meta");
    body = requireText(body, "Skill 正文");
    source = Objects.requireNonNull(source, "source");
    entry = Objects.requireNonNull(entry, "entry").toAbsolutePath().normalize();
    directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    tools = tools == null ? List.of() : List.copyOf(tools);
  }

  /** YAML frontmatter 对应的元信息。 */
  public record Meta(
      String name,
      String description,
      List<String> tools,
      Mode mode,
      ForkContext context,
      int contextCount,
      String model) {

    public Meta {
      name = requireText(name, "Skill 名称").toLowerCase(Locale.ROOT);
      if (!name.matches("[a-z0-9][a-z0-9_-]*")) {
        throw new IllegalArgumentException("Skill 名称只能包含小写字母、数字、下划线和连字符");
      }
      description = requireText(description, "Skill 说明");
      tools = tools == null ? List.of() : List.copyOf(tools);
      mode = mode == null ? Mode.SHARED : mode;
      context = context == null ? ForkContext.NONE : context;
      if (contextCount <= 0) throw new IllegalArgumentException("context_count 必须是正整数");
      model = model == null || model.isBlank() ? null : model.strip();
    }
  }

  /** 目录型 Skill 的一个脚本工具声明。 */
  public record ToolSpec(
      String name, String description, Map<String, Object> inputSchema, Path executable) {

    public ToolSpec {
      name = requireText(name, "工具名称");
      description = requireText(description, "工具说明");
      inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
      executable = Objects.requireNonNull(executable, "executable").toAbsolutePath().normalize();
    }
  }

  public enum Mode {
    SHARED,
    FORK
  }

  public enum ForkContext {
    NONE,
    RECENT,
    FULL
  }

  public enum Source {
    BUILTIN,
    USER,
    PROJECT
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + "不能为空");
    return value;
  }
}
