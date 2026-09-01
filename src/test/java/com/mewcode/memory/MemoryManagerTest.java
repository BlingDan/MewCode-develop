package com.mewcode.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.conversation.Message;
import com.mewcode.llm.CancellableLlmStream;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.PromptRequest;
import com.mewcode.llm.StreamEvent;
import com.mewcode.testsupport.FakeLlmClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void asynchronouslyCreatesProjectNoteWithoutTools() throws Exception {
        var client = new FakeLlmClient();
        client.enqueue(
                new StreamEvent.TextDelta("[{\"action\":\"create\",\"level\":\"project\",\"type\":\"project_knowledge\",\"title\":\"API conventions\",\"slug\":\"api_conventions\",\"content\":\"Keep the existing API conventions.\"}]"),
                new StreamEvent.StreamEnd("end_turn"));
        var errors = new AtomicReference<String>();

        try (var manager = new MemoryManager(tempDir.resolve("project"), tempDir.resolve("home"), errors::set)) {
            manager.attachClient(client, "test-model");
            manager.updateAsync(List.of(new Message("user", "remember this"), new Message("assistant", "done")));
            waitFor(tempDir.resolve("project/.mewcode/memory/project_knowledge_api_conventions.md"));

            assertTrue(Files.exists(tempDir.resolve("project/.mewcode/memory/MEMORY.md")));
            assertEquals(1, client.requestCount());
            assertTrue(client.requests().getFirst().tools().isEmpty());
            assertTrue(errors.get() == null, errors.get());
        }
    }

    @Test
    void acceptsFencedJsonWhenCreatingUserPreference() throws Exception {
        var client = new FakeLlmClient();
        client.enqueue(
                new StreamEvent.TextDelta(
                        "```json\n[{\"action\":\"create\",\"level\":\"user\",\"type\":\"user_preference\",\"title\":\"Job search\",\"slug\":\"job_search\",\"content\":\"正在找 agent 相关工作。\"}]\n```"),
                new StreamEvent.StreamEnd("end_turn"));

        try (var manager =
                new MemoryManager(tempDir.resolve("project"), tempDir.resolve("home"), ignored -> {})) {
            manager.attachClient(client, "test-model");
            manager.updateAsync(List.of(new Message("user", "记住我正在找 agent 相关工作")));
            waitFor(tempDir.resolve("home/.mewcode/memory/user_preference_job_search.md"));
        }
    }

    @Test
    void emptyOperationArrayDoesNotChangeExistingMemory() throws Exception {
        Path memory = tempDir.resolve("project/.mewcode/memory");
        Files.createDirectories(memory);
        Files.writeString(memory.resolve("MEMORY.md"), "old index");
        var client = new FakeLlmClient();
        client.enqueue(new StreamEvent.TextDelta("[]"), new StreamEvent.StreamEnd("end_turn"));

        try (var manager = new MemoryManager(tempDir.resolve("project"), tempDir.resolve("home"), ignored -> {})) {
            manager.attachClient(client, "test-model");
            manager.updateAsync(List.of(new Message("user", "nothing to remember")));
            waitForRequest(client);

            assertEquals("## Project memory\nold index\n", manager.indexText());
            assertEquals("old index", Files.readString(memory.resolve("MEMORY.md")));
        }
    }

    @Test
    void invalidResponseKeepsExistingMemoryAndReportsOnlyASafeDiagnostic() throws Exception {
        Path project = tempDir.resolve("project");
        Path memory = project.resolve(".mewcode/memory");
        var store = new MemoryStore(memory, MemoryLevel.PROJECT);
        var existing =
                new MemoryOperation(
                        "create",
                        "project",
                        "project_knowledge",
                        "Existing",
                        "existing",
                        null,
                        "Keep this note.");
        store.commit(store.stage(List.of(existing)));
        String noteBefore = Files.readString(memory.resolve("project_knowledge_existing.md"));
        String indexBefore = Files.readString(memory.resolve("MEMORY.md"));

        var client = new FakeLlmClient();
        client.enqueue(new StreamEvent.TextDelta("我已经记住了。"), new StreamEvent.StreamEnd("end_turn"));
        var diagnostic = new AtomicReference<String>();

        try (var manager = new MemoryManager(project, tempDir.resolve("home"), diagnostic::set)) {
            manager.attachClient(client, "test-model");
            manager.updateAsync(List.of(new Message("user", "记住新内容")));
            waitForDiagnostic(diagnostic);

            assertEquals(noteBefore, Files.readString(memory.resolve("project_knowledge_existing.md")));
            assertEquals(indexBefore, Files.readString(memory.resolve("MEMORY.md")));
            assertEquals("memory 更新失败，保留旧笔记和索引。", diagnostic.get());
            assertFalse(diagnostic.get().contains("我已经记住了"));
        }
    }

    @Test
    void routesUserAndProjectOperationsToTheirOwnStores() throws Exception {
        Path project = tempDir.resolve("project");
        Path home = tempDir.resolve("home");
        var client = new FakeLlmClient();
        client.enqueue(
                new StreamEvent.TextDelta(
                        "[{\"action\":\"create\",\"level\":\"user\",\"type\":\"user_preference\",\"title\":\"Language\",\"slug\":\"language\",\"content\":\"Use Chinese.\"},"
                                + "{\"action\":\"create\",\"level\":\"project\",\"type\":\"project_knowledge\",\"title\":\"CI\",\"slug\":\"ci\",\"content\":\"Use GitHub Actions.\"}]"),
                new StreamEvent.StreamEnd("end_turn"));

        try (var manager = new MemoryManager(project, home, ignored -> {})) {
            manager.attachClient(client, "test-model");
            manager.updateAsync(List.of(new Message("user", "记录个人偏好和项目知识")));
            waitFor(project.resolve(".mewcode/memory/project_knowledge_ci.md"));

            assertTrue(Files.exists(home.resolve(".mewcode/memory/user_preference_language.md")));
            assertTrue(
                    manager.indexText().contains("## User memory")
                            && manager.indexText().contains("## Project memory"));
            assertFalse(Files.exists(project.resolve(".mewcode/memory/user_preference_language.md")));
            assertFalse(Files.exists(home.resolve(".mewcode/memory/project_knowledge_ci.md")));
            assertTrue(client.requests().stream().allMatch(request -> request.tools().isEmpty()));
        }
    }

    @Test
    void executesUpdateAndDeleteOperationsAgainstTheSameNote() throws Exception {
        Path project = tempDir.resolve("project");
        Path memory = project.resolve(".mewcode/memory");
        var store = new MemoryStore(memory, MemoryLevel.PROJECT);
        var create =
                new MemoryOperation(
                        "create",
                        "project",
                        "project_knowledge",
                        "API",
                        "api",
                        null,
                        "old");
        store.commit(store.stage(List.of(create)));

        var client = new FakeLlmClient();
        client.enqueue(
                new StreamEvent.TextDelta(
                        "[{\"action\":\"update\",\"level\":\"project\",\"filename\":\"project_knowledge_api.md\",\"title\":\"API v2\",\"content\":\"new\"}]"),
                new StreamEvent.StreamEnd("end_turn"));
        client.enqueue(
                new StreamEvent.TextDelta(
                        "[{\"action\":\"delete\",\"level\":\"project\",\"filename\":\"project_knowledge_api.md\"}]"),
                new StreamEvent.StreamEnd("end_turn"));

        try (var manager = new MemoryManager(project, tempDir.resolve("home"), ignored -> {})) {
            manager.attachClient(client, "test-model");
            Path note = memory.resolve("project_knowledge_api.md");
            manager.updateAsync(List.of(new Message("user", "update")));
            waitForContent(note, "title: API v2");
            manager.updateAsync(List.of(new Message("user", "delete")));
            waitForAbsent(note);
            assertEquals(2, client.requestCount());
            assertTrue(client.requests().stream().allMatch(request -> request.tools().isEmpty()));
        }
    }

    @Test
    void prunesOversizedIndexesBeforeReturningTheRequestSnapshot() throws Exception {
        Path project = tempDir.resolve("project");
        Path projectIndex = project.resolve(".mewcode/memory/MEMORY.md");
        Files.createDirectories(projectIndex.getParent());
        Files.writeString(projectIndex, "old\n".repeat(201));
        var client = new FakeLlmClient();
        client.enqueue(
                new StreamEvent.TextDelta("{\"user\":\"user-index\",\"project\":\"project-index\"}"),
                new StreamEvent.StreamEnd("end_turn"));

        try (var manager = new MemoryManager(project, tempDir.resolve("home"), ignored -> {})) {
            manager.attachClient(client, "test-model");
            assertEquals(
                    "## User memory\nuser-index\n\n## Project memory\nproject-index\n",
                    manager.indexText());
            assertEquals("project-index", Files.readString(projectIndex));
            assertTrue(client.requests().getFirst().tools().isEmpty());
        }
    }

    @Test
    void closeWaitsForInFlightUpdate() throws Exception {
        var client = new BlockingMemoryClient();
        Path project = tempDir.resolve("project");
        Path note = project.resolve(".mewcode/memory/project_knowledge_ci.md");

        try (var manager = new MemoryManager(project, tempDir.resolve("home"), ignored -> {})) {
            manager.attachClient(client, "test-model");
            manager.updateAsync(List.of(new Message("user", "记住 CI"), new Message("assistant", "已记住")));
            assertTrue(client.started.await(3, TimeUnit.SECONDS));

            var closeReturned = new CountDownLatch(1);
            Thread closer =
                    Thread.startVirtualThread(
                            () -> {
                                manager.close();
                                closeReturned.countDown();
                            });
            assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS));

            client.release.countDown();
            assertTrue(closeReturned.await(3, TimeUnit.SECONDS));
            closer.join(3_000);
            assertTrue(Files.exists(note));
            assertTrue(Files.exists(note.getParent().resolve("MEMORY.md")));
        }
    }

    private static void waitFor(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!Files.exists(file) && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(Files.exists(file), "后台 memory 更新未完成");
    }

    private static void waitForContent(Path file, String needle) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (Files.exists(file) && Files.readString(file).contains(needle)) return;
            Thread.sleep(10);
        }
        assertTrue(false, "后台 memory 更新未完成：" + needle);
    }

    private static void waitForAbsent(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (Files.notExists(file)) return;
            Thread.sleep(10);
        }
        assertFalse(Files.exists(file), "后台 memory 删除未完成");
    }

    private static void waitForRequest(FakeLlmClient client) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (client.requestCount() == 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(1, client.requestCount());
    }

    private static void waitForDiagnostic(AtomicReference<String> diagnostic) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (diagnostic.get() == null && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals("memory 更新失败，保留旧笔记和索引。", diagnostic.get());
    }

    private static final class BlockingMemoryClient implements LlmClient {
        private static final String RESPONSE =
                "[{\"action\":\"create\",\"level\":\"project\",\"type\":\"project_knowledge\",\"title\":\"CI\",\"slug\":\"ci\",\"content\":\"Use GitHub Actions.\"}]";

        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public CancellableLlmStream openStream(PromptRequest request) {
            BlockingQueue<StreamEvent> events = new LinkedBlockingQueue<>();
            Thread.startVirtualThread(
                    () -> {
                        started.countDown();
                        try {
                            release.await();
                            events.add(new StreamEvent.TextDelta(RESPONSE));
                            events.add(new StreamEvent.StreamEnd("end_turn"));
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                        }
                    });
            return new CancellableLlmStream(events, () -> {});
        }
    }
}
