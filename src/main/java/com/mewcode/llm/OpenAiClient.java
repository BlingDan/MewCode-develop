package com.mewcode.llm;

import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** OpenAI Chat Completions streaming adapter. */
public final class OpenAiClient implements LlmClient {

    private static final int QUEUE_CAPACITY = 256;

    private final com.openai.client.OpenAIClient client;
    private final String model;
    private final String systemPrompt;

    public OpenAiClient(ProviderConfig provider, String systemPrompt) {
        var builder = OpenAIOkHttpClient.builder()
                .apiKey(provider.getApiKey())
                .maxRetries(0);
        if (provider.getBaseUrl() != null && !provider.getBaseUrl().isBlank()) {
            builder.baseUrl(provider.getBaseUrl());
        }
        this.client = builder.build();
        this.model = provider.getModel();
        this.systemPrompt = systemPrompt;
    }

    @Override
    public BlockingQueue<StreamEvent> stream(ConversationManager conversation) {
        var queue = new LinkedBlockingQueue<StreamEvent>(QUEUE_CAPACITY);
        List<Message> snapshot = conversation.getMessages();
        Thread.startVirtualThread(() -> streamInCurrentThread(snapshot, queue));
        return queue;
    }

    private void streamInCurrentThread(List<Message> messages, BlockingQueue<StreamEvent> queue) {
        try {
            var params = ChatCompletionCreateParams.builder()
                    .model(model)
                    .addSystemMessage(systemPrompt);
            for (Message message : messages) {
                if ("assistant".equals(message.role())) {
                    params.addAssistantMessage(message.content());
                } else {
                    params.addUserMessage(message.content());
                }
            }

            try (StreamResponse<ChatCompletionChunk> response =
                         client.chat().completions().createStreaming(params.build())) {
                response.stream()
                        .flatMap(chunk -> chunk.choices().stream())
                        .flatMap(choice -> choice.delta().content().stream())
                        .filter(text -> !text.isEmpty())
                        .forEach(text -> putText(queue, text));
            }
            queue.put(new StreamEvent.StreamEnd("end_turn"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            putError(queue, safeError(e));
        }
    }

    private static void putText(BlockingQueue<StreamEvent> queue, String text) {
        try {
            queue.put(new StreamEvent.TextDelta(text));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StreamInterruptedException();
        }
    }

    private static String safeError(Exception error) {
        if (error instanceof com.openai.errors.UnauthorizedException) {
            return "Authentication failed. Check api_key.";
        }
        if (error instanceof com.openai.errors.RateLimitException) {
            return "Rate limited. Try again later.";
        }
        if (error instanceof com.openai.errors.NotFoundException) {
            return "Model or endpoint not found.";
        }
        if (error instanceof com.openai.errors.BadRequestException) {
            return "Request rejected. Check the model and conversation length.";
        }
        if (error instanceof com.openai.errors.OpenAIIoException) {
            return "Network error while contacting OpenAI.";
        }
        if (error instanceof com.openai.errors.OpenAIServiceException service) {
            return service.statusCode() == 413
                    ? "Conversation is too long for this model."
                    : "OpenAI API error (HTTP " + service.statusCode() + ").";
        }
        return "Unexpected OpenAI streaming error.";
    }

    private static void putError(BlockingQueue<StreamEvent> queue, String message) {
        try {
            queue.put(new StreamEvent.Error(message));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class StreamInterruptedException extends RuntimeException {}
}
