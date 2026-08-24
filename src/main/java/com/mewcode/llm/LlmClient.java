package com.mewcode.llm;

import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/** Common streaming interface for all provider protocols. */
public interface LlmClient {

    default BlockingQueue<StreamEvent> stream(List<Message> messages,
                                               List<Map<String, Object>> apiTools) {
        var conversation = new ConversationManager();
        if (messages != null) {
            for (Message message : messages) conversation.addMessage(message);
        }
        return stream(conversation);
    }

    /** 兼容旧的测试客户端，同时保留对真实客户端的工具定义传递。 */
    default BlockingQueue<StreamEvent> stream(ConversationManager conversation,
                                               List<Map<String, Object>> apiTools) {
        return stream(conversation);
    }

    default BlockingQueue<StreamEvent> stream(ConversationManager conversation) {
        return stream(conversation.getMessages(), List.of());
    }
}
