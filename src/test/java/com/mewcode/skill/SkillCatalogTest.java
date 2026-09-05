package com.mewcode.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillCatalogTest {

  @TempDir Path temp;

  @Test
  void appliesThreeLevelOverrideAndFallsBackAfterHotRefresh() throws Exception {
    Path project = temp.resolve("project");
    Path home = temp.resolve("home");
    Path userSkills = home.resolve(".mewcode/skills");
    Path projectSkills = project.resolve(".mewcode/skills");
    Files.createDirectories(userSkills);
    Files.createDirectories(projectSkills);
    write(userSkills.resolve("review.md"), "review", "user review", "ReadFile");
    write(projectSkills.resolve("review.md"), "Review", "project review", "ReadFile");
    write(projectSkills.resolve("help.md"), "help", "reserved", "ReadFile");

    SkillCatalog catalog = SkillCatalog.load(project, home);
    SkillCatalog.RefreshResult first =
        catalog.refresh(Set.of("ReadFile", "Bash", "Grep", "Glob"), Set.of("help", "h"));

    assertEquals("project review", catalog.find("REVIEW").orElseThrow().meta().description());
    assertFalse(catalog.find("help").isPresent());
    assertTrue(first.diagnostics().stream().anyMatch(text -> text.contains("保留命令")));
    assertTrue(catalog.find("commit").isPresent());
    assertTrue(catalog.find("test").isPresent());

    Files.writeString(
        projectSkills.resolve("review.md"), "---\nname: review\ndescription:\n---\nbad\n");
    catalog.refreshHot(Set.of("ReadFile", "Bash", "Grep", "Glob"), Set.of("help", "h"));

    assertEquals("user review", catalog.find("review").orElseThrow().meta().description());

    write(projectSkills.resolve("review.md"), "review", "unknown tool", "MissingTool");
    catalog.refreshHot(Set.of("ReadFile", "Bash", "Grep", "Glob"), Set.of("help", "h"));
    assertEquals("user review", catalog.find("review").orElseThrow().meta().description());
  }

  @Test
  void reportsUnknownToolsAndKeepsOnlySummaryInCatalogPrompt() throws Exception {
    Path project = temp.resolve("p2");
    Path home = temp.resolve("h2");
    Path skills = project.resolve(".mewcode/skills");
    Files.createDirectories(skills);
    write(skills.resolve("custom.md"), "custom", "one line", "MissingTool");

    SkillCatalog catalog = SkillCatalog.load(project, home);
    SkillCatalog.RefreshResult result =
        catalog.refresh(Set.of("ReadFile", "Bash", "Grep", "Glob"), Set.of("help"));

    assertEquals(1, result.missingTools().size());
    assertEquals("custom", result.missingTools().getFirst().skill());
    assertTrue(catalog.promptSummary().contains("custom: one line"));
    assertFalse(catalog.promptSummary().contains("Do the secret work"));
  }

  @Test
  void hotRefreshDropsScriptToolConflictsAndFallsBack() throws Exception {
    Path userSkills = temp.resolve("home/.mewcode/skills");
    Path projectSkills = temp.resolve("project/.mewcode/skills");
    Files.createDirectories(userSkills);
    Files.createDirectories(projectSkills);
    write(userSkills.resolve("same.md"), "same", "user version", "");
    writeDirectorySkill(projectSkills.resolve("same"), "same", "project version", "ReadFile");
    SkillCatalog catalog = SkillCatalog.load(temp.resolve("project"), temp.resolve("home"));

    SkillCatalog.RefreshResult result =
        catalog.refreshHot(Set.of("ReadFile", "LoadSkill"), Set.of());

    assertEquals("user version", catalog.find("same").orElseThrow().meta().description());
    assertTrue(result.diagnostics().stream().anyMatch(text -> text.contains("工具名称冲突")));
  }

  private static void write(Path file, String name, String description, String tool)
      throws Exception {
    Files.writeString(
        file,
        "---\nname: "
            + name
            + "\ndescription: "
            + description
            + "\ntools: ["
            + tool
            + "]\n---\nDo the secret work\n");
  }

  private static void writeDirectorySkill(
      Path directory, String name, String description, String tool) throws Exception {
    Files.createDirectories(directory);
    Files.writeString(
        directory.resolve("SKILL.md"),
        "---\nname: "
            + name
            + "\ndescription: "
            + description
            + "\ntools: ["
            + tool
            + "]\n---\nRun.\n");
    Path script = directory.resolve("run.sh");
    Files.writeString(script, "#!/bin/sh\necho '{\"content\":\"ok\",\"is_error\":false}'\n");
    script.toFile().setExecutable(true);
    Files.writeString(
        directory.resolve("tool.json"),
        "{\"tools\":[{\"name\":\""
            + tool
            + "\",\"description\":\"conflict\",\"input_schema\":{\"type\":\"object\"},\"script\":\"run.sh\"}]}");
  }
}
