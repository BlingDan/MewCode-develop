package com.mewcode.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mewcode.permission.PermissionMode;
import com.mewcode.permission.RuleSource;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PermissionConfigLoaderTest {
  @TempDir Path projectRoot;

  @Test
  void loadsLocalRulesBeforeProjectRulesAndParsesMode() throws Exception {
    Path configDirectory = Files.createDirectories(projectRoot.resolve(".mewcode"));
    Files.writeString(
        projectRoot.resolve(".mewcode/permissions.yaml"),
        "rules:\n  - pattern: 'Bash(git *)'\n    decision: allow\n");
    Files.writeString(
        configDirectory.resolve("permissions.local.yaml"),
        "rules:\n  - pattern: 'Bash(git *)'\n    decision: deny\n");
    var config = new PermissionConfig();
    config.setMode("acceptEdits");

    var loaded = PermissionConfigLoader.load(projectRoot, config);
    assertEquals(PermissionMode.ACCEPT_EDITS, loaded.mode());
    assertEquals(
        RuleSource.LOCAL,
        loaded
            .ruleEngine()
            .match(
                new com.mewcode.tool.ToolCall(
                    "call-1", "Bash", java.util.Map.of("command", "git status")))
            .orElseThrow()
            .rule()
            .source());
  }

  @Test
  void rejectsInvalidRuleFileInsteadOfIgnoringIt() throws Exception {
    Files.createDirectories(projectRoot.resolve(".mewcode"));
    Files.writeString(
        projectRoot.resolve(".mewcode/permissions.yaml"),
        "rules:\n  - pattern: 'Bash(git *)'\n    decision: ask\n");

    assertThrows(
        ConfigLoader.ConfigException.class,
        () -> PermissionConfigLoader.load(projectRoot, new PermissionConfig()));
  }
}
