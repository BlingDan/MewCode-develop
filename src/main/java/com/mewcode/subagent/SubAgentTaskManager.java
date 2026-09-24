package com.mewcode.subagent;

import com.mewcode.agent.AgentEvent;
import com.mewcode.agent.AgentRun;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 当前进程内的子 Agent 任务生命周期、取消和通知管理器。 */
public final class SubAgentTaskManager implements AutoCloseable {

  private static final int SUMMARY_LIMIT = 2_000;

  private final Map<String, TaskEntry> tasks = new LinkedHashMap<>();
  private final Map<String, List<TaskNotification>> notifications = new LinkedHashMap<>();
  private final AtomicLong sequence = new AtomicLong();
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private boolean closed;

  public synchronized TaskHandle start(TaskRequest request) {
    Objects.requireNonNull(request, "request");
    if (closed) throw new IllegalStateException("任务管理器已关闭");
    String id = "agent-" + sequence.incrementAndGet();
    var entry = new TaskEntry(id, request);
    entry.published = request.publishImmediately();
    tasks.put(id, entry);
    workers.submit(() -> run(entry));
    return new TaskHandle(id, entry.completion);
  }

  /** 将尚未发布的前台任务原子地转为后台任务。 */
  public synchronized Optional<String> publish(String sessionId, String taskId) {
    TaskEntry entry = findOwned(sessionId, taskId).orElse(null);
    if (entry == null || isTerminal(entry.status)) return Optional.empty();
    if (entry.published) return Optional.of(entry.id);
    entry.published = true;
    if (entry.denyPermissionsWhenBackground && entry.run != null) {
      entry.run.permissionBroker().close();
    }
    if (entry.onPublished != null) entry.onPublished.run();
    return Optional.of(entry.id);
  }

  public synchronized boolean isPublished(String sessionId, String taskId) {
    return findOwned(sessionId, taskId).map(entry -> entry.published).orElse(false);
  }

  public synchronized List<TaskSnapshot> list(String sessionId) {
    return tasks.values().stream()
        .filter(entry -> entry.sessionId.equals(sessionId) && entry.published)
        .map(TaskEntry::snapshot)
        .toList();
  }

  public synchronized Optional<TaskSnapshot> get(String sessionId, String taskId) {
    return findOwned(sessionId, taskId).filter(entry -> entry.published).map(TaskEntry::snapshot);
  }

  public synchronized TaskSnapshot createManual(
      String sessionId, String subject, String description) {
    if (closed) throw new IllegalStateException("任务管理器已关闭");
    String id = "task-" + sequence.incrementAndGet();
    var request =
        new TaskRequest(
            sessionId,
            TaskType.MANUAL,
            subject,
            description,
            false,
            false,
            () -> null,
            null,
            null,
            null);
    var entry = new TaskEntry(id, request);
    entry.published = true;
    tasks.put(id, entry);
    return entry.snapshot();
  }

  public synchronized UpdateResult update(String sessionId, TaskUpdate update) {
    Objects.requireNonNull(update, "update");
    TaskEntry entry = findOwned(sessionId, update.taskId()).orElse(null);
    if (entry == null || !entry.published) return UpdateResult.notFound(update.taskId());
    if (entry.type == TaskType.MANUAL) return updateManual(entry, update);
    if (update.status() != Status.CANCELLED) {
      return UpdateResult.rejected(update.taskId(), "运行中的子 Agent 只允许请求取消。");
    }
    entry.cancelRequested = true;
    if (entry.run != null) {
      entry.run.cancel();
    } else {
      finish(entry, Status.CANCELLED, "任务已取消。", "任务在启动前被取消。", true);
    }
    return UpdateResult.accepted(entry.snapshot());
  }

  /** 供父 Agent 取消钩子使用；前台任务尚未发布时也必须能收到取消信号。 */
  public synchronized boolean cancel(String sessionId, String taskId) {
    TaskEntry entry = findOwned(sessionId, taskId).orElse(null);
    if (entry == null || isTerminal(entry.status)) return false;
    entry.cancelRequested = true;
    if (entry.run != null) {
      entry.run.cancel();
    } else {
      finish(entry, Status.CANCELLED, "任务已取消。", "任务在启动前被取消。", entry.published);
    }
    return true;
  }

