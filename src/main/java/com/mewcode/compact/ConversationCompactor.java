package com.mewcode.compact;

import com.mewcode.conversation.ContentBlock;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.TextBlock;
import com.mewcode.conversation.ThinkingBlock;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.conversation.ToolUseBlock;
import com.mewcode.llm.CancellableLlmStream;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.PromptRequest;
import com.mewcode.llm.StreamEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 负责选择旧历史、生成结构化摘要并原子替换会话。 */
public final class ConversationCompactor {

    private static final long TAIL_TOKEN_TARGET = 10_000;
    private static final int TAIL_MESSAGE_TARGET = 5;
    private static final double CHARACTERS_PER_TOKEN = 3.5d;
    private static final List<String> SUMMARY_HEADINGS = List.of(
            "用户目标与约束",
            "已完成工作与关键决策",
            "当前代码/文件状态",
            "未完成事项与下一步",
            "重要工具结果文件索引");
    private static final String SUMMARY_SYSTEM = """
            你是 MewCode 的上下文摘要器。禁止调用任何工具，摘要请求没有可用工具。
            请先在内部完成分析草稿，再输出正式摘要。内部草稿只用于本次生成，
            不得输出、写入文件或写入会话历史。正式摘要必须且只能围绕以下五个部分组织：
            用户目标与约束、已完成工作与关键决策、当前代码/文件状态、
            未完成事项与下一步、重要工具结果文件索引。
            """;
    private static final String BOUNDARY_MESSAGE = """
            上下文已被压缩。上面的摘要只代表历史概况，不是完整代码或完整工具输出。
            需要文件细节时必须重新读取对应文件；不得根据摘要臆测代码、工具输出或文件内容。
            """;

    private final LlmClient client;
    private final TokenEstimator estimator;
    private final ToolResultExternalizer externalizer;

    public ConversationCompactor(
            LlmClient client,
            TokenEstimator estimator,
            ToolResultExternalizer externalizer) {
        this.client = Objects.requireNonNull(client, "client");
        this.estimator = Objects.requireNonNull(estimator, "estimator");
        this.externalizer = Objects.requireNonNull(externalizer, "externalizer");
    }

    /** 强制执行一次摘要；没有可压缩旧内容时返回 changed=false。 */
    public CompactResult compact(
            ConversationManager conversation,
            ContextRequest request) {
        return compact(conversation, request, "");
    }

    /** 可选重点只参与摘要提示，不写入会话历史。 */
    public CompactResult compact(
            ConversationManager conversation,
            ContextRequest request,
            String focus) {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(request, "request");
        List<Message> history = conversation.getMessages();
        long beforeTokens = estimator.estimate(request, history);
        int tailStart = findTailStart(history);
        List<Message> oldMessages = history.subList(0, tailStart);
        List<Message> tailMessages = history.subList(tailStart, history.size());
        if (oldMessages.stream().noneMatch(ConversationCompactor::isSummarizable)) {
            return new CompactResult(beforeTokens, beforeTokens, false);
        }

        String summary = requestSummary(oldMessages, focus);
        var replacement = new ArrayList<Message>();
        boolean summaryInserted = false;
        for (Message message : oldMessages) {
            if (isSummarizable(message)) {
                if (!summaryInserted) {
                    replacement.add(new Message("assistant", summary));
                    summaryInserted = true;
                }
            } else {
                replacement.add(message);
            }
        }
        replacement.addAll(tailMessages);
        replacement.add(new Message("user", BOUNDARY_MESSAGE));
        conversation.replaceMessages(replacement);
        estimator.invalidateBaseline();
        long afterTokens = estimator.estimate(request, conversation.getMessages());
        return new CompactResult(beforeTokens, afterTokens, true);
    }

    private String requestSummary(List<Message> oldMessages, String focus) {
        String normalizedFocus = focus == null ? "" : focus.strip();
        List<String> systems = normalizedFocus.isEmpty()
                ? List.of(SUMMARY_SYSTEM)
                : List.of(SUMMARY_SYSTEM, "本次摘要额外保留重点：" + normalizedFocus);
        var summaryContext = new ContextRequest(systems, List.of(), Optional.empty());
        var summaryHistory = List.of(new Message("user", serializeMessages(oldMessages)));
        var summaryRequest = new PromptRequest(
                summaryContext.systemSegments(),
                List.of(),
                summaryHistory,
                Optional.empty());
        StringBuilder text = new StringBuilder();
        StreamEvent.Usage lastUsage = null;
        boolean ended = false;
        try (CancellableLlmStream stream = client.openStream(summaryRequest)) {
            while (true) {
                StreamEvent event = stream.next();
                if (event == null) break;
                if (event instanceof StreamEvent.TextDelta delta) {
                    text.append(delta.text());
                } else if (event instanceof StreamEvent.Usage usage) {
                    lastUsage = usage;
                } else if (event instanceof StreamEvent.StreamEnd) {
                    ended = true;
                } else if (event instanceof StreamEvent.Error
                        || event instanceof StreamEvent.ToolCallComplete
                        || event instanceof StreamEvent.ToolCallParseError) {
                    break;
                }
                if (ended) break;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ContextException("上下文摘要被中断。", error);
        } catch (RuntimeException error) {
            throw new ContextException("上下文摘要请求失败。", error);
        }
        if (lastUsage != null) estimator.recordUsage(lastUsage, summaryHistory, summaryContext);
        if (!ended) throw new ContextException("上下文摘要未完整结束。");
        String result = text.toString().trim();
        if (result.isEmpty() || SUMMARY_HEADINGS.stream().anyMatch(heading -> !result.contains(heading))) {
            throw new ContextException("上下文摘要缺少固定结构。");
        }
        return result;
    }

    private static int findTailStart(List<Message> history) {
        int index = history.size();
        long tokenCount = 0;
        int messageCount = 0;
        while (index > 0
                && (tokenCount < TAIL_TOKEN_TARGET || messageCount < TAIL_MESSAGE_TARGET)) {
            Message message = history.get(--index);
            tokenCount += (long) Math.ceil(messageCharacters(message) / CHARACTERS_PER_TOKEN);
            messageCount++;
        }
        return index;
    }

    private static boolean isSummarizable(Message message) {
        if ("assistant".equals(message.role())) return true;
        return message.content().stream().anyMatch(block -> block instanceof ToolResultBlock);
    }

    private static long messageCharacters(Message message) {
        long total = 0;
        for (ContentBlock block : message.content()) {
            if (block instanceof TextBlock text) {
                total += characterCount(text.text());
            } else if (block instanceof ThinkingBlock thinking) {
                total += characterCount(thinking.text());
                total += characterCount(thinking.signature());
            } else if (block instanceof ToolUseBlock toolUse) {
                total += characterCount(toolUse.toolUseId());
                total += characterCount(toolUse.toolName());
                total += characterCount(toolUse.arguments().toString());
            } else if (block instanceof ToolResultBlock result) {
                total += characterCount(result.toolUseId());
                total += characterCount(result.content());
            }
        }
        return total;
    }

    private static String serializeMessages(List<Message> messages) {
        var result = new StringBuilder();
        for (int index = 0; index < messages.size(); index++) {
            result.append("消息 ").append(index + 1)
                    .append(" role=").append(messages.get(index).role())
                    .append('\n')
                    .append(messages.get(index).content())
                    .append("\n\n");
        }
        return result.toString();
    }

    private static int characterCount(String value) {
        return value.codePointCount(0, value.length());
    }
}
