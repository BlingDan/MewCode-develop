package com.mewcode.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.hook.HookAction;
import com.mewcode.hook.HookEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HookConfigLoaderTest {
  @TempDir Path projectRoot;

  @Test
  void loadsValidRulesWithDefaultsAndStructuredConditions() throws Exception {
    Path projectFile =
        writeProjectHooks(
            "hooks:\n"
                + "  - name: startup-reminder\n"
                + "    event: startup\n"
                + "    if:\n"
                + "      all_of:\n"
                + "        - field: tool_input.command\n"
                + "          match: {type: glob, value: 'git *'}\n"
                + "    action:\n"
                + "      type: prompt\n"
                + "      text: remember this\n"
                + "  - name: notify\n"
                + "    event: Stop\n"
                + "    async: true\n"
                + "    timeout: 250ms\n"
                + "    action:\n"
                + "      type: http\n"
                + "      url: http://localhost:8080/hook\n"
                + "      headers: {X-Test: value}\n"
                + "      body: '${request_id}'\n");
    var diagnostics = new ArrayList<String>();

    var loaded = HookConfigLoader.load(projectRoot, projectRoot.resolve("user"), diagnostics::add);

    assertEquals(2, loaded.rules().size());
    assertEquals(List.of(projectFile.toAbsolutePath().normalize()), loaded.sources());
    var first = loaded.rules().getFirst();
    assertEquals(HookEvent.STARTUP, first.event());
    assertEquals(Duration.ofSeconds(30), first.timeout());
    assertFalse(first.onlyOnce());
    assertFalse(first.async());
    assertTrue(first.condition().isPresent());
    assertTrue(first.action() instanceof HookAction.Prompt);
    var second = loaded.rules().get(1);
    assertEquals(Duration.ofMillis(250), second.timeout());
    assertTrue(second.async());
    assertTrue(second.action() instanceof HookAction.Http);
    assertTrue(diagnostics.isEmpty(), diagnostics.toString());
  }

  @Test
  void mergesProjectBeforeUserAndOnlyLegalNamesReserveConflicts() throws Exception {
    Path projectFile =
        writeProjectHooks(
            "hooks:\n"
                + "  - name: shared\n"
                + "    event: startup\n"
                + "    action: {type: prompt, text: project}\n"
                + "  - name: invalid-shared\n"
                + "    event: startup\n"
                + "    action: {type: unknown, text: ignored}\n");
    Path userRoot = Files.createDirectories(projectRoot.resolve("user"));
    Path userFile = Files.createDirectories(userRoot.resolve(".mewcode")).resolve("hooks.yaml");
    Files.writeString(
        userFile,
        "hooks:\n"
            + "  - name: shared\n"
            + "    event: shutdown\n"
            + "    action: {type: prompt, text: user}\n"
            + "  - name: invalid-shared\n"
            + "    event: shutdown\n"
            + "    action: {type: prompt, text: user-valid}\n");
    var diagnostics = new ArrayList<String>();

    var loaded = HookConfigLoader.load(projectRoot, userRoot, diagnostics::add);

    assertEquals(2, loaded.rules().size());
    assertEquals(HookEvent.STARTUP, loaded.rules().getFirst().event());
    assertEquals(HookEvent.SHUTDOWN, loaded.rules().get(1).event());
    assertEquals(
        List.of(projectFile.toAbsolutePath().normalize(), userFile.toAbsolutePath().normalize()),
        loaded.sources());
    assertTrue(diagnostics.stream().anyMatch(message -> message.contains("shared")));
    assertTrue(diagnostics.stream().anyMatch(message -> message.contains("invalid-shared")));
  }

  @Test
  void skipsInvalidFilesAndRulesWithoutBlockingOtherSources() throws Exception {
    Path projectFile =
        Files.createDirectories(projectRoot.resolve(".mewcode")).resolve("hooks.yaml");
    Files.writeString(
        projectFile,
        "hooks: [\n  - name: broken\n    event: startup\n    action: {type: prompt, text: ok}\n");
    Path userRoot = Files.createDirectories(projectRoot.resolve("user"));
    Path userFile = Files.createDirectories(userRoot.resolve(".mewcode")).resolve("hooks.yaml");
    Files.writeString(
        userFile,
        "hooks:\n"
            + "  - name: missing\n"
            + "    event: startup\n"
            + "    action: {type: prompt}\n"
            + "  - name: bad-regex\n"
            + "    event: startup\n"
            + "    if: {all_of: [{field: x, match: {type: regex, value: '['}}]}\n"
            + "    action: {type: prompt, text: no}\n"
            + "  - name: blocking-async\n"
            + "    event: PreToolUse\n"
            + "    async: true\n"
            + "    action: {type: shell, command: 'exit 0'}\n"
            + "  - name: valid\n"
            + "    event: startup\n"
            + "    only_once: true\n"
            + "    timeout: 1s\n"
            + "    action: {type: prompt, text: 'yes'}\n");
    var diagnostics = new ArrayList<String>();

    var loaded = HookConfigLoader.load(projectRoot, userRoot, diagnostics::add);

    assertEquals(List.of("valid"), loaded.rules().stream().map(rule -> rule.name()).toList());
    assertEquals(List.of(userFile.toAbsolutePath().normalize()), loaded.sources());
    assertTrue(diagnostics.stream().anyMatch(message -> message.contains("无法解析")));
    assertTrue(diagnostics.stream().anyMatch(message -> message.contains("missing")));
    assertTrue(diagnostics.stream().anyMatch(message -> message.contains("bad-regex")));
    assertTrue(diagnostics.stream().anyMatch(message -> message.contains("blocking-async")));
  }

  @Test
  void duplicateKeysAndInvalidConditionShapesAreRejected() throws Exception {
    Path file =
        writeProjectHooks(
            "hooks:\n"
                + "  - name: duplicate\n"
                + "    name: duplicate-again\n"
                + "    event: startup\n"
                + "    action: {type: prompt, text: no}\n");
    var diagnostics = new ArrayList<String>();

    var loaded =
        HookConfigLoader.load(projectRoot, projectRoot.resolve("missing-user"), diagnostics::add);

    assertTrue(loaded.rules().isEmpty());
    assertTrue(loaded.sources().isEmpty());
    assertTrue(
        diagnostics.stream().anyMatch(message -> message.contains(file.getFileName().toString())));
  }

  private Path writeProjectHooks(String content) throws Exception {
    Path directory = Files.createDirectories(projectRoot.resolve(".mewcode"));
    Path file = directory.resolve("hooks.yaml");
    Files.writeString(file, content);
    return file;
  }
}
