package com.mewcode.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 解析带 YAML frontmatter 的 Skill Markdown 及其可选脚本工具声明。 */
public final class SkillParser {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> META_FIELDS =
      Set.of("name", "description", "tools", "mode", "context", "context_count", "model");
  private static final Set<String> SCHEMA_FIELDS =
      Set.of("type", "properties", "required", "items", "enum", "additionalProperties");
  private static final Set<String> SCHEMA_TYPES =
      Set.of("object", "array", "string", "integer", "number", "boolean", "null");

  private SkillParser() {}

  public static SkillDefinition parse(Path entry, SkillDefinition.Source source) {
    Path absolute = entry.toAbsolutePath().normalize();
    try {
      String text = Files.readString(absolute, StandardCharsets.UTF_8).replace("\r\n", "\n");
      Frontmatter split = split(text);
      Map<String, Object> yaml = yaml(split.yaml());
      rejectUnknownFields(yaml);
      SkillDefinition.Meta meta = bindMeta(yaml);
      Path directory = absolute.getParent();
      List<SkillDefinition.ToolSpec> tools =
          "SKILL.md".equals(absolute.getFileName().toString()) ? parseTools(directory) : List.of();
      return new SkillDefinition(meta, split.body(), source, absolute, directory, tools);
    } catch (ParseException error) {
      throw error.withPath(absolute);
    } catch (Exception error) {
      throw new ParseException(absolute + "：" + safeReason(error), error);
    }
  }

  /** 解析 classpath 内置单文件，不尝试读取目录工具。 */
  static SkillDefinition parseBuiltin(String resourceName, String text) {
    Path marker = Path.of("/classpath/skills/builtin", resourceName).toAbsolutePath();
    try {
      Frontmatter split = split(text.replace("\r\n", "\n"));
      Map<String, Object> yaml = yaml(split.yaml());
      rejectUnknownFields(yaml);
      return new SkillDefinition(
          bindMeta(yaml),
          split.body(),
          SkillDefinition.Source.BUILTIN,
          marker,
          marker.getParent(),
          List.of());
    } catch (ParseException error) {
      throw error.withPath(marker);
    } catch (Exception error) {
      throw new ParseException(marker + "：" + safeReason(error), error);
    }
  }

