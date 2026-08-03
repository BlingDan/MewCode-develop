package com.mewcode.tui;

import com.github.ajalt.mordant.markdown.Markdown;
import com.github.ajalt.mordant.rendering.AnsiLevel;
import com.github.ajalt.mordant.rendering.Theme;
import com.github.ajalt.mordant.terminal.Terminal;
import com.github.ajalt.mordant.terminal.TerminalInterface;
import com.github.ajalt.mordant.terminal.TerminalInterfaceProvider;

import java.util.ServiceLoader;

/** Renders complete Markdown messages to ANSI without printing them directly. */
public final class MarkdownRenderer {

    private MarkdownRenderer() {}

    public static String render(String markdown, int width) {
        if (markdown == null || markdown.isEmpty()) return "";

        int safeWidth = Math.max(width, 20);
        TerminalInterface terminalInterface = ServiceLoader.load(TerminalInterfaceProvider.class)
                .findFirst()
                .map(TerminalInterfaceProvider::load)
                .orElseThrow(() -> new IllegalStateException("No Mordant terminal provider available"));
        var terminal = new Terminal(
                AnsiLevel.ANSI256,
                Theme.Companion.getDefault(),
                safeWidth,
                null,
                safeWidth,
                24,
                false,
                4,
                true,
                terminalInterface);
        return terminal.render(new Markdown(markdown, false, false));
    }
}
