package com.mewcode.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 当前进程内存中的 provider 无关会话历史。
 *
 * <p>所有公开写入方法同步执行，读取时返回不可变快照；工具回合使用
 * {@link #addToolTurn(List, List)} 原子写入 assistant 调用和 user 结果，避免取消时留下
 * 不完整的消息配对。</p>
 */
public final class ConversationManager {

    private final List<Message> messages = new ArrayList<>();
    private Consumer<Mutation> mutationListener = ignored -> {};

    public enum MutationKind {
        APPEND,
        REPLACE
    }

    public record Mutation(MutationKind kind, List<Message> messages) {
        public Mutation {
            kind = Objects.requireNonNull(kind, "kind");
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        }
    }

    /** 追加一条用户文本消息。 */
    public synchronized void addUserMessage(String text) {
        append(List.of(new Message("user", text)));
    }

    /** 追加一条完整的 assistant 文本消息。 */
    public synchronized void addAssistantMessage(String text) {
        append(List.of(new Message("assistant", text)));
    }

    /** 追加一条包含文本、思考或工具调用的完整 assistant 消息。 */
    public synchronized void addAssistantMessage(List<ContentBlock> content) {
        append(List.of(new Message("assistant", content)));
    }

    /** 原子追加一次 fork 调用及其摘要，避免只持久化其中一半。 */
    public synchronized void addExchange(String userText, String assistantText) {
        append(List.of(new Message("user", userText), new Message("assistant", assistantText)));
    }

    /** 追加一条工具结果消息；通常由原子回合方法代替。 */
    public synchronized void addToolResults(List<ToolResultBlock> results) {
        append(List.of(new Message("user", new ArrayList<>(results))));
    }

    /** 原子提交一轮完整的 assistant 工具调用和对应结果。 */
    public synchronized void addToolTurn(List<ContentBlock> assistantContent,
                                          List<ToolResultBlock> results) {
        append(List.of(
                new Message("assistant", assistantContent),
                new Message("user", new ArrayList<>(results))));
    }

    /** 追加已经构造好的 provider 无关消息，主要用于兼容适配器和测试。 */
    public synchronized void addMessage(Message message) {
        append(List.of(message));
    }

    /** 原子替换完整会话快照，供上下文压缩成功后提交新历史。 */
    public synchronized void replaceMessages(List<Message> replacement) {
        List<Message> copy = List.copyOf(replacement);
        notifyMutation(new Mutation(MutationKind.REPLACE, copy));
        messages.clear();
        messages.addAll(copy);
    }

    /** 恢复已有 session 的消息；不会再次写入 JSONL。 */
    public synchronized void loadMessages(List<Message> replacement) {
        messages.clear();
        messages.addAll(List.copyOf(replacement));
    }

    /** 设置 session 持久化回调；后续 mutation 在内存提交前通知。 */
    public synchronized void setMutationListener(Consumer<Mutation> listener) {
        mutationListener = listener == null ? ignored -> {} : listener;
    }

    /** 返回当前历史的不可变快照，供一次 provider 请求使用。 */
    public synchronized List<Message> getMessages() {
        return List.copyOf(messages);
    }

    private void append(List<Message> additions) {
        List<Message> copy = List.copyOf(additions);
        notifyMutation(new Mutation(MutationKind.APPEND, copy));
        messages.addAll(copy);
    }

    private void notifyMutation(Mutation mutation) {
        mutationListener.accept(mutation);
    }
}
