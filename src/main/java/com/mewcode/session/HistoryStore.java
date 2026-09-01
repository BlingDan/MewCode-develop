package com.mewcode.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mewcode.conversation.ContentBlock;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.TextBlock;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.conversation.ToolUseBlock;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
// TODO： 对话没有触发自动记录
/** 一个 session 的 JSONL 追加存储、扫描和恢复实现。 */
public final class HistoryStore implements Closeable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SESSION_ID = Pattern.compile("\\d{8}-\\d{6}-[0-9A-Za-z]{4}");
    private static final int TITLE_LIMIT = 80;

    private final Path sessionDir;
    private final Path file;
    private String model;
    private boolean modelWritten;
    private boolean closed;

    public HistoryStore(Path sessionDir, String sessionId, String model) {
        this.sessionDir = sessionDir.toAbsolutePath().normalize();
        this.file = this.sessionDir.resolve("conversation.jsonl");
        this.model = model == null ? "" : model;
        this.modelWritten = containsMessageRecord(file);
        if (!isValidSessionId(sessionId)) throw new IllegalArgumentException("非法 session ID");
    }

    /** 追加普通消息；单次调用中的每一条消息都是一行 JSON。 */
    public synchronized void appendMessages(List<Message> messages) throws IOException {
        ensureOpen();
        if (messages == null || messages.isEmpty()) return;
        var lines = new ArrayList<ObjectNode>(messages.size());
        boolean first = !modelWritten;
        for (Message message : messages) {
            lines.add(toMessageNode(message, first));
            if (first) first = false;
        }
        appendNodes(lines);
        modelWritten = modelWritten || !messages.isEmpty();
    }

    /** 设置当前 Provider 模型；已有 session 的首条模型记录不会被覆盖。 */
    public synchronized void setModel(String model) {
        if (!modelWritten && model != null && !model.isBlank()) this.model = model;
    }

    /** 追加 compact 控制记录。 */
    public synchronized void appendCompact() throws IOException {
        ensureOpen();
        appendNodes(List.of(controlNode("compact", null)));
    }

    /** 在同一追加锁内写入 compact 标记和压缩后的新历史。 */
    public synchronized void appendCompactedMessages(List<Message> messages) throws IOException {
        ensureOpen();
        var lines = new ArrayList<ObjectNode>(1 + (messages == null ? 0 : messages.size()));
        lines.add(controlNode("compact", null));
        boolean first = !modelWritten;
        if (messages != null) {
            for (Message message : messages) {
                lines.add(toMessageNode(message, first));
                if (first) first = false;
            }
        }
        appendNodes(lines);
        modelWritten = modelWritten || messages != null && !messages.isEmpty();
    }

    /** 追加 LLM 生成或 fallback 的标题控制记录。 */
    public synchronized void appendTitle(String title) throws IOException {
        ensureOpen();
        if (title == null || title.isBlank()) return;
        appendNodes(List.of(controlNode("title", title.strip())));
    }

    /** 从最后一个 compact 边界恢复有效消息。 */
    public LoadedHistory load() throws IOException {
        return readHistory(file);
    }

    /** 扫描当前项目的 session 子目录。 */
    public static List<SessionInfo> scan(Path sessionsRoot) throws IOException {
        Path root = sessionsRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return List.of();
        var result = new ArrayList<SessionInfo>();
        try (Stream<Path> paths = Files.list(root)) {
            for (Path dir : paths.toList()) {
                if (!Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        || !isValidSessionId(dir.getFileName().toString())) continue;
                Path file = dir.resolve("conversation.jsonl");
                if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    LoadedHistory history = readHistory(file);
                    if (history.lastActive().isEmpty()) continue;
                    String title = history.title().orElseGet(() -> titleFallback(history.firstUserText()));
                    String model = history.model().orElse("");
                    result.add(
                            new SessionInfo(
                                    dir.getFileName().toString(),
                                    title,
                                    history.lastActive().orElse(Instant.EPOCH),
                                    model,
                                    Files.size(file),
                                    dir.toAbsolutePath().normalize()));
                } catch (IOException | RuntimeException ignored) {
                    // 单个损坏 session 不应阻止其他 session 出现在列表中。
                }
            }
        }
        result.sort(
                java.util.Comparator.comparing(SessionInfo::modifiedAt)
                        .reversed()
                        .thenComparing(SessionInfo::id));
        return List.copyOf(result);
    }

    /** 返回列表展示所需的有效消息数；坏行和 compact 前历史不计入。 */
    public static int countMessages(Path sessionDir) throws IOException {
        Path file = sessionDir.toAbsolutePath().normalize().resolve("conversation.jsonl");
        return readHistory(file).messageCount();
    }

    /** 删除当前项目内最后有效记录超过指定时长的合法 session。 */
    public static void deleteExpired(Path sessionsRoot, Duration maxAge) throws IOException {
        if (maxAge == null || maxAge.isNegative()) throw new IllegalArgumentException("maxAge must be non-negative");
        Path root = sessionsRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        Instant cutoff = Instant.now().minus(maxAge);
        try (Stream<Path> paths = Files.list(root)) {
            for (Path dir : paths.toList()) {
                if (!Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        || !isValidSessionId(dir.getFileName().toString())) continue;
                Path file = dir.resolve("conversation.jsonl");
                if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                LoadedHistory history;
                try {
                    history = readHistory(file);
                } catch (IOException | RuntimeException ignored) {
                    continue;
                }
                if (history.lastActive().isPresent() && history.lastActive().get().isBefore(cutoff)) {
                    deleteTree(dir);
                }
            }
        }
    }

    public static boolean isValidSessionId(String value) {
        return value != null && SESSION_ID.matcher(value).matches();
    }

    @Override
    public synchronized void close() {
        closed = true;
    }

    private void appendNodes(List<ObjectNode> nodes) throws IOException {
        if (nodes.isEmpty()) return;
        Files.createDirectories(sessionDir);
        try (FileChannel channel =
                FileChannel.open(
                        file,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND)) {
            for (ObjectNode node : nodes) {
                byte[] bytes = (writeJson(node) + "\n").getBytes(StandardCharsets.UTF_8);
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private ObjectNode toMessageNode(Message message, boolean first) {
        if (message == null) throw new IllegalArgumentException("message must not be null");
        ObjectNode node = JSON.createObjectNode();
        List<ToolResultBlock> results = toolResults(message);
        if (!results.isEmpty()) {
            node.put("role", "tool");
            ArrayNode values = node.putArray("tool_results");
            for (ToolResultBlock result : results) {
                ObjectNode value = values.addObject();
                value.put("toolUseId", result.toolUseId());
                value.put("content", result.content());
                value.put("isError", result.isError());
            }
        } else {
            node.put("role", message.role());
            String content = message.textContent();
            if (!content.isEmpty()) node.put("content", content);
            if ("assistant".equals(message.role())) {
                ArrayNode calls = node.putArray("tool_calls");
                for (ContentBlock block : message.content()) {
                    if (block instanceof ToolUseBlock call) {
                        ObjectNode value = calls.addObject();
                        value.put("toolUseId", call.toolUseId());
                        value.put("toolName", call.toolName());
                        value.set("arguments", JSON.valueToTree(call.arguments()));
                    }
                }
                if (calls.isEmpty()) node.remove("tool_calls");
            }
        }
        node.put("ts", Instant.now().getEpochSecond());
        if (first && !model.isBlank()) node.put("model", model);
        return node;
    }

    private ObjectNode controlNode(String type, String title) {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", type);
        if (title != null) node.put("title", title);
        node.put("ts", Instant.now().getEpochSecond());
        return node;
    }

    private static LoadedHistory readHistory(Path file) throws IOException {
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return LoadedHistory.empty();
        }
        var messages = new ArrayList<Message>();
        PendingToolTurn pending = null;
        String title = null;
        String model = null;
        String firstUserText = null;
        Instant lastActive = null;
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            for (String line : lines.toList()) {
                JsonNode node;
                try {
                    node = JSON.readTree(line);
                } catch (JsonProcessingException | IllegalArgumentException ignored) {
                    continue;
                }
                if (node == null || !node.isObject()) continue;
                Optional<Instant> timestamp = timestamp(node);
                if (timestamp.isEmpty()) continue;
                if (node.has("type")) {
                    String type = node.path("type").asText("");
                    if ("compact".equals(type)) {
                        messages.clear();
                        pending = null;
                        lastActive = timestamp.get();
                    } else if ("title".equals(type) && node.path("title").isTextual()) {
                        title = node.path("title").asText().strip();
                        lastActive = timestamp.get();
                    }
                    continue;
                }

                ParsedMessage parsed = parseMessage(node);
                if (parsed == null) continue;
                if (model == null && node.path("model").isTextual()) model = node.path("model").asText();
                lastActive = timestamp.get();
                if (pending != null) {
                    if (!parsed.tool() || !sameCalls(pending.callIds(), parsed.resultIds())) break;
                    messages.add(pending.message());
                    messages.add(parsed.message());
                    pending = null;
                    continue;
                }
                if (parsed.tool()) {
                    // tool 结果必须紧跟一个待配对的 assistant 工具调用；孤立结果之后的
                    // 内容也不能被当成同一段可靠历史继续恢复。
                    break;
                }
                if (!parsed.callIds().isEmpty()) {
                    pending = new PendingToolTurn(parsed.message(), parsed.callIds());
                    continue;
                }
                messages.add(parsed.message());
                if (firstUserText == null && "user".equals(parsed.role())) {
                    firstUserText = parsed.message().textContent();
                }
            }
        }
        return new LoadedHistory(
                messages,
                Optional.ofNullable(title).filter(value -> !value.isBlank()),
                Optional.ofNullable(lastActive),
                Optional.ofNullable(model).filter(value -> !value.isBlank()),
                messages.size(),
                Optional.ofNullable(firstUserText));
    }

    private static ParsedMessage parseMessage(JsonNode node) {
        String role = node.path("role").asText("");
        if (!(role.equals("user") || role.equals("assistant") || role.equals("tool"))) return null;
        if (role.equals("assistant")) return parseAssistant(node);
        if (role.equals("tool")) return parseTool(node);
        return new ParsedMessage(new Message("user", node.path("content").asText("")), false, Set.of(), Set.of(), "user");
    }

    private static ParsedMessage parseAssistant(JsonNode node) {
        var blocks = new ArrayList<ContentBlock>();
        if (node.path("content").isTextual()) blocks.add(new TextBlock(node.path("content").asText()));
        var ids = new HashSet<String>();
        JsonNode calls = node.path("tool_calls");
        if (calls.isArray()) {
            for (JsonNode call : calls) {
                String id = firstText(call, "toolUseId", "id");
                String name = firstText(call, "toolName", "name");
                if (id.isBlank() || name.isBlank() || !ids.add(id)) return null;
                JsonNode arguments = call.path("arguments");
                Map<String, Object> values =
                        arguments.isObject()
                                ? JSON.convertValue(arguments, new TypeReference<>() {})
                                : Map.of();
                blocks.add(new ToolUseBlock(id, name, values));
            }
        }
        return new ParsedMessage(new Message("assistant", blocks), false, ids, Set.of(), "assistant");
    }

    private static ParsedMessage parseTool(JsonNode node) {
        JsonNode values = node.path("tool_results");
        if (!values.isArray() || values.isEmpty()) return null;
        var blocks = new ArrayList<ContentBlock>();
        var ids = new HashSet<String>();
        for (JsonNode value : values) {
            String id = firstText(value, "toolUseId", "id");
            if (id.isBlank() || !ids.add(id)) return null;
            blocks.add(
                    new ToolResultBlock(
                            id,
                            value.path("content").asText(""),
                            value.path("isError").asBoolean(value.path("is_error").asBoolean(false))));
        }
        return new ParsedMessage(new Message("user", blocks), true, Set.of(), ids, "tool");
    }

    private static String firstText(JsonNode node, String first, String second) {
        String value = node.path(first).asText("");
        return value.isBlank() ? node.path(second).asText("") : value;
    }

    private static boolean sameCalls(Set<String> calls, Set<String> results) {
        return !calls.isEmpty() && calls.equals(results);
    }

    private static Optional<Instant> timestamp(JsonNode node) {
        JsonNode value = node.path("ts");
        if (!value.isIntegralNumber()) return Optional.empty();
        try {
            return Optional.of(Instant.ofEpochSecond(value.longValue()));
        } catch (DateTimeException error) {
            return Optional.empty();
        }
    }

    private static String titleFallback(Optional<String> firstUserText) {
        if (firstUserText.isEmpty()) return "未命名会话";
        String singleLine = firstUserText.get().replaceAll("\\s+", " ").strip();
        return singleLine.length() <= TITLE_LIMIT
                ? singleLine
                : singleLine.substring(0, TITLE_LIMIT) + "…";
    }

    private static List<ToolResultBlock> toolResults(Message message) {
        return message.content().stream()
                .filter(ToolResultBlock.class::isInstance)
                .map(ToolResultBlock.class::cast)
                .toList();
    }

    private static boolean containsMessageRecord(Path file) {
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false;
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.anyMatch(line -> {
                try {
                    JsonNode node = JSON.readTree(line);
                    return node != null && node.isObject() && node.has("role");
                } catch (JsonProcessingException | IllegalArgumentException ignored) {
                    return false;
                }
            });
        } catch (IOException ignored) {
            return false;
        }
    }

    private static String writeJson(ObjectNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (JsonProcessingException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    public record LoadedHistory(
            List<Message> messages,
            Optional<String> title,
            Optional<Instant> lastActive,
            Optional<String> model,
            int messageCount,
            Optional<String> firstUserText) {

        public LoadedHistory {
            messages = messages == null ? List.of() : List.copyOf(messages);
            title = title == null ? Optional.empty() : title;
            lastActive = lastActive == null ? Optional.empty() : lastActive;
            model = model == null ? Optional.empty() : model;
            firstUserText = firstUserText == null ? Optional.empty() : firstUserText;
        }

        private static LoadedHistory empty() {
            return new LoadedHistory(List.of(), Optional.empty(), Optional.empty(), Optional.empty(), 0, Optional.empty());
        }
    }

    private record ParsedMessage(
            Message message,
            boolean tool,
            Set<String> callIds,
            Set<String> resultIds,
            String role) {}

    private record PendingToolTurn(Message message, Set<String> callIds) {}

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("history store is closed");
    }
}
