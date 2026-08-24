package com.mewcode.llm;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingBlockParam;
import com.anthropic.models.messages.ThinkingConfigEnabled;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ContentBlock;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.TextBlock;
import com.mewcode.conversation.ThinkingBlock;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.conversation.ToolUseBlock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Anthropic Messages API 适配器。 */
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
    public CancellableLlmStream openStream(List<Message> messages,
                                           List<Map<String, Object>> apiTools) {
        return openStream(messages, apiTools, systemPrompt);
    }

    @Override
    public CancellableLlmStream openStream(ConversationManager conversation,
                                           List<Map<String, Object>> apiTools,
                                           String prompt) {
        return openStream(conversation.getMessages(), apiTools, prompt);
    }

    private CancellableLlmStream openStream(List<Message> messages,
                                            List<Map<String, Object>> apiTools,
                                            String prompt) {
        var queue = new LinkedBlockingQueue<StreamEvent>(QUEUE_CAPACITY);
        List<Message> snapshot = messages == null ? List.of() : List.copyOf(messages);
        List<Map<String, Object>> tools = apiTools == null ? List.of() : List.copyOf(apiTools);
        var control = new StreamControl();
        Thread worker = Thread.startVirtualThread(() ->
                streamInCurrentThread(snapshot, tools, prompt, queue, control));
        control.worker(worker);
        return new CancellableLlmStream(queue, control::close);
    }

    @Override
    public BlockingQueue<StreamEvent> stream(List<Message> messages,
                                             List<Map<String, Object>> apiTools) {
        return openStream(messages, apiTools).events();
    }

    @Override
    public BlockingQueue<StreamEvent> stream(ConversationManager conversation,
                                             List<Map<String, Object>> apiTools) {
        return stream(conversation.getMessages(), apiTools);
    }

    private void streamInCurrentThread(List<Message> messages,
                                       List<Map<String, Object>> apiTools,
                                       String prompt,
                                       BlockingQueue<StreamEvent> queue,
                                       StreamControl control) {
        try {
            MessageCreateParams.Builder params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(thinking ? THINKING_MAX_TOKENS : DEFAULT_MAX_TOKENS)
                    .system(prompt == null ? systemPrompt : prompt)
                    .messages(buildMessages(messages));
            for (Map<String, Object> definition : apiTools) {
                params.addTool(toAnthropicTool(definition));
            }
            if (thinking) {
                params.thinking(ThinkingConfigEnabled.builder()
                        .budgetTokens(THINKING_BUDGET_TOKENS)
                        .build());
            }

            String stopReason = "end_turn";
            OptionalLong inputTokens = OptionalLong.empty();
            OptionalLong outputTokens = OptionalLong.empty();
            var accumulator = new ToolCallAccumulator();
            var blockIndexToId = new HashMap<Long, String>();
            try (StreamResponse<RawMessageStreamEvent> response =
                         client.messages().createStreaming(params.build())) {
                control.response(response);
                if (control.isClosed()) return;
                var iterator = response.stream().iterator();
                while (iterator.hasNext()) {
                    if (control.isClosed()) return;
                    RawMessageStreamEvent event = iterator.next();
                    if (event.isMessageStart()) {
                        var usage = event.asMessageStart().message().usage();
                        inputTokens = OptionalLong.of(usage.inputTokens());
                        outputTokens = OptionalLong.of(usage.outputTokens());
                    } else if (event.isContentBlockStart()) {
                        var block = event.asContentBlockStart().contentBlock();
                        if (block.isToolUse()) {
                            var toolUse = block.asToolUse();
                            String id = toolUse.id();
                            blockIndexToId.put(event.asContentBlockStart().index(), id);
                            accumulator.start(id, toolUse.name());
                        } else if (block.isThinking()) {
                            String signature = block.asThinking().signature();
                            if (!signature.isBlank()) {
                                putEvent(queue, new StreamEvent.ThinkingDelta("", signature));
                            }
                        }
                    } else if (event.isContentBlockDelta()) {
                        var deltaEvent = event.asContentBlockDelta();
                        var delta = deltaEvent.delta();
                        if (delta.isText()) {
                            putEvent(queue, new StreamEvent.TextDelta(delta.asText().text()));
                        } else if (delta.isThinking()) {
                            putEvent(queue, new StreamEvent.ThinkingDelta(delta.asThinking().thinking()));
                        } else if (delta.isSignature()) {
                            putEvent(queue, new StreamEvent.ThinkingDelta("",
                                    delta.asSignature().signature()));
                        } else if (delta.isInputJson()) {
                            String id = blockIndexToId.get(deltaEvent.index());
                            if (id != null) accumulator.append(id, delta.asInputJson().partialJson());
                        }
                    } else if (event.isContentBlockStop()) {
                        long index = event.asContentBlockStop().index();
                        String id = blockIndexToId.remove(index);
                        if (id != null) putEvent(queue, accumulator.finish(id));
                    } else if (event.isMessageDelta()) {
                        var reason = event.asMessageDelta().delta().stopReason();
                        if (reason.isPresent()) stopReason = reason.get().asString();
                        outputTokens = OptionalLong.of(event.asMessageDelta().usage().outputTokens());
                    }
                }
            }
            if (control.isClosed()) return;
            for (StreamEvent event : accumulator.finishAll()) putEvent(queue, event);
            if (inputTokens.isPresent() || outputTokens.isPresent()) {
                putEvent(queue, new StreamEvent.Usage(inputTokens, outputTokens));
            }
            putEvent(queue, new StreamEvent.StreamEnd(stopReason));
        } catch (InterruptedException error) {
            if (!control.isClosed()) Thread.currentThread().interrupt();
        } catch (Exception error) {
            if (!control.isClosed()) putError(queue, safeError(error));
        }
    }

    private static List<MessageParam> buildMessages(List<Message> messages) {
        var result = new ArrayList<MessageParam>();
        for (Message message : messages) {
            var blocks = new ArrayList<ContentBlockParam>();
            for (ContentBlock block : message.content()) {
                if (block instanceof TextBlock text) {
                    blocks.add(ContentBlockParam.ofText(TextBlockParam.builder()
                            .text(text.text())
                            .build()));
                } else if (block instanceof ToolUseBlock toolUse) {
                    var input = ToolUseBlockParam.Input.builder()
                            .additionalProperties(toAnthropicJsonMap(toolUse.arguments()))
                            .build();
                    blocks.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                            .id(toolUse.toolUseId())
                            .name(toolUse.toolName())
                            .input(input)
                            .build()));
                } else if (block instanceof ThinkingBlock thinkingBlock
                        && !thinkingBlock.signature().isBlank()) {
                    blocks.add(ContentBlockParam.ofThinking(ThinkingBlockParam.builder()
                            .thinking(thinkingBlock.text())
                            .signature(thinkingBlock.signature())
                            .build()));
                } else if (block instanceof ToolResultBlock toolResult) {
                    blocks.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(toolResult.toolUseId())
                            .content(toolResult.content())
                            .isError(toolResult.isError())
                            .build()));
                }
            }
            MessageParam.Role role = "assistant".equals(message.role())
                    ? MessageParam.Role.ASSISTANT
                    : MessageParam.Role.USER;
            result.add(MessageParam.builder()
                    .role(role)
                    .contentOfBlockParams(blocks)
                    .build());
        }
        return result;
    }

    private static Tool toAnthropicTool(Map<String, Object> definition) {
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) definition.get("input_schema");
        var schemaExtras = new HashMap<String, com.anthropic.core.JsonValue>();
        if (schema != null) {
            for (Map.Entry<String, Object> entry : schema.entrySet()) {
                if (!entry.getKey().equals("type")) {
                    schemaExtras.put(entry.getKey(), com.anthropic.core.JsonValue.from(entry.getValue()));
                }
            }
        }
        var inputSchema = Tool.InputSchema.builder()
                .type(com.anthropic.core.JsonValue.from(schema == null ? "object" : schema.get("type")))
                .additionalProperties(schemaExtras)
                .build();
        return Tool.builder()
                .name((String) definition.get("name"))
                .description((String) definition.get("description"))
                .inputSchema(inputSchema)
                .build();
    }

    private static Map<String, com.anthropic.core.JsonValue> toAnthropicJsonMap(
            Map<String, Object> values) {
        var result = new HashMap<String, com.anthropic.core.JsonValue>();
        if (values != null) {
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                result.put(entry.getKey(), com.anthropic.core.JsonValue.from(entry.getValue()));
            }
        }
        return result;
    }

    private static void putEvent(BlockingQueue<StreamEvent> queue, StreamEvent event)
            throws InterruptedException {
        queue.put(event);
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
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class StreamControl {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<AutoCloseable> response = new AtomicReference<>();
        private final AtomicReference<Thread> worker = new AtomicReference<>();

        private void worker(Thread thread) {
            worker.set(thread);
            if (closed.get()) thread.interrupt();
        }

        private void response(AutoCloseable value) {
            if (!response.compareAndSet(null, value)) return;
            if (closed.get()) closeQuietly(value);
        }

        private boolean isClosed() {
            return closed.get();
        }

        private void close() {
            if (!closed.compareAndSet(false, true)) return;
            AutoCloseable currentResponse = response.get();
            if (currentResponse != null) {
                Thread.startVirtualThread(() -> closeQuietly(currentResponse));
            }
            Thread thread = worker.get();
            if (thread != null) thread.interrupt();
        }

        private static void closeQuietly(AutoCloseable closeable) {
            if (closeable == null) return;
            try {
                closeable.close();
            } catch (Exception ignored) {
                // provider close 是 best-effort。
            }
        }
    }
}
