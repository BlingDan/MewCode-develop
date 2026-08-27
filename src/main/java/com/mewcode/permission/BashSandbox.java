package com.mewcode.permission;

import java.io.IOException;

/** Bash OS 级进程沙箱的跨平台接口。 */
public interface BashSandbox {
  boolean isAvailable();

  SandboxedProcess prepare(BashSandboxRequest request) throws IOException;
}
