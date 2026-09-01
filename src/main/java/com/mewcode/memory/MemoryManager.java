package com.mewcode.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.conversation.Message;
import com.mewcode.llm.CancellableLlmStream;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.PromptRequest;
import com.mewcode.llm.StreamEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
// TODO: memory 的更新逻辑，在对话时候不能更新
/** 编排用户级和项目级 memory 的索引注入及异步 LLM 更新。 */
public final class MemoryManager implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_LINES = 200;
    private static final int MAX_BYTES = 25 * 1024;
    private static final String UPDATE_SYSTEM = """
            你是 MewCode 的长期记忆整理器。禁止调用工具，只输出 JSON 数组。
            根据刚完成的一轮对话和已有笔记，判断是否需要新增、修改、删除、去重或无需变更。
            用户偏好和纠正反馈只能写入 user；项目知识和参考资料只能写入 project。
            create 必须提供 type、title、slug、content；update 必须提供 filename、title、content；delete 只需要 filename。
            不要保存密码、API key、Authorization、完整工具结果或一次性临时细节。
            无需变化时只输出 []。
            """;
    private static final String PRUNE_SYSTEM = """
            你是 MewCode 的 memory 索引裁剪器。禁止调用工具。
            输入是用户级和项目级 memory 索引。只输出 JSON 对象，字段必须是 user 和 project，值必须是 Markdown 字符串。
            保留最重要、可长期复用的信息，不能凭空添加事实。
            """;

    private final MemoryStore projectStore;
    private final MemoryStore userStore;
    private final Consumer<String> diagnostics;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ReentrantLock updateLock = new ReentrantLock();
    private volatile LlmClient client;
    private volatile String model = "";
    private volatile boolean closed;

    public MemoryManager(Path projectRoot, Path userHome, Consumer<String> diagnostics) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path home = userHome.toAbsolutePath().normalize();
        this.projectStore = new MemoryStore(root.resolve(".mewcode/memory"), MemoryLevel.PROJECT);
        this.userStore = new MemoryStore(home.resolve(".mewcode/memory"), MemoryLevel.USER);
        this.diagnostics = diagnostics == null ? ignored -> {} : diagnostics;
        try {
            if (Files.isSymbolicLink(root.resolve(".mewcode"))
                    || Files.isSymbolicLink(home.resolve(".mewcode"))) {
                throw new IOException("memory 父目录不能是符号链接");
            }
            projectStore.ensureDirectory();
            userStore.ensureDirectory();
        } catch (IOException error) {
            this.diagnostics.accept("memory 目录初始化失败。");
        }
    }

    /** 返回下一次普通请求要注入的用户级 + 项目级索引快照。 */
    public String indexText() {
        updateLock.lock();
        try {
            String user = readIndex(userStore);
            String project = readIndex(projectStore);
            String combined = combineIndexes(user, project);
            if (withinBudget(combined)) return combined;
            LlmClient current = client;
            if (current != null && !closed) {
                try {
                    String[] pruned = requestPrunedIndexes(current, user, project);
                    combined = combineIndexes(pruned[0], pruned[1]);
                    if (withinBudget(combined)) {
                        MemoryStore.Snapshot userBefore = userStore.snapshot();
                        MemoryStore.Snapshot projectBefore = projectStore.snapshot();
                        try {
                            userStore.replaceIndex(pruned[0]);
                            projectStore.replaceIndex(pruned[1]);
                        } catch (IOException | RuntimeException error) {
                            restoreSnapshots(userBefore, projectBefore, error);
                            throw error;
                        }
                        return combined;
                    }
                    throw new IllegalArgumentException("memory 索引裁剪后仍超出限制。");
                } catch (RuntimeException | IOException error) {
                    diagnostics.accept("memory 索引裁剪失败，使用安全截断。");
                }
            }
            return hardLimit(combined);
        } finally {
            updateLock.unlock();
        }
    }

    public void attachClient(LlmClient client, String model) {
        if (closed) return;
        this.client = client;
        this.model = model == null ? "" : model;
    }

    /** 后台更新 memory；不会阻塞当前 Agent Loop。 */
    public synchronized void updateAsync(List<Message> completedTurn) {
        if (closed || client == null) return;
        List<Message> turn = List.copyOf(completedTurn == null ? List.of() : completedTurn);
        LlmClient currentClient = client;
        String currentModel = model;
        executor.submit(() -> update(turn, currentClient, currentModel));
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        executor.close();
    }

    private void update(List<Message> completedTurn, LlmClient currentClient, String currentModel) {
        updateLock.lock();
        try {
            if (currentClient == null || completedTurn.isEmpty()) return;
            String body = UPDATE_SYSTEM
                    + "\n当前模型：" + currentModel
                    + "\n\n刚完成的对话：\n" + serialize(completedTurn)
                    + "\n用户级已有笔记：\n" + safeDescribe(userStore)
                    + "\n项目级已有笔记：\n" + safeDescribe(projectStore);
            String response = requestText(currentClient, new PromptRequest(
                    List.of(UPDATE_SYSTEM),
                    List.of(),
                    List.of(new Message("user", body)),
                    Optional.empty()));
            List<MemoryOperation> operations = parseOperations(response);
            if (operations.isEmpty()) return;
            var userOperations = new ArrayList<MemoryOperation>();
            var projectOperations = new ArrayList<MemoryOperation>();
            for (MemoryOperation operation : operations) {
                switch (MemoryLevel.fromWire(operation.level())) {
                    case USER -> userOperations.add(operation);
                    case PROJECT -> projectOperations.add(operation);
                }
            }
            MemoryStore.StagedMemory user = userStore.stage(userOperations);
            MemoryStore.StagedMemory project = projectStore.stage(projectOperations);
            String combined = combineIndexes(user.index(), project.index());
            if (!withinBudget(combined)) {
                String[] pruned = requestPrunedIndexes(client, user.index(), project.index());
                user = user.withIndex(pruned[0]);
                project = project.withIndex(pruned[1]);
                if (!withinBudget(combineIndexes(pruned[0], pruned[1]))) {
                    throw new IllegalArgumentException("memory 索引裁剪后仍超出限制。");
                }
            }
            MemoryStore.Snapshot userBefore = userStore.snapshot();
            MemoryStore.Snapshot projectBefore = projectStore.snapshot();
            try {
                userStore.commit(user);
                projectStore.commit(project);
            } catch (IOException | RuntimeException error) {
                restoreSnapshots(userBefore, projectBefore, error);
                throw error;
            }
        } catch (RuntimeException | IOException error) {
            diagnostics.accept("memory 更新失败，保留旧笔记和索引。");
        } finally {
            updateLock.unlock();
        }
    }

    private static List<MemoryOperation> parseOperations(String response) {
        try {
            JsonNode root = JSON.readTree(stripJsonFence(response));
            if (root == null || !root.isArray()) throw new IllegalArgumentException("memory 返回不是数组。");
            var result = new ArrayList<MemoryOperation>();
            Set<String> fields = Set.of("action", "level", "type", "title", "slug", "filename", "content");
            for (JsonNode node : root) {
                if (!node.isObject()) throw new IllegalArgumentException("memory 操作不是对象。");
                var names = new HashSet<String>();
                node.fieldNames().forEachRemaining(names::add);
                if (!fields.containsAll(names)) throw new IllegalArgumentException("memory 操作包含非法字段。");
                result.add(new MemoryOperation(
                        text(node, "action"),
                        text(node, "level"),
                        text(node, "type"),
                        text(node, "title"),
                        text(node, "slug"),
                        text(node, "filename"),
                        text(node, "content")));
            }
            return List.copyOf(result);
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new IllegalArgumentException("memory 返回 JSON 无效。", error);
        }
    }

    private static String stripJsonFence(String response) {
        String value = response == null ? "" : response.strip();
        if (!value.startsWith("```")) return value;
        int bodyStart = value.indexOf('\n');
        int fenceEnd = value.lastIndexOf("```");
        if (bodyStart < 0 || fenceEnd <= bodyStart) throw new IllegalArgumentException("memory 返回 JSON 无效。");
        // ponytail: 只兼容首尾代码围栏；混合自然语言仍按无效 JSON 处理，升级时改用 provider 原生 JSON schema。
        return value.substring(bodyStart + 1, fenceEnd).strip();
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.isTextual() ? value.asText() : null;
    }

    private String[] requestPrunedIndexes(LlmClient current, String user, String project) {
        String body = "user:\n" + user + "\n\nproject:\n" + project;
        String response = requestText(current, new PromptRequest(
                List.of(PRUNE_SYSTEM),
                List.of(),
                List.of(new Message("user", body)),
                Optional.empty()));
        try {
            JsonNode object = JSON.readTree(response);
            if (object == null || !object.isObject()
                    || !object.path("user").isTextual()
                    || !object.path("project").isTextual()) {
                throw new IllegalArgumentException("memory 索引裁剪返回无效。");
            }
            return new String[] {object.path("user").asText(), object.path("project").asText()};
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new IllegalArgumentException("memory 索引裁剪返回无效。", error);
        }
    }

    private static String requestText(LlmClient client, PromptRequest request) {
        StringBuilder result = new StringBuilder();
        boolean ended = false;
        try (CancellableLlmStream stream = client.openStream(request)) {
            while (true) {
                StreamEvent event = stream.next();
                if (event == null) break;
                if (event instanceof StreamEvent.TextDelta delta) result.append(delta.text());
                else if (event instanceof StreamEvent.StreamEnd) {
                    ended = true;
                    break;
                } else if (event instanceof StreamEvent.Error
                        || event instanceof StreamEvent.ToolCallComplete
                        || event instanceof StreamEvent.ToolCallParseError) {
                    throw new IllegalStateException("memory 请求未正常完成。");
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("memory 请求被中断。", error);
        }
        if (!ended) throw new IllegalStateException("memory 请求未正常结束。");
        return result.toString().strip();
    }

    private static String serialize(List<Message> messages) {
        var result = new StringBuilder();
        for (Message message : messages) {
            result.append("role=").append(message.role()).append('\n')
                    .append(message.textContent()).append('\n')
                    .append(message.content()).append("\n\n");
        }
        return result.toString();
    }

    private String safeDescribe(MemoryStore store) {
        try {
            return store.describeNotes();
        } catch (IOException | RuntimeException error) {
            return "";
        }
    }

    private static String readIndex(MemoryStore store) {
        try {
            return store.loadIndex();
        } catch (IOException | RuntimeException error) {
            return "";
        }
    }

    private static String combineIndexes(String user, String project) {
        var result = new StringBuilder();
        if (user != null && !user.isBlank()) result.append("## User memory\n").append(user.strip()).append('\n');
        if (project != null && !project.isBlank()) {
            if (!result.isEmpty()) result.append('\n');
            result.append("## Project memory\n").append(project.strip()).append('\n');
        }
        return result.toString();
    }

    private static boolean withinBudget(String value) {
        return value.split("\\R", -1).length <= MAX_LINES
                && value.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }

    private static String hardLimit(String value) {
        String[] lines = value.split("\\R", -1);
        var kept = new ArrayList<String>();
        for (int index = 0; index < Math.min(MAX_LINES, lines.length); index++) kept.add(lines[index]);
        while (StandardCharsets.UTF_8.encode(String.join("\n", kept)).remaining() > MAX_BYTES && !kept.isEmpty()) {
            kept.removeLast();
        }
        return String.join("\n", kept);
    }

    private void restoreSnapshots(
            MemoryStore.Snapshot user,
            MemoryStore.Snapshot project,
            Throwable failure) {
        try {
            userStore.restore(user);
        } catch (IOException | RuntimeException error) {
            failure.addSuppressed(error);
        }
        try {
            projectStore.restore(project);
        } catch (IOException | RuntimeException error) {
            failure.addSuppressed(error);
        }
    }
}