  public synchronized List<TaskNotification> drainNotifications(String sessionId) {
    List<TaskNotification> result = notifications.remove(sessionId);
    return result == null ? List.of() : List.copyOf(result);
  }

  /** 判断当前 Session 是否仍有已发布任务或待注入通知。 */
  public synchronized boolean hasWork(String sessionId) {
    if (sessionId == null) return false;
    if (notifications.containsKey(sessionId) && !notifications.get(sessionId).isEmpty())
      return true;
    return tasks.values().stream()
        .anyMatch(
            entry ->
                entry.sessionId.equals(sessionId) && entry.published && !isTerminal(entry.status));
  }

  public synchronized void cancelSession(String sessionId) {
    for (TaskEntry entry : tasks.values()) {
      if (!entry.sessionId.equals(sessionId) || isTerminal(entry.status)) continue;
      entry.cancelRequested = true;
      if (entry.run != null) entry.run.cancel();
      else finish(entry, Status.CANCELLED, "任务已取消。", "任务在启动前被取消。", false);
    }
    notifications.remove(sessionId);
  }

  public synchronized void cancelAll() {
    for (String sessionId :
        tasks.values().stream().map(entry -> entry.sessionId).distinct().toList()) {
      cancelSession(sessionId);
    }
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    for (TaskEntry entry : tasks.values()) {
      if (!isTerminal(entry.status)) {
        entry.cancelRequested = true;
        if (entry.run != null) entry.run.cancel();
        else finish(entry, Status.CANCELLED, "任务已取消。", "任务在关闭时被取消。", false);
      }
    }
    notifications.clear();
    workers.shutdownNow();
  }

  private void run(TaskEntry entry) {
    try {
      synchronized (this) {
        if (entry.cancelRequested) {
          finish(entry, Status.CANCELLED, "任务已取消。", "任务在启动前被取消。", entry.published);
          return;
        }
        entry.status = Status.RUNNING;
        entry.startedAt = Instant.now();
        entry.lastActivity = entry.startedAt;
      }
      AgentRun run = entry.starter.get();
      synchronized (this) {
        entry.run = run;
        if (entry.published && entry.denyPermissionsWhenBackground) run.permissionBroker().close();
      }
      if (run == null) {
        synchronized (this) {
          finish(entry, Status.FAILED, "子 Agent 启动失败。", "子 Agent 未能启动。", entry.published);
        }
        return;
      }
      consume(entry, run);
    } catch (Throwable error) {
      synchronized (this) {
        finish(
            entry,
            entry.cancelRequested ? Status.CANCELLED : Status.FAILED,
            entry.cancelRequested ? "任务已取消。" : "子 Agent 执行失败。",
            safeMessage(error),
            entry.published);
      }
    }
  }

  private void consume(TaskEntry entry, AgentRun run) throws InterruptedException {
    while (true) {
      AgentEvent event = run.events().poll(50, TimeUnit.MILLISECONDS);
      if (event == null) {
        if (run.state() == AgentRun.State.CANCELLED) {
          synchronized (this) {
            finish(entry, Status.CANCELLED, "任务已取消。", "任务收到取消请求。", entry.published);
          }
          return;
        }
        if (!run.events().isClosed()) continue;
        synchronized (this) {
          if (!isTerminal(entry.status)) {
            finish(
                entry,
                entry.cancelRequested ? Status.CANCELLED : Status.FAILED,
                entry.cancelRequested ? "任务已取消。" : "子 Agent 未正常结束。",
                "事件流提前结束。",
                entry.published);
          }
        }
        return;
      }
      synchronized (this) {
        entry.lastActivity = Instant.now();
        if (event instanceof AgentEvent.StreamText text) {
          entry.result.append(text.text());
        } else if (event instanceof AgentEvent.TurnComplete) {
          entry.awaitingToolRound = true;
        } else if (event instanceof AgentEvent.ToolUse tool) {
          if (entry.awaitingToolRound) {
            entry.result.setLength(0);
            entry.awaitingToolRound = false;
          }
          entry.lastTool = tool.toolName();
          entry.toolCalls++;
        } else if (event instanceof AgentEvent.ToolResult result) {
          entry.completedToolCalls++;
          entry.lastTool = result.toolName();
        } else if (event instanceof AgentEvent.PermissionRequested
            && !entry.published
            && entry.onEvent != null) {
          entry.onEvent.accept(event);
        } else if (event instanceof AgentEvent.Usage usage) {
          usage.inputTokens().ifPresent(value -> entry.inputTokens = value);
          usage.outputTokens().ifPresent(value -> entry.outputTokens = value);
        } else if (event instanceof AgentEvent.Error error) {
          entry.lastError = error.message();
        } else if (event instanceof AgentEvent.LoopComplete) {
          Status status =
              entry.cancelRequested || run.state() == AgentRun.State.CANCELLED
                  ? Status.CANCELLED
                  : entry.lastError == null ? Status.COMPLETED : Status.FAILED;
          finish(
              entry,
              status,
              status == Status.COMPLETED ? entry.result.toString() : safeResult(entry.lastError),
              entry.lastError,
              entry.published);
          return;
        }
      }
    }
  }

