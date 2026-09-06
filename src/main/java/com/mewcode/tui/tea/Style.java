// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.tui.tea;

import java.util.ArrayList;

// 终端文本样式（前景色、粗体），用 ANSI 转义序列渲染
public final class Style {

    private Integer fg;
    private boolean bold;

    private Style() {}

    public static Style newStyle() {
        return new Style();
    }

    public Style foreground(ANSI256Color color) {
        this.fg = color.index();
        return this;
    }

    public Style bold(boolean b) {
        this.bold = b;
        return this;
    }

    public String render(String text) {
        var codes = new ArrayList<String>();
        if (bold) codes.add("1");
        if (fg != null) codes.add("38;5;" + fg);

        var sb = new StringBuilder();
        if (!codes.isEmpty()) {
            sb.append("\033[").append(String.join(";", codes)).append("m");
            sb.append(text);
            sb.append("\033[0m");
        } else {
            sb.append(text);
        }
        return sb.toString();
    }
}
