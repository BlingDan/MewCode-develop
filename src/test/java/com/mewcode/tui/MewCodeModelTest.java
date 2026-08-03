package com.mewcode.tui;

import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.StreamEvent;
import com.mewcode.tui.tea.KeyPressMessage;
import com.mewcode.tui.tea.WindowSizeMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MewCodeModelTest {

    @Test
    void singleProviderGoesDirectlyToChat() {
        var model = model(List.of(provider("one", "model-one")), new QueueClient());
        model.update(new WindowSizeMessage(80, 24));

        String view = model.view();
        assertTrue(view.contains("Ready for pure conversation"));
        assertTrue(view.contains("Send a message..."));
        assertTrue(view.contains("one"));
        assertTrue(view.contains("model-one"));
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
    void altEnterSubmitsMultilineAndLocksSecondSubmit() {
        var client = new QueueClient();
        var model = model(List.of(provider("one", "model-one")), client);
        model.update(new WindowSizeMessage(80, 24));

        type(model, "first");
        model.update(key("alt+enter"));
        type(model, "second");
        model.update(key("enter"));
        model.update(key("enter"));

        assertEquals(1, client.calls.get());
        assertEquals("first\nsecond", client.lastConversation.get().getMessages().getFirst().content());
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
                client.lastConversation.get().getMessages().getLast().content());
    }

    @Test
    void errorRecoversAndAllowsAnotherRequest() {
        var first = new LinkedBlockingQueue<StreamEvent>();
        var second = new LinkedBlockingQueue<StreamEvent>();
        var client = new QueueClient(first, second);
        var model = model(List.of(provider("one", "model-one")), client);
        model.update(new WindowSizeMessage(80, 24));
        type(model, "first");
        model.update(key("enter"));

        first.offer(new StreamEvent.Error("Authentication failed."));
        var result = model.update(new MewCodeModel.StreamPollMessage());

        assertNotNull(result.command());
        assertTrue(model.view().contains("Send a message..."));
        type(model, "second");
        model.update(key("enter"));
        assertEquals(2, client.calls.get());
    }

    @Test
    void ctrlCAlwaysReturnsQuitCommand() {
        var model = model(List.of(provider("one", "model-one")), new QueueClient());
        model.update(new WindowSizeMessage(80, 24));
        assertNotNull(model.update(key("ctrl+c")).command());

        type(model, "hello");
        model.update(key("enter"));
        assertNotNull(model.update(key("ctrl+c")).command());
    }

    @Test
    void editsMultilineInputAtTheCursor() {
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

        assertEquals("abc\n<xy", client.lastMessages.get().getFirst().content());
    }

    @Test
    void nonExitSlashInputIsSentAndPartialErrorIsNotAssistantHistory() {
        var first = new LinkedBlockingQueue<StreamEvent>();
        var second = new LinkedBlockingQueue<StreamEvent>();
        var client = new QueueClient(first, second);
        var model = model(List.of(provider("one", "model-one")), client);
        model.update(new WindowSizeMessage(80, 24));

        type(model, "/help");
        model.update(key("enter"));
        assertEquals("/help", client.lastMessages.get().getFirst().content());

        first.offer(new StreamEvent.TextDelta("partial-secret"));
        first.offer(new StreamEvent.Error("Connection interrupted."));
        model.update(new MewCodeModel.StreamPollMessage());
        type(model, "next");
        model.update(key("enter"));

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
