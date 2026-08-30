package com.mewcode.compact;

/** 一次重量压缩的估算结果。 */
public record CompactResult(long beforeTokens, long afterTokens, boolean changed) {

    public CompactResult {
        if (beforeTokens < 0 || afterTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }
}
