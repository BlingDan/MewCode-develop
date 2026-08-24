package com.mewcode.conversation;

import java.util.Objects;

/** provider 返回的思考/推理内容，供后续请求保留协议要求的上下文。 */
public record ThinkingBlock(String text, String signature) implements ContentBlock {

    public ThinkingBlock {
        text = Objects.requireNonNullElse(text, "");
        signature = Objects.requireNonNullElse(signature, "");
    }
}
