package com.mewcode.conversation;

import java.util.ArrayList;
import java.util.List;

/** In-memory conversation history for the current process. */
public final class ConversationManager {

    private final List<Message> messages = new ArrayList<>();

    public synchronized void addUserMessage(String text) {
        messages.add(new Message("user", text));
    }

    public synchronized void addAssistantMessage(String text) {
        messages.add(new Message("assistant", text));
    }

    public synchronized void addAssistantMessage(List<ContentBlock> content) {
        messages.add(new Message("assistant", content));
    }

    public synchronized void addToolResults(List<ToolResultBlock> results) {
        messages.add(new Message("user", new ArrayList<>(results)));
    }

    /** 原子提交一轮完整的 assistant 工具调用和对应结果。 */
    public synchronized void addToolTurn(List<ContentBlock> assistantContent,
                                          List<ToolResultBlock> results) {
        messages.add(new Message("assistant", assistantContent));
        messages.add(new Message("user", new ArrayList<>(results)));
    }

    public synchronized void addMessage(Message message) {
        messages.add(message);
    }

    public synchronized List<Message> getMessages() {
        return List.copyOf(messages);
    }
}
