package com.mewcode.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import com.mewcode.conversation.Message;
import com.mewcode.conversation.TextBlock;
import com.mewcode.conversation.ToolResultBlock;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProviderMessageNormalizerTest {

  @Test
  void mergesCompatibleAdjacentRolesWithoutChangingProviderIndependentHistory() {
    var firstUser = new Message("user", "first");
    var secondUser = new Message("user", "second");
    var firstAssistant = new Message("assistant", "answer 1");
    var secondAssistant = new Message("assistant", "answer 2");
    var toolResults = new Message("user", List.of(new ToolResultBlock("tool-1", "result", false)));
    var followUp = new Message("user", "follow up");

    List<Message> normalized =
        ProviderMessageNormalizer.normalize(
            List.of(firstUser, secondUser, firstAssistant, secondAssistant, toolResults, followUp));

    assertEquals(4, normalized.size());
    assertEquals("firstsecond", normalized.get(0).textContent());
    assertEquals("answer 1answer 2", normalized.get(1).textContent());
    assertEquals(List.of(new ToolResultBlock("tool-1", "result", false)), normalized.get(2).content());
    assertEquals("follow up", normalized.get(3).textContent());
    assertNotSame(firstUser, normalized.get(0));
    assertEquals("first", firstUser.textContent());
  }

  @Test
  void keepsAdjacentToolResultMessagesSeparateToPreserveToolProtocolOrdering() {
    var first = new Message("user", List.of(new ToolResultBlock("tool-1", "one", false)));
    var second = new Message("user", List.of(new ToolResultBlock("tool-2", "two", false)));

    List<Message> normalized = ProviderMessageNormalizer.normalize(List.of(first, second));

    assertEquals(List.of(first, second), normalized);
  }
}
