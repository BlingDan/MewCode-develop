package com.mewcode.memory;

/** memory 笔记类型及其 JSON/文件名前缀。 */
public enum MemoryType {
    USER_PREFERENCE("user_preference"),
    CORRECTION_FEEDBACK("correction_feedback"),
    PROJECT_KNOWLEDGE("project_knowledge"),
    REFERENCE_MATERIAL("reference_material");

    private final String wire;

    MemoryType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static MemoryType fromWire(String value) {
        for (MemoryType type : values()) {
            if (type.wire.equals(value)) return type;
        }
        throw new IllegalArgumentException("非法 memory type。");
    }
}
