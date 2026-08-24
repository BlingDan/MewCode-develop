package com.mewcode.tui;

import com.mewcode.tool.ToolResult;
import com.mewcode.tui.tea.Program;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ToolDisplayFormatterTest {

    @Test
    void formatsBuiltInToolNamesAndKeyArguments() {
        assertEquals("● Read(/project/src/Main.java)",
                ToolDisplayFormatter.invocation("ReadFile",
                        Map.of("path", "/project/src/Main.java", "offset", 1), 120));
        assertEquals("● Write(/project/note.txt)",
                ToolDisplayFormatter.invocation("WriteFile",
                        Map.of("path", "/project/note.txt", "content", "hello"), 120));
        assertEquals("● Edit(/project/note.txt)",
                ToolDisplayFormatter.invocation("EditFile",
                        Map.of("path", "/project/note.txt", "old_string", "a"), 120));
        assertEquals("● Bash(pwd)",
                ToolDisplayFormatter.invocation("Bash", Map.of("command", "pwd"), 120));
        assertEquals("● Glob(**/*.java)",
                ToolDisplayFormatter.invocation("Glob", Map.of("pattern", "**/*.java"), 120));
        assertEquals("● Grep(todo, include=*.java)",
                ToolDisplayFormatter.invocation("Grep",
                        Map.of("pattern", "todo", "include", "*.java"), 120));
    }

    @Test
    void formatsUnknownArgumentsInStableKeyOrder() {
        var arguments = new LinkedHashMap<String, Object>();
        arguments.put("z", "last");
        arguments.put("a", "first");

        assertEquals("● Custom(a=first, z=last)",
                ToolDisplayFormatter.invocation("Custom", arguments, 120));
    }

    @Test
    void fallsBackWhenArgumentFormattingThrows() {
        Map<String, Object> brokenArguments = new AbstractMap<>() {
            @Override
            public Object get(Object key) {
                throw new IllegalStateException("broken argument map");
            }

            @Override
            public Set<Entry<String, Object>> entrySet() {
                return Set.of();
            }
        };

        assertEquals("● UnknownTool(参数格式化失败)",
                ToolDisplayFormatter.invocation("ReadFile", brokenArguments, 120));
    }

    @Test
    void removesControlSequencesAndTruncatesByDisplayWidth() {
        String command = "printf '\u001b[31m你好\u001b[0m\\nsecond'";
        String line = ToolDisplayFormatter.invocation("Bash", Map.of("command", command), 24);

        assertFalse(line.contains("\u001b"));
        assertFalse(line.contains("\n"));
        assertTrue(line.endsWith(ToolDisplayFormatter.TRUNCATION_MARKER));
        assertTrue(Program.displayWidth(line) <= 24);
    }

    @Test
    void summarizesSuccessErrorEmptyAndLongResultsWithoutMetadata() {
        var longResult = new ToolResult("first line\nsecond line", false, Map.of("secret", "hidden"));
        var summary = ToolDisplayFormatter.result(longResult, 120);
        assertEquals("⎿ first line … [truncated]", summary.text());
        assertFalse(summary.isError());
        assertFalse(summary.text().contains("hidden"));
        assertEquals("first line\nsecond line", longResult.content());

        var error = ToolDisplayFormatter.result(ToolResult.error("permission denied"), 120);
        assertEquals("⎿ Error: permission denied", error.text());
        assertTrue(error.isError());

        assertEquals("⎿ Completed",
                ToolDisplayFormatter.result(ToolResult.success("\n"), 120).text());
    }

    @Test
    void truncatesLongResultAndRemovesAnsi() {
        String content = "\u001b[2K\u001b[31m" + "x".repeat(200) + "\u001b[0m";
        var summary = ToolDisplayFormatter.result(ToolResult.success(content), 32);

        assertFalse(summary.text().contains("\u001b"));
        assertTrue(summary.text().endsWith(ToolDisplayFormatter.TRUNCATION_MARKER));
        assertTrue(Program.displayWidth(summary.text()) <= 32);
    }
}
