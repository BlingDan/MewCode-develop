package com.mewcode.session;

import java.nio.file.Path;
import java.time.Instant;

/** 会话列表中的即时扫描摘要。 */
public record SessionInfo(
        String id,
        String title,
        Instant modifiedAt,
        String model,
        long size,
        Path dir) {}
