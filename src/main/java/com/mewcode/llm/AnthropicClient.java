package com.mewcode.llm;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.ThinkingConfigEnabled;
import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** Anthropic Messages API adapter. */
public final class AnthropicClient implements LlmClient {

    private static final int QUEUE_CAPACITY = 256;
    private static final long DEFAULT_MAX_TOKENS = 8_192;
    private static final long THINKING_MAX_TOKENS = 16_384;
    private static final long THINKING_BUDGET_TOKENS = 8_192;

    private final com.anthropic.client.AnthropicClient client;
    private final String model;
    private final String systemPrompt;
    private final boolean thinking;

    public AnthropicClient(ProviderConfig provider, String systemPrompt) {
        var builder = AnthropicOkHttpClient.builder()
                .apiKey(provider.getApiKey())
                .maxRetries(0);
        if (provider.getBaseUrl() != null && !provider.getBaseUrl().isBlank()) {
            builder.baseUrl(provider.getBaseUrl());
        }
        this.client = builder.build();
        this.model = provider.getModel();
        this.systemPrompt = systemPrompt;
        this.thinking = provider.isThinking();
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
            MessageCreateParams.Builder params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(thinking ? THINKING_MAX_TOKENS : DEFAULT_MAX_TOKENS)
                    .system(systemPrompt)
                    .messages(buildMessages(messages));
            if (thinking) {
                params.thinking(ThinkingConfigEnabled.builder()
                        .budgetTokens(THINKING_BUDGET_TOKENS)
                        .build());
            }

            String stopReason = "end_turn";
            try (StreamResponse<RawMessageStreamEvent> response =
                         client.messages().createStreaming(params.build())) {
                var iterator = response.stream().iterator();
                while (iterator.hasNext()) {
                    RawMessageStreamEvent event = iterator.next();
                    if (event.isContentBlockDelta()) {
                        var delta = event.asContentBlockDelta().delta();
                        if (delta.isText()) {
                            queue.put(new StreamEvent.TextDelta(delta.asText().text()));
                        } else if (delta.isThinking()) {
                            queue.put(new StreamEvent.ThinkingDelta(delta.asThinking().thinking()));
                        }
                    } else if (event.isMessageDelta()) {
                        var reason = event.asMessageDelta().delta().stopReason();
                        if (reason.isPresent()) stopReason = reason.get().asString();
                    }
                }
            }
            queue.put(new StreamEvent.StreamEnd(stopReason));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            putError(queue, safeError(e));
        }
    }

    private static List<MessageParam> buildMessages(List<Message> messages) {
        var merged = new ArrayList<Message>();
        for (Message current : messages) {
            if (!merged.isEmpty() && merged.getLast().role().equals(current.role())) {
                Message previous = merged.removeLast();
                merged.add(new Message(previous.role(), previous.content() + "\n\n" + current.content()));
            } else {
                merged.add(current);
            }
        }

        var result = new ArrayList<MessageParam>();
        for (Message message : merged) {
            MessageParam.Role role = "assistant".equals(message.role())
                    ? MessageParam.Role.ASSISTANT
                    : MessageParam.Role.USER;
            result.add(MessageParam.builder()
                    .role(role)
                    .content(message.content())
                    .build());
        }
        return result;
    }

    private static String safeError(Exception error) {
        if (error instanceof com.anthropic.errors.UnauthorizedException) {
            return "Authentication failed. Check api_key.";
        }
        if (error instanceof com.anthropic.errors.RateLimitException) {
            return "Rate limited. Try again later.";
        }
        if (error instanceof com.anthropic.errors.NotFoundException) {
            return "Model or endpoint not found.";
        }
        if (error instanceof com.anthropic.errors.BadRequestException) {
            return "Request rejected. Check the model and conversation length.";
        }
        if (error instanceof com.anthropic.errors.AnthropicIoException) {
            return "Network error while contacting Anthropic.";
        }
        if (error instanceof com.anthropic.errors.AnthropicServiceException service) {
            return service.statusCode() == 413
                    ? "Conversation is too long for this model."
                    : "Anthropic API error (HTTP " + service.statusCode() + ").";
        }
        return "Unexpected Anthropic streaming error.";
    }

    private static void putError(BlockingQueue<StreamEvent> queue, String message) {
        try {
            queue.put(new StreamEvent.Error(message));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
