package com.mewcode.conversation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;

class ConversationManagerTest {

    @Test
    void preservesOrderAndMultilineContent() {
        var history = new ConversationManager();
        history.addUserMessage("one\ntwo");
        history.addAssistantMessage("answer one");
        history.addUserMessage("next");
        history.addAssistantMessage("answer two");

        var messages = history.getMessages();
        assertEquals(4, messages.size());
        assertEquals(new Message("user", "one\ntwo"), messages.get(0));
        assertEquals("assistant", messages.get(3).role());
    }

    @Test
    void returnsImmutableIndependentSnapshots() {
        var history = new ConversationManager();
        history.addUserMessage("first");
        var snapshot = history.getMessages();

        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(new Message("assistant", "bad")));

        history.addAssistantMessage("second");
        assertEquals(1, snapshot.size());
        assertEquals(2, history.getMessages().size());
    }

    @Test
    void commitsAssistantToolTurnAndResultsAsOneHistoryOperation() {
        var history = new ConversationManager();
        var assistant = java.util.List.<ContentBlock>of(
                new TextBlock("inspect"),
                new ToolUseBlock("call-1", "ReadFile", java.util.Map.of("path", "/tmp/a")));
        var results = java.util.List.of(new ToolResultBlock("call-1", "content", false));

        history.addToolTurn(assistant, results);

        assertEquals(2, history.getMessages().size());
        assertEquals("assistant", history.getMessages().get(0).role());
        assertEquals("user", history.getMessages().get(1).role());
        assertInstanceOf(ToolResultBlock.class, history.getMessages().get(1).content().getFirst());
    }

    @Test
    void replacesHistoryWithAnIndependentSnapshot() {
        var history = new ConversationManager();
        history.addUserMessage("old");
        var replacement = new java.util.ArrayList<Message>();
        replacement.add(new Message("user", "new user"));
        replacement.add(new Message("assistant", "new answer"));

        history.replaceMessages(replacement);
        replacement.clear();

        assertEquals(2, history.getMessages().size());
        assertEquals(
                "new user",
                ((TextBlock) history.getMessages().get(0).content().getFirst()).text());
        assertEquals("assistant", history.getMessages().get(1).role());
    }

    @Test
    void notifiesPersistenceListenerWithAtomicMutationKinds() {
        var history = new ConversationManager();
        var mutations = new ArrayList<String>();
        attachListenerReflectively(history, mutations);

        history.addUserMessage("hello");
        history.addToolTurn(
                java.util.List.of(new ToolUseBlock("call-1", "ReadFile", java.util.Map.of())),
                java.util.List.of(new ToolResultBlock("call-1", "ok", false)));

        assertEquals(java.util.List.of("APPEND:1", "APPEND:2"), mutations);
    }

    private static void attachListenerReflectively(
            ConversationManager history, ArrayList<String> mutations) {
        try {
            var method = ConversationManager.class.getMethod("setMutationListener", java.util.function.Consumer.class);
            method.invoke(history, (java.util.function.Consumer<Object>) mutation -> {
                try {
                    var kind = mutation.getClass().getMethod("kind").invoke(mutation);
                    var messages = mutation.getClass().getMethod("messages").invoke(mutation);
                    mutations.add(kind + ":" + ((java.util.List<?>) messages).size());
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
            });
        } catch (ReflectiveOperationException error) {
            fail("ConversationManager mutation listener 尚未实现", error);
        }
    }
}
