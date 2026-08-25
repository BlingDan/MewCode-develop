package com.mewcode.prompt;

import com.mewcode.agent.AgentMode;
import java.util.Objects;

/** 生成当前轮次 System Reminder 所需的运行时上下文。 */
public record ReminderContext(AgentMode mode, int round, boolean forceFull) {

  public ReminderContext {
    mode = Objects.requireNonNull(mode, "mode");
    if (round < 1) throw new IllegalArgumentException("round must be positive");
  }
}
