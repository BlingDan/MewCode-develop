package com.mewcode.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DangerousCommandBlocklistTest {
  private final DangerousCommandBlocklist blocklist = new DangerousCommandBlocklist();

  @Test
  void blocksDangerousRootRemovalAndEquivalentFlagSpacing() {
    assertEquals("rm -rf /", blocklist.findMatch("rm -rf /").orElseThrow());
    assertTrue(blocklist.findMatch("sudo rm -r -f -- /").isPresent());
    assertTrue(blocklist.findMatch("rm -f -r /").isPresent());
    assertTrue(blocklist.findMatch("rm --recursive --force / && echo never").isPresent());
  }

  @Test
  void doesNotTreatOrdinaryCommandsAsDangerous() {
    assertTrue(blocklist.findMatch("git status").isEmpty());
    assertTrue(blocklist.findMatch("rm -rf ./build").isEmpty());
  }

  @Test
  void usesTheRequiredHardDenyMessage() {
    String message = blocklist.rejectionMessage("rm -rf /", "rm -rf /");
    assertEquals("操作被拒绝：检测到危险命令 \"rm -rf /\"。\n" + "此操作可能造成不可逆的系统损坏，已被安全策略硬拦截。", message);
  }
}
