package com.mewcode.config;

/** SubAgent 的全局运行配置。 */
public final class SubAgentConfig {

  public static final long DEFAULT_AUTO_BACKGROUND_MS = 20_000L;

  private long autoBackgroundMs = DEFAULT_AUTO_BACKGROUND_MS;

  public long getAutoBackgroundMs() {
    return autoBackgroundMs;
  }

  public void setAutoBackgroundMs(long autoBackgroundMs) {
    this.autoBackgroundMs = autoBackgroundMs;
  }

  public void validate() {
    if (autoBackgroundMs <= 0) {
      throw new IllegalArgumentException("autoBackgroundMs must be positive");
    }
  }

  public SubAgentConfig copy() {
    var copy = new SubAgentConfig();
    copy.setAutoBackgroundMs(autoBackgroundMs);
    copy.validate();
    return copy;
  }
}
