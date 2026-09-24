package com.mewcode.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentCatalogTest {

  @TempDir Path temp;

  @Test
  void appliesPluginBuiltinUserProjectPriorityAndFallsBackFromInvalidVersion() throws Exception {
    Path project = temp.resolve("project");
    Path user = temp.resolve("user");
    Path plugin = temp.resolve("plugin");
    Files.createDirectories(project.resolve(".mewcode/agents"));
    Files.createDirectories(user.resolve(".mewcode/agents"));
    Files.createDirectories(plugin);
    write(plugin.resolve("role.md"), "plugin");
    write(user.resolve(".mewcode/agents/role.md"), "user");
    write(project.resolve(".mewcode/agents/role.md"), "project");

    var catalog = AgentCatalog.load(project, user, List.of(plugin), 20);
    assertEquals("project", catalog.find("role").orElseThrow().systemPrompt().strip());

    Files.writeString(
        project.resolve(".mewcode/agents/role.md"),
        "---\nname: role\ndescription: bad\nmodel: invalid\n---\nproject\n");
    var fallback = AgentCatalog.load(project, user, List.of(plugin), 20);
    assertEquals("user", fallback.find("role").orElseThrow().systemPrompt().strip());
    assertTrue(fallback.diagnostics().stream().anyMatch(text -> text.contains("role.md")));
    assertTrue(fallback.find("general-purpose").isPresent());
  }

  @Test
  void returnsStableSummaryAndUnknownToolDiagnostics() throws Exception {
    Path project = temp.resolve("project");
    Path user = temp.resolve("user");
    Files.createDirectories(project.resolve(".mewcode/agents"));
    Files.writeString(
        project.resolve(".mewcode/agents/a.md"),
        "---\nname: a\ndescription: A role\ntools: [ReadFile, Missing]\n---\nbody\n");

    var catalog = AgentCatalog.load(project, user, List.of(), 20);
    assertTrue(catalog.promptSummary().contains("a: A role"));
    assertTrue(
        catalog.diagnoseUnknownTools(java.util.Set.of("ReadFile")).stream()
            .anyMatch(text -> text.contains("a.md")));
  }

  private static void write(Path path, String body) throws Exception {
    Files.createDirectories(path.getParent());
    Files.writeString(path, "---\nname: role\ndescription: role\n---\n" + body + "\n");
  }
}
