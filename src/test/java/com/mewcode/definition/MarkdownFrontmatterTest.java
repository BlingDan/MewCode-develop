package com.mewcode.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MarkdownFrontmatterTest {

  @Test
  void normalizesCrLfAndReturnsImmutableMetadata() {
    var document =
        MarkdownFrontmatter.parse("---\r\nname: demo\r\nvalues: [one, two]\r\n---\r\nbody\r\n");

    assertEquals("demo", document.frontmatter().get("name"));
    assertEquals("body\n", document.body());
    assertThrows(
        UnsupportedOperationException.class, () -> document.frontmatter().put("other", "value"));
  }

  @Test
  void rejectsUnsafeOrIncompleteDocuments() {
    assertThrows(
        MarkdownFrontmatter.ParseException.class,
        () -> MarkdownFrontmatter.parse("---\na: &a [x]\nb: *a\n---\n"));
    assertThrows(
        MarkdownFrontmatter.ParseException.class,
        () -> MarkdownFrontmatter.parse("---\n- not-an-object\n---\nbody"));
    assertThrows(
        MarkdownFrontmatter.ParseException.class,
        () -> MarkdownFrontmatter.parse("---\nname: demo\n---\n"));
  }
}
