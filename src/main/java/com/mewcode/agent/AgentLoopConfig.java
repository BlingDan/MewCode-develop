package com.mewcode.agent;

/** Agent Loop 的有界执行配置，也可由 SnakeYAML 直接绑定。 */
public final class AgentLoopConfig {

    public static final int DEFAULT_MAX_ITERATIONS = 20;
    public static final int DEFAULT_UNKNOWN_TOOL_ROUND_LIMIT = 3;

    private int maxIterations = DEFAULT_MAX_ITERATIONS;
    private int unknownToolRoundLimit = DEFAULT_UNKNOWN_TOOL_ROUND_LIMIT;

    public AgentLoopConfig() {
    }

    public AgentLoopConfig(int maxIterations, int unknownToolRoundLimit) {
        this.maxIterations = maxIterations;
        this.unknownToolRoundLimit = unknownToolRoundLimit;
        validate();
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public int getUnknownToolRoundLimit() {
        return unknownToolRoundLimit;
    }

    public void setUnknownToolRoundLimit(int unknownToolRoundLimit) {
        this.unknownToolRoundLimit = unknownToolRoundLimit;
    }

    public void validate() {
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be positive");
        }
        if (unknownToolRoundLimit <= 0) {
            throw new IllegalArgumentException("unknownToolRoundLimit must be positive");
        }
    }

    public AgentLoopConfig copy() {
        return new AgentLoopConfig(maxIterations, unknownToolRoundLimit);
    }
}
