package com.mewcode.tool;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** 一次工具调用共享的运行上下文。 */
public record ToolExecutionContext(
        Path projectRoot,
        Duration timeout,
        FileStateCache fileStateCache) {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    public ToolExecutionContext {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(fileStateCache, "fileStateCache");
        if (!projectRoot.isAbsolute()) {
            throw new IllegalArgumentException("projectRoot must be absolute");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        projectRoot = projectRoot.normalize();
    }

    public ToolExecutionContext(Path projectRoot, FileStateCache fileStateCache) {
        this(projectRoot, DEFAULT_TIMEOUT, fileStateCache);
    }
}
