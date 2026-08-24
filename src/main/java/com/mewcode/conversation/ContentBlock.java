package com.mewcode.conversation;

/** provider 无关的消息内容块。 */
public sealed interface ContentBlock
        permits TextBlock, ThinkingBlock, ToolUseBlock, ToolResultBlock {
}
