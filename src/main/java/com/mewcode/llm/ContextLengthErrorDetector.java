package com.mewcode.llm;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 识别 provider 明确返回的上下文长度错误，不把普通请求错误误判成可恢复错误。 */
final class ContextLengthErrorDetector {

  private static final Set<String> MARKERS =
      Set.of(
          "prompt_too_long",
          "context_length_exceeded",
          "context length exceeded",
          "maximum context length",
          "max context length",
          "prompt is too long",
          "input is too long");

  private ContextLengthErrorDetector() {}

  static boolean isContextLength(Throwable error) {
    Set<Throwable> seen = new HashSet<>();
    Throwable current = error;
    while (current != null && seen.add(current)) {
      String details =
          (current.getClass().getName() + " " + String.valueOf(current.getMessage()))
              .toLowerCase(Locale.ROOT);
      if (MARKERS.stream().anyMatch(details::contains)) return true;
      current = current.getCause();
    }
    return false;
  }
}
