package com.mewcode.compact;

/** 上下文管理失败；对外消息必须由调用方转换为安全文本。 */
public class ContextException extends RuntimeException {

    public ContextException(String message) {
        super(message);
    }

    public ContextException(String message, Throwable cause) {
        super(message, cause);
    }
}
