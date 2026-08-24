package com.mewcode.tool;

import com.mewcode.agent.CancellationToken;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** 一次工具调用共享的运行上下文。 */
public record ToolExecutionContext(
        Path projectRoot,
        Duration timeout,
        FileStateCache fileStateCache,
        CancellationToken cancellationToken) {

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

    public ToolExecutionContext(Path projectRoot,
                                Duration timeout,
                                FileStateCache fileStateCache) {
        this(projectRoot, timeout, fileStateCache, new CancellationToken());
    }

    public ToolExecutionContext(Path projectRoot, FileStateCache fileStateCache) {
        this(projectRoot, DEFAULT_TIMEOUT, fileStateCache, new CancellationToken());
    }

    public ToolExecutionContext withCancellationToken(CancellationToken token) {
        return new ToolExecutionContext(projectRoot, timeout, fileStateCache, token);
    }
}