  private UpdateResult updateManual(TaskEntry entry, TaskUpdate update) {
    if (update.status() != null && !isManualTransitionAllowed(entry.status, update.status())) {
      return UpdateResult.rejected(update.taskId(), "手工任务状态转换不合法。");
    }
    if (update.subject() != null) entry.subject = update.subject();
    if (update.description() != null) entry.description = update.description();
    if (update.status() != null) entry.status = update.status();
    if (isTerminal(entry.status) && entry.endedAt == null) entry.endedAt = Instant.now();
    return UpdateResult.accepted(entry.snapshot());
  }

  private void finish(TaskEntry entry, Status status, String result, String error, boolean notify) {
    if (isTerminal(entry.status)) return;
    entry.status = status;
    entry.resultText = result == null ? "" : result;
    entry.error = error == null ? "" : safeResult(error);
    entry.endedAt = Instant.now();
    entry.lastActivity = entry.endedAt;
    if (notify
        && entry.published
        && entry.type != TaskType.MANUAL
        && !entry.notificationPublished) {
      entry.notificationPublished = true;
      notifications
          .computeIfAbsent(entry.sessionId, ignored -> new ArrayList<>())
          .add(new TaskNotification(entry.id, entry.status, notificationXml(entry)));
    }
    entry.completion.complete(entry.snapshot());
    if (!entry.cleanupCalled && entry.onFinished != null) {
      entry.cleanupCalled = true;
      try {
        entry.onFinished.run();
      } catch (RuntimeException ignored) {
        // 资源清理失败不能改变已经发布的终态。
      }
    }
  }

  private Optional<TaskEntry> findOwned(String sessionId, String taskId) {
    if (sessionId == null || taskId == null) return Optional.empty();
    TaskEntry entry = tasks.get(taskId);
    return entry != null && entry.sessionId.equals(sessionId)
        ? Optional.of(entry)
        : Optional.empty();
  }

  private static boolean isTerminal(Status status) {
    return status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED;
  }

  private static boolean isManualTransitionAllowed(Status from, Status to) {
    return switch (from) {
      case PENDING -> to == Status.RUNNING || to == Status.CANCELLED;
      case RUNNING -> isTerminal(to);
      case COMPLETED, FAILED, CANCELLED -> false;
    };
  }

  private static String notificationXml(TaskEntry entry) {
    String summary = safeResult(entry.status == Status.COMPLETED ? entry.resultText : entry.error);
    return "<task-notification>"
        + "<task-id>"
        + escape(entry.id)
        + "</task-id>"
        + "<status>"
        + entry.status.name().toLowerCase()
        + "</status>"
        + "<summary>"
        + escape(summary)
        + "</summary>"
        + "<usage input-tokens=\""
        + entry.inputTokens
        + "\" output-tokens=\""
        + entry.outputTokens
        + "\" />"
        + "</task-notification>";
  }

  private static String safeResult(String value) {
    if (value == null || value.isBlank()) return "";
    String text = value.replaceAll("(?is)<task-notification.*?</task-notification>", "");
    text =
        text.replaceAll(
            "(?is)<(?:system|system-prompt|tool[-_ ]?input|stack[-_ ]?trace|credential|secret)[^>]*>.*?</(?:system|system-prompt|tool[-_ ]?input|stack[-_ ]?trace|credential|secret)>",
            "[已省略内部内容]");
    text =
        text.replaceAll(
            "(?i)(api[-_ ]?key|authorization|password|secret|token)\\s*[:=]\\s*[^\\s,;]+",
            "$1=[已省略]");
    text = text.replaceAll("(?i)\\bsk-[A-Za-z0-9_-]+\\b", "[已省略]");
    return text.length() <= SUMMARY_LIMIT ? text : text.substring(0, SUMMARY_LIMIT) + "…";
  }

