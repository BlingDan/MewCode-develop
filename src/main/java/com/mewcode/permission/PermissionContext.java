package com.mewcode.permission;

import com.mewcode.agent.CancellationToken;
import java.nio.file.Path;
import java.util.Objects;

/** 单次 Agent Run 固定使用的权限上下文。 */
public record PermissionContext(
    Path projectRoot,
    PermissionMode mode,
    PermissionRuleEngine ruleEngine,
    PathAuthorizationStore pathAuthorizationStore,
    BashSandbox bashSandbox,
    PermissionBroker permissionBroker,
    CancellationToken cancellationToken) {
  public PermissionContext {
    projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
    mode = Objects.requireNonNull(mode, "mode");
    ruleEngine = Objects.requireNonNull(ruleEngine, "ruleEngine");
    pathAuthorizationStore =
        Objects.requireNonNull(pathAuthorizationStore, "pathAuthorizationStore");
    bashSandbox = Objects.requireNonNull(bashSandbox, "bashSandbox");
    permissionBroker = Objects.requireNonNull(permissionBroker, "permissionBroker");
    cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
  }
}
