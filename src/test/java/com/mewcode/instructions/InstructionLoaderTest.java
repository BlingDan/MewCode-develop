package com.mewcode.instructions;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstructionLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsInstructionLayersInPriorityOrder() throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Path userHome = tempDir.resolve("home");
        Files.createDirectories(projectRoot.resolve(".mewcode"));
        Files.createDirectories(userHome.resolve(".mewcode"));
        Files.writeString(projectRoot.resolve("MEWCODE.md"), "ROOT");
        Files.writeString(projectRoot.resolve(".mewcode/MEWCODE.md"), "PROJECT");
        Files.writeString(userHome.resolve(".mewcode/MEWCODE.md"), "USER");

        String loaded = load(projectRoot, userHome);

        assertTrue(loaded.indexOf("ROOT") < loaded.indexOf("PROJECT"));
        assertTrue(loaded.indexOf("PROJECT") < loaded.indexOf("USER"));
    }

    @Test
    void missingIncludeLeavesHtmlMarkerAndContinues() throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Path userHome = tempDir.resolve("home");
        Files.createDirectories(projectRoot);
        Files.writeString(
                projectRoot.resolve("MEWCODE.md"),
                "before\n@include missing.md\nafter");

        String loaded = load(projectRoot, userHome);

        assertTrue(loaded.contains("before"));
        assertTrue(loaded.contains("<!--"));
        assertTrue(loaded.contains("after"));
    }

    @Test
    void rejectsCycleDepthAndBoundaryWhileKeepingOtherLayers() throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Path userHome = tempDir.resolve("home");
        Files.createDirectories(projectRoot.resolve(".mewcode"));
        Files.createDirectories(userHome.resolve(".mewcode"));
        Files.writeString(projectRoot.resolve(".mewcode/MEWCODE.md"), "PROJECT_OK");
        Files.writeString(userHome.resolve(".mewcode/MEWCODE.md"), "USER_OK");
        Files.writeString(tempDir.resolve("secret.md"), "SECRET");

        Files.writeString(projectRoot.resolve("MEWCODE.md"), "@include ../secret.md");
        InstructionLoadResult boundary = new InstructionLoader(projectRoot, userHome).load();
        assertTrue(boundary.text().contains("PROJECT_OK"));
        assertTrue(boundary.text().contains("USER_OK"));
        assertFalse(boundary.text().contains("SECRET"));
        assertTrue(boundary.diagnostics().size() == 1);

        Files.writeString(projectRoot.resolve("cycle-a.md"), "@include cycle-b.md");
        Files.writeString(projectRoot.resolve("cycle-b.md"), "@include cycle-a.md");
        Files.writeString(projectRoot.resolve("MEWCODE.md"), "@include cycle-a.md");
        InstructionLoadResult cycle = new InstructionLoader(projectRoot, userHome).load();
        assertTrue(cycle.text().contains("PROJECT_OK"));
        assertTrue(cycle.text().contains("USER_OK"));
        assertTrue(cycle.diagnostics().size() == 1);

        for (int index = 1; index <= 6; index++) {
            String content = index == 6 ? "DEEP" : "@include deep-" + (index + 1) + ".md";
            Files.writeString(projectRoot.resolve("deep-" + index + ".md"), content);
        }
        Files.writeString(projectRoot.resolve("MEWCODE.md"), "@include deep-1.md");
        InstructionLoadResult deep = new InstructionLoader(projectRoot, userHome).load();
        assertTrue(deep.text().contains("PROJECT_OK"));
        assertTrue(deep.text().contains("USER_OK"));
        assertFalse(deep.text().contains("DEEP"));
        assertTrue(deep.diagnostics().size() == 1);
    }

    private static String load(Path projectRoot, Path userHome) throws Exception {
        try {
            Class<?> type = Class.forName("com.mewcode.instructions.InstructionLoader");
            Object loader =
                    type.getConstructor(Path.class, Path.class).newInstance(projectRoot, userHome);
            Object result = type.getMethod("load").invoke(loader);
            String text = (String) result.getClass().getMethod("text").invoke(result);
            if (text.isEmpty()) {
                throw new AssertionError(result.getClass().getMethod("diagnostics").invoke(result));
            }
            return text;
        } catch (ClassNotFoundException error) {
            fail("InstructionLoader 尚未实现", error);
            return "";
        } catch (InvocationTargetException error) {
            throw unwrap(error);
        }
    }

    private static Exception unwrap(InvocationTargetException error) {
        Throwable cause = error.getCause();
        if (cause instanceof Exception exception) return exception;
        if (cause instanceof Error fatal) throw fatal;
        return new RuntimeException(cause);
    }
}
