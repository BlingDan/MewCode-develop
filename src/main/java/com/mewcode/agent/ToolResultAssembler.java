package com.mewcode.agent;

import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.tool.ToolCall;
import com.mewcode.tool.ToolInvocationResult;
import com.mewcode.tool.ToolResult;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

/** 将执行结果按原始调用顺序组装成同一条 user tool-result 消息。 */
public final class ToolResultAssembler {

    private ToolResultAssembler() {
    }

    public static List<ToolResultBlock> assemble(
            List<ToolCall> allCalls,
            List<ToolInvocationResult> executed,
            Map<String, String> parseErrors) {
        var byId = new java.util.HashMap<String, ArrayDeque<ToolResult>>();
        for (ToolInvocationResult result : executed) {
            byId.computeIfAbsent(result.toolUseId(), ignored -> new ArrayDeque<>())
                    .addLast(result.result());
        }
        var blocks = new java.util.ArrayList<ToolResultBlock>();
        for (ToolCall call : allCalls) {
            String parseError = parseErrors.get(call.toolUseId());
            if (parseError != null) {
                blocks.add(new ToolResultBlock(call.toolUseId(), parseError, true));
                continue;
            }
            var results = byId.get(call.toolUseId());
            ToolResult result = results == null ? null : results.pollFirst();
            if (result == null) {
                result = ToolResult.error("工具没有返回结果，请重试该调用。");
            }
            blocks.add(new ToolResultBlock(call.toolUseId(), result.content(), result.isError()));
        }
        return List.copyOf(blocks);
    }
}
