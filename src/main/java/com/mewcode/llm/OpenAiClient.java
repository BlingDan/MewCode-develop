package com.mewcode.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ContentBlock;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.TextBlock;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.conversation.ToolUseBlock;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** OpenAI Chat Completions 及兼容端点的流式适配器。 */
public final class OpenAiClient implements LlmClient {

    private static final int QUEUE_CAPACITY = 256;
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    public BlockingQueue<StreamEvent> stream(List<Message> messages,
                                             List<Map<String, Object>> apiTools) {
        var queue = new LinkedBlockingQueue<StreamEvent>(QUEUE_CAPACITY);
        List<Message> snapshot = messages == null ? List.of() : List.copyOf(messages);
        List<Map<String, Object>> tools = apiTools == null ? List.of() : List.copyOf(apiTools);
        Thread.startVirtualThread(() -> streamInCurrentThread(snapshot, tools, queue));
        return queue;
    }

    @Override
    public BlockingQueue<StreamEvent> stream(ConversationManager conversation,
                                             List<Map<String, Object>> apiTools) {
        return stream(conversation.getMessages(), apiTools);
    }

    private void streamInCurrentThread(List<Message> messages,
                                       List<Map<String, Object>> apiTools,
                                       BlockingQueue<StreamEvent> queue) {
        try {
            var params = ChatCompletionCreateParams.builder()
                    .model(model)
                    .addSystemMessage(systemPrompt);
            for (Map<String, Object> definition : apiTools) {
                params.addTool(toOpenAiTool(definition));
            }
            for (Message message : messages) {
                addMessage(params, message);
            }

            var accumulator = new ToolCallAccumulator();
            var indexToId = new HashMap<Long, String>();
            try (StreamResponse<ChatCompletionChunk> response =
                         client.chat().completions().createStreaming(params.build())) {
                response.stream()
                        .flatMap(chunk -> chunk.choices().stream())
                        .forEach(choice -> {
                            choice.delta().content().ifPresent(text -> putEvent(queue,
                                    new StreamEvent.TextDelta(text)));
                            choice.delta().toolCalls().ifPresent(toolCalls -> {
                                for (var toolCall : toolCalls) {
                                    long index = toolCall.index();
                                    String id = toolCall.id().orElse(indexToId.get(index));
                                    if (id == null || id.isBlank()) id = "openai-tool-" + index;
                                    indexToId.put(index, id);
                                    String name = toolCall.function()
                                            .flatMap(function -> function.name())
                                            .orElse("");
                                    if (!accumulator.has(id)) accumulator.start(id, name);
                                    final String callId = id;
                                    toolCall.function().flatMap(function -> function.arguments())
                                            .ifPresent(arguments -> accumulator.append(callId, arguments));
                                }
                            });
                        });
            }
            for (StreamEvent event : accumulator.finishAll()) putEvent(queue, event);
            putEvent(queue, new StreamEvent.StreamEnd("end_turn"));
        } catch (Exception error) {
            putError(queue, safeError(error));
        }
    }

    private static void addMessage(ChatCompletionCreateParams.Builder params, Message message) {
        if ("assistant".equals(message.role())) {
            var assistant = ChatCompletionAssistantMessageParam.builder();
            String text = message.textContent();
            if (!text.isEmpty()) assistant.content(text);
            for (ContentBlock block : message.content()) {
                if (block instanceof ToolUseBlock toolUse) {
                    var function = ChatCompletionMessageFunctionToolCall.Function.builder()
                            .name(toolUse.toolName())
                            .arguments(writeJson(toolUse.arguments()))
                            .build();
                    var call = ChatCompletionMessageFunctionToolCall.builder()
                            .id(toolUse.toolUseId())
                            .function(function)
                            .build();
                    assistant.addToolCall(ChatCompletionMessageToolCall.ofFunction(call));
                }
            }
            params.addMessage(assistant.build());
            return;
        }

        StringBuilder userText = new StringBuilder();
        for (ContentBlock block : message.content()) {
            if (block instanceof TextBlock text) userText.append(text.text());
        }
        if (!userText.isEmpty()) params.addUserMessage(userText.toString());
        for (ContentBlock block : message.content()) {
            if (block instanceof ToolResultBlock result) {
                params.addMessage(ChatCompletionToolMessageParam.builder()
                        .toolCallId(result.toolUseId())
                        .content(result.content())
                        .build());
            }
        }
    }

    private static ChatCompletionTool toOpenAiTool(Map<String, Object> definition) {
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) definition.get("function");
        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");
        var functionParameters = FunctionParameters.builder()
                .additionalProperties(toOpenAiJsonMap(parameters))
                .build();
        var functionDefinition = FunctionDefinition.builder()
                .name((String) function.get("name"))
                .description((String) function.get("description"))
                .parameters(functionParameters)
                .build();
        return ChatCompletionTool.ofFunction(ChatCompletionFunctionTool.builder()
                .function(functionDefinition)
                .build());
    }

    private static Map<String, com.openai.core.JsonValue> toOpenAiJsonMap(Map<String, Object> values) {
        var result = new HashMap<String, com.openai.core.JsonValue>();
        if (values != null) {
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                result.put(entry.getKey(), com.openai.core.JsonValue.from(entry.getValue()));
            }
        }
        return result;
    }

    private static String writeJson(Map<String, Object> value) {
        try {
            return MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception error) {
            return "{}";
        }
    }

    private static void putEvent(BlockingQueue<StreamEvent> queue, StreamEvent event) {
        try {
            queue.put(event);
        } catch (InterruptedException error) {
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
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class StreamInterruptedException extends RuntimeException {
    }
}
