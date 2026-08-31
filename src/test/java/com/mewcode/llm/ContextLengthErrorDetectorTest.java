package com.mewcode.llm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ContextLengthErrorDetectorTest {

  @Test
  void recognizesProviderContextLengthCodesAndMessages() {
    assertTrue(ContextLengthErrorDetector.isContextLength(new RuntimeException("prompt_too_long")));
    assertTrue(
        ContextLengthErrorDetector.isContextLength(
            new RuntimeException("context_length_exceeded: maximum context length")));
    assertFalse(
        ContextLengthErrorDetector.isContextLength(
            new RuntimeException("invalid request: malformed tool arguments")));
    assertFalse(
        ContextLengthErrorDetector.isContextLength(
            new RuntimeException("context window is not configured")));
  }
}
