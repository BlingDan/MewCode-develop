package com.mewcode.tui;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class SpinnerVerbs {

    private static final List<String> VERBS = List.of("Imagining", "Thinking", "Composing");

    private SpinnerVerbs() {}

    public static String random() {
        return VERBS.get(ThreadLocalRandom.current().nextInt(VERBS.size()));
    }
}
