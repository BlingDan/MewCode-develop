package com.mewcode.compact;

import com.mewcode.conversation.ContentBlock;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.StreamEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** MewCode 会话上下文策略的唯一入口。 */
public final class ContextManager implements AutoCloseable {

    public static final long AUTOMATIC_SAFETY_MARGIN = 13_000;
    public static final long MANUAL_SAFETY_MARGIN = 3_000;

    private final LlmClient client;
    private final int contextWindowTokens;
    private final TokenEstimator estimator;
    private ToolResultExternalizer externalizer;
    private ConversationCompactor compactor;
    private final AutoCompactFuse fuse;
    private boolean closed;

    public ContextManager(Path projectRoot, LlmClient client, int contextWindowTokens) {
        this.client = Objects.requireNonNull(client, "client");
        this.contextWindowTokens = contextWindowTokens > 0
                ? contextWindowTokens
                : com.mewcode.config.ProviderConfig.DEFAULT_CONTEXT_WINDOW_TOKENS;
        this.estimator = new TokenEstimator();
        this.externalizer = new ToolResultExternalizer(projectRoot);
        this.compactor = new ConversationCompactor(this.client, estimator, externalizer);
        this.fuse = new AutoCompactFuse();
    }

    /**
     * 请求发送前执行自动累积检查；未达到阈值时不调用摘要模型。
     */
    public synchronized ContextPreparation prepareForRequest(
            ConversationManager conversation, ContextRequest request) {
        return prepareForRequest(conversation, request, ignored -> {});
    }

    /** 只估算当前请求，不触发 Provider 或压缩。 */
    public synchronized long estimateTokens(
            ConversationManager conversation, ContextRequest request) {
        ensureOpen();
        return estimator.estimate(
                Objects.requireNonNull(request, "request"),
                Objects.requireNonNull(conversation, "conversation").getMessages());
    }

    /** 请求前执行自动检查，并在真正开始摘要前通知调用方显示状态。 */
    public synchronized ContextPreparation prepareForRequest(
            ConversationManager conversation,
            ContextRequest request,
            Consumer<ContextTrigger> onCompactionStart) {
        ensureOpen();
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(onCompactionStart, "onCompactionStart");
        long estimated = estimator.estimate(request, conversation.getMessages());
        long threshold = Math.max(0, contextWindowTokens - AUTOMATIC_SAFETY_MARGIN);
        if (estimated < threshold) return ContextPreparation.unchanged(estimated);
        if (fuse.isTripped()) {
            throw new ContextException("自动上下文压缩已熔断，当前请求仍超过可用预算。");
        }
        try {
            onCompactionStart.accept(ContextTrigger.AUTO);
            CompactResult result = compactor.compact(conversation, request);
            if (!result.changed() || result.afterTokens() >= threshold) {
                throw new ContextException("上下文没有可压缩的旧内容，无法满足当前预算。");
            }
            fuse.recordSuccess(ContextTrigger.AUTO);
            return new ContextPreparation(
                    result.afterTokens(), true, java.util.Optional.of(result));
        } catch (ContextException error) {
            fuse.recordFailure(ContextTrigger.AUTO);
            throw error;
        }
    }

    /** 强制执行一次重量压缩；MANUAL 和 EMERGENCY 不受自动阈值限制。 */
    public synchronized CompactResult forceCompact(
            ConversationManager conversation,
            ContextRequest request,
            ContextTrigger trigger) {
        return forceCompact(conversation, request, trigger, "");
    }

    /** 强制压缩，并把用户指定重点仅加入摘要请求。 */
    public synchronized CompactResult forceCompact(
            ConversationManager conversation,
            ContextRequest request,
            ContextTrigger trigger,
            String focus) {
        ensureOpen();
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(trigger, "trigger");
        if (trigger == ContextTrigger.AUTO && fuse.isTripped()) {
            throw new ContextException("自动上下文压缩已熔断。");
        }
        try {
            CompactResult result = compactor.compact(conversation, request, focus);
            if (result.changed()
                    && result.afterTokens() >= Math.max(0L, contextWindowTokens - safetyMargin(trigger))) {
                throw new ContextException("上下文压缩后仍未达到可用预算。请减少本轮输入或继续拆分任务。");
            }
            if (result.changed()) fuse.recordSuccess(trigger);
            return result;
        } catch (ContextException error) {
            fuse.recordFailure(trigger);
            throw error;
        }
    }

    /** 工具结果完成第一层处理后，原子提交完整工具回合。 */
    public synchronized void commitToolTurn(
            ConversationManager conversation,
            List<ContentBlock> assistantContent,
            List<ToolResultBlock> rawResults) {
        ensureOpen();
        Objects.requireNonNull(conversation, "conversation");
        conversation.addToolTurn(
                Objects.requireNonNull(assistantContent, "assistantContent"),
                externalizer.externalize(Objects.requireNonNull(rawResults, "rawResults")));
    }

    /** 记录一次 Provider 请求的真实 usage。 */
    public synchronized void recordUsage(
            StreamEvent.Usage usage,
            List<Message> sentHistory,
            ContextRequest request) {
        ensureOpen();
        estimator.recordUsage(usage, sentHistory, request);
    }

    /** 返回当前 session 的外置文件目录，供测试和诊断使用。 */
    public Path sessionDirectory() {
        return externalizer.sessionDirectory();
    }

    /** 切换 session 后重新绑定工具结果目录和上下文预算状态。 */
    public synchronized void resetForSession(Path sessionDir) {
        ensureOpen();
        Objects.requireNonNull(sessionDir, "sessionDir");
        ToolResultExternalizer previous = externalizer;
        externalizer = ToolResultExternalizer.forPersistentDirectory(
                sessionDir.resolve("tool-results"));
        compactor = new ConversationCompactor(client, estimator, externalizer);
        estimator.reset();
        fuse.reset();
        previous.close();
    }

    /** 正常退出时清理当前 session。 */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        externalizer.close();
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("context manager is closed");
    }

    private static long safetyMargin(ContextTrigger trigger) {
        return trigger == ContextTrigger.MANUAL
                ? MANUAL_SAFETY_MARGIN
                : AUTOMATIC_SAFETY_MARGIN;
    }
}
