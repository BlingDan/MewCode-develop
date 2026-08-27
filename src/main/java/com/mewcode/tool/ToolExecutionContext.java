package com.mewcode.tool;

import com.mewcode.agent.CancellationToken;
import com.mewcode.permission.PermissionContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * 一次工具调用共享的运行上下文。
 *
 * <p>项目根目录、超时、文件状态缓存和取消 token 在调用链中统一传递，工具不需要 自己读取全局状态；{@link
 * #withCancellationToken(CancellationToken)} 用于让同一执行器 下的每个调用绑定到当前 AgentRun。
 */
public record ToolExecutionContext(
    Path projectRoot,
    Duration timeout,
    FileStateCache fileStateCache,
    CancellationToken cancellationToken,
    PermissionContext permissionContext,
    boolean externalPathAuthorized) {

  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

  public ToolExecutionContext {
    Objects.requireNonNull(projectRoot, "projectRoot");
    Objects.requireNonNull(timeout, "timeout");
    Objects.requireNonNull(fileStateCache, "fileStateCache");
    Objects.requireNonNull(cancellationToken, "cancellationToken");
    if (!projectRoot.isAbsolute()) {
      throw new IllegalArgumentException("projectRoot must be absolute");
    }
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    projectRoot = projectRoot.normalize();
  }

  public ToolExecutionContext(Path projectRoot, Duration timeout, FileStateCache fileStateCache) {
    this(projectRoot, timeout, fileStateCache, new CancellationToken(), null, false);
  }

  public ToolExecutionContext(
      Path projectRoot,
      Duration timeout,
      FileStateCache fileStateCache,
      CancellationToken cancellationToken) {
    this(projectRoot, timeout, fileStateCache, cancellationToken, null, false);
  }

  public ToolExecutionContext(Path projectRoot, FileStateCache fileStateCache) {
    this(projectRoot, DEFAULT_TIMEOUT, fileStateCache, new CancellationToken());
  }

  /** 复制上下文但替换为本轮 AgentRun 的取消 token。 */
  public ToolExecutionContext withCancellationToken(CancellationToken token) {
    return new ToolExecutionContext(
        projectRoot, timeout, fileStateCache, token, permissionContext, externalPathAuthorized);
  }

  /** 绑定一次权限判断产生的上下文和取消 token。 */
  public ToolExecutionContext withPermissionContext(
      PermissionContext context, CancellationToken token, boolean allowExternalPath) {
    return new ToolExecutionContext(
        projectRoot, timeout, fileStateCache, token, context, allowExternalPath);
  }
}
