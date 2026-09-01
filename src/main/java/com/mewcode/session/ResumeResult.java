package com.mewcode.session;

import java.nio.file.Path;
import java.time.Instant;

/** 一次成功恢复的 session 信息。 */
public record ResumeResult(String sessionId, Path sessionDir, Instant lastActive, boolean stale) {}
