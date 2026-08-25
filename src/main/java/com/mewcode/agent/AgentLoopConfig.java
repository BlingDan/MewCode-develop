package com.mewcode.agent;

/**
 * Agent Loop 的有界执行配置，也可由 SnakeYAML 直接绑定。
 *
 * <p>最大迭代次数是所有正常/异常路径之外的安全兜底；未知工具连续轮次限制用于
 * 防止模型持续请求无法执行的工具而无界循环。</p>
 */
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

    /** 校验两个保护阈值必须为正数。 */
    public void validate() {
        if (maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be positive");
        }
        if (unknownToolRoundLimit <= 0) {
            throw new IllegalArgumentException("unknownToolRoundLimit must be positive");
        }
    }

    /** 返回独立副本，避免配置在一次运行中被外部修改。 */
    public AgentLoopConfig copy() {
        return new AgentLoopConfig(maxIterations, unknownToolRoundLimit);
    }
}
