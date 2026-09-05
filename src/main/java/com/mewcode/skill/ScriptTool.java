package com.mewcode.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolExecutionContext;
import com.mewcode.tool.ToolResult;
import com.mewcode.tool.support.CommandRunner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 目录型 Skill 脚本的 Tool 适配器。 */
public final class ScriptTool implements Tool {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final SkillDefinition.ToolSpec spec;
  private final Path skillDirectory;
  private final CommandRunner runner;

  public ScriptTool(SkillDefinition.ToolSpec spec, Path skillDirectory) {
    this(spec, skillDirectory, new CommandRunner());
  }

  ScriptTool(SkillDefinition.ToolSpec spec, Path skillDirectory, CommandRunner runner) {
    this.spec = Objects.requireNonNull(spec, "spec");
    this.skillDirectory = realDirectory(Objects.requireNonNull(skillDirectory, "skillDirectory"));
    this.runner = Objects.requireNonNull(runner, "runner");
  }

  @Override
  public String name() {
    return spec.name();
  }

  @Override
  public String description() {
    return spec.description();
  }

  @Override
  public ToolCategory category() {
    return ToolCategory.SHELL;
  }

  @Override
  public Map<String, Object> inputSchema() {
    return spec.inputSchema();
  }

  @Override
  public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
    try {
      String boundaryError = validateExecutable();
      if (boundaryError != null) return ToolResult.error(boundaryError);
      CommandRunner.ScriptResult result =
          runner.runScript(
              spec.executable(), skillDirectory, JSON.writeValueAsString(input), context);
      if (result.cancelled()) return ToolResult.error("Skill 工具执行已取消。");
      if (result.timedOut()) return ToolResult.error("Skill 工具执行超时，进程已终止。");
      if (result.exitCode() != 0) return ToolResult.error("Skill 工具执行失败，退出码：" + result.exitCode());
      Map<String, Object> output =
          JSON.readValue(result.stdout().strip(), new TypeReference<Map<String, Object>>() {});
      Object content = output.get("content");
      Object isError = output.getOrDefault("is_error", false);
      if (!(content instanceof String text) || !(isError instanceof Boolean failed)) {
        return ToolResult.error("Skill 工具输出格式无效：需要 content 字符串和 is_error 布尔值。");
      }
      return new ToolResult(text, failed, Map.of("truncated", result.truncated()));
    } catch (Exception error) {
      return ToolResult.error("Skill 工具执行失败：输出或运行环境无效。");
    }
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @Override
  public boolean isDestructive() {
    return true;
  }

  @Override
  public boolean isConcurrencySafe(Map<String, Object> input) {
    return false;
  }

  @Override
  public boolean isSkillTool() {
    return true;
  }

  @Override
  public String validateInput(Map<String, Object> input) {
    return validateValue(input, spec.inputSchema(), "input");
  }

  private String validateExecutable() {
    try {
      Path root = skillDirectory.toRealPath();
      Path executable = spec.executable();
      if (!executable.startsWith(skillDirectory)
          || !Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
          || !executable.toRealPath().startsWith(root)
          || !Files.isExecutable(executable)) {
        return "Skill 工具脚本无效或越出 Skill 目录。";
      }
      try (var reader = Files.newBufferedReader(executable, StandardCharsets.UTF_8)) {
        String first = reader.readLine();
        if (first == null || !first.startsWith("#!") || first.substring(2).isBlank()) {
          return "Skill 工具脚本缺少有效 shebang。";
        }
      }
      return null;
    } catch (Exception error) {
      return "Skill 工具脚本不可访问。";
    }
  }

  private static Path realDirectory(Path directory) {
    try {
      return directory.toRealPath();
    } catch (Exception error) {
      return directory.toAbsolutePath().normalize();
    }
  }

  private static String validateValue(Object value, Map<String, Object> schema, String path) {
    Object allowed = schema.get("enum");
    if (allowed instanceof List<?> values && !values.contains(value)) {
      return path + " 必须是声明的枚举值之一。";
    }
    String type = schema.get("type") instanceof String text ? text : null;
    if (type != null && !matchesType(value, type)) return path + " 必须是 " + type + "。";
    if (value instanceof Map<?, ?> object) {
      Object required = schema.get("required");
      if (required instanceof List<?> fields) {
        for (Object field : fields) {
          if (field instanceof String name && !object.containsKey(name)) {
            return path + " 缺少必填字段 " + name + "。";
          }
        }
      }
      Map<?, ?> properties = schema.get("properties") instanceof Map<?, ?> map ? map : Map.of();
      if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
        for (Object key : object.keySet()) {
          if (!properties.containsKey(key)) return path + " 包含未声明字段 " + key + "。";
        }
      }
      for (var entry : object.entrySet()) {
        Object childSchema = properties.get(entry.getKey());
        if (childSchema instanceof Map<?, ?> raw) {
          @SuppressWarnings("unchecked")
          String error =
              validateValue(
                  entry.getValue(), (Map<String, Object>) raw, path + "." + entry.getKey());
          if (error != null) return error;
        }
      }
    }
    if (value instanceof List<?> list && schema.get("items") instanceof Map<?, ?> raw) {
      @SuppressWarnings("unchecked")
      Map<String, Object> itemSchema = (Map<String, Object>) raw;
      for (int i = 0; i < list.size(); i++) {
        String error = validateValue(list.get(i), itemSchema, path + "[" + i + "]");
        if (error != null) return error;
      }
    }
    return null;
  }

  private static boolean matchesType(Object value, String type) {
    return switch (type) {
      case "object" -> value instanceof Map<?, ?>;
      case "array" -> value instanceof List<?>;
      case "string" -> value instanceof String;
      case "integer" ->
          value instanceof Byte
              || value instanceof Short
              || value instanceof Integer
              || value instanceof Long;
      case "number" -> value instanceof Number;
      case "boolean" -> value instanceof Boolean;
      case "null" -> value == null;
      default -> false;
    };
  }
}
