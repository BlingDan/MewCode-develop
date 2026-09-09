package com.mewcode.hook;

import com.mewcode.permission.RuleMatcher;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 一条已经完成校验的 Hook 规则。 */
public record HookRule(
    String name,
    HookEvent event,
    Optional<HookCondition> condition,
    HookAction action,
    boolean onlyOnce,
    boolean async,
    Duration timeout,
    Path source) {
  public HookRule {
    if (name == null || name.isBlank())
      throw new IllegalArgumentException("name must not be blank");
    Objects.requireNonNull(event, "event");
    condition = Objects.requireNonNull(condition, "condition");
    Objects.requireNonNull(action, "action");
    timeout = Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    source = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
  }

  /** 条件组只允许一种组合方式，字段值必须通过点路径取得。 */
  public record HookCondition(Combination combination, List<FieldMatch> fields) {
    public HookCondition {
      combination = Objects.requireNonNull(combination, "combination");
      if (fields == null || fields.isEmpty()) {
        throw new IllegalArgumentException("condition fields must not be empty");
      }
      fields = List.copyOf(fields);
    }

    public boolean matches(Map<String, Object> payload) {
      Objects.requireNonNull(payload, "payload");
      return switch (combination) {
        case ALL_OF -> fields.stream().allMatch(field -> field.matches(payload));
        case ANY_OF -> fields.stream().anyMatch(field -> field.matches(payload));
      };
    }

    public enum Combination {
      ALL_OF,
      ANY_OF
    }

    public record FieldMatch(String field, RuleMatcher matcher) {
      public FieldMatch {
        if (field == null || field.isBlank() || field.startsWith(".") || field.endsWith(".")) {
          throw new IllegalArgumentException("field must be a non-empty dotted path");
        }
        if (field.contains("..")) throw new IllegalArgumentException("field path is invalid");
        matcher = Objects.requireNonNull(matcher, "matcher");
      }

      private boolean matches(Map<String, Object> payload) {
        Object value = payload;
        for (String part : field.split("\\.")) {
          if (!(value instanceof Map<?, ?> map) || !map.containsKey(part)) return false;
          value = map.get(part);
        }
        if (value == null || value instanceof Map<?, ?> || value instanceof List<?>) return false;
        if (!(value instanceof String || value instanceof Boolean || value instanceof Number)) {
          return false;
        }
        return matcher.matches(String.valueOf(value));
      }
    }
  }
}
