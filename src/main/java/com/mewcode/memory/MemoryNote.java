package com.mewcode.memory;

import java.time.Instant;

/** 一条 Markdown memory 笔记的解析结果。 */
public record MemoryNote(
        MemoryType type,
        String title,
        String slug,
        String content,
        String filename,
        Instant created,
        Instant updated) {}
