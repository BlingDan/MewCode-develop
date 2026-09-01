package com.mewcode.memory;

/** memory LLM 返回的单条结构化操作；wire 字段保留字符串以便整体校验。 */
public record MemoryOperation(
        String action,
        String level,
        String type,
        String title,
        String slug,
        String filename,
        String content) {}
