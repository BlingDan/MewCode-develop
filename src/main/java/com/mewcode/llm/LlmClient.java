package com.mewcode.llm;

import com.mewcode.conversation.ConversationManager;

import java.util.concurrent.BlockingQueue;

/** Common streaming interface for all provider protocols. */
public interface LlmClient {
    BlockingQueue<StreamEvent> stream(ConversationManager conversation);
}
