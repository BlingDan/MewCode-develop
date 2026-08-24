package com.mewcode.conversation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
}
