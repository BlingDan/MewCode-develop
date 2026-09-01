package com.mewcode.memory;

/** memory 的物理存储级别。 */
public enum MemoryLevel {
    USER("user"),
    PROJECT("project");

    private final String wire;

    MemoryLevel(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static MemoryLevel fromWire(String value) {
        for (MemoryLevel level : values()) {
            if (level.wire.equals(value)) return level;
        }
        throw new IllegalArgumentException("非法 memory level。");
    }
}
