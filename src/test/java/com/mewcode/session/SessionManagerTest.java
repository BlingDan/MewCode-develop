package com.mewcode.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.mewcode.conversation.Message;
import com.mewcode.llm.StreamEvent;
import com.mewcode.testsupport.FakeLlmClient;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void createsNewSessionAndContinuesAppendingAfterResume() throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Path userHome = tempDir.resolve("home");
        Object first = newManager(projectRoot, userHome);
        String id = (String) method(first, "currentSessionId").invoke(first);
        Object conversation = method(first, "conversation").invoke(first);
        conversation.getClass().getMethod("addUserMessage", String.class).invoke(conversation, "hello");
        conversation.getClass().getMethod("addAssistantMessage", String.class).invoke(conversation, "hi");
        Path dir = (Path) method(first, "currentSessionDirectory").invoke(first);
        method(first, "close").invoke(first);

        Object second = newManager(projectRoot, userHome);
        Object result = method(second, "resume", String.class).invoke(second, id);
        Object restored = method(second, "conversation").invoke(second);
        restored.getClass().getMethod("addUserMessage", String.class).invoke(restored, "next");

        @SuppressWarnings("unchecked")
        List<Message> messages = (List<Message>) restored.getClass().getMethod("getMessages").invoke(restored);
        assertEquals(List.of("hello", "hi", "next"), messages.stream().map(Message::textContent).toList());
        assertEquals(id, result.getClass().getMethod("sessionId").invoke(result));
        assertTrue(Files.readString(dir.resolve("conversation.jsonl")).contains("next"));
        method(second, "close").invoke(second);
    }

    @Test
    void marksSessionStaleAfterTwentyFourHours() throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Path userHome = tempDir.resolve("home");
        Object first = newManager(projectRoot, userHome);
        String id = (String) method(first, "currentSessionId").invoke(first);
        Path dir = (Path) method(first, "currentSessionDirectory").invoke(first);
        Files.createDirectories(dir);
        Files.writeString(
                dir.resolve("conversation.jsonl"),
                "{\"role\":\"user\",\"content\":\"old\",\"ts\":"
                        + Instant.now().minusSeconds(25 * 60 * 60).getEpochSecond()
                        + "}\n");
        method(first, "close").invoke(first);

        Object second = newManager(projectRoot, userHome);
        Object result = method(second, "resume", String.class).invoke(second, id);

        assertTrue((Boolean) result.getClass().getMethod("stale").invoke(result));
        method(second, "close").invoke(second);
    }

    @Test
    void fallsBackToTheFirstUserMessageWhenTitleRequestFails() throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Path userHome = tempDir.resolve("home");
        var client = new FakeLlmClient();
        client.enqueue(new StreamEvent.Error("provider failed"));

        try (var manager = new SessionManager(projectRoot, userHome, ignored -> {})) {
            manager.attachTitleClient(client, "test-model");
            String userText = "first\n" + "x".repeat(100);
            manager.conversation().addUserMessage(userText);
            manager.conversation().addAssistantMessage("done");
            manager.onCompletedTurn(manager.conversation().getMessages());

            Path history = manager.currentSessionDirectory().resolve("conversation.jsonl");
            waitForLine(history, "\"type\":\"title\"");
            String expected = userText.replaceAll("\\s+", " ").strip();
            expected = expected.substring(0, 80) + "…";
            assertEquals(expected, manager.listSessions().getFirst().title());
            assertEquals(1, client.requestCount());
            assertTrue(client.requests().getFirst().tools().isEmpty());
        }
    }

    private static Object newManager(Path projectRoot, Path userHome) throws Exception {
        try {
            Class<?> type = Class.forName("com.mewcode.session.SessionManager");
            return type.getConstructor(Path.class, Path.class, Consumer.class)
                    .newInstance(projectRoot, userHome, (Consumer<String>) ignored -> {});
        } catch (ClassNotFoundException error) {
            fail("SessionManager 尚未实现", error);
            return null;
        } catch (InvocationTargetException error) {
            throw unwrap(error);
        }
    }

    private static java.lang.reflect.Method method(Object target, String name, Class<?>... types)
            throws Exception {
        return target.getClass().getMethod(name, types);
    }

    private static void waitForLine(Path file, String needle) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (Files.exists(file) && Files.readString(file).contains(needle)) return;
            Thread.sleep(10);
        }
        fail("未等到历史追加：" + needle);
    }

    private static Exception unwrap(InvocationTargetException error) {
        Throwable cause = error.getCause();
        if (cause instanceof Exception exception) return exception;
        if (cause instanceof Error fatal) throw fatal;
        return new RuntimeException(cause);
    }
}
