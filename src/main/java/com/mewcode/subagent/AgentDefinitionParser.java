package com.mewcode.subagent;

import com.mewcode.definition.MarkdownFrontmatter;
import com.mewcode.definition.MarkdownFrontmatter.MarkdownDocument;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 绑定 Agent Markdown frontmatter；单个定义失败不会由此类影响其他文件。 */
public final class AgentDefinitionParser {

  private static final Set<String> FIELDS =
      Set.of(
          "name", "description", "tools", "disallowedTools", "model", "maxTurns", "permissionMode");

  private AgentDefinitionParser() {}

  public static SubAgentSpec parse(Path entry, SubAgentSpec.Source source, int defaultMaxTurns) {
    Path absolute = entry.toAbsolutePath().normalize();
    try {
      return bind(MarkdownFrontmatter.read(absolute), absolute, source, defaultMaxTurns);
    } catch (ParseException error) {
      throw error.withPath(absolute);
    } catch (IOException error) {
      throw new ParseException(absolute + "：Agent 文件读取失败", error);
    } catch (RuntimeException error) {
      throw new ParseException(absolute + "：" + safeReason(error), error);
    }
  }

  public static SubAgentSpec parse(
      String text, Path sourcePath, SubAgentSpec.Source source, int defaultMaxTurns) {
    Path absolute = sourcePath.toAbsolutePath().normalize();
    try {
      return bind(MarkdownFrontmatter.parse(text), absolute, source, defaultMaxTurns);
    } catch (ParseException error) {
      throw error.withPath(absolute);
    } catch (RuntimeException error) {
      throw new ParseException(absolute + "：" + safeReason(error), error);
    }
  }

  private static SubAgentSpec bind(
      MarkdownDocument document, Path sourcePath, SubAgentSpec.Source source, int defaultMaxTurns) {
    if (defaultMaxTurns <= 0) throw new ParseException("默认 maxTurns 必须是正整数");
    Map<String, Object> metadata = document.frontmatter();
    for (String field : metadata.keySet()) {
      if (!FIELDS.contains(field)) throw new ParseException("未知 frontmatter 字段：" + field);
    }
    String name = requiredString(metadata.get("name"), "name");
    String description = requiredString(metadata.get("description"), "description");
    Set<String> tools =
        metadata.containsKey("tools") ? stringSet(metadata.get("tools"), "tools") : null;
    Set<String> disallowed =
        metadata.containsKey("disallowedTools")
            ? stringSet(metadata.get("disallowedTools"), "disallowedTools")
            : Set.of();
    int maxTurns = integer(metadata.get("maxTurns"), defaultMaxTurns, "maxTurns");
    if (maxTurns <= 0) throw new ParseException("maxTurns 必须是正整数");
    String model =
        metadata.get("model") == null ? "inherit" : requiredString(metadata.get("model"), "model");
    String permission =
        metadata.get("permissionMode") == null
            ? "default"
            : requiredString(metadata.get("permissionMode"), "permissionMode");
    try {
      return new SubAgentSpec(
          name,
          description,
          tools,
          disallowed,
          document.body(),
          maxTurns,
          model,
          parsePermissionMode(permission),
          source,
          sourcePath);
    } catch (IllegalArgumentException error) {
      throw new ParseException(error.getMessage());
    }
  }

  private static SubAgentSpec.PermissionMode parsePermissionMode(String value) {
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "default" -> SubAgentSpec.PermissionMode.DEFAULT;
      case "dontask", "dont_ask", "dont-ask" -> SubAgentSpec.PermissionMode.DONT_ASK;
      default -> throw new ParseException("permissionMode 的值无效：" + value);
    };
  }

  private static Set<String> stringSet(Object value, String field) {
    if (!(value instanceof List<?> list)) throw new ParseException(field + " 必须是字符串数组");
    var result = new LinkedHashSet<String>();
    for (Object item : list) result.add(requiredString(item, field));
    return Set.copyOf(result);
  }

  private static int integer(Object value, int defaultValue, String field) {
    if (value == null) return defaultValue;
    if (!(value instanceof Number number) || number.intValue() != number.doubleValue()) {
      throw new ParseException(field + " 必须是整数");
    }
    return number.intValue();
  }

  private static String requiredString(Object value, String field) {
    if (!(value instanceof String text) || text.isBlank()) {
      throw new ParseException(field + " 必须是非空字符串");
    }
    return text.strip();
  }

  private static String safeReason(Throwable error) {
    if (error.getMessage() != null && !error.getMessage().isBlank()) return error.getMessage();
    return error.getClass().getSimpleName();
  }

  public static final class ParseException extends RuntimeException {
    public ParseException(String message) {
      super(message == null ? "Agent 定义无效" : message);
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
