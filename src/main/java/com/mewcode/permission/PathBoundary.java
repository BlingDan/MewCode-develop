package com.mewcode.permission;

/** 文件工具路径相对于项目根目录的安全边界。 */
public enum PathBoundary {
  INSIDE_PROJECT,
  OUTSIDE_PROJECT,
  INVALID
}
