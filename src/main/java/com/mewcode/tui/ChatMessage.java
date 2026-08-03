package com.mewcode.tui;

/** A finalized message shown in terminal scrollback. */
public record ChatMessage(String role, String content, double elapsedSeconds) {}
