package com.mewcode.prompt;

import java.util.Objects;

/** 一个可独立装配的系统提示模块。 */
public record PromptModule(String name, int priority, String content) {

  public PromptModule {
    Objects.requireNonNull(name, "name");
    if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
    content = content == null ? "" : content;
  }
}
