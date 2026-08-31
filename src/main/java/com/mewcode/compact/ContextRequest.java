package com.mewcode.compact;

import com.mewcode.conversation.Message;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 一次待发送请求的上下文快照，不包含会话 history。 */
public record ContextRequest(
        List<String> systemSegments,
        List<Map<String, Object>> tools,
        Optional<Message> reminder) {

    public ContextRequest {
        systemSegments = systemSegments == null
                ? List.of()
                : systemSegments.stream()
                        .map(value -> Objects.requireNonNullElse(value, ""))
                        .toList();
        tools = copyTools(tools);
        reminder = reminder == null ? Optional.empty() : reminder;
    }

    private static List<Map<String, Object>> copyTools(
            List<Map<String, Object>> values) {
        if (values == null) return List.of();
        var result = new ArrayList<Map<String, Object>>(values.size());
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
            return list.stream().map(ContextRequest::immutableCopy).toList();
        }
        return value;
    }
}
