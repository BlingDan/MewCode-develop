package com.mewcode.hook;

import java.util.Arrays;

/** Hook 可声明的固定生命周期事件。 */
public enum HookEvent {
  SESSION_START("SessionStart", false),
  SESSION_END("SessionEnd", false),
  SESSION_RESUME("SessionResume", false),
  USER_PROMPT_SUBMIT("UserPromptSubmit", true),
  TURN_START("TurnStart", false),
  STOP("Stop", false),
  MESSAGE_START("MessageStart", false),
  MESSAGE_END("MessageEnd", false),
  PRE_TOOL_USE("PreToolUse", true),
  POST_TOOL_USE("PostToolUse", false),
  STARTUP("startup", false),
  SHUTDOWN("shutdown", false),
  ERROR("error", false),
  COMPACT("compact", false);

  private final String configName;
  private final boolean blocking;

  HookEvent(String configName, boolean blocking) {
    this.configName = configName;
    this.blocking = blocking;
  }

  public String configName() {
    return configName;
  }

  public boolean blocking() {
    return blocking;
  }

  public static HookEvent parse(String value) {
    return Arrays.stream(values())
        .filter(event -> event.configName.equals(value))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown hook event"));
  }
}
