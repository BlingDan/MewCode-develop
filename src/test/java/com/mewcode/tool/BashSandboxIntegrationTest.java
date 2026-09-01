package com.mewcode.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.permission.BashSandboxFactory;
import com.mewcode.tool.impl.BashTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BashSandboxIntegrationTest {
  @TempDir Path projectRoot;

  @Test
  void allowsProjectWritesButRejectsWritesOutsideTheSandbox() throws Exception {
    Assumptions.assumeTrue(BashSandboxFactory.create().isAvailable());
    Path outside = projectRoot.resolveSibling("mewcode-bash-sandbox-outside.txt");
    Path inside = projectRoot.resolve("inside.txt");
    try {
      ToolExecutionContext context =
          new ToolExecutionContext(projectRoot, Duration.ofSeconds(2), new FileStateCache());
      ToolResult insideResult =
          new BashTool()
              .execute(
                  context, java.util.Map.of("command", "printf inside > " + shellQuote(inside)));
      ToolResult outsideResult =
          new BashTool()
              .execute(
                  context, java.util.Map.of("command", "printf outside > " + shellQuote(outside)));

      assertFalse(insideResult.isError(), insideResult.content());
      assertTrue(Files.exists(inside));
      assertTrue(outsideResult.isError(), outsideResult.content());
      assertFalse(Files.exists(outside));
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  @Test
  void allowsCommandsToWriteToTheStandardNullDevice() throws Exception {
    Assumptions.assumeTrue(BashSandboxFactory.create().isAvailable());
    ToolExecutionContext context =
        new ToolExecutionContext(projectRoot, Duration.ofSeconds(2), new FileStateCache());

    ToolResult result =
        new BashTool().execute(context, java.util.Map.of("command", "printf ignored > /dev/null"));

    assertFalse(result.isError(), result.content());
  }

  private static String shellQuote(Path path) {
    return "'" + path.toString().replace("'", "'\\''") + "'";
  }
}
