package com.mewcode.agent;

import java.util.OptionalLong;
import java.util.HashMap;
import java.util.Map;

/** 跨 Agent Loop 轮次累计 provider 用量；缺失的维度永久保持未知。 */
public final class TokenUsageAccumulator {

    private final Map<Integer, RoundUsage> rounds = new HashMap<>();

    /** 兼容不带轮次的调用，每次调用视为一个独立轮次。 */
    public synchronized void add(OptionalLong input, OptionalLong output) {
        updateRound(rounds.size() + 1, input, output);
    }

    /** provider 的 usage 是本轮总量，重复回传时覆盖而不是重复累加。 */
    public synchronized void updateRound(int round,
                                         OptionalLong input,
                                         OptionalLong output) {
        if (round <= 0) throw new IllegalArgumentException("round must be positive");
        rounds.put(round, new RoundUsage(input, output));
    }

    public synchronized OptionalLong inputTokens() {
        long total = 0;
        for (RoundUsage usage : rounds.values()) {
            if (usage.input().isEmpty()) return OptionalLong.empty();
            total += usage.input().getAsLong();
        }
        return rounds.isEmpty() ? OptionalLong.empty() : OptionalLong.of(total);
    }

    public synchronized OptionalLong outputTokens() {
        long total = 0;
        for (RoundUsage usage : rounds.values()) {
            if (usage.output().isEmpty()) return OptionalLong.empty();
            total += usage.output().getAsLong();
        }
        return rounds.isEmpty() ? OptionalLong.empty() : OptionalLong.of(total);
    }

    private record RoundUsage(OptionalLong input, OptionalLong output) {
        private RoundUsage {
            input = input == null ? OptionalLong.empty() : input;
            output = output == null ? OptionalLong.empty() : output;
        }
    }
}
