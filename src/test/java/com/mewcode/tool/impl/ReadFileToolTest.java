package com.mewcode.tool.impl;

import com.mewcode.tool.FileStateCache;
import com.mewcode.tool.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReadFileToolTest {

    @TempDir Path tempDir;

    @Test
    void readsRequestedLineRangeWithLineNumberPrefix() throws Exception {
        Path file = tempDir.resolve("main.py");
        Files.writeString(file, "def main():\n    print('hello')\n    return 0\n");
        var result = new ReadFileTool().execute(context(), Map.of(
                "path", file.toString(), "offset", 2, "limit", 1));

        assertFalse(result.isError());
        assertEquals("2\t    print('hello')", result.content());
    }

    @Test
    void rejectsRelativeMissingAndBinaryPathsWithActionableErrors() throws Exception {
        var tool = new ReadFileTool();
        var context = context();

        var relative = tool.execute(context, Map.of("path", "main.py"));
        assertTrue(relative.isError());
        assertTrue(relative.content().contains("绝对路径"));

        var missing = tool.execute(context, Map.of("path", tempDir.resolve("missing.py").toString()));
        assertTrue(missing.isError());
        assertTrue(missing.content().contains("不存在"));

        Path binary = tempDir.resolve("image.bin");
        Files.write(binary, new byte[]{'P', 'K', 0, 1, 2});
        var binaryResult = tool.execute(context, Map.of("path", binary.toString()));
        assertTrue(binaryResult.isError());
        assertTrue(binaryResult.content().contains("二进制"));
        assertTrue(binaryResult.content().contains("Bash"));
    }

    @Test
    void readsTheRepositorySkillFileWhenGivenItsAbsolutePath() {
        Path projectRoot = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        Path skill = projectRoot.resolve(".trae/skills/mew-spec/SKILL.md");
        assertTrue(Files.isRegularFile(skill), skill.toString());

        var result = new ReadFileTool().execute(
                new ToolExecutionContext(projectRoot, Duration.ofSeconds(2), new FileStateCache()),
                Map.of("path", skill.toString(), "offset", 1, "limit", 1));

        assertFalse(result.isError(), result.content());
        assertFalse(result.content().isBlank());
        assertTrue(result.content().startsWith("1\t"), result.content());
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext(tempDir, Duration.ofSeconds(2), new FileStateCache());
    }
}
