package com.mewcode.tool.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.subagent.SubAgentTaskManager;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolExecutionContext;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** 四个固定任务管理工具；任务记录只属于当前 Session。 */
public final class TaskTools {

  private TaskTools() {}

  public static List<Tool> createAll(
      SubAgentTaskManager manager, Supplier<String> sessionIdSupplier) {
    Objects.requireNonNull(manager, "manager");
    Objects.requireNonNull(sessionIdSupplier, "sessionIdSupplier");
    return List.of(
        new TaskListTool(manager, sessionIdSupplier),
        new TaskGetTool(manager, sessionIdSupplier),
        new TaskCreateTool(manager, sessionIdSupplier),
        new TaskUpdateTool(manager, sessionIdSupplier));
  }

  public static void registerAll(
      ToolRegistry registry, SubAgentTaskManager manager, Supplier<String> sessionIdSupplier) {
    for (Tool tool : createAll(manager, sessionIdSupplier)) registry.register(tool);
  }

  private abstract static class Base implements Tool {
    protected final SubAgentTaskManager manager;
    protected final Supplier<String> sessionIdSupplier;

    private Base(SubAgentTaskManager manager, Supplier<String> sessionIdSupplier) {
      this.manager = manager;
      this.sessionIdSupplier = sessionIdSupplier;
    }

    @Override
    public ToolCategory category() {
      return ToolCategory.SEARCH;
    }

    @Override
    public boolean isSystem() {
      return true;
    }

    @Override
    public boolean isReadOnly() {
      return true;
    }

    @Override
    public boolean isDestructive() {
      return false;
    }

    @Override
    public boolean isConcurrencySafe(Map<String, Object> input) {
      return true;
    }

    protected String sessionId() {
      String value = sessionIdSupplier.get();
      return value == null || value.isBlank() ? "unknown-session" : value;
    }

    protected static ToolResult json(Object value) {
      try {
        return ToolResult.success(new ObjectMapper().writeValueAsString(value));
      } catch (JsonProcessingException error) {
        return ToolResult.error("任务结果序列化失败。");
      }
    }
  }

  private static final class TaskListTool extends Base {
    private TaskListTool(SubAgentTaskManager manager, Supplier<String> sessionIdSupplier) {
      super(manager, sessionIdSupplier);
    }

    @Override
    public String name() {
      return "TaskList";
    }

    @Override
    public String description() {
      return "列出当前会话中已发布的后台子 Agent 和手工任务。";
    }

    @Override
    public Map<String, Object> inputSchema() {
      return objectSchema(Map.of(), List.of());
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
      return json(manager.list(sessionId()).stream().map(TaskTools::snapshotMap).toList());
    }

    @Override
    public String validateInput(Map<String, Object> input) {
      return null;
    }
  }

  private static final class TaskGetTool extends Base {
    private TaskGetTool(SubAgentTaskManager manager, Supplier<String> sessionIdSupplier) {
      super(manager, sessionIdSupplier);
    }

    @Override
    public String name() {
      return "TaskGet";
    }

    @Override
    public String description() {
      return "查询当前会话中一个任务的状态、结果、进度和用量。";
    }

    @Override
    public Map<String, Object> inputSchema() {
      return objectSchema(
          Map.of("task_id", Map.of("type", "string", "description", "任务 ID")), List.of("task_id"));
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
      String taskId = string(input, "task_id");
      var snapshot = manager.get(sessionId(), taskId);
      return snapshot
          .map(value -> json(snapshotMap(value)))
          .orElseGet(() -> ToolResult.error("不存在任务：" + (taskId == null ? "" : taskId)));
    }

    @Override
    public String validateInput(Map<String, Object> input) {
      return string(input, "task_id") == null ? "TaskGet 需要非空 task_id。" : null;
    }
  }

  private static final class TaskCreateTool extends Base {
    private TaskCreateTool(SubAgentTaskManager manager, Supplier<String> sessionIdSupplier) {
      super(manager, sessionIdSupplier);
    }

    @Override
    public String name() {
      return "TaskCreate";
    }

    @Override
    public String description() {
      return "创建一个当前会话的手工任务记录，不启动子 Agent。";
    }

