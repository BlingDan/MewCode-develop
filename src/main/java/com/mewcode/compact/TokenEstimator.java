package com.mewcode.compact;

import com.mewcode.conversation.ContentBlock;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.TextBlock;
import com.mewcode.conversation.ThinkingBlock;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.conversation.ToolUseBlock;
import com.mewcode.llm.StreamEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * 使用 Provider usage 锚点和字符增量估算请求 Token。
 *
 * <p>这是近似估算，不调用精确 tokenizer；字符换算固定使用 3.5。
 */
public final class TokenEstimator {

    private static final double CHARACTERS_PER_TOKEN = 3.5d;
    private UsageAnchor anchor = UsageAnchor.unknown();

    /** 估算当前请求将消耗的 Token。 */
    public synchronized long estimate(
            ContextRequest request, List<Message> history) {
        long characters = requestCharacters(request, history);
        if (anchor.baselineValid() && characters >= anchor.requestCharacters()) {
            return anchor.totalTokens()
                    + approximateTokens(characters - anchor.requestCharacters());
        }
        return approximateTokens(characters);
    }

    /** 统计请求快照的稳定字符数。 */
    public long requestCharacters(
            ContextRequest request, List<Message> history) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(history, "history");
        long total = 0;
        for (String segment : request.systemSegments()) {
            total = add(total, characterCount(segment));
        }
        for (Map<String, Object> tool : request.tools()) {
            total = add(total, characterCount(canonicalValue(tool)));
        }
        if (request.reminder().isPresent()) {
            total = add(total, messageCharacters(request.reminder().get()));
        }
        for (Message message : history) {
            total = add(total, messageCharacters(Objects.requireNonNull(message, "message")));
        }
        return total;
    }

    /** 用一次 Provider 的真实 usage 建立下一次请求的估算锚点。 */
    public synchronized void recordUsage(
            StreamEvent.Usage usage,
            List<Message> sentHistory,
            ContextRequest request) {
        Objects.requireNonNull(usage, "usage");
        long characters = requestCharacters(request, sentHistory);
        OptionalLong input = usage.inputTokens();
        OptionalLong output = usage.outputTokens();
        if (input.isEmpty() || output.isEmpty()) {
            anchor = UsageAnchor.unknown();
            return;
        }
        long total = add(
                add(input.getAsLong(), cacheValue(usage.cacheReadTokens())),
                add(cacheValue(usage.cacheCreationTokens()), output.getAsLong()));
        anchor = new UsageAnchor(total, characters, true);
    }

    /** 历史原子替换后使旧请求字符基线失效。 */
    public synchronized void invalidateBaseline() {
        anchor = new UsageAnchor(anchor.totalTokens(), anchor.requestCharacters(), false);
    }

    /** 切换 session 后丢弃旧 session 的 usage 锚点。 */
    public synchronized void reset() {
        anchor = UsageAnchor.unknown();
    }

    private static long cacheValue(OptionalLong value) {
        return value.isPresent() ? value.getAsLong() : 0;
    }

    private static long approximateTokens(long characters) {
        return (long) Math.ceil(characters / CHARACTERS_PER_TOKEN);
    }

    private static long messageCharacters(Message message) {
        long total = 0;
        for (ContentBlock block : message.content()) {
            if (block instanceof TextBlock text) {
                total = add(total, characterCount(text.text()));
            } else if (block instanceof ThinkingBlock thinking) {
                total = add(total, characterCount(thinking.text()));
                total = add(total, characterCount(thinking.signature()));
            } else if (block instanceof ToolUseBlock toolUse) {
                total = add(total, characterCount(toolUse.toolUseId()));
                total = add(total, characterCount(toolUse.toolName()));
                total = add(total, characterCount(canonicalValue(toolUse.arguments())));
            } else if (block instanceof ToolResultBlock result) {
                total = add(total, characterCount(result.toolUseId()));
                total = add(total, characterCount(result.content()));
            }
        }
        return total;
    }

    private static String canonicalValue(Object value) {
        if (value == null) return "null";
        if (value instanceof Map<?, ?> map) {
            var entries = new ArrayList<Map.Entry<?, ?>>(map.entrySet());
            entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
            var result = new StringBuilder("{");
            for (var entry : entries) {
                result.append(canonicalValue(entry.getKey()))
                        .append(':')
                        .append(canonicalValue(entry.getValue()))
                        .append(',');
            }
            return result.append('}').toString();
        }
        if (value instanceof List<?> list) {
            var result = new StringBuilder("[");
            for (Object item : list) result.append(canonicalValue(item)).append(',');
            return result.append(']').toString();
        }
        return String.valueOf(value);
    }

    private static int characterCount(String value) {
        return value.codePointCount(0, value.length());
    }

    private static long add(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0 && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
    }

    private record UsageAnchor(
            long totalTokens, long requestCharacters, boolean baselineValid) {
        private static UsageAnchor unknown() {
            return new UsageAnchor(0, 0, false);
        }
    }
}
