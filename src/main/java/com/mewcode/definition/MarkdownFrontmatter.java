package com.mewcode.definition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 读取带 YAML frontmatter 的 Markdown 文档；业务字段校验留给调用方。 */
public final class MarkdownFrontmatter {

  private MarkdownFrontmatter() {}

  public static MarkdownDocument read(Path path) throws IOException {
    return parse(Files.readString(path, StandardCharsets.UTF_8));
  }

  public static MarkdownDocument parse(String source) {
    String text = normalize(source);
    if (!text.startsWith("---\n")) {
      throw new ParseException("缺少文件开头的 YAML frontmatter");
    }
    int end = text.indexOf("\n---\n", 4);
    int separatorLength = 5;
    if (end < 0 && text.endsWith("\n---")) {
      end = text.length() - 4;
      separatorLength = 4;
    }
    if (end < 0) {
      throw new ParseException("YAML frontmatter 缺少结束分隔符");
    }

    String body = text.substring(end + separatorLength);
    if (body.isBlank()) throw new ParseException("Markdown 正文不能为空");

    LoaderOptions options = new LoaderOptions();
    options.setMaxAliasesForCollections(20);
    options.setNestingDepthLimit(20);
    Object loaded;
    try {
      loaded = new Yaml(new SafeConstructor(options)).load(text.substring(4, end));
    } catch (RuntimeException error) {
      throw new ParseException("YAML 格式无效", error);
    }
    if (!(loaded instanceof Map<?, ?> map)) {
      throw new ParseException("frontmatter 必须是对象");
    }
    var result = new LinkedHashMap<String, Object>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key) || key.isBlank()) {
        throw new ParseException("frontmatter 字段名必须是非空字符串");
      }
      result.put(key, immutableCopy(entry.getValue()));
    }
    return new MarkdownDocument(Collections.unmodifiableMap(result), body);
  }

  private static String normalize(String source) {
    return (source == null ? "" : source).replace("\r\n", "\n").replace('\r', '\n');
  }

  private static Object immutableCopy(Object value) {
    if (value instanceof Map<?, ?> map) {
      var copy = new LinkedHashMap<Object, Object>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        copy.put(entry.getKey(), immutableCopy(entry.getValue()));
      }
      return Collections.unmodifiableMap(copy);
    }
    if (value instanceof List<?> list) {
      var copy = new ArrayList<Object>(list.size());
      for (Object item : list) copy.add(immutableCopy(item));
      return List.copyOf(copy);
    }
    return value;
  }

  public record MarkdownDocument(Map<String, Object> frontmatter, String body) {
    public MarkdownDocument {
      frontmatter = Map.copyOf(frontmatter == null ? Map.of() : frontmatter);
      body = body == null ? "" : body;
    }
  }

  public static final class ParseException extends RuntimeException {
    public ParseException(String message) {
      super(message);
    }

    public ParseException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
