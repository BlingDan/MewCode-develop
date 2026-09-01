package com.mewcode.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.mewcode.conversation.Message;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.conversation.ToolUseBlock;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void appendsJsonlAndLoadsMessagesAfterLastCompactBoundary() throws Exception {
        Path sessionDir = tempDir.resolve("sessions/20260831-120000-abcd");
        Object store = newStore(sessionDir, "20260831-120000-abcd", "test-model");
        invoke(store, "appendMessages", List.of(new Message("user", "before")));
        invoke(store, "appendCompact");
        invoke(store, "appendMessages", List.of(new Message("assistant", "after")));
        invoke(store, "close");

        String jsonl = Files.readString(sessionDir.resolve("conversation.jsonl"));
        assertTrue(jsonl.contains("\"type\":\"compact\""));
        assertEquals(List.of("after"), loadedTexts(sessionDir));
    }

    @Test
    void skipsMalformedLinesAndDoesNotRestoreOrphanedToolCall() throws Exception {
        Path sessionDir = tempDir.resolve("sessions/20260831-120000-abcd");
        Files.createDirectories(sessionDir);
        Files.writeString(
                sessionDir.resolve("conversation.jsonl"),
                "{\"role\":\"user\",\"content\":\"ok\",\"ts\":1}\n"
                        + "not-json\n"
                        + "{\"role\":\"tool\",\"tool_results\":[{\"id\":\"orphan\",\"content\":\"bad\"}],\"ts\":2}\n"
                        + "{\"role\":\"assistant\",\"content\":\"dangling\",\"tool_calls\":[{\"id\":\"c1\"}],\"ts\":3}\n");

        assertEquals(List.of("ok"), loadedTexts(sessionDir));
    }

    @Test
    void persistsToolFieldsAndScansOnlyValidRecentSessions() throws Exception {
        Path sessionDir = tempDir.resolve("sessions/20260831-120000-abcd");
        Object store = newStore(sessionDir, "20260831-120000-abcd", "test-model");
        invoke(
                store,
                "appendMessages",
                List.of(
                        new Message(
                                "assistant",
                                List.of(
                                        new ToolUseBlock(
                                                "call-1", "ReadFile", Map.of("path", "README.md")))),
                        new Message(
                                "user", List.of(new ToolResultBlock("call-1", "file", true)))));
        invoke(store, "close");

        String jsonl = Files.readString(sessionDir.resolve("conversation.jsonl"));
        assertTrue(jsonl.contains("\"toolUseId\":\"call-1\""));
        assertTrue(jsonl.contains("\"toolName\":\"ReadFile\""));
        assertTrue(jsonl.contains("\"isError\":true"));
        assertEquals(2, loadedTexts(sessionDir).size());

        Path sessions = tempDir.resolve("all-sessions");
        Path old = sessions.resolve("20260831-120000-old1");
        Path recent = sessions.resolve("20260831-120000-new1");
        Files.createDirectories(old);
        Files.createDirectories(recent);
        Files.writeString(
                old.resolve("conversation.jsonl"),
                "{\"role\":\"user\",\"content\":\"old\",\"ts\":"
                        + Instant.now().minus(Duration.ofDays(31)).getEpochSecond()
                        + "}\n");
        Files.writeString(
                recent.resolve("conversation.jsonl"),
                "{\"role\":\"user\",\"content\":\"recent\",\"ts\":"
                        + Instant.now().minus(Duration.ofHours(1)).getEpochSecond()
                        + "}\n");
        Files.writeString(sessions.resolve("keep.txt"), "keep");

        assertEquals(2, HistoryStore.scan(sessions).size());
        HistoryStore.deleteExpired(sessions, Duration.ofDays(30));
        assertTrue(Files.notExists(old));
        assertTrue(Files.exists(recent));
        assertTrue(Files.exists(sessions.resolve("keep.txt")));
    }

    private static Object newStore(Path dir, String id, String model) throws Exception {
        try {
            Class<?> type = Class.forName("com.mewcode.session.HistoryStore");
            return type.getConstructor(Path.class, String.class, String.class)
                    .newInstance(dir, id, model);
        } catch (ClassNotFoundException error) {
            fail("HistoryStore 尚未实现", error);
            return null;
        } catch (InvocationTargetException error) {
            throw unwrap(error);
        }
    }

    private static List<String> loadedTexts(Path sessionDir) throws Exception {
        try {
            Class<?> type = Class.forName("com.mewcode.session.HistoryStore");
            Object loaded = type.getMethod("load").invoke(newStore(sessionDir, "20260831-120000-abcd", "test-model"));
            Method messages = loaded.getClass().getMethod("messages");
            @SuppressWarnings("unchecked")
            List<Message> values = (List<Message>) messages.invoke(loaded);
            return values.stream().map(Message::textContent).toList();
        } catch (InvocationTargetException error) {
            throw unwrap(error);
        }
    }

    private static Object invoke(Object target, String method, Object argument) throws Exception {
        try {
            return target.getClass().getMethod(method, List.class).invoke(target, argument);
        } catch (InvocationTargetException error) {
            throw unwrap(error);
        }
    }

    private static Object invoke(Object target, String method) throws Exception {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (InvocationTargetException error) {
            throw unwrap(error);
        }
    }

    private static Exception unwrap(InvocationTargetException error) {
        Throwable cause = error.getCause();
        if (cause instanceof Exception exception) return exception;
        if (cause instanceof Error fatal) throw fatal;
        return new RuntimeException(cause);
    }
}
