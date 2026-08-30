package com.mewcode.compact;

import java.util.Objects;

/** 当前会话的自动摘要失败熔断器。 */
public final class AutoCompactFuse {

    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private int consecutiveFailures;

    /** 记录一次摘要失败；只有自动触发才会计入熔断。 */
    public synchronized void recordFailure(ContextTrigger trigger) {
        if (Objects.requireNonNull(trigger, "trigger") != ContextTrigger.AUTO) return;
        consecutiveFailures = Math.min(MAX_CONSECUTIVE_FAILURES, consecutiveFailures + 1);
    }

    /** 记录一次成功；手动或自动成功都能恢复自动路径。 */
    public synchronized void recordSuccess(ContextTrigger trigger) {
        Objects.requireNonNull(trigger, "trigger");
        consecutiveFailures = 0;
    }

    public synchronized boolean isTripped() {
        return consecutiveFailures >= MAX_CONSECUTIVE_FAILURES;
    }
}
