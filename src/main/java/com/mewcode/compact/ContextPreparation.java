package com.mewcode.compact;

import java.util.Objects;
import java.util.Optional;

/** 一次普通请求前的上下文预检结果。 */
public record ContextPreparation(
        long estimatedTokens,
        boolean compacted,
        Optional<CompactResult> compactResult) {

    public ContextPreparation {
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException("estimatedTokens must not be negative");
        }
        compactResult = compactResult == null ? Optional.empty() : compactResult;
        compactResult.ifPresent(Objects::requireNonNull);
    }

    public static ContextPreparation unchanged(long estimatedTokens) {
        return new ContextPreparation(estimatedTokens, false, Optional.empty());
    }
}
