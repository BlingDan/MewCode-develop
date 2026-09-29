package com.mewcode.permission;

import java.io.IOException;
import java.util.Locale;

/** Bash OS 级进程沙箱的跨平台接口。 */
public interface BashSandbox {
  boolean isAvailable();

  SandboxedProcess prepare(BashSandboxRequest request) throws IOException;

  /** 返回当前操作系统支持的沙箱；不支持时拒绝执行 Bash。 */
  static BashSandbox create() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (os.contains("mac")) return new MacSeatbeltSandbox();
    if (os.contains("linux")) return new LinuxBubblewrapSandbox();
    return new BashSandbox() {
      @Override
      public boolean isAvailable() {
        return false;
      }

      @Override
      public SandboxedProcess prepare(BashSandboxRequest request) throws IOException {
        throw new IOException("当前操作系统没有受支持的 Bash OS 沙箱，Bash 已安全拒绝执行");
      }
    };
  }
}
