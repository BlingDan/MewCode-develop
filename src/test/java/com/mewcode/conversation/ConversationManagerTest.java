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
}
