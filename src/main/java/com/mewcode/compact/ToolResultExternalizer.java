package com.mewcode.compact;

import com.mewcode.conversation.ToolResultBlock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 在工具结果进入会话历史前，把超大结果保存到当前 session 文件。
 *
 * <p>这里的字符阈值使用 Unicode code point 统计，不依赖 tokenizer。
 */
public final class ToolResultExternalizer implements AutoCloseable {

    public static final int SINGLE_RESULT_LIMIT = 50_000;
    public static final int MESSAGE_RESULT_LIMIT = 200_000;
    public static final int PREVIEW_HEAD_LENGTH = 2_000;
    public static final int PREVIEW_TAIL_LENGTH = 2_000;

    private static final String CONTEXT_DIRECTORY = ".mewcode";
    private static final String SESSION_DIRECTORY = "context";
    private static final String PREVIEW_OMISSION = "...（中间内容已省略）...";
    private static final String SAVE_FAILURE =
            "工具结果完整内容保存失败，未将原文写入对话历史。";

    private final Path sessionDirectory;
    private int nextFileNumber = 1;
    private boolean closed;

    public ToolResultExternalizer(Path projectRoot) {
        Path root = Objects.requireNonNull(projectRoot, "projectRoot")
                .toAbsolutePath()
                .normalize();
        sessionDirectory = root.resolve(CONTEXT_DIRECTORY)
                .resolve(SESSION_DIRECTORY)
                .resolve(UUID.randomUUID().toString());
    }

    /**
     * 按单结果和整条工具结果消息的限制处理结果，返回可以正式写入 history 的副本。
     */
    public synchronized List<ToolResultBlock> externalize(List<ToolResultBlock> rawResults) {
        if (closed) throw new IllegalStateException("context externalizer is closed");
        Objects.requireNonNull(rawResults, "rawResults");
        if (rawResults.isEmpty()) return List.of();

        var visible = new ArrayList<ToolResultBlock>(rawResults.size());
        var originalLengths = new int[rawResults.size()];
        var spilled = new boolean[rawResults.size()];
        for (int index = 0; index < rawResults.size(); index++) {
            ToolResultBlock result = Objects.requireNonNull(rawResults.get(index), "tool result");
            originalLengths[index] = characterCount(result.content());
            if (originalLengths[index] > SINGLE_RESULT_LIMIT) {
                visible.add(saveWithPreview(result, originalLengths[index]));
                spilled[index] = true;
            } else {
                visible.add(result);
            }
        }

        while (aggregateLength(visible) > MESSAGE_RESULT_LIMIT) {
            int selected = largestUnspilled(originalLengths, spilled);
            if (selected < 0) break;
            visible.set(selected, saveWithPreview(rawResults.get(selected), originalLengths[selected]));
            spilled[selected] = true;
        }
        return List.copyOf(visible);
    }

    /** 返回当前 session 目录路径；目录在首次实际外置前可能尚未创建。 */
    public Path sessionDirectory() {
        return sessionDirectory;
    }

    private ToolResultBlock saveWithPreview(ToolResultBlock result, int originalLength) {
        try {
            Path file = saveFullResult(result.content());
            return new ToolResultBlock(
                    result.toolUseId(),
                    buildPreview(result.content(), originalLength, file),
                    result.isError());
        } catch (IOException | RuntimeException error) {
            return new ToolResultBlock(result.toolUseId(), SAVE_FAILURE, true);
        }
    }

    private Path saveFullResult(String content) throws IOException {
        Files.createDirectories(sessionDirectory);
        Path target = sessionDirectory.resolve("result-" + String.format("%04d", nextFileNumber++)
                + ".txt");
        Path temporary = Files.createTempFile(sessionDirectory, ".result-", ".tmp");
        try {
            Files.writeString(
                    temporary,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target.toAbsolutePath().normalize();
        } catch (IOException | RuntimeException error) {
            Files.deleteIfExists(temporary);
            throw error;
        }
    }

    private static String buildPreview(
            String content, int originalLength, Path file) {
        String head = firstCodePoints(content, PREVIEW_HEAD_LENGTH);
        String tail = lastCodePoints(content, PREVIEW_TAIL_LENGTH);
        return "[工具结果已外置]\n"
                + "文件：" + file + "\n"
                + "原始长度：" + originalLength + " 字符\n"
                + "--- 预览开始 ---\n"
                + head + "\n"
                + PREVIEW_OMISSION + "\n"
                + tail + "\n"
                + "--- 预览结束 ---";
    }

    private static int largestUnspilled(int[] lengths, boolean[] spilled) {
        int selected = -1;
        for (int index = 0; index < lengths.length; index++) {
            if (spilled[index]) continue;
            if (selected < 0 || lengths[index] > lengths[selected]) selected = index;
        }
        return selected;
    }

    private static long aggregateLength(List<ToolResultBlock> results) {
        long total = 0;
        for (ToolResultBlock result : results) total += characterCount(result.content());
        return total;
    }

    private static int characterCount(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String firstCodePoints(String value, int count) {
        int end = value.offsetByCodePoints(0, Math.min(count, characterCount(value)));
        return value.substring(0, end);
    }

    private static String lastCodePoints(String value, int count) {
        int start = value.offsetByCodePoints(
                value.length(), -Math.min(count, characterCount(value)));
        return value.substring(start);
    }

    /** 正常退出时只删除当前 session 目录；重复关闭安全。 */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (!Files.exists(sessionDirectory)) return;
        try (Stream<Path> paths = Files.walk(sessionDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(ToolResultExternalizer::deleteQuietly);
        } catch (IOException ignored) {
            // 清理失败不能阻止其他资源继续关闭。
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理是 best-effort，不能扩大删除范围。
        }
    }
}
