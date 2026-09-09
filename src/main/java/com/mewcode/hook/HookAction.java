package com.mewcode.hook;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Hook 支持的固定动作数据。 */
public sealed interface HookAction
    permits HookAction.Shell, HookAction.Prompt, HookAction.Http, HookAction.Subagent {
  record Shell(String command) implements HookAction {
    public Shell {
      requireText(command, "command");
    }
  }

  record Prompt(String text) implements HookAction {
    public Prompt {
      requireText(text, "text");
    }
  }

  record Http(URI url, String method, Map<String, String> headers, Optional<String> body)
      implements HookAction {
    public Http {
      if (url == null || !isHttpUrl(url)) throw new IllegalArgumentException("url must be http(s)");
      requireText(method, "method");
      method = method.trim().toUpperCase(Locale.ROOT);
      headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
      body = Objects.requireNonNull(body, "body");
      body.ifPresent(value -> requireText(value, "body"));
    }

    private static boolean isHttpUrl(URI url) {
      return ("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme()))
          && url.getHost() != null;
    }
  }

  record Subagent(String agentName, String prompt) implements HookAction {
    public Subagent {
      requireText(agentName, "agentName");
      requireText(prompt, "prompt");
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
