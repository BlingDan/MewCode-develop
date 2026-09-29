package com.mewcode.tool.impl;

import com.mewcode.tool.*;
import com.mewcode.worktree.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 主会话的串行生命周期入口；模型意图不能构造可信丢弃授权。 */
public final class WorktreeTool implements Tool {
  public static final String NAME = "Worktree";
  private final AgentWorkspace workspace;
  private final Map<Map<String, Object>, String> userDiscards =
      java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

  public WorktreeTool(AgentWorkspace workspace) {
    this.workspace = workspace;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "管理独立 Git 工作树。create 仅创建，enter 才切换；exit 默认保留。delete 或 exit(delete=true) 默认保护修改、新增提交、未知状态和占用。discardChanges 仅表达意图，必须有目标明确的可信用户命令授权。";
  }

  @Override
  public ToolCategory category() {
    return ToolCategory.SHELL;
  }

  @Override
  public Map<String, Object> inputSchema() {
    return Map.of(
        "type",
        "object",
        "properties",
        Map.of(
            "action",
                Map.of(
                    "type", "string", "enum", List.of("create", "enter", "exit", "list", "delete")),
            "name", Map.of("type", "string", "description", "安全工作树名称，可用斜杠嵌套"),
            "delete", Map.of("type", "boolean", "default", false),
            "discardChanges", Map.of("type", "boolean", "default", false)),
        "required",
        List.of("action"),
        "additionalProperties",
        false);
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @Override
  public boolean isDestructive() {
    return true;
  }

  @Override
  public boolean isConcurrencySafe(Map<String, Object> input) {
    return false;
  }

  @Override
  public String validateInput(ToolExecutionContext context, Map<String, Object> input) {
    if (input == null
        || !(input.get("action") instanceof String action)
        || !Set.of("create", "enter", "exit", "list", "delete").contains(action))
      return "action 必须为 create/enter/exit/list/delete。";
    if (!Set.of("action", "name", "delete", "discardChanges").containsAll(input.keySet()))
      return "Worktree 不接受路径、来源、基线或授权字段。";
    for (String field : List.of("delete", "discardChanges"))
      if (input.containsKey(field) && !(input.get(field) instanceof Boolean))
        return field + " 必须是布尔值。";
    boolean named = Set.of("create", "enter", "delete").contains(action);
    if (named) {
      if (!(input.get("name") instanceof String name)) return "此动作需要 name。";
      try {
        SlugValidator.validate(name);
      } catch (IllegalArgumentException error) {
        return "name 不符合安全名称规则。";
      }
    } else if (input.containsKey("name")) return "exit/list 不能指定 name。";
    if (input.containsKey("delete") && !"exit".equals(action)) return "delete 字段仅适用于 exit。";
    if (Boolean.TRUE.equals(input.get("discardChanges"))
        && !("delete".equals(action)
            || "exit".equals(action) && Boolean.TRUE.equals(input.get("delete"))))
      return "丢弃意图必须有明确删除目标。";
    return null;
  }

  /** 授权绑定不可变调用对象及资源 UUID，来源仅为直接用户命令。 */
  public AutoCloseable authorizeUserDiscard(Map<String, Object> arguments, String recordId) {
    if (!Boolean.TRUE.equals(arguments.get("discardChanges")) || recordId == null)
      throw new IllegalArgumentException("丢弃授权缺少目标");
    userDiscards.put(arguments, recordId);
    return () -> userDiscards.remove(arguments);
  }

  @Override
  public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
    String invalid = validateInput(context, input);
    if (invalid != null) return ToolResult.error(invalid);
    String action = (String) input.get("action");
    String name = (String) input.get("name");
    String discardId =
        Boolean.TRUE.equals(input.get("discardChanges")) ? userDiscards.get(input) : null;
    if (Boolean.TRUE.equals(input.get("discardChanges")) && discardId == null)
      return ToolResult.error(
          "丢弃修改需要用户对本次目标明确授权，请使用 /worktree delete <name> --discard 或 /worktree exit --delete --discard。");
    var manager = workspace.manager();
    try {
      context.cancellationToken().throwIfCancelled();
      return switch (action) {
        case "create" -> {
          var created = manager.create(context.projectRoot(), name, context.cancellationToken());
          String warnings = String.join("\n", manager.warnings(name));
          yield ToolResult.success(
              "已创建工作树，尚未进入。\n路径："
                  + created.path()
                  + "\n分支："
                  + created.branch()
                  + (warnings.isBlank() ? "" : "\n" + warnings));
        }
        case "enter" -> {
          var entered = manager.enter(workspace, name);
          yield ToolResult.success(
              "已进入工作树。\n路径："
                  + entered.worktreePath()
                  + "\n分支："
                  + entered.worktreeBranch()
                  + "\n后续文件操作使用此目录，编辑前重新读取。");
        }
        case "list" ->
            ToolResult.success(
                "当前目录：" + workspace.currentCwd() + "\n" + manager.describeResources());
        case "exit" -> {
          boolean delete = Boolean.TRUE.equals(input.get("delete"));
          var prepared =
              discardId == null
                  ? manager.prepareExit(workspace, delete, context.cancellationToken())
                  : manager.prepareExitFromUserCommand(
                      workspace, discardId, context.cancellationToken());
          yield ToolResult.success("prepared：退出已准备，等待 Post Hook 收口后提交。")
              .withMetadata(
                  Map.of("status", "prepared", WorktreeManager.FINALIZATION_KEY, prepared));
        }
        case "delete" -> {
          if (workspace
              .currentSession()
              .filter(session -> session.worktreeName().equals(name))
              .isPresent()) {
            var prepared =
                discardId == null
                    ? manager.prepareExit(workspace, true, context.cancellationToken())
                    : manager.prepareExitFromUserCommand(
                        workspace, discardId, context.cancellationToken());
            yield ToolResult.success("prepared：当前目录删除已准备，等待 Post Hook 收口。")
                .withMetadata(
                    Map.of("status", "prepared", WorktreeManager.FINALIZATION_KEY, prepared));
          }
          if (discardId == null) manager.remove(name, context.cancellationToken());
          else manager.discardFromUserCommand(name, discardId, context.cancellationToken());
          yield ToolResult.success("已删除工作树及分支：" + name);
        }
        default -> ToolResult.error("未知 Worktree action：" + action);
      };
    } catch (WorktreeException error) {
      return ToolResult.error(error.getMessage());
    } catch (RuntimeException error) {
      return ToolResult.error("Worktree 操作失败，目录及成果保留。请查看 /worktree list。");
    }
  }
}
