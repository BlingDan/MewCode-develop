package com.mewcode.permission;

import java.util.Locale;

/** 根据当前操作系统选择 Bash 沙箱适配器。 */
public final class BashSandboxFactory {
  private BashSandboxFactory() {}

  public static BashSandbox create() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (os.contains("mac")) return new MacSeatbeltSandbox();
    if (os.contains("linux")) return new LinuxBubblewrapSandbox();
    return new UnavailableBashSandbox("当前操作系统没有受支持的 Bash OS 沙箱");
  }

  private record UnavailableBashSandbox(String message) implements BashSandbox {
    @Override
    public boolean isAvailable() {
      return false;
    }

    @Override
    public SandboxedProcess prepare(BashSandboxRequest request) throws java.io.IOException {
      throw new java.io.IOException(message + "，Bash 已安全拒绝执行");
    }
  }
}
