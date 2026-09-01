package com.mewcode.session;

import com.mewcode.conversation.Message;
import com.mewcode.llm.CancellableLlmStream;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.PromptRequest;
import com.mewcode.llm.StreamEvent;
import com.mewcode.conversation.ConversationManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** 管理当前内存会话、JSONL writer、显式恢复和一次性恢复提醒。 */
public final class SessionManager implements AutoCloseable {

    private static final Duration STALE_AFTER = Duration.ofHours(24);
    private static final DateTimeFormatter ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX").withZone(ZoneId.systemDefault());
    private static final String TITLE_SYSTEM =
            "你是 MewCode 的会话标题生成器。禁止调用工具。请根据输入生成一个简短、准确的中文标题，只输出标题本身，不要 Markdown、引号或解释。";

    private final Path projectRoot;
    private final Path sessionsRoot;
    private final ConversationManager conversation;
    private final Consumer<String> diagnostics;

    private HistoryStore historyStore;
    private String sessionId;
    private Path sessionDirectory;
    private LlmClient titleClient;
    private String model = "";
    private boolean titleRequested;
    private Optional<Message> resumeReminder = Optional.empty();
    private boolean closed;

    public SessionManager(Path projectRoot, Path userHome, Consumer<String> diagnostics) {
        this(projectRoot, userHome, new ConversationManager(), diagnostics);
    }

    /** 使用调用方已有的会话对象，保证 Agent、TUI 和持久化共享同一份历史。 */
    public SessionManager(
            Path projectRoot,
            Path userHome,
            ConversationManager conversation,
            Consumer<String> diagnostics) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        Objects.requireNonNull(userHome, "userHome");
        this.conversation = Objects.requireNonNull(conversation, "conversation");
        this.sessionsRoot = this.projectRoot.resolve(".mewcode/sessions");
        this.diagnostics = diagnostics == null ? ignored -> {} : diagnostics;
        try {
            Path configRoot = this.projectRoot.resolve(".mewcode");
            if (Files.isSymbolicLink(configRoot) || Files.isSymbolicLink(sessionsRoot)) {
                throw new IOException("session 存储路径不能是符号链接");
            }
            if (Files.exists(sessionsRoot, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(sessionsRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("session 存储路径不是目录");
            }
            Files.createDirectories(sessionsRoot);
            createCurrentSession();
        } catch (IOException error) {
            throw new IllegalStateException("无法创建 session 存储目录。", error);
        }
        conversation.setMutationListener(this::persistMutation);
    }

    public synchronized ConversationManager conversation() {
        return conversation;
    }

    public synchronized String currentSessionId() {
        return sessionId;
    }

    public synchronized Path currentSessionDirectory() {
        return sessionDirectory;
    }

    /** 开启空白会话；旧会话保留在磁盘，可继续通过 resume 恢复。 */
    public synchronized NewSessionResult startNewSession() {
        ensureOpen();
        try {
            NewSession next = createSession();
            HistoryStore previous = historyStore;
            historyStore = next.store();
            sessionId = next.id();
            sessionDirectory = next.directory();
            titleRequested = false;
            resumeReminder = Optional.empty();
            conversation.loadMessages(List.of());
            previous.close();
            return new NewSessionResult(sessionId, sessionDirectory);
        } catch (IOException error) {
            throw new IllegalStateException("无法创建新 session。", error);
        }
    }

    public synchronized List<SessionInfo> listSessions() {
        ensureOpen();
        try {
            return HistoryStore.scan(sessionsRoot);
        } catch (IOException error) {
            diagnostics.accept("session 列表读取失败。");
            return List.of();
        }
    }

    /** 恢复当前项目内指定 session，并返回是否需要一次性 stale 提醒。 */
    public synchronized ResumeResult resume(String requestedId) {
        ensureOpen();
        if (!HistoryStore.isValidSessionId(requestedId)) {
            throw new IllegalArgumentException("非法 session ID。");
        }
        Path candidate = sessionsRoot.resolve(requestedId).normalize();
        if (!sessionsRoot.equals(candidate.getParent())) {
            throw new IllegalArgumentException("非法 session 路径。");
        }
        try {
            Path rootReal = realOrNormalized(sessionsRoot);
            if (!Files.isDirectory(sessionsRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("session 存储目录不存在。");
            }
            if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("session 不存在。");
            }
            Path historyFile = candidate.resolve("conversation.jsonl");
            if (!Files.isRegularFile(historyFile, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(candidate.resolve("tool-results"))) {
                throw new IllegalArgumentException("session 没有可恢复的有效历史。");
            }
            Path realDirectory = candidate.toRealPath();
            if (!realDirectory.startsWith(rootReal)) {
                throw new IllegalArgumentException("非法 session 路径。");
            }
            var target = new HistoryStore(realDirectory, requestedId, model);
            HistoryStore.LoadedHistory loaded = target.load();
            if (loaded.lastActive().isEmpty()) {
                target.close();
                throw new IllegalArgumentException("session 没有可恢复的有效历史。");
            }
            HistoryStore previous = historyStore;
            historyStore = target;
            sessionId = requestedId;
            sessionDirectory = realDirectory;
            titleRequested = loaded.messageCount() > 0;
            boolean stale = loaded.lastActive().get().isBefore(Instant.now().minus(STALE_AFTER));
            resumeReminder = stale
                    ? Optional.of(new Message("user", resumeMessage(loaded.lastActive().get())))
                    : Optional.empty();
            conversation.loadMessages(loaded.messages());
            previous.close();
            return new ResumeResult(requestedId, realDirectory, loaded.lastActive().get(), stale);
        } catch (IOException error) {
            throw new IllegalArgumentException("session 恢复失败。", error);
        }
    }

    /** 取出恢复提醒；每次恢复最多消费一次。 */
    public synchronized Optional<Message> consumeResumeReminder() {
        Optional<Message> result = resumeReminder;
        resumeReminder = Optional.empty();
        return result;
    }

    /** Provider 选定后绑定标题模型，并让首条新消息带上模型标签。 */
    public synchronized void attachTitleClient(LlmClient client, String model) {
        ensureOpen();
        titleClient = client;
        this.model = model == null ? "" : model;
        historyStore.setModel(this.model);
    }

    /** 首次完整最终回复后异步生成 session 标题。 */
    public synchronized void onCompletedTurn(List<Message> completedTurn) {
        ensureOpen();
        if (titleRequested) return;
        titleRequested = true;
        List<Message> turn = List.copyOf(completedTurn == null ? List.of() : completedTurn);
        String targetId = sessionId;
        Path targetDirectory = sessionDirectory;
        LlmClient client = titleClient;
        Thread.startVirtualThread(() -> generateTitle(targetId, targetDirectory, client, turn));
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        historyStore.close();
    }

    private void createCurrentSession() throws IOException {
        NewSession session = createSession();
        sessionId = session.id();
        sessionDirectory = session.directory();
        historyStore = session.store();
    }

    private NewSession createSession() throws IOException {
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidateId = ID_TIME.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 4);
            Path candidate = sessionsRoot.resolve(candidateId);
            try {
                Files.createDirectory(candidate);
                Path directory = candidate.toAbsolutePath().normalize();
                return new NewSession(candidateId, directory, new HistoryStore(directory, candidateId, model));
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // 随机后缀碰撞时继续生成新的 session ID。
            }
        }
        throw new IOException("无法生成唯一 session ID。");
    }

