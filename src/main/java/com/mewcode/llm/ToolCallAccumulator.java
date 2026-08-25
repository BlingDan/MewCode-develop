package com.mewcode.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 按工具调用 ID 独立拼接并解析流式 JSON 参数。 */
public final class ToolCallAccumulator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /** 为一个工具调用创建独立参数缓冲区；同 ID 的后续片段复用首次记录的名称。 */
    public synchronized void start(String id, String name) {
        if (id == null || id.isBlank()) return;
        entries.putIfAbsent(id, new Entry(name == null ? "" : name));
    }

    /** 判断 provider 是否已经发过指定调用 ID 的开始片段。 */
    public synchronized boolean has(String id) {
        return entries.containsKey(id);
    }

    /** 追加一个参数 JSON 片段，不在片段级别尝试解析。 */
    public synchronized void append(String id, String partialJson) {
        if (id == null || partialJson == null) return;
        Entry entry = entries.get(id);
        if (entry != null) entry.json().append(partialJson);
    }

    /** 结束并解析一个调用，解析失败会转换为可回写给模型的错误事件。 */
    public synchronized StreamEvent finish(String id) {
        Entry entry = entries.remove(id);
        if (entry == null) {
            return new StreamEvent.ToolCallParseError(id, "", "找不到对应的工具调用参数缓冲区。");
        }
        return parse(id, entry);
    }

    /** 收尾所有尚未收到结束片段的调用，保持它们首次出现的顺序。 */
    public synchronized List<StreamEvent> finishAll() {
        var events = new ArrayList<StreamEvent>();
        for (String id : new ArrayList<>(entries.keySet())) {
            events.add(finish(id));
        }
        return events;
    }

    private static StreamEvent parse(String id, Entry entry) {
        String json = entry.json().toString().trim();
        if (json.isEmpty()) return new StreamEvent.ToolCallComplete(id, entry.name(), Map.of());
        try {
            Map<String, Object> arguments = MAPPER.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            return new StreamEvent.ToolCallComplete(id, entry.name(), arguments);
        } catch (Exception error) {
            return new StreamEvent.ToolCallParseError(id, entry.name(),
                    "工具参数 JSON 解析失败：" + error.getMessage()
                            + "。请重新生成合法的 JSON 参数。");
        }
    }

    private record Entry(String name, StringBuilder json) {
        private Entry(String name) {
            this(name, new StringBuilder());
        }
    }
}
