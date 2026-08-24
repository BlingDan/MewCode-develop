package com.mewcode.tool.support;

import java.util.Map;

/** 工具参数的基础类型读取，避免校验失败时抛出 ClassCastException。 */
public final class ToolInput {

    private ToolInput() {
    }

    public static String requiredString(Map<String, Object> input, String name) {
        Object value = input == null ? null : input.get(name);
        return value instanceof String text ? text : null;
    }

    public static Integer integer(Map<String, Object> input, String name) {
        Object value = input == null ? null : input.get(name);
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static String requireString(Map<String, Object> input, String name, String example) {
        String value = requiredString(input, name);
        if (value == null || value.isBlank()) {
            return "参数 " + name + " 必须是非空字符串。" + example;
        }
        return null;
    }
}
