package com.mewcode.permission;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** 权限规则和 Hook 条件共用的字符串匹配器。 */
public sealed interface RuleMatcher
    permits RuleMatcher.Exact, RuleMatcher.Glob, RuleMatcher.Regex, RuleMatcher.Not {
  boolean matches(String value);

  static RuleMatcher glob(String value) {
    return new Glob(value);
  }

  static RuleMatcher parse(Map<?, ?> definition) {
    if (definition == null)
      throw new IllegalArgumentException("match definition must be an object");
    Object type = definition.get("type");
    if (!(type instanceof String typeName) || typeName.isBlank()) {
      throw new IllegalArgumentException("match type must be a non-empty string");
    }
    return switch (typeName) {
      case "exact", "glob", "regex" -> parseValue(typeName, definition.get("value"));
      case "not" -> {
        Object inner = definition.get("inner");
        if (!(inner instanceof Map<?, ?> innerDefinition)) {
          throw new IllegalArgumentException("not match requires an inner object");
        }
        yield new Not(parse(innerDefinition));
      }
      default -> throw new IllegalArgumentException("unknown match type");
    };
  }

  private static RuleMatcher parseValue(String type, Object value) {
    if (!(value instanceof String text)) {
      throw new IllegalArgumentException("match value must be a string");
    }
    return switch (type) {
      case "exact" -> new Exact(text);
      case "glob" -> new Glob(text);
      case "regex" -> {
        try {
          yield new Regex(Pattern.compile(text));
        } catch (RuntimeException error) {
          throw new IllegalArgumentException("invalid regex", error);
        }
      }
      default -> throw new IllegalArgumentException("unknown match type");
    };
  }

  record Exact(String value) implements RuleMatcher {
    public Exact {
      Objects.requireNonNull(value, "value");
    }

    @Override
    public boolean matches(String candidate) {
      return candidate != null && value.equals(candidate);
    }
  }

  /** 将旧权限语义固定为整串匹配，* 和 ? 都可以跨路径及换行。 */
  final class Glob implements RuleMatcher {
    private final String value;
    private final Pattern pattern;

    public Glob(String value) {
      this.value = Objects.requireNonNull(value, "value");
      this.pattern = Pattern.compile(toRegex(value), Pattern.DOTALL);
    }

    public String value() {
      return value;
    }

    @Override
    public boolean matches(String candidate) {
      return candidate != null && pattern.matcher(candidate).matches();
    }

    private static String toRegex(String glob) {
      var regex = new StringBuilder("^");
      for (int index = 0; index < glob.length(); index++) {
        char character = glob.charAt(index);
        if (character == '*') {
          regex.append(".*");
        } else if (character == '?') {
          regex.append('.');
        } else {
          if ("\\.^$|()[]{}+".indexOf(character) >= 0) regex.append('\\');
          regex.append(character);
        }
      }
      return regex.append('$').toString();
    }
  }

  record Regex(Pattern pattern) implements RuleMatcher {
    public Regex {
      Objects.requireNonNull(pattern, "pattern");
    }

    @Override
    public boolean matches(String value) {
      return value != null && pattern.matcher(value).find();
    }
  }

  final class Not implements RuleMatcher {
    private final RuleMatcher inner;

    public Not(RuleMatcher inner) {
      this.inner = Objects.requireNonNull(inner, "inner");
    }

    public RuleMatcher inner() {
      return inner;
    }

    @Override
    public boolean matches(String value) {
      return value != null && !inner.matches(value);
    }
  }
}
