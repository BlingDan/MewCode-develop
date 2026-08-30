package com.mewcode.compact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.tool.FileStateCache;
import com.mewcode.tool.ToolExecutionContext;
import com.mewcode.tool.impl.ReadFileTool;
import com.mewcode.conversation.ToolResultBlock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolResultExternalizerTest {

    @TempDir
    Path tempDir;

    @Test
    void keepsExactlyFiftyThousandCharactersInHistory() throws Exception {
        var result = new ToolResultBlock("exact", "x".repeat(50_000), false);

        try (var externalizer = new ToolResultExternalizer(tempDir)) {
            var processed = externalizer.externalize(List.of(result));

            assertEquals(result, processed.getFirst());
            assertFalse(Files.exists(externalizer.sessionDirectory()));
        }
    }

    @Test
    void externalizesLargeResultWithPreviewAndReadableFullFile() throws Exception {
        String head = "H".repeat(2_000);
        String middle = "M".repeat(46_001);
        String tail = "T".repeat(2_000);
        String original = head + middle + tail;
        var result = new ToolResultBlock("large", original, false);

        try (var externalizer = new ToolResultExternalizer(tempDir)) {
            var processed = externalizer.externalize(List.of(result));
            String preview = processed.getFirst().content();
            Path file = Files.list(externalizer.sessionDirectory()).findFirst().orElseThrow();

            assertTrue(preview.contains(head));
            assertTrue(preview.contains(tail));
            assertTrue(preview.contains(externalizer.sessionDirectory().toString()));
            assertTrue(preview.contains("原始长度：50001"));
            assertFalse(preview.contains("M".repeat(100)));
            assertEquals(original, Files.readString(file));
        }
    }

    @Test
    void existingReadFileToolCanReadBackTheExternalizedPath() throws Exception {
        String original = "H".repeat(2_000) + "M".repeat(46_001) + "T".repeat(2_000);
        var result = new ToolResultBlock("large", original, false);

        try (var externalizer = new ToolResultExternalizer(tempDir)) {
            String preview = externalizer.externalize(List.of(result)).getFirst().content();
            String path = preview.lines()
                    .filter(line -> line.startsWith("文件："))
                    .findFirst()
                    .orElseThrow()
                    .substring("文件：".length());

            var read = new ReadFileTool().execute(
                    new ToolExecutionContext(tempDir, Duration.ofSeconds(2), new FileStateCache()),
                    Map.of("path", path));

            assertFalse(read.isError(), read.content());
            assertEquals("1\t" + original, read.content());
        }
    }

    @Test
    void externalizesLargestUnprocessedResultUntilAggregateFits() throws Exception {
        var results = List.of(
                new ToolResultBlock("r1", "1".repeat(40_000), false),
                new ToolResultBlock("r2", "2".repeat(41_000), false),
                new ToolResultBlock("r3", "3".repeat(42_000), false),
                new ToolResultBlock("r4", "4".repeat(43_000), false),
                new ToolResultBlock("r5", "5".repeat(45_000), false));

        try (var externalizer = new ToolResultExternalizer(tempDir)) {
            var processed = externalizer.externalize(results);

            assertTrue(processed.get(4).content().contains("原始长度：45000"));
            assertEquals(results.get(0), processed.get(0));
            assertEquals(results.get(1), processed.get(1));
            assertEquals(results.get(2), processed.get(2));
            assertEquals(results.get(3), processed.get(3));
            assertTrue(processed.stream()
                    .mapToInt(value -> value.content().codePointCount(0, value.content().length()))
                    .sum() <= 200_000);
        }
    }

    @Test
    void doesNotPutOriginalContentBackWhenWritingTheExternalFileFails() throws Exception {
        Path contextRoot = tempDir.resolve(".mewcode").resolve("context");
        Files.createDirectories(contextRoot.getParent());
        Files.writeString(contextRoot, "not a directory");
        String original = "H".repeat(2_000) + "SECRET".repeat(10_000) + "T".repeat(2_000);

        try (var externalizer = new ToolResultExternalizer(tempDir)) {
            var processed = externalizer.externalize(
                    List.of(new ToolResultBlock("failed", original, false)));

            assertTrue(processed.getFirst().isError());
            assertFalse(processed.getFirst().content().contains("SECRET"));
        }
    }

    @Test
    void closeRemovesOnlyTheCurrentSessionDirectory() throws Exception {
        Path existingFile = tempDir.resolve("existing-project-file.txt");
        Files.writeString(existingFile, "keep");
        var first = new ToolResultExternalizer(tempDir);
        var second = new ToolResultExternalizer(tempDir);

        first.externalize(List.of(new ToolResultBlock("first", "x".repeat(50_001), false)));
        second.externalize(List.of(new ToolResultBlock("second", "y".repeat(50_001), false)));
        Path firstSession = first.sessionDirectory();
        Path secondSession = second.sessionDirectory();

        first.close();
        first.close();

        assertFalse(Files.exists(firstSession));
        assertTrue(Files.exists(secondSession));
        assertEquals("keep", Files.readString(existingFile));

        second.close();
        assertFalse(Files.exists(secondSession));
    }
}