    @Override
    public Map<String, Object> inputSchema() {
      return objectSchema(
          Map.of(
              "subject", Map.of("type", "string", "description", "任务标题"),
              "description", Map.of("type", "string", "description", "任务描述")),
          List.of("subject", "description"));
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
      return json(
          snapshotMap(
              manager.createManual(
                  sessionId(), string(input, "subject"), string(input, "description"))));
    }

    @Override
    public String validateInput(Map<String, Object> input) {
      String subjectError = required(input, "subject", "TaskCreate 需要非空 subject。");
      return subjectError != null
          ? subjectError
          : required(input, "description", "TaskCreate 需要非空 description。");
    }
  }

  private static final class TaskUpdateTool extends Base {
    private TaskUpdateTool(SubAgentTaskManager manager, Supplier<String> sessionIdSupplier) {
      super(manager, sessionIdSupplier);
    }

    @Override
    public String name() {
      return "TaskUpdate";
    }

    @Override
    public String description() {
      return "更新手工任务，或请求取消正在运行的后台子 Agent。";
    }

    @Override
    public Map<String, Object> inputSchema() {
      return objectSchema(
          Map.of(
              "task_id", Map.of("type", "string", "description", "任务 ID"),
              "status",
                  Map.of(
                      "type",
                      "string",
                      "enum",
                      List.of("PENDING", "RUNNING", "COMPLETED", "FAILED", "CANCELLED")),
              "subject", Map.of("type", "string"),
              "description", Map.of("type", "string")),
          List.of("task_id"));
    }

    @Override
    public boolean isReadOnly() {
      return false;
    }

    @Override
    public boolean isConcurrencySafe(Map<String, Object> input) {
      return false;
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
      String status = string(input, "status");
      SubAgentTaskManager.Status parsed =
          status == null ? null : SubAgentTaskManager.Status.valueOf(status.toUpperCase());
      var result =
          manager.update(
              sessionId(),
              new SubAgentTaskManager.TaskUpdate(
                  string(input, "task_id"),
                  parsed,
                  string(input, "subject"),
                  string(input, "description")));
      if (!result.accepted()) return ToolResult.error(result.message());
      return json(snapshotMap(result.snapshot()));
    }

    @Override
    public String validateInput(Map<String, Object> input) {
      if (string(input, "task_id") == null) return "TaskUpdate 需要非空 task_id。";
      String status = string(input, "status");
      if (status == null
          && string(input, "subject") == null
          && string(input, "description") == null) {
        return "TaskUpdate 至少需要一个更新字段。";
      }
      if (status != null) {
        try {
          SubAgentTaskManager.Status.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException error) {
          return "status 不是有效的任务状态。";
        }
      }
      return null;
    }
  }

  private static Map<String, Object> snapshotMap(SubAgentTaskManager.TaskSnapshot snapshot) {
    var result = new LinkedHashMap<String, Object>();
    result.put("task_id", snapshot.taskId());
    result.put("type", snapshot.type().name().toLowerCase());
    result.put("status", snapshot.status().name().toLowerCase());
    result.put("published", snapshot.published());
    result.put("subject", snapshot.subject());
    result.put("description", snapshot.description());
    result.put("started_at", instant(snapshot.startedAt()));
    result.put("ended_at", instant(snapshot.endedAt()));
    result.put("last_activity", instant(snapshot.lastActivity()));
    result.put("last_tool", snapshot.lastTool());
    result.put("tool_calls", snapshot.toolCalls());
    result.put("completed_tool_calls", snapshot.completedToolCalls());
    result.put("result", snapshot.result());
    result.put("error", snapshot.error());
    result.put("input_tokens", snapshot.inputTokens());
    result.put("output_tokens", snapshot.outputTokens());
    return result;
  }

  private static String instant(Instant value) {
    return value == null ? null : value.toString();
  }

  private static Map<String, Object> objectSchema(
      Map<String, Object> properties, List<String> required) {
    var result = new LinkedHashMap<String, Object>();
    result.put("type", "object");
    result.put("properties", properties);
    result.put("required", required);
    result.put("additionalProperties", false);
    return Map.copyOf(result);
  }

  private static String required(Map<String, Object> input, String field, String message) {
    String value = string(input, field);
    return value == null || value.isBlank() ? message : null;
  }

  private static String string(Map<String, Object> input, String name) {
    Object value = input == null ? null : input.get(name);
    return value instanceof String text ? text : null;
  }
}
