package com.mewcode.llm;

import com.mewcode.conversation.Message;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 一次 provider 请求的不可变提示词分层快照。 */
public record PromptRequest(
    List<String> systemSegments,
    List<Map<String, Object>> tools,
    List<Message> history,
    Optional<Message> reminder) {

  public PromptRequest {
    systemSegments = copyStrings(systemSegments);
    tools = copyTools(tools);
    history = history == null ? List.of() : List.copyOf(history);
    reminder = reminder == null ? Optional.empty() : reminder;
  }

  /** 为旧的字符串式客户端提供兼容的 system 文本。 */
  public String flattenedSystemPrompt() {
    return String.join("\n\n", systemSegments);
  }

  private static List<String> copyStrings(List<String> values) {
    if (values == null) return List.of();
    return values.stream().map(value -> Objects.requireNonNullElse(value, "")).toList();
  }

  private static List<Map<String, Object>> copyTools(List<Map<String, Object>> values) {
    if (values == null) return List.of();
    var result = new ArrayList<Map<String, Object>>();
    for (Map<String, Object> value : values) {
      Objects.requireNonNull(value, "tool definition");
      var copy = new LinkedHashMap<String, Object>();
      for (Map.Entry<String, Object> entry : value.entrySet()) {
        copy.put(entry.getKey(), immutableCopy(entry.getValue()));
      }
      result.add(Collections.unmodifiableMap(copy));
    }
    return List.copyOf(result);
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
      return list.stream().map(PromptRequest::immutableCopy).toList();
    }
    return value;
  }
}
