package com.mewcode.hook;

import com.mewcode.agent.CancellationToken;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Hook 触发时的不可变事件快照。 */
public record HookInvocation(
    HookEvent event,
    Map<String, Object> payload,
    HookSessionState state,
    CancellationToken cancellation) {
  public HookInvocation {
    event = Objects.requireNonNull(event, "event");
    state = Objects.requireNonNull(state, "state");
    cancellation = Objects.requireNonNull(cancellation, "cancellation");
    if (payload == null) throw new IllegalArgumentException("payload must be an object");
    var copy = copyMap(payload);
    Object eventName = copy.putIfAbsent("event", event.configName());
    if (eventName != null && !event.configName().equals(eventName)) {
      throw new IllegalArgumentException("payload event does not match invocation event");
    }
    Object cwd = copy.get("cwd");
    if (!(cwd instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("payload cwd must be a non-empty string");
    }
    payload = Collections.unmodifiableMap(copy);
  }

  private static LinkedHashMap<String, Object> copyMap(Map<?, ?> source) {
    var copy = new LinkedHashMap<String, Object>();
    for (Map.Entry<?, ?> entry : source.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalArgumentException("payload object keys must be strings");
      }
      copy.put(key, copyValue(entry.getValue()));
    }
    return copy;
  }

  private static Object copyValue(Object value) {
    if (value instanceof Map<?, ?> map) return Collections.unmodifiableMap(copyMap(map));
    if (value instanceof List<?> list) {
      var copy = new ArrayList<>(list.size());
      list.forEach(item -> copy.add(copyValue(item)));
      return List.copyOf(copy);
    }
    return value;
  }
}
