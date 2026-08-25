package com.mewcode.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.agent.AgentMode;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.TextBlock;
import org.junit.jupiter.api.Test;

class SystemReminderFactoryTest {

  @Test
  void createsAUserMessageWithOneTaggedTextBlock() {
    Message reminder = SystemReminderFactory.full(new ReminderContext(AgentMode.PLAN, 1, false));

    assertEquals("user", reminder.role());
    assertEquals(1, reminder.content().size());
    String text = ((TextBlock) reminder.content().getFirst()).text();
    assertTrue(text.startsWith("<system-reminder>\n"));
    assertTrue(text.endsWith("\n</system-reminder>"));
    assertTrue(text.contains("Current mode: PLAN"));
    assertTrue(text.contains("Agent Loop round: 1"));
    assertTrue(text.contains("read-only"));
  }

  @Test
  void repeatsFullyOnFirstRoundOfEachFourRoundPeriod() {
    for (int round = 1; round <= 9; round++) {
      String text =
          SystemReminderFactory.create(new ReminderContext(AgentMode.EXECUTE, round, false))
              .orElseThrow()
              .textContent();
      assertEquals(round % 4 == 1, text.contains("This is a full reminder"), "round=" + round);
    }
  }

  @Test
  void forceFullOverridesTheNormalCompactRound() {
    String text =
        SystemReminderFactory.create(new ReminderContext(AgentMode.EXECUTE, 2, true))
            .orElseThrow()
            .textContent();

    assertTrue(text.contains("This is a full reminder"));
    assertTrue(text.contains("Current mode: EXECUTE"));
  }

  @Test
  void preservesTagsForEmptyMultilineAndSpecialContent() {
    String text = SystemReminderFactory.fromContent("<custom>&\nsecond").textContent();

    assertEquals("<system-reminder>\n&lt;custom&gt;&amp;\nsecond\n</system-reminder>", text);
  }
}
