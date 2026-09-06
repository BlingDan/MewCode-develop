package com.mewcode.testsupport;

import com.mewcode.agent.AgentLoopConfig;
import com.mewcode.agent.AgentTurnCoordinator;
import com.mewcode.agent.PromptRequestFactory;
import com.mewcode.compact.ContextManager;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.llm.LlmClient;
import com.mewcode.permission.BashSandboxFactory;
import com.mewcode.permission.PathAuthorizationStore;
import com.mewcode.permission.PermissionGate;
import com.mewcode.permission.PermissionMode;
import com.mewcode.permission.PermissionRuleEngine;
import com.mewcode.permission.PermissionRuntime;
import com.mewcode.prompt.PromptBuilder;
import com.mewcode.tool.FileStateCache;
import com.mewcode.tool.ToolApiProtocol;
import com.mewcode.tool.ToolExecutionContext;
import com.mewcode.tool.ToolExecutor;
import com.mewcode.tool.ToolRegistry;
import java.nio.file.Path;
import java.time.Duration;

/** 使用与正式入口相同依赖的 Agent 测试运行时。 */
public record AgentTestRuntime(
    AgentTurnCoordinator coordinator, ToolExecutor tools, ContextManager context)
    implements AutoCloseable {

  public static AgentTestRuntime create(
      Path root,
      LlmClient client,
      ToolRegistry registry,
      ConversationManager conversation,
      ToolApiProtocol protocol) {
    return create(root, client, registry, conversation, protocol, new AgentLoopConfig(), 128_000);
  }

  public static AgentTestRuntime create(
      Path root,
      LlmClient client,
      ToolRegistry registry,
      ConversationManager conversation,
      ToolApiProtocol protocol,
      AgentLoopConfig config,
      int contextWindowTokens) {
    var gate = new PermissionGate();
    var tools =
        new ToolExecutor(
            registry,
            new ToolExecutionContext(root, Duration.ofSeconds(2), new FileStateCache()),
            gate);
    var context = new ContextManager(root, client, contextWindowTokens);
    var permissions =
        new PermissionRuntime(PermissionMode.BYPASS_PERMISSIONS, new PermissionRuleEngine());
    var coordinator =
        new AgentTurnCoordinator(
            client,
            registry,
            tools,
            conversation,
            protocol,
            config,
            new PromptRequestFactory(PromptBuilder.buildBundle(root)),
            context,
            permissions,
            new PathAuthorizationStore(root),
            BashSandboxFactory.create());
    return new AgentTestRuntime(coordinator, tools, context);
  }

  @Override
  public void close() {
    context.close();
    tools.close();
  }
}
