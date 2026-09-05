package com.mewcode.skill;

import com.mewcode.agent.AgentEvent;
import com.mewcode.agent.AgentMode;
import com.mewcode.agent.AgentRun;
import com.mewcode.agent.AgentTurnCoordinator;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.TextBlock;
import com.mewcode.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;

/** shared 激活与 fork 临时运行的具体执行器。 */
public final class SkillExecutor {

  private SkillExecutor() {}

  /** 按完整用户轮次截取 fork 初始历史，避免产生孤立工具结果。 */
  public static List<Message> selectHistory(
      List<Message> history, SkillDefinition.ForkContext context, int count) {
    List<Message> source = history == null ? List.of() : List.copyOf(history);
    if (context == null || context == SkillDefinition.ForkContext.NONE) return List.of();
    if (context == SkillDefinition.ForkContext.FULL) return source;
    int wanted = Math.max(count, 1);
    var starts = new ArrayList<Integer>();
    for (int i = 0; i < source.size(); i++) {
      Message message = source.get(i);
      if ("user".equals(message.role())
          && message.content().stream().anyMatch(TextBlock.class::isInstance)) {
        starts.add(i);
      }
    }
    if (starts.isEmpty()) return List.of();
    int start = starts.get(Math.max(0, starts.size() - wanted));
    return List.copyOf(source.subList(start, source.size()));
  }

  /** 阻塞等待临时 Agent，内部历史不回写父会话，只返回最终摘要。 */
  public static ToolResult runFork(
      ForkRequest request,
      AgentTurnCoordinator coordinator,
      ConversationManager temporaryConversation,
      SkillRun skills) {
    AgentRun child =
        coordinator.startRun(
            "/"
                + request.skill().meta().name()
                + (request.arguments().isBlank() ? "" : " " + request.arguments()),
            request.mode(),
            skills);
    Runnable removePermissionDelegate = request.parentRun().delegatePermissionsTo(child);
    Runnable cancelChild = child::cancel;
    request.parentRun().addCancellationHook(cancelChild);
    String error = null;
    try {
      while (true) {
        AgentEvent event = child.events().next();
        if (event == null || event instanceof AgentEvent.LoopComplete) break;
        if (event instanceof AgentEvent.Error failed) error = failed.message();
        if (event instanceof AgentEvent.ToolUse
            || event instanceof AgentEvent.ToolResult
            || event instanceof AgentEvent.PermissionRequested
            || event instanceof AgentEvent.Usage) {
          request.parentRun().events().publish(event);
        }
      }
      if (request.parentRun().cancellationToken().isCancelled()) {
        return ToolResult.error("fork Skill 已取消。");
      }
      List<Message> history = temporaryConversation.getMessages();
      if (!history.isEmpty() && "assistant".equals(history.getLast().role())) {
        return ToolResult.success(history.getLast().textContent());
      }
      return ToolResult.error(error == null ? "fork Skill 未返回完整结果。" : "fork Skill 执行失败：" + error);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      child.cancel();
      return ToolResult.error("fork Skill 等待被中断。");
    } finally {
      request.parentRun().removeCancellationHook(cancelChild);
      removePermissionDelegate.run();
      child.close();
      skills.clear();
    }
  }

  /** Agent 内部发起一次阻塞 fork 所需的不可变输入。 */
  public record ForkRequest(
      SkillDefinition skill,
      String arguments,
      List<Message> mainHistory,
      AgentMode mode,
      AgentRun parentRun) {

    public ForkRequest {
      arguments = arguments == null ? "" : arguments;
      mainHistory = mainHistory == null ? List.of() : List.copyOf(mainHistory);
    }
  }
}
