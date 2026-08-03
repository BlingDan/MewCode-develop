package com.mewcode.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownRendererTest {

    @Test
    void rendersCommonMarkdownWithoutLosingText() {
        String rendered = MarkdownRenderer.render("""
                # Heading

                **bold**

                - one
                - two

                ```java
                int answer = 42;
                ```
                """, 60);

        assertTrue(rendered.contains("Heading"));
        assertTrue(rendered.contains("bold"));
        assertTrue(rendered.contains("one"));
        assertTrue(rendered.contains("int answer = 42;"));
        assertFalse(rendered.contains("```"));
    }

    @Test
    void handlesNarrowAndEmptyInput() {
        assertDoesNotThrow(() -> MarkdownRenderer.render("A long paragraph that should wrap.", 5));
        assertEquals("", MarkdownRenderer.render("", 80));
        assertEquals("", MarkdownRenderer.render(null, 80));
    }
}