  private static String escape(String value) {
    return safeResult(value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }

  private static String safeMessage(Throwable error) {
    return error == null || error.getMessage() == null || error.getMessage().isBlank()
        ? "内部错误"
        : error.getMessage();
  }

  public enum TaskType {
    DEFINITION,
    FORK,
    MANUAL
  }

  public enum Status {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
  }

  public record TaskRequest(
      String sessionId,
      TaskType type,
      String subject,
      String description,
      boolean publishImmediately,
      boolean denyPermissionsWhenBackground,
      Supplier<AgentRun> starter,
      Runnable onPublished,
      Runnable onFinished,
      Consumer<AgentEvent> onEvent) {
    public TaskRequest {
      sessionId = requireText(sessionId, "sessionId");
      type = Objects.requireNonNull(type, "type");
      subject = requireText(subject, "subject");
      description = requireText(description, "description");
      starter = Objects.requireNonNull(starter, "starter");
    }
  }

  public record TaskHandle(String taskId, CompletableFuture<TaskSnapshot> completion) {}

  public record TaskUpdate(String taskId, Status status, String subject, String description) {
    public TaskUpdate {
      taskId = requireText(taskId, "taskId");
    }
  }

  public record UpdateResult(
      boolean accepted, boolean found, String message, TaskSnapshot snapshot) {
    static UpdateResult notFound(String taskId) {
      return new UpdateResult(false, false, "不存在任务：" + taskId, null);
    }

    static UpdateResult rejected(String taskId, String message) {
      return new UpdateResult(false, true, message, null);
    }

    static UpdateResult accepted(TaskSnapshot snapshot) {
      return new UpdateResult(true, true, "ok", snapshot);
    }
  }

  public record TaskSnapshot(
      String taskId,
      String sessionId,
      TaskType type,
      Status status,
      boolean published,
      String subject,
      String description,
      Instant startedAt,
      Instant endedAt,
      Instant lastActivity,
      String lastTool,
      int toolCalls,
      int completedToolCalls,
      String result,
      String error,
      long inputTokens,
      long outputTokens) {}

  public record TaskNotification(String taskId, Status status, String content) {}

  private static final class TaskEntry {
    private final String id;
    private final String sessionId;
    private final TaskType type;
    private final CompletableFuture<TaskSnapshot> completion = new CompletableFuture<>();
    private final Supplier<AgentRun> starter;
    private final boolean denyPermissionsWhenBackground;
    private final Runnable onPublished;
    private final Runnable onFinished;
    private final Consumer<AgentEvent> onEvent;
    private final StringBuilder result = new StringBuilder();
    private String subject;
    private String description;
    private Status status = Status.PENDING;
    private boolean published;
    private boolean cancelRequested;
    private boolean notificationPublished;
    private boolean cleanupCalled;
    private AgentRun run;
    private Instant startedAt;
    private Instant endedAt;
    private Instant lastActivity;
    private String lastTool = "";
    private int toolCalls;
    private int completedToolCalls;
    private String lastError;
    private String error = "";
    private String resultText = "";
    private long inputTokens;
    private long outputTokens;
    private boolean awaitingToolRound;

    private TaskEntry(String id, TaskRequest request) {
      this.id = id;
      this.sessionId = request.sessionId();
      this.type = request.type();
      this.subject = request.subject();
      this.description = request.description();
      this.starter = request.starter();
      this.denyPermissionsWhenBackground = request.denyPermissionsWhenBackground();
      this.onPublished = request.onPublished();
      this.onFinished = request.onFinished();
      this.onEvent = request.onEvent();
    }

    private TaskSnapshot snapshot() {
      return new TaskSnapshot(
          id,
          sessionId,
          type,
          status,
          published,
          subject,
          description,
          startedAt,
          endedAt,
          lastActivity,
          lastTool,
          toolCalls,
          completedToolCalls,
          resultText.isBlank() ? result.toString() : resultText,
          error,
          inputTokens,
          outputTokens);
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + "不能为空");
    return value.strip();
  }
}
