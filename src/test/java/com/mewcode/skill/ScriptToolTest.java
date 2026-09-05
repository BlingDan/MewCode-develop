package com.mewcode.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.agent.CancellationToken;
import com.mewcode.tool.FileStateCache;
import com.mewcode.tool.ToolExecutionContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScriptToolTest {

  @TempDir Path temp;

  @Test
  void validatesInputAndExecutesJsonProtocol() throws Exception {
    Path script =
        executable(
            "ok.sh", "#!/bin/sh\nread input\nprintf '{\"content\":\"ok\",\"is_error\":false}'\n");
    ScriptTool tool =
        new ScriptTool(
            new SkillDefinition.ToolSpec(
                "custom",
                "custom tool",
                Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of("path", Map.of("type", "string")),
                    "required",
                    List.of("path"),
                    "additionalProperties",
                    false),
                script),
            temp);

    assertTrue(tool.validateInput(Map.of()).contains("path"));
    assertTrue(tool.validateInput(Map.of("path", "x", "extra", true)).contains("extra"));
    assertEquals(null, tool.validateInput(Map.of("path", "x")));

    var result = tool.execute(context(new CancellationToken()), Map.of("path", "x"));
    assertFalse(result.isError(), result.content());
    assertEquals("ok", result.content());
  }

  @Test
  void turnsInvalidOutputAndCancellationIntoSafeErrors() throws Exception {
    Path bad = executable("bad.sh", "#!/bin/sh\necho SECRET >&2\necho nope\n");
    ScriptTool badTool =
        new ScriptTool(
            new SkillDefinition.ToolSpec("bad", "bad", Map.of("type", "object"), bad), temp);
    var invalid = badTool.execute(context(new CancellationToken()), Map.of());
    assertTrue(invalid.isError());
    assertFalse(invalid.content().contains("SECRET"));

    Path slow = executable("slow.sh", "#!/bin/sh\nsleep 10\n");
    ScriptTool slowTool =
        new ScriptTool(
            new SkillDefinition.ToolSpec("slow", "slow", Map.of("type", "object"), slow), temp);
    CancellationToken cancelled = new CancellationToken();
    cancelled.cancel();
    var result = slowTool.execute(context(cancelled), Map.of());
    assertTrue(result.isError());
    assertTrue(result.content().contains("取消"), result.content());
  }

  private Path executable(String name, String content) throws Exception {
    Path script = temp.resolve(name);
    Files.writeString(script, content);
    script.toFile().setExecutable(true);
    return script.toRealPath();
  }

  private ToolExecutionContext context(CancellationToken token) {
    return new ToolExecutionContext(
        temp.toAbsolutePath(), Duration.ofSeconds(2), new FileStateCache(), token);
  }
}
