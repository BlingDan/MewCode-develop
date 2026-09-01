package com.mewcode.instructions;

import java.util.List;

/** 三层项目指令加载结果；诊断只包含安全摘要。 */
public record InstructionLoadResult(String text, List<String> diagnostics) {

    public InstructionLoadResult {
        text = text == null ? "" : text;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
