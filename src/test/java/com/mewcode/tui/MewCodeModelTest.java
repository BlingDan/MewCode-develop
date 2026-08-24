package com.mewcode.tui;

import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.StreamEvent;
import com.mewcode.tui.tea.Command;
import com.mewcode.tui.tea.KeyPressMessage;
import com.mewcode.tui.tea.WindowSizeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayDeque;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class MewCodeModelTest {

    @TempDir Path projectRoot;

    @Test
    void rendersToolCallResultAndFinalTextInOrderWithoutChangingHistory() throws Exception {
        String path = Path.of(System.getProperty("user.dir"), "README.md")
                .toAbsolutePath().normalize().toString();
        var first = new LinkedBlockingQueue<StreamEvent>();
        first.add(new StreamEvent.TextDelta("I will read it."));
        first.add(new StreamEvent.ToolCallComplete(
                "call-read", "ReadFile", Map.of("path", path, "offset", 1, "limit", 2)));
        first.add(new StreamEvent.StreamEnd("tool_use"));
        var second = new LinkedBlockingQueue<StreamEvent>();
        second.add(new StreamEvent.TextDelta("Read complete."));
        second.add(new StreamEvent.StreamEnd("end_turn"));
        var client = new QueueClient(first, second);
        var model = model(List.of(provider("one", "model-one")), client);
        model.update(new WindowSizeMessage(100, 30));
        type(model, "read README");
        model.update(key("enter"));

        List<String> printed = pollUntilReady(model, client);

        int callLine = indexContaining(printed, "● Read(");
        int resultLine = indexContaining(printed, "⎿ ");
        int finalLine = indexContaining(printed, "Read complete.");
        assertTrue(callLine >= 0, printed.toString());
        assertTrue(resultLine > callLine, printed.toString());
        assertTrue(finalLine > resultLine, printed.toString());
        assertFalse(client.lastMessages.get().stream()
                .map(Message::textContent)
                .anyMatch(text -> text.contains("● Read(") || text.contains("⎿ ")));
        assertTrue(model.view().contains("Send a message..."));
    }

    @Test
    void rendersToolErrorSummaryAndRecoversInput() throws Exception {
        var first = new LinkedBlockingQueue<StreamEvent>();
        first.add(new StreamEvent.ToolCallComplete("call-unknown", "MissingTool", Map.of()));
        first.add(new StreamEvent.StreamEnd("tool_use"));
        var second = new LinkedBlockingQueue<StreamEvent>();
        second.add(new StreamEvent.TextDelta("I could not use that tool."));
        second.add(new StreamEvent.StreamEnd("end_turn"));
        var client = new QueueClient(first, second);
        var model = model(List.of(provider("one", "model-one")), client);
        model.update(new WindowSizeMessage(100, 30));
        type(model, "try missing tool");
        model.update(key("enter"));

        List<String> printed = pollUntilReady(model, client);

        assertTrue(printed.stream().anyMatch(line -> line.contains("● MissingTool(")), printed.toString());
        assertTrue(printed.stream().anyMatch(line -> line.contains("⎿ Error:")), printed.toString());
        assertTrue(model.view().contains("Send a message..."));
    }

    @Test
    void rendersMultipleToolRowsInAssistantOrderAndKeepsStreamingText() throws Exception {
        var first = new LinkedBlockingQueue<StreamEvent>();
        first.add(new StreamEvent.TextDelta("Before tools. "));
        first.add(new StreamEvent.ToolCallComplete("call-a", "MissingA", Map.of()));
        first.add(new StreamEvent.ToolCallComplete("call-b", "MissingB", Map.of()));
        first.add(new StreamEvent.TextDelta("After tools."));
        first.add(new StreamEvent.StreamEnd("tool_use"));
        var second = new LinkedBlockingQueue<StreamEvent>();
        second.add(new StreamEvent.TextDelta("Final text."));
        second.add(new StreamEvent.StreamEnd("end_turn"));
        var client = new QueueClient(first, second);
        var model = model(List.of(provider("one", "model-one")), client);
        model.update(new WindowSizeMessage(100, 30));
        type(model, "run two tools");
        model.update(key("enter"));

        List<String> printed = pollUntilReady(model, client);

        int firstCall = indexContaining(printed, "● MissingA(");
        int secondCall = indexContaining(printed, "● MissingB(");
        int firstResult = indexContaining(printed, "未知工具：MissingA");
        int secondResult = indexContaining(printed, "未知工具：MissingB");
        int finalText = indexContaining(printed, "Final text.");
        assertTrue(firstCall >= 0, printed.toString());
        assertTrue(secondCall > firstCall, printed.toString());
        assertTrue(firstResult > secondCall, printed.toString());
        assertTrue(secondResult > firstResult, printed.toString());
        assertTrue(finalText > secondResult, printed.toString());
        assertTrue(printed.stream().anyMatch(line -> line.contains("Before tools. After tools.")),
                printed.toString());
    }

    @Test
    void singleProviderGoesDirectlyToChat() {
        var model = model(List.of(provider("one", "model-one")), new QueueClient());
        model.update(new WindowSizeMessage(80, 24));

        String view = model.view();
        assertTrue(view.contains("Ready for conversation and tools"));
        assertTrue(view.contains("Send a message..."));
        assertTrue(view.contains("one"));
        assertTrue(view.contains("model-one"));
    }

    @Test
    void planAndDoAreLocalPersistentModeSwitches() {
        var client = new QueueClient();
        var model = model(List.of(provider("one", "model-one")), client);
        model.update(new WindowSizeMessage(80, 24));

        type(model, "/plan");
        var plan = model.update(key("enter"));
        assertNotNull(plan.command());
        assertTrue(model.view().contains("Plan Mode"));
        assertEquals(0, client.calls.get());

        type(model, "/do");
        var execute = model.update(key("enter"));
        assertNotNull(execute.command());
        assertTrue(model.view().contains("Execute Mode"));
        assertEquals(0, client.calls.get());
    }

    @Test
    void usesTheExplicitRootForPromptAndBanner() {
        var prompt = new AtomicReference<String>();
        var model = new MewCodeModel(
                List.of(provider("one", "model-one")),
                projectRoot,
                (provider, systemPrompt) -> {
                    prompt.set(systemPrompt);
                    return new QueueClient();
                });

        var update = model.update(new WindowSizeMessage(100, 30));
        var printed = new ArrayList<String>();
        collectPrintLines(update.command(), printed);
        String root = projectRoot.toAbsolutePath().normalize().toString();

        assertTrue(printed.stream().anyMatch(line -> line.contains(root)), printed.toString());
        assertNotNull(prompt.get());
        assertTrue(prompt.get().contains("The current project root is: " + root));
        assertTrue(prompt.get().contains(root + "/.trae/skills/mew-spec/SKILL.md"));
    }

    @Test
    void multipleProvidersCanSelectSecondEntry() {
        var model = model(List.of(provider("one", "model-one"), provider("two", "model-two")),
                new QueueClient());
        model.update(new WindowSizeMessage(80, 24));
        assertTrue(model.view().contains("Select a provider"));

        model.update(key("down"));
        model.update(key("enter"));

        assertTrue(model.view().contains("two"));
        assertTrue(model.view().contains("model-two"));
    }

    @Test
    void altEnterSubmitsMultilineAndLocksSecondSubmit() throws Exception {
        var client = new QueueClient();
        var model = model(List.of(provider("one", "model-one")), client);
        model.update(new WindowSizeMessage(80, 24));

        type(model, "first");
        model.update(key("alt+enter"));
        type(model, "second");
        model.update(key("enter"));
        model.update(key("enter"));

        awaitCalls(client, 1);
        assertEquals(1, client.calls.get());
        assertEquals("first\nsecond", client.lastConversation.get().getMessages().getFirst().textContent());
        assertTrue(model.view().contains("Waiting for response"));
    }

    @Test
    void thinkingIsHiddenTextStreamsAndCompletionCommitsAssistant() {
        var queue = new LinkedBlockingQueue<StreamEvent>();
        var client = new QueueClient(queue);
        var model = model(List.of(provider("one", "model-one")), client);
        model.update(new WindowSizeMessage(80, 24));
        type(model, "hello");
        model.update(key("enter"));

        queue.offer(new StreamEvent.ThinkingDelta("NEVER_SHOW_THIS"));
        model.update(new MewCodeModel.StreamPollMessage());
        assertFalse(model.view().contains("NEVER_SHOW_THIS"));

        queue.offer(new StreamEvent.TextDelta("Hello "));
        model.update(new MewCodeModel.StreamPollMessage());
        assertTrue(model.view().contains("Hello "));

        queue.offer(new StreamEvent.TextDelta("world"));
        queue.offer(new StreamEvent.StreamEnd("end_turn"));
        model.update(new MewCodeModel.StreamPollMessage());

        assertTrue(model.view().contains("Send a message..."));
        assertEquals("Hello world",
                client.lastConversation.get().getMessages().getLast().textContent());
    }

    @Test
    void keepsStreamingViewWithinViewportForLongResponses() throws Exception {
        var queue = new LinkedBlockingQueue<StreamEvent>();
        var client = new QueueClient(queue);
        var model = model(List.of(provider("one", "model-one")), client);
        int width = 80;
        int height = 20;
        model.update(new WindowSizeMessage(width, height));
        type(model, "long response");
        model.update(key("enter"));
        awaitCalls(client, 1);

        for (int i = 0; i < 80; i++) {
            queue.offer(new StreamEvent.TextDelta("line-" + i + "\n"));
        }
        model.update(new MewCodeModel.StreamPollMessage());

        assertTrue(physicalLines(model.view(), width) <= height,
                () -> "streaming view exceeded terminal height: "
                        + physicalLines(model.view(), width));
    }

    @Test
    void rendersCumulativeUsageWhileStreaming() throws Exception {
        var queue = new LinkedBlockingQueue<StreamEvent>();
        var client = new QueueClient(queue);
        var model = model(List.of(provider("one", "model-one")), client);
        model.update(new WindowSizeMessage(80, 24));
        type(model, "usage");
        model.update(key("enter"));
        awaitCalls(client, 1);

        queue.offer(new StreamEvent.Usage(OptionalLong.of(11), OptionalLong.of(3)));
        model.update(new MewCodeModel.StreamPollMessage());

        assertTrue(model.view().contains("输入 11"));
        assertTrue(model.view().contains("输出 3"));
        model.update(key("escape"));
    }

    @Test
    void errorRecoversAndAllowsAnotherRequest() throws Exception {
        var first = new LinkedBlockingQueue<StreamEvent>();
        var second = new LinkedBlockingQueue<StreamEvent>();
        var client = new QueueClient(first, second);
        var model = model(List.of(provider("one", "model-one")), client);
        model.update(new WindowSizeMessage(80, 24));
        type(model, "first");
        model.update(key("enter"));
        awaitCalls(client, 1);

        first.offer(new StreamEvent.Error("Authentication failed."));
        awaitIdle(model);
        var result = model.update(new MewCodeModel.StreamPollMessage());

        assertNotNull(result);
        assertTrue(model.view().contains("Send a message..."));
        type(model, "second");
        model.update(key("enter"));
        awaitCalls(client, 2);
        assertEquals(2, client.calls.get());
    }

    @Test
    void ctrlCQuitsWhenIdleButCancelsTheActiveLoop() throws Exception {
        var model = model(List.of(provider("one", "model-one")), new QueueClient());
        model.update(new WindowSizeMessage(80, 24));
        assertNotNull(model.update(key("ctrl+c")).command());

        type(model, "hello");
        model.update(key("enter"));
        assertNotNull(model.update(key("ctrl+c")).command());
        assertTrue(model.view().contains("Send a message..."));
    }

    @Test
    void escapeCancelsTheActiveLoopAndReturnsToIdle() throws Exception {
        var client = new QueueClient(new LinkedBlockingQueue<>());
        var model = model(List.of(provider("one", "model-one")), client);
        model.update(new WindowSizeMessage(80, 24));

        type(model, "hello");
        model.update(key("enter"));
        awaitCalls(client, 1);

        assertNotNull(model.update(key("escape")).command());
        assertTrue(model.view().contains("Send a message..."));
        assertEquals(1, client.calls.get());
    }

    @Test
    void editsMultilineInputAtTheCursor() throws Exception {
        var client = new QueueClient();
        var model = model(List.of(provider("one", "model-one")), client);
        model.update(new WindowSizeMessage(80, 24));

        type(model, "ac");
        model.update(key("left"));
        type(model, "b");
        model.update(key("end"));
        model.update(key("alt+enter"));
        type(model, "xy");
        model.update(key("home"));
        type(model, "<");
        model.update(key("end"));
        type(model, ">");
        model.update(key("backspace"));
        model.update(key("enter"));
        awaitCalls(client, 1);

        assertEquals("abc\n<xy", client.lastMessages.get().getFirst().textContent());
    }

    @Test
    void nonExitSlashInputIsSentAndPartialErrorIsNotAssistantHistory() throws Exception {
        var first = new LinkedBlockingQueue<StreamEvent>();
        var second = new LinkedBlockingQueue<StreamEvent>();
        var client = new QueueClient(first, second);
        var model = model(List.of(provider("one", "model-one")), client);
        model.update(new WindowSizeMessage(80, 24));

        type(model, "/help");
        model.update(key("enter"));
        awaitCalls(client, 1);
        assertEquals("/help", client.lastMessages.get().getFirst().textContent());

        first.offer(new StreamEvent.TextDelta("partial-secret"));
        first.offer(new StreamEvent.Error("Connection interrupted."));
        awaitIdle(model);
        type(model, "next");
        model.update(key("enter"));
        awaitCalls(client, 2);

        assertEquals(List.of(new Message("user", "/help"), new Message("user", "next")),
                client.lastMessages.get());
    }

    private static MewCodeModel model(List<ProviderConfig> providers, LlmClient client) {
        return new MewCodeModel(providers, (provider, prompt) -> client);
    }

    private static ProviderConfig provider(String name, String model) {
        var provider = new ProviderConfig();
        provider.setName(name);
        provider.setProtocol("openai");
        provider.setModel(model);
        provider.setApiKey("test-key");
        return provider;
    }

    private static KeyPressMessage key(String key) {
        return new KeyPressMessage(key, null);
    }

    private static void type(MewCodeModel model, String text) {
        for (char c : text.toCharArray()) {
            model.update(new KeyPressMessage(String.valueOf(c), new char[]{c}));
        }
    }

    private static List<String> pollUntilReady(MewCodeModel model, QueueClient client)
            throws Exception {
        var printed = new ArrayList<String>();
        for (int attempt = 0; attempt < 150; attempt++) {
            Thread.sleep(20);
            var update = model.update(new MewCodeModel.StreamPollMessage());
            collectPrintLines(update.command(), printed);
            if (client.calls.get() == 2 && model.view().contains("Send a message...")) {
                return printed;
            }
        }
        fail("agent turn did not complete; printed=" + printed);
        return printed;
    }

    private static void awaitCalls(QueueClient client, int expected) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (client.calls.get() >= expected) return;
            Thread.sleep(10);
        }
        fail("expected " + expected + " provider calls, got " + client.calls.get());
    }

    private static void awaitIdle(MewCodeModel model) throws Exception {
        for (int attempt = 0; attempt < 150; attempt++) {
            model.update(new MewCodeModel.StreamPollMessage());
            if (model.view().contains("Send a message...")) return;
            Thread.sleep(10);
        }
        fail("agent turn did not return to idle");
    }

    private static void collectPrintLines(Command command, List<String> output) {
        if (command instanceof Command.PrintLine line) {
            output.add(line.text());
        } else if (command instanceof Command.Batch batch) {
            for (Command child : batch.commands()) collectPrintLines(child, output);
        }
    }

    private static int indexContaining(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) return i;
        }
        return -1;
    }

    private static int physicalLines(String value, int width) {
        int total = 0;
        for (String line : value.split("\\n", -1)) {
            String plain = ANSI_ESCAPE.matcher(line).replaceAll("");
            total += Math.max(1, (int) Math.ceil(
                    (double) com.mewcode.tui.tea.Program.displayWidth(plain) / width));
        }
        return total;
    }

    private static final Pattern ANSI_ESCAPE = Pattern.compile(
            "\\033\\[[0-9;]*[a-zA-Z]|\\033\\][^\\007\\033]*(?:\\007|\\033\\\\\\\\)");

    private static final class QueueClient implements LlmClient {
        private final ArrayDeque<BlockingQueue<StreamEvent>> queues = new ArrayDeque<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<ConversationManager> lastConversation = new AtomicReference<>();
        private final AtomicReference<List<Message>> lastMessages = new AtomicReference<>();

        @SafeVarargs
        private QueueClient(BlockingQueue<StreamEvent>... queues) {
            this.queues.addAll(List.of(queues));
        }

        @Override
        public BlockingQueue<StreamEvent> stream(ConversationManager conversation) {
            calls.incrementAndGet();
            lastConversation.set(conversation);
            lastMessages.set(conversation.getMessages());
            return queues.isEmpty() ? new LinkedBlockingQueue<>() : queues.removeFirst();
        }
    }
}