  private static Frontmatter split(String text) {
    if (!text.startsWith("---\n")) throw new ParseException("缺少文件开头的 YAML frontmatter");
    int end = text.indexOf("\n---\n", 4);
    if (end < 0) throw new ParseException("YAML frontmatter 缺少结束分隔符");
    String body = text.substring(end + 5);
    if (body.isBlank()) throw new ParseException("Skill 正文不能为空");
    return new Frontmatter(text.substring(4, end), body);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> yaml(String source) {
    LoaderOptions options = new LoaderOptions();
    options.setMaxAliasesForCollections(20);
    options.setNestingDepthLimit(20);
    Object loaded = new Yaml(new SafeConstructor(options)).load(source);
    if (!(loaded instanceof Map<?, ?> map)) throw new ParseException("frontmatter 必须是对象");
    return (Map<String, Object>) map;
  }

  private static void rejectUnknownFields(Map<String, Object> yaml) {
    for (String field : yaml.keySet()) {
      if (!META_FIELDS.contains(field)) throw new ParseException("未知 frontmatter 字段：" + field);
    }
  }

  private static SkillDefinition.Meta bindMeta(Map<String, Object> yaml) {
    String name = string(yaml, "name", true);
    String description = string(yaml, "description", true);
    List<String> tools = stringList(yaml.get("tools"), "tools");
    SkillDefinition.Mode mode =
        enumValue(
            yaml.get("mode"), SkillDefinition.Mode.class, SkillDefinition.Mode.SHARED, "mode");
    SkillDefinition.ForkContext context =
        enumValue(
            yaml.get("context"),
            SkillDefinition.ForkContext.class,
            SkillDefinition.ForkContext.NONE,
            "context");
    int count = integer(yaml.get("context_count"), 3, "context_count");
    String model = string(yaml, "model", false);
    try {
      return new SkillDefinition.Meta(name, description, tools, mode, context, count, model);
    } catch (IllegalArgumentException error) {
      throw new ParseException(error.getMessage());
    }
  }

  private static List<SkillDefinition.ToolSpec> parseTools(Path directory) throws IOException {
    Path manifest = directory.resolve("tool.json");
    if (!Files.exists(manifest)) return List.of();
    Map<String, Object> root =
        JSON.readValue(
            Files.readString(manifest, StandardCharsets.UTF_8), new TypeReference<>() {});
    Object value = root.get("tools");
    if (!(value instanceof List<?> entries)) throw new ParseException("tool.json 的 tools 必须是数组");
    var result = new ArrayList<SkillDefinition.ToolSpec>();
    for (Object item : entries) {
      if (!(item instanceof Map<?, ?> raw)) throw new ParseException("tool.json 工具项必须是对象");
      @SuppressWarnings("unchecked")
      Map<String, Object> tool = (Map<String, Object>) raw;
      String name = requiredString(tool.get("name"), "tool.name");
      String description = requiredString(tool.get("description"), "tool.description");
      if (!(tool.get("input_schema") instanceof Map<?, ?> rawSchema)) {
        throw new ParseException("工具 " + name + " 的 input_schema 必须是对象");
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> schema = (Map<String, Object>) rawSchema;
      validateSchema(schema, "工具 " + name + " 的 input_schema", true);
      String script = requiredString(tool.get("script"), "tool.script");
      result.add(
          new SkillDefinition.ToolSpec(
              name, description, Map.copyOf(schema), validateScript(directory, script, name)));
    }
    return List.copyOf(result);
  }

  private static void validateSchema(Map<String, Object> schema, String location, boolean root) {
    for (String field : schema.keySet()) {
      if (!SCHEMA_FIELDS.contains(field)) throw new ParseException(location + " 含不支持字段 " + field);
    }
    Object rawType = schema.get("type");
    if (!(rawType instanceof String type) || !SCHEMA_TYPES.contains(type)) {
      throw new ParseException(location + " 的 type 无效");
    }
    if (root && !"object".equals(type)) throw new ParseException(location + " 顶层 type 必须是 object");

    Map<?, ?> properties = Map.of();
    if (schema.containsKey("properties")) {
      if (!(schema.get("properties") instanceof Map<?, ?> map)) {
        throw new ParseException(location + " 的 properties 必须是对象");
      }
      properties = map;
      for (var entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String name)
            || !(entry.getValue() instanceof Map<?, ?> child)) {
          throw new ParseException(location + " 的 properties 项无效");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> childSchema = (Map<String, Object>) child;
        validateSchema(childSchema, location + ".properties." + name, false);
      }
    }
    if (schema.containsKey("required")) {
      if (!(schema.get("required") instanceof List<?> required)) {
        throw new ParseException(location + " 的 required 必须是字符串数组");
      }
      for (Object field : required) {
        if (!(field instanceof String name) || !properties.containsKey(name)) {
          throw new ParseException(location + " 的 required 含未声明字段");
        }
      }
    }
    if (schema.containsKey("items")) {
      if (!(schema.get("items") instanceof Map<?, ?> rawItems)) {
        throw new ParseException(location + " 的 items 必须是对象");
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> items = (Map<String, Object>) rawItems;
      validateSchema(items, location + ".items", false);
    }
    if (schema.containsKey("enum") && !(schema.get("enum") instanceof List<?>)) {
      throw new ParseException(location + " 的 enum 必须是数组");
    }
    if (schema.containsKey("additionalProperties")
        && !(schema.get("additionalProperties") instanceof Boolean)) {
      throw new ParseException(location + " 的 additionalProperties 必须是布尔值");
    }
  }

  private static Path validateScript(Path directory, String script, String toolName)
      throws IOException {
    Path relative = Path.of(script);
    if (relative.isAbsolute()) throw new ParseException("工具 " + toolName + " 的脚本必须使用相对路径");
    Path root = directory.toRealPath();
    Path executable = directory.resolve(relative).normalize();
    if (!executable.startsWith(directory.toAbsolutePath().normalize())) {
      throw new ParseException("工具 " + toolName + " 的脚本越出 Skill 目录");
    }
    if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) {
      throw new ParseException("工具 " + toolName + " 的脚本不是普通文件");
    }
    Path real = executable.toRealPath();
    if (!real.startsWith(root)) throw new ParseException("工具 " + toolName + " 的脚本越出 Skill 目录");
    if (!Files.isExecutable(real)) throw new ParseException("工具 " + toolName + " 的脚本不可执行");
    String firstLine;
    try (var reader = Files.newBufferedReader(real, StandardCharsets.UTF_8)) {
      firstLine = reader.readLine();
    }
    if (firstLine == null || !firstLine.startsWith("#!") || firstLine.substring(2).isBlank()) {
      throw new ParseException("工具 " + toolName + " 的脚本缺少有效 shebang");
    }
    return real;
  }

  private static String string(Map<String, Object> map, String key, boolean required) {
    Object value = map.get(key);
    if (value == null && !required) return null;
    return requiredString(value, key);
  }

  private static String requiredString(Object value, String field) {
    if (!(value instanceof String text) || text.isBlank()) {
      throw new ParseException(field + " 必须是非空字符串");
    }
    return text;
  }

  private static List<String> stringList(Object value, String field) {
    if (value == null) return List.of();
    if (!(value instanceof List<?> list)) throw new ParseException(field + " 必须是字符串数组");
    var result = new ArrayList<String>();
    for (Object item : list) result.add(requiredString(item, field));
    return List.copyOf(result);
  }

  private static int integer(Object value, int defaultValue, String field) {
    if (value == null) return defaultValue;
    if (!(value instanceof Number number) || number.intValue() != number.doubleValue()) {
      throw new ParseException(field + " 必须是整数");
    }
    return number.intValue();
  }

  private static <T extends Enum<T>> T enumValue(
      Object value, Class<T> type, T defaultValue, String field) {
    if (value == null) return defaultValue;
    String text = requiredString(value, field).toUpperCase(Locale.ROOT);
    try {
      return Enum.valueOf(type, text);
    } catch (IllegalArgumentException error) {
      throw new ParseException(field + " 的值无效：" + value);
    }
  }

  private static String safeReason(Throwable error) {
    if (error instanceof ParseException && error.getMessage() != null) return error.getMessage();
    if (error instanceof org.yaml.snakeyaml.error.YAMLException) return "YAML 格式无效";
    if (error instanceof com.fasterxml.jackson.core.JsonProcessingException) {
      return "tool.json 格式无效";
    }
    if (error instanceof IOException) return "Skill 文件读取失败";
    return error.getClass().getSimpleName();
  }

  private record Frontmatter(String yaml, String body) {}

  public static final class ParseException extends RuntimeException {
    public ParseException(String message) {
      super(message);
    }

    public ParseException(String message, Throwable cause) {
      super(message, cause);
    }

    private ParseException withPath(Path path) {
      if (getMessage() != null && getMessage().startsWith(path.toString())) return this;
      return new ParseException(path + "：" + getMessage(), this);
    }
  }
}
