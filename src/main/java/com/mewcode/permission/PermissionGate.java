package com.mewcode.permission;

import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCall;
import com.mewcode.tool.ToolCategory;
import java.util.Objects;

/** 五层权限系统的唯一判定入口。 */
public final class PermissionGate {
  private final DangerousCommandBlocklist blocklist;
  private final PathSandbox pathSandbox;

  public PermissionGate() {
    this(new DangerousCommandBlocklist(), new PathSandbox());
  }

  public PermissionGate(DangerousCommandBlocklist blocklist, PathSandbox pathSandbox) {
    this.blocklist = Objects.requireNonNull(blocklist, "blocklist");
    this.pathSandbox = Objects.requireNonNull(pathSandbox, "pathSandbox");
  }

  public PermissionCheck check(ToolCall call, Tool tool, PermissionContext context) {
    Objects.requireNonNull(call, "call");
    Objects.requireNonNull(tool, "tool");
    Objects.requireNonNull(context, "context");

    if ("Bash".equals(tool.name())) {
      String command = command(call);
      var match = blocklist.findMatch(command);
      if (match.isPresent()) {
        return deny(
            PermissionReason.DANGEROUS_COMMAND,
            blocklist.rejectionMessage(command, match.get()),
            PermissionRuleEngine.authorizationKey(call));
      }
      if (!context.bashSandbox().isAvailable()) {
        return deny(
            PermissionReason.BASH_SANDBOX_UNAVAILABLE,
            "操作被拒绝：当前平台没有可用的 Bash OS 级沙箱，安全策略不会退回裸执行。",
            PermissionRuleEngine.authorizationKey(call));
      }
    }

    PathCheck pathCheck =
        isPathTool(tool) ? pathSandbox.inspect(call, context.projectRoot()) : null;
    boolean pathOutside = pathCheck != null && pathCheck.boundary() == PathBoundary.OUTSIDE_PROJECT;
    boolean pathNeedsConfirmation = false;
    if (pathCheck != null) {
      if (pathCheck.boundary() == PathBoundary.INVALID) {
        return deny(
            PermissionReason.PATH_SANDBOX, pathCheck.reason(), pathCheck.authorizationKey());
      }
      if (pathCheck.boundary() == PathBoundary.OUTSIDE_PROJECT
          && !context.pathAuthorizationStore().isAuthorized(pathCheck.authorizationKey())
          && !context.ruleEngine().isSessionGranted(pathCheck.authorizationKey())) {
        pathNeedsConfirmation = true;
      }
    }

    String authorizationKey =
        pathCheck == null
            ? PermissionRuleEngine.authorizationKey(call)
            : pathCheck.authorizationKey();
    if (context.ruleEngine().isSessionGranted(authorizationKey)
        || context.pathAuthorizationStore().isAuthorized(authorizationKey)) {
      return new PermissionCheck(
          PermissionDecision.ALLOW,
          PermissionReason.USER,
          "已复用用户授权",
          "",
          authorizationKey,
          pathOutside);
    }

    var rule = context.ruleEngine().match(call, context.projectRoot());
    if (rule.isPresent()) {
      PermissionRule matched = rule.get().rule();
      if (matched.decision() == RuleDecision.DENY) {
        return deny(
            PermissionReason.RULE_DENY,
            "操作被权限规则拒绝：" + matched.pattern(),
            authorizationKey,
            matched.pattern());
      }
      if (pathNeedsConfirmation) {
        return new PermissionCheck(
            PermissionDecision.ASK,
            PermissionReason.PATH_SANDBOX,
            "路径解析后超出当前项目目录，需要用户明确确认：" + pathCheck.resolvedPath(),
            matched.pattern(),
            authorizationKey,
            true);
      }
      return new PermissionCheck(
          PermissionDecision.ALLOW,
          PermissionReason.RULE_ALLOW,
          "命中允许规则：" + matched.pattern(),
          matched.pattern(),
          authorizationKey,
          pathOutside);
    }

    if (pathNeedsConfirmation) {
      return new PermissionCheck(
          PermissionDecision.ASK,
          PermissionReason.PATH_SANDBOX,
          "路径解析后超出当前项目目录，需要用户明确确认：" + pathCheck.resolvedPath(),
          "",
          authorizationKey,
          true);
    }

    PermissionDecision decision = context.mode().defaultDecision(tool);
    String message = decision == PermissionDecision.ALLOW ? "当前权限模式自动允许操作" : "当前权限模式要求用户确认操作";
    return new PermissionCheck(
        decision, PermissionReason.MODE, message, "", authorizationKey, pathOutside);
  }

  private static boolean isPathTool(Tool tool) {
    return tool.category() == ToolCategory.FILE
        || "Glob".equals(tool.name())
        || "Grep".equals(tool.name());
  }

  private static String command(ToolCall call) {
    Object value = call.arguments().get("command");
    return value instanceof String text ? text : "";
  }

  private static PermissionCheck allow(PermissionReason reason, String message, String key) {
    return new PermissionCheck(PermissionDecision.ALLOW, reason, message, "", key, false);
  }

  private static PermissionCheck allow(
      PermissionReason reason, String message, String pattern, String key) {
    return new PermissionCheck(PermissionDecision.ALLOW, reason, message, pattern, key, false);
  }

  private static PermissionCheck deny(PermissionReason reason, String message, String key) {
    return new PermissionCheck(PermissionDecision.DENY, reason, message, "", key, false);
  }

  private static PermissionCheck deny(
      PermissionReason reason, String message, String key, String pattern) {
    return new PermissionCheck(PermissionDecision.DENY, reason, message, pattern, key, false);
  }
}
