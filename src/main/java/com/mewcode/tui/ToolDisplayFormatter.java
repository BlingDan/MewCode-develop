package com.mewcode.tui;

import com.mewcode.tool.ToolResult;
import com.mewcode.tui.tea.Program;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 生成工具调用行和结果摘要，不参与工具执行或模型协议转换。 */
final class ToolDisplayFormatter {

    static final int DEFAULT_MAX_COLUMNS = 120;
    static final String TRUNCATION_MARKER = "… [truncated]";
    private static final Pattern ANSI_ESCAPE = Pattern.compile(
            "\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))");

    private ToolDisplayFormatter() {
    }

    static String invocation(String toolName,
                             Map<String, Object> arguments,
                             int maxColumns) {
        try {
            String safeName = safeText(toolName, "UnknownTool");
            String label = displayLabel(safeName);
            String argument = keyArgument(safeName, arguments);
            return truncate("● " + label + "(" + argument + ")", maxColumns);
        } catch (RuntimeException error) {
            return truncate("● UnknownTool(参数格式化失败)", maxColumns);
        }
    }

    static ResultSummary result(ToolResult toolResult, int maxColumns) {
        boolean isError = toolResult != null && toolResult.isError();
        String content = toolResult == null ? "" : toolResult.content();
        String summary = summarizeContent(content, isError);
        return new ResultSummary(truncate("⎿ " + summary, maxColumns), isError);
    }

    private static String displayLabel(String toolName) {
        return switch (toolName.toLowerCase(Locale.ROOT)) {
            case "readfile" -> "Read";
            case "writefile" -> "Write";
            case "editfile" -> "Edit";
            case "bashtool" -> "Bash";
            default -> toolName;
        };
    }

    private static String keyArgument(String toolName, Map<String, Object> arguments) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        String normalized = toolName.toLowerCase(Locale.ROOT);
        if (normalized.equals("readfile")
                || normalized.equals("writefile")
                || normalized.equals("editfile")) {
            return value(safeArguments.get("path"));
        }
        if (normalized.equals("bash") || normalized.equals("bashtool")) {
            return value(safeArguments.get("command"));
        }
        if (normalized.equals("glob")) {
            return value(safeArguments.get("pattern"));
        }
        if (normalized.equals("grep")) {
            String pattern = value(safeArguments.get("pattern"));
            String include = value(safeArguments.get("include"));
            return include.isBlank() ? pattern : pattern + ", include=" + include;
        }
        if (safeArguments.isEmpty()) return "";
        return safeArguments.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.nullsFirst(String::compareTo)))
                .map(entry -> safeText(entry.getKey(), "key") + "=" + value(entry.getValue()))
                .collect(Collectors.joining(", "));
    }

    private static String value(Object value) {
        if (value == null) return "";
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(entry -> String.valueOf(entry.getKey()) + "=" + value(entry.getValue()))
                    .sorted()
                    .collect(Collectors.joining(", ", "{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ToolDisplayFormatter::value)
                    .collect(Collectors.joining(", ", "[", "]"));
        }
        return safeText(Objects.toString(value), "");
    }

    private static String summarizeContent(String content, boolean isError) {
        String safe = safeContent(content);
        String[] lines = safe.split("\\R", -1);
        String first = "";
        int meaningfulLines = 0;
        for (String line : lines) {
            String candidate = line.trim();
            if (candidate.isEmpty() || isWrapperLine(candidate)) continue;
            if (first.isEmpty()) first = candidate;
            meaningfulLines++;
        }

        if (first.isEmpty()) return isError ? "Error: tool failed" : "Completed";
        if (meaningfulLines > 1 || lines.length > 1) {
            return first + " " + TRUNCATION_MARKER;
        }
        return isError ? "Error: " + first : first;
    }

    private static boolean isWrapperLine(String line) {
        return line.equals("<output>") || line.equals("</output>")
                || line.equals("<exit_code>") || line.equals("</exit_code>")
                || line.matches("<exit_code>[^<]*</exit_code>");
    }

    private static String safeText(String value, String fallback) {
        if (value == null) return fallback;
        String withoutAnsi = ANSI_ESCAPE.matcher(value).replaceAll("");
        var result = new StringBuilder(withoutAnsi.length());
        for (int i = 0; i < withoutAnsi.length(); i++) {
            char character = withoutAnsi.charAt(i);
            if (character == '\r' || character == '\n' || character == '\t') {
                result.append(' ');
            } else if (character >= 32 && character != 127) {
                result.append(character);
            }
        }
        return result.toString();
    }

    private static String safeContent(String value) {
        if (value == null) return "";
        String withoutAnsi = ANSI_ESCAPE.matcher(value).replaceAll("");
        var result = new StringBuilder(withoutAnsi.length());
        for (int i = 0; i < withoutAnsi.length(); i++) {
            char character = withoutAnsi.charAt(i);
            if (character == '\r' || character == '\n') {
                result.append(character);
            } else if (character == '\t') {
                result.append(' ');
            } else if (character >= 32 && character != 127) {
                result.append(character);
            }
        }
        return result.toString();
    }

    private static String truncate(String value, int maxColumns) {
        int limit = Math.max(maxColumns, 8);
        if (Program.displayWidth(value) <= limit) return value;

        int markerWidth = Program.displayWidth(TRUNCATION_MARKER);
        int budget = Math.max(limit - markerWidth, 1);
        var result = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int width = Program.displayWidth(character);
            if (used + width > budget) break;
            result.append(character);
            used += width;
            offset += Character.charCount(codePoint);
        }
        return result + TRUNCATION_MARKER;
    }

    record ResultSummary(String text, boolean isError) {
    }
}
