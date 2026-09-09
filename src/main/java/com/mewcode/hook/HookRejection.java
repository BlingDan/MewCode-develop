package com.mewcode.hook;

/** Hook 对可拦截事件返回的业务拒绝。 */
public record HookRejection(String hookName, String reason) {
  public HookRejection {
    if (hookName == null || hookName.isBlank()) {
      throw new IllegalArgumentException("hookName must not be blank");
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("reason must not be blank");
    }
  }
}
