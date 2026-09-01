package com.mewcode.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void stagesNoteWithFrontmatterAndBuildsIndex() throws Exception {
        try {
            Class<?> levelType = Class.forName("com.mewcode.memory.MemoryLevel");
            Object project = Enum.valueOf(levelType.asSubclass(Enum.class), "PROJECT");
            Class<?> operationType = Class.forName("com.mewcode.memory.MemoryOperation");
            Object operation = operationType.getConstructor(
                            String.class,
                            String.class,
                            String.class,
                            String.class,
                            String.class,
                            String.class,
                            String.class)
                    .newInstance(
                            "create",
                            "project",
                            "project_knowledge",
                            "API conventions",
                            "api_conventions",
                            null,
                            "Use the existing API conventions.");
            Class<?> storeType = Class.forName("com.mewcode.memory.MemoryStore");
            Object store = storeType.getConstructor(Path.class, levelType)
                    .newInstance(tempDir.resolve("memory"), project);
            Object staged = storeType.getMethod("stage", List.class).invoke(store, List.of(operation));
            storeType.getMethod("commit", staged.getClass()).invoke(store, staged);

            String note = Files.readString(tempDir.resolve("memory/project_knowledge_api_conventions.md"));
            String index = Files.readString(tempDir.resolve("memory/MEMORY.md"));
            assertTrue(note.contains("type: project_knowledge"));
            assertTrue(note.contains("title: API conventions"));
            assertTrue(note.contains("Use the existing API conventions."));
            assertTrue(index.contains("api_conventions"));
        } catch (ClassNotFoundException error) {
            fail("MemoryStore 尚未实现", error);
        } catch (InvocationTargetException error) {
            throw unwrap(error);
        }
    }

    @Test
    void rejectsMissingContentAndCrossLevelNotesBeforeCommit() {
        var store = new MemoryStore(tempDir.resolve("memory"), MemoryLevel.PROJECT);
        var invalid =
                new MemoryOperation(
                        "create",
                        "project",
                        "project_knowledge",
                        "title",
                        "valid_slug",
                        null,
                        null);

        assertThrows(IllegalArgumentException.class, () -> store.stage(List.of(invalid)));
        assertFalse(Files.exists(tempDir.resolve("memory/project_knowledge_valid_slug.md")));

        var crossLevel =
                new MemoryOperation(
                        "create",
                        "project",
                        "user_preference",
                        "title",
                        "valid_slug",
                        null,
                        "content");
        assertThrows(IllegalArgumentException.class, () -> store.stage(List.of(crossLevel)));
    }

    @Test
    void validatesAllCommitTargetsBeforeWritingAnyFile() {
        var store = new MemoryStore(tempDir.resolve("memory"), MemoryLevel.PROJECT);
        var staged =
                new MemoryStore.StagedMemory(
                        java.util.Map.of("project_knowledge_valid_slug.md", "note"),
                        "index",
                        java.util.Set.of("invalid.md"));

        assertThrows(IllegalArgumentException.class, () -> store.commit(staged));
        assertFalse(Files.exists(tempDir.resolve("memory/project_knowledge_valid_slug.md")));
        assertFalse(Files.exists(tempDir.resolve("memory/MEMORY.md")));
    }

    private static Exception unwrap(InvocationTargetException error) {
        Throwable cause = error.getCause();
        if (cause instanceof Exception exception) return exception;
        if (cause instanceof Error fatal) throw fatal;
        return new RuntimeException(cause);
    }
}
