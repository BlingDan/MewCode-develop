package com.mewcode.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ContentBlock;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.TextBlock;
import com.mewcode.conversation.ThinkingBlock;
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
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** OpenAI Chat Completions 及兼容端点的流式适配器。 */
public final class OpenAiClient implements LlmClient {

  private static final int QUEUE_CAPACITY = 256;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final com.openai.client.OpenAIClient client;
  private final String model;
  private final String systemPrompt;

  public OpenAiClient(ProviderConfig provider, String systemPrompt) {
    var builder = OpenAIOkHttpClient.builder().apiKey(provider.getApiKey()).maxRetries(0);
    if (provider.getBaseUrl() != null && !provider.getBaseUrl().isBlank()) {
      builder.baseUrl(provider.getBaseUrl());
    }
    this.client = builder.build();
    this.model = provider.getModel();
    this.systemPrompt = systemPrompt;
  }

  @Override
  public CancellableLlmStream openStream(
      List<Message> messages, List<Map<String, Object>> apiTools) {
    return openStream(messages, apiTools, systemPrompt);
  }

  @Override
  public CancellableLlmStream openStream(
      ConversationManager conversation, List<Map<String, Object>> apiTools, String prompt) {
    return openStream(conversation.getMessages(), apiTools, prompt);
  }

  /** 创建后台 worker，把 SDK 的同步 SSE 迭代转换为可取消事件流。 */
  private CancellableLlmStream openStream(
      List<Message> messages, List<Map<String, Object>> apiTools, String prompt) {
    var queue = new LinkedBlockingQueue<StreamEvent>(QUEUE_CAPACITY);
    List<Message> snapshot = messages == null ? List.of() : List.copyOf(messages);
    List<Map<String, Object>> tools = apiTools == null ? List.of() : List.copyOf(apiTools);
    var control = new StreamControl();
    Thread worker =
        Thread.startVirtualThread(
            () -> streamInCurrentThread(snapshot, tools, prompt, queue, control));
    control.worker(worker);
    return new CancellableLlmStream(queue, control::close);
  }

  @Override
  public BlockingQueue<StreamEvent> stream(
      List<Message> messages, List<Map<String, Object>> apiTools) {
    return openStream(messages, apiTools).events();
  }

  @Override
  public BlockingQueue<StreamEvent> stream(
      ConversationManager conversation, List<Map<String, Object>> apiTools) {
    return stream(conversation.getMessages(), apiTools);
  }

  /** 在 worker 线程中消费 Chat Completions；取消同时关闭响应和中断线程。 */
  private void streamInCurrentThread(
      List<Message> messages,
      List<Map<String, Object>> apiTools,
      String prompt,
      BlockingQueue<StreamEvent> queue,
      StreamControl control) {
    try {
      var params =
          ChatCompletionCreateParams.builder()
              .model(model)
              .streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build())
              .addSystemMessage(prompt == null ? systemPrompt : prompt);
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
        control.response(response);
        if (control.isClosed()) return;
        response.stream()
            .forEach(
                chunk -> {
                  if (control.isClosed()) return;
                  chunk
                      .usage()
                      .ifPresent(
                          usage ->
                              putEvent(
                                  queue,
                                  new StreamEvent.Usage(
                                      OptionalLong.of(usage.promptTokens()),
                                      OptionalLong.of(usage.completionTokens()))));
                  chunk
                      .choices()
                      .forEach(
                          choice -> {
                            choice
                                .delta()
                                .content()
                                .ifPresent(
                                    text -> putEvent(queue, new StreamEvent.TextDelta(text)));
                            String reasoning =
                                additionalString(
                                    choice
                                        .delta()
                                        ._additionalProperties()
                                        .get("reasoning_content"));
                            if (!reasoning.isBlank()) {
                              putEvent(queue, new StreamEvent.ThinkingDelta(reasoning));
                            }
                            choice
                                .delta()
                                .toolCalls()
                                .ifPresent(
                                    toolCalls -> {
                                      for (var toolCall : toolCalls) {
                                        long index = toolCall.index();
                                        String id = toolCall.id().orElse(indexToId.get(index));
                                        if (id == null || id.isBlank()) id = "openai-tool-" + index;
                                        indexToId.put(index, id);
                                        String name =
                                            toolCall
                                                .function()
                                                .flatMap(function -> function.name())
                                                .orElse("");
                                        if (!accumulator.has(id)) accumulator.start(id, name);
                                        final String callId = id;
                                        toolCall
                                            .function()
                                            .flatMap(function -> function.arguments())
                                            .ifPresent(
                                                arguments -> accumulator.append(callId, arguments));
                                      }
                                    });
                          });
                });
      }
      if (control.isClosed()) return;
      for (StreamEvent event : accumulator.finishAll()) putEvent(queue, event);
      putEvent(queue, new StreamEvent.StreamEnd("end_turn"));
    } catch (Exception error) {
      if (!control.isClosed()) putError(queue, safeError(error));
    }
  }

  /** 将 provider 无关消息转换为 OpenAI assistant/user/tool 消息。 */
  private static void addMessage(ChatCompletionCreateParams.Builder params, Message message) {
    if ("assistant".equals(message.role())) {
      var assistant = ChatCompletionAssistantMessageParam.builder();
      String text = message.textContent();
      if (!text.isEmpty()) assistant.content(text);
      for (ContentBlock block : message.content()) {
        if (block instanceof ToolUseBlock toolUse) {
          var function =
              ChatCompletionMessageFunctionToolCall.Function.builder()
                  .name(toolUse.toolName())
                  .arguments(writeJson(toolUse.arguments()))
                  .build();
          var call =
              ChatCompletionMessageFunctionToolCall.builder()
                  .id(toolUse.toolUseId())
                  .function(function)
                  .build();
          assistant.addToolCall(ChatCompletionMessageToolCall.ofFunction(call));
        }
      }
      String reasoning =
          message.content().stream()
              .filter(ThinkingBlock.class::isInstance)
              .map(ThinkingBlock.class::cast)
              .map(ThinkingBlock::text)
              .filter(value -> !value.isBlank())
              .reduce("", String::concat);
      if (!reasoning.isBlank()) {
        assistant.putAdditionalProperty(
            "reasoning_content", com.openai.core.JsonValue.from(reasoning));
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
        params.addMessage(
            ChatCompletionToolMessageParam.builder()
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
    var functionParameters =
        FunctionParameters.builder().additionalProperties(toOpenAiJsonMap(parameters)).build();
    var functionDefinition =
        FunctionDefinition.builder()
            .name((String) function.get("name"))
            .description((String) function.get("description"))
            .parameters(functionParameters)
            .build();
    return ChatCompletionTool.ofFunction(
        ChatCompletionFunctionTool.builder().function(functionDefinition).build());
  }

  private static Map<String, com.openai.core.JsonValue> toOpenAiJsonMap(
      Map<String, Object> values) {
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

  private static String additionalString(com.openai.core.JsonValue value) {
    if (value == null) return "";
    try {
      String result = value.convert(String.class);
      return result == null ? "" : result;
    } catch (RuntimeException ignored) {
      return "";
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

  private static final class StreamInterruptedException extends RuntimeException {}

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
