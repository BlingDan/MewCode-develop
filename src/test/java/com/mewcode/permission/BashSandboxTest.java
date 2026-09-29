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
    BashSandbox sandbox = BashSandbox.create();
    assertTrue(sandbox != null);
  }

  @Test
  void linuxMountsReadOnlyOverlaysAfterWritableDirectories() throws Exception {
    Path child = java.nio.file.Files.createDirectory(projectRoot.resolve("child"));
    Path shared = java.nio.file.Files.createDirectory(projectRoot.resolve("shared"));
    var request =
        new BashSandboxRequest(
            "true", child, List.of(child), List.of(shared), projectRoot, List.of(child, shared));
    List<String> argv = LinuxBubblewrapSandbox.arguments(request);
    assertTrue(argv.contains("--unshare-pid"));
    assertTrue(argv.indexOf("--tmpfs") < argv.indexOf("--bind"));
    assertTrue(argv.lastIndexOf("--ro-bind") > argv.indexOf("--bind"));
    assertEquals(
        child.toAbsolutePath().normalize().toString(), argv.get(argv.indexOf("--chdir") + 1));
    assertEquals("true", argv.getLast());
  }

  @Test
  void linuxMasksMissingProtectedRegionsRatherThanLeavingThemWritable() throws Exception {
    Path absent = projectRoot.resolve(".mewcode/worktrees");
    var request =
        new BashSandboxRequest(
            "true", projectRoot, List.of(projectRoot), List.of(absent), null, List.of());
    var argv = LinuxBubblewrapSandbox.arguments(request);
    int mask = argv.indexOf("--tmpfs");
    assertTrue(mask > argv.indexOf("--bind"));
    assertEquals(absent.toString(), argv.get(mask + 1));
    assertEquals("--remount-ro", argv.get(mask + 2));
    assertEquals(absent.toString(), argv.get(mask + 3));
    assertFalse(java.nio.file.Files.exists(absent));
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
