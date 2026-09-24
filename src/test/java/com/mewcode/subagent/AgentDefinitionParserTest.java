package com.mewcode.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentDefinitionParserTest {

  private static final Path SOURCE = Path.of("/tmp/test-agent.md");

  @Test
  void bindsAllFieldsAndKeepsMissingWhitelistDistinctFromEmptyWhitelist() {
    var parsed =
        AgentDefinitionParser.parse(
            """
            ---
            name: reviewer
            description: Review code
            disallowedTools: [Bash]
            model: sonnet
            maxTurns: 7
            permissionMode: dontAsk
            ---
            Read the diff.
            """,
            SOURCE,
            SubAgentSpec.Source.PROJECT,
            20);

    assertEquals("reviewer", parsed.name());
    assertEquals("Review code", parsed.description());
    assertNull(parsed.tools());
    assertEquals(java.util.Set.of("Bash"), parsed.disallowedTools());
    assertEquals("Read the diff.", parsed.systemPrompt());
    assertEquals(7, parsed.maxTurns());
    assertEquals("sonnet", parsed.model());
    assertEquals(SubAgentSpec.PermissionMode.DONT_ASK, parsed.permissionMode());

    var empty =
        AgentDefinitionParser.parse(
            "---\nname: empty\ndescription: Empty\ntools: []\n---\nbody\n",
            SOURCE,
            SubAgentSpec.Source.USER,
            12);
    assertEquals(java.util.Set.of(), empty.tools());
  }

  @Test
  void usesDefaultTurnsAndRejectsInvalidMetadata() {
    var parsed =
        AgentDefinitionParser.parse(
            "---\nname: defaulted\ndescription: Default\n---\nbody\n",
            SOURCE,
            SubAgentSpec.Source.BUILTIN,
            13);
    assertEquals(13, parsed.maxTurns());
    assertEquals("inherit", parsed.model());

    for (String metadata : new String[] {"model: unknown", "permissionMode: ask", "maxTurns: 0"}) {
      assertThrows(
          AgentDefinitionParser.ParseException.class,
          () ->
              AgentDefinitionParser.parse(
                  "---\nname: invalid\ndescription: Invalid\n" + metadata + "\n---\nbody\n",
                  SOURCE,
                  SubAgentSpec.Source.PROJECT,
                  10));
    }
  }
}
