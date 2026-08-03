package com.mewcode.tui;

import com.mewcode.tui.tea.ANSI256Color;
import com.mewcode.tui.tea.Style;

public final class Styles {

    public static final Style BANNER = Style.newStyle().foreground(new ANSI256Color(80)).bold(true);
    public static final Style DIM = Style.newStyle().foreground(new ANSI256Color(242));
    public static final Style PROMPT = Style.newStyle().foreground(new ANSI256Color(80)).bold(true);
    public static final Style ASSISTANT = Style.newStyle().foreground(new ANSI256Color(99));
    public static final Style ERROR = Style.newStyle().foreground(new ANSI256Color(203)).bold(true);
    public static final Style SELECTED = Style.newStyle().foreground(new ANSI256Color(80)).bold(true);
    public static final Style STATUS = Style.newStyle().foreground(new ANSI256Color(245));
    public static final Style SEPARATOR = Style.newStyle().foreground(new ANSI256Color(239));

    private Styles() {}
}