    private synchronized void persistMutation(ConversationManager.Mutation mutation) {
        ensureOpen();
        try {
            if (mutation.kind() == ConversationManager.MutationKind.REPLACE) {
                historyStore.appendCompactedMessages(mutation.messages());
            } else {
                historyStore.appendMessages(mutation.messages());
            }
        } catch (IOException error) {
            throw new IllegalStateException("会话历史写入失败。", error);
        }
    }

    private void generateTitle(String targetId, Path targetDirectory, LlmClient client, List<Message> turn) {
        String title;
        try {
            title = client == null ? "" : requestTitle(client, turn);
        } catch (RuntimeException error) {
            diagnostics.accept("session 标题生成失败，使用首条用户消息。");
            title = "";
        }
        if (title.isBlank()) title = fallbackTitle(targetDirectory, targetId);
        appendTitle(targetId, targetDirectory, title);
    }

    private String requestTitle(LlmClient client, List<Message> turn) {
        var request = new PromptRequest(List.of(TITLE_SYSTEM), List.of(), turn, Optional.empty());
        StringBuilder text = new StringBuilder();
        boolean ended = false;
        try (CancellableLlmStream stream = client.openStream(request)) {
            while (true) {
                StreamEvent event = stream.next();
                if (event == null) break;
                if (event instanceof StreamEvent.TextDelta delta) text.append(delta.text());
                else if (event instanceof StreamEvent.StreamEnd) {
                    ended = true;
                    break;
                } else if (event instanceof StreamEvent.Error
                        || event instanceof StreamEvent.ToolCallComplete
                        || event instanceof StreamEvent.ToolCallParseError) {
                    throw new IllegalStateException("标题请求未正常完成。");
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("标题请求被中断。", error);
        }
        if (!ended) throw new IllegalStateException("标题请求未正常结束。");
        return normalizeTitle(text.toString());
    }

    private void appendTitle(String targetId, Path targetDirectory, String title) {
        synchronized (this) {
            if (closed) return;
            try {
                if (targetId.equals(sessionId)) {
                    historyStore.appendTitle(normalizeTitle(title));
                    return;
                }
                try (HistoryStore target = new HistoryStore(targetDirectory, targetId, model)) {
                    target.appendTitle(normalizeTitle(title));
                }
            } catch (IOException | RuntimeException error) {
                diagnostics.accept("session 标题保存失败。");
            }
        }
    }

    private static String fallbackTitle(Path directory, String id) {
        try (HistoryStore store = new HistoryStore(directory, id, "")) {
            return store.load().firstUserText().map(SessionManager::normalizeTitle).orElse("未命名会话");
        } catch (IOException | RuntimeException ignored) {
            return "未命名会话";
        }
    }

    private static String normalizeTitle(String value) {
        String oneLine = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        if (oneLine.length() <= 80) return oneLine;
        return oneLine.substring(0, 80) + "…";
    }

    private static String resumeMessage(java.time.Instant lastActive) {
        return "[会话恢复提醒] 上次活跃于 " + DISPLAY_TIME.format(lastActive)
                + "。期间项目代码可能发生变化，请重新读取相关文件后再做决策。";
    }

    private static Path realOrNormalized(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException ignored) {
            return path.toAbsolutePath().normalize();
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("session manager is closed");
    }

    public record NewSessionResult(String sessionId, Path sessionDirectory) {}

    private record NewSession(String id, Path directory, HistoryStore store) {}

}
