package com.mewcode.hook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.tool.support.CommandRunner;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 集中执行 Hook 的 shell、prompt、HTTP 和子 Agent 占位动作。 */
public final class HookActionExecutor {
  private static final int MAX_RESPONSE_CHARS = 20_000;
  private static final Pattern FIELD =
      Pattern.compile("\\$\\{([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*)}");
  private final CommandRunner commandRunner;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Consumer<String> diagnostics;

  public HookActionExecutor(CommandRunner commandRunner, HttpClient httpClient) {
    this(commandRunner, httpClient, System.err::println);
  }

  public HookActionExecutor(
      CommandRunner commandRunner, HttpClient httpClient, Consumer<String> diagnostics) {
    this.commandRunner = java.util.Objects.requireNonNull(commandRunner, "commandRunner");
    this.httpClient = java.util.Objects.requireNonNull(httpClient, "httpClient");
    this.diagnostics = diagnostics == null ? ignored -> {} : diagnostics;
  }

  public Optional<HookRejection> execute(HookRule rule, HookInvocation invocation)
      throws IOException, InterruptedException {
    if (!rule.event().equals(invocation.event())) {
      throw new IllegalArgumentException("rule event does not match invocation event");
    }
    return switch (rule.action()) {
      case HookAction.Shell shell -> executeShell(rule, invocation, shell);
      case HookAction.Prompt prompt -> {
        invocation.state().enqueuePrompt(prompt.text());
        yield Optional.empty();
      }
      case HookAction.Http http -> executeHttp(rule, invocation, http);
      case HookAction.Subagent ignored -> {
        diagnostics.accept("[hook subagent] not yet implemented, skipped: " + rule.name());
        yield Optional.empty();
      }
    };
  }

  private Optional<HookRejection> executeShell(
      HookRule rule, HookInvocation invocation, HookAction.Shell action) throws IOException {
    CommandRunner.ScriptResult result =
        commandRunner.runHook(
            action.command(),
            java.nio.file.Path.of((String) invocation.payload().get("cwd")),
            objectMapper.writeValueAsString(invocation.payload()),
            rule.timeout(),
            invocation.cancellation());
    if (result.cancelled() || invocation.cancellation().isCancelled()) {
      throw new IOException("shell hook cancelled");
    }
    if (result.timedOut()) throw new IOException("shell hook timed out");
    if (result.truncated()) throw new IOException("shell hook output exceeded limit");
    if (result.exitCode() == 0) return Optional.empty();
    if (result.exitCode() == 2 && rule.event().blocking()) {
      String reason = reason(result.stderr(), result.stdout());
      if (!reason.isBlank()) return Optional.of(new HookRejection(rule.name(), reason));
    }
    throw new IOException("shell hook failed");
  }

  private Optional<HookRejection> executeHttp(
      HookRule rule, HookInvocation invocation, HookAction.Http action)
      throws IOException, InterruptedException {
    boolean defaultBody = action.body().isEmpty();
    String body =
        defaultBody
            ? objectMapper.writeValueAsString(invocation.payload())
            : render(action.body().orElseThrow(), invocation.payload());
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(action.url())
            .timeout(rule.timeout())
            .method(
                action.method(), HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    boolean contentTypeConfigured = false;
    try {
      for (Map.Entry<String, String> header : action.headers().entrySet()) {
        builder.header(header.getKey(), header.getValue());
        if ("content-type".equalsIgnoreCase(header.getKey())) contentTypeConfigured = true;
      }
    } catch (IllegalArgumentException error) {
      throw new IOException("http hook header is invalid", error);
    }
    if (defaultBody && !contentTypeConfigured) builder.header("Content-Type", "application/json");

    CompletableFuture<HttpResponse<InputStream>> responseFuture =
        httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    HttpResponse<InputStream> response;
    try {
      response = await(responseFuture, rule.timeout(), invocation.cancellation());
    } catch (RuntimeException error) {
      responseFuture.cancel(true);
      throw error;
    }
    String responseBody;
    try (InputStream stream = response.body()) {
      CompletableFuture<String> bodyFuture =
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return readBounded(stream);
                } catch (IOException error) {
                  throw new java.util.concurrent.CompletionException(error);
                }
              });
      responseBody = await(bodyFuture, rule.timeout(), invocation.cancellation());
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException("http hook returned a non-success status");
    }
    Map<String, Object> result;
    try {
      result = objectMapper.readValue(responseBody, new TypeReference<>() {});
    } catch (RuntimeException error) {
      throw new IOException("http hook returned invalid JSON", error);
    }
    if (!invocation.event().blocking()) return Optional.empty();
    Object decision = result.get("decision");
    Object reason = result.get("reason");
    if ("block".equals(decision) && reason instanceof String text && !text.isBlank()) {
      return Optional.of(new HookRejection(rule.name(), text.stripTrailing()));
    }
    return Optional.empty();
  }

  private static String render(String template, Map<String, Object> payload) throws IOException {
    Matcher matcher = FIELD.matcher(template);
    int cursor = 0;
    while (true) {
      int start = template.indexOf("${", cursor);
      if (start < 0) break;
      matcher.region(start, template.length());
      if (!matcher.lookingAt()) throw new IOException("http hook body template is invalid");
      cursor = matcher.end();
    }

    matcher.reset();
    var rendered = new StringBuffer();
    while (matcher.find()) {
      Object value = field(payload, matcher.group(1));
      if (value == null || value instanceof Map<?, ?> || value instanceof Iterable<?>) {
        throw new IOException("http hook body field is missing or non-scalar");
      }
      if (!(value instanceof String || value instanceof Boolean || value instanceof Number)) {
        throw new IOException("http hook body field is non-scalar");
      }
      matcher.appendReplacement(rendered, Matcher.quoteReplacement(String.valueOf(value)));
    }
    matcher.appendTail(rendered);
    return rendered.toString();
  }

  private static Object field(Map<String, Object> payload, String path) {
    Object value = payload;
    for (String part : path.split("\\.")) {
      if (!(value instanceof Map<?, ?> map) || !map.containsKey(part)) return null;
      value = map.get(part);
    }
    return value;
  }

  private static String reason(String preferred, String fallback) {
    String text = preferred == null || preferred.isBlank() ? fallback : preferred;
    return text == null ? "" : text.stripTrailing();
  }

  private static String readBounded(InputStream input) throws IOException {
    var result = new StringBuilder();
    try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
      char[] buffer = new char[1024];
      int count;
      while ((count = reader.read(buffer)) >= 0) {
        result.append(buffer, 0, count);
        if (result.length() > MAX_RESPONSE_CHARS) {
          throw new IOException("http hook response exceeded limit");
        }
      }
    }
    return result.toString();
  }

  private static <T> T await(
      CompletableFuture<T> future,
      Duration timeout,
      com.mewcode.agent.CancellationToken cancellation)
      throws IOException, InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      if (cancellation.isCancelled()) {
        future.cancel(true);
        throw new IOException("http hook cancelled");
      }
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        future.cancel(true);
        throw new IOException("http hook timed out");
      }
      try {
        return future.get(
            Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
      } catch (TimeoutException ignored) {
        // 继续检查取消和统一截止时间。
      } catch (ExecutionException error) {
        Throwable cause = error.getCause();
        if (cause instanceof java.util.concurrent.CompletionException completion
            && completion.getCause() != null) cause = completion.getCause();
        if (cause instanceof IOException io) throw io;
        throw new IOException("http hook failed", cause);
      }
    }
  }
}
