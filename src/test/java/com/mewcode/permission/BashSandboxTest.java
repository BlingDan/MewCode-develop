package com.mewcode.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BashSandboxTest {
  @TempDir Path projectRoot;

  @Test
  void buildsAParameterizedProcessAndKeepsTheCommandAsOneArgument() throws Exception {
    BashSandbox sandbox = new RecordingSandbox();
    String command = "printf '%s' 'a; echo should-not-be-a-wrapper'";
    SandboxedProcess process =
        sandbox.prepare(new BashSandboxRequest(command, projectRoot, List.of(projectRoot)));

    assertEquals(command, process.argv().getLast());
    assertEquals(projectRoot.toAbsolutePath().normalize(), process.workingDirectory());
    assertEquals("fake-sandbox", process.argv().getFirst());
  }

  @Test
  void unavailableSandboxFailsClosed() {
    BashSandbox sandbox =
        new BashSandbox() {
          @Override
          public boolean isAvailable() {
            return false;
          }

          @Override
          public SandboxedProcess prepare(BashSandboxRequest request) throws IOException {
            throw new IOException("sandbox unavailable");
          }
        };

    assertFalse(sandbox.isAvailable());
    assertThrows(
        IOException.class,
        () ->
            sandbox.prepare(
                new BashSandboxRequest("printf ok", projectRoot, List.of(projectRoot))));
  }

  @Test
  void factorySelectsTheCurrentPlatformAdapter() {
    BashSandbox sandbox = BashSandboxFactory.create();
    assertTrue(sandbox != null);
  }

  private static final class RecordingSandbox implements BashSandbox {
    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public SandboxedProcess prepare(BashSandboxRequest request) {
      return new SandboxedProcess(
          List.of("fake-sandbox", "/bin/sh", "-c", request.command()), request.projectRoot());
    }
  }
}
