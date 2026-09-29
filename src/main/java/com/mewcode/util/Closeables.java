package com.mewcode.util;

/** 关闭必须继续收尾的资源，不让清理失败覆盖原始结果。 */
public final class Closeables {
  private Closeables() {}

  public static void closeQuietly(AutoCloseable closeable) {
    if (closeable == null) return;
    try {
      closeable.close();
    } catch (Exception ignored) {
    }
  }
}
