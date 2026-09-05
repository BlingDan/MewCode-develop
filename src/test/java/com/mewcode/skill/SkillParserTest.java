package com.mewcode.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillParserTest {

  @TempDir Path temp;

  @Test
  void parsesSingleFileDefaultsAndDirectoryTool() throws Exception {
    Path single = temp.resolve("hello.md");
    Files.writeString(
        single, "---\nname: Hello\ndescription: Greets once\n---\nSay {{arguments}}\n");

    SkillDefinition parsed = SkillParser.parse(single, SkillDefinition.Source.PROJECT);

    assertEquals("hello", parsed.meta().name());
    assertEquals(SkillDefinition.Mode.SHARED, parsed.meta().mode());
    assertEquals(3, parsed.meta().contextCount());
    assertEquals("Say {{arguments}}\n", parsed.body());

    Path directory = temp.resolve("inspect");
    Files.createDirectories(directory.resolve("tools"));
    Files.writeString(
        directory.resolve("SKILL.md"),
        "---\nname: inspect\ndescription: Inspect a report\ntools: [inspect_report]\nmode: fork\ncontext: recent\ncontext_count: 4\n---\nInspect it.\n");
    Path script = directory.resolve("tools/inspect.sh");
    Files.writeString(script, "#!/bin/sh\necho ok\n");
    script.toFile().setExecutable(true);
    Files.writeString(
        directory.resolve("tool.json"),
        """
        {"tools":[{"name":"inspect_report","description":"Inspect report",
        "input_schema":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]},
        "script":"tools/inspect.sh"}]}
        """);

    SkillDefinition directorySkill =
        SkillParser.parse(directory.resolve("SKILL.md"), SkillDefinition.Source.USER);

    assertEquals(SkillDefinition.Mode.FORK, directorySkill.meta().mode());
    assertEquals(SkillDefinition.ForkContext.RECENT, directorySkill.meta().context());
    assertEquals(4, directorySkill.meta().contextCount());
    assertEquals(script.toRealPath(), directorySkill.tools().getFirst().executable());
  }

  @Test
  void rejectsInvalidMetadataWithoutEchoingBody() throws Exception {
    Path entry = temp.resolve("bad.md");
    Files.writeString(
        entry, "---\nname: bad\ndescription: Bad\ncontext_count: 0\n---\nSECRET BODY\n");

    SkillParser.ParseException error =
        assertThrows(
            SkillParser.ParseException.class,
            () -> SkillParser.parse(entry, SkillDefinition.Source.PROJECT));

    assertTrue(error.getMessage().contains("context_count"));
    assertTrue(error.getMessage().contains(entry.toString()));
    assertTrue(!error.getMessage().contains("SECRET BODY"));
  }

  @Test
  void rejectsInvalidToolSchemaDuringParsing() throws Exception {
    Path directory = temp.resolve("bad-schema");
    Files.createDirectories(directory);
    Files.writeString(
        directory.resolve("SKILL.md"),
        "---\nname: bad-schema\ndescription: Bad schema\n---\nRun.\n");
    Path script = directory.resolve("run.sh");
    Files.writeString(script, "#!/bin/sh\necho ok\n");
    script.toFile().setExecutable(true);
    Files.writeString(
        directory.resolve("tool.json"),
        """
        {"tools":[{"name":"bad","description":"Bad",
        "input_schema":{"type":"object","required":"path"},"script":"run.sh"}]}
        """);

    SkillParser.ParseException error =
        assertThrows(
            SkillParser.ParseException.class,
            () -> SkillParser.parse(directory.resolve("SKILL.md"), SkillDefinition.Source.PROJECT));

    assertTrue(error.getMessage().contains("required"), error.getMessage());
  }
}
