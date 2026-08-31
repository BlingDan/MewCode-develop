package com.mewcode.compact;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AutoCompactFuseTest {

    @Test
    void tripsAfterThreeAutomaticFailures() {
        var fuse = new AutoCompactFuse();

        fuse.recordFailure(ContextTrigger.AUTO);
        fuse.recordFailure(ContextTrigger.AUTO);
        assertFalse(fuse.isTripped());

        fuse.recordFailure(ContextTrigger.AUTO);

        assertTrue(fuse.isTripped());
    }

    @Test
    void ignoresEmergencyFailuresAndManualSuccessResetsTheFuse() {
        var fuse = new AutoCompactFuse();
        fuse.recordFailure(ContextTrigger.AUTO);
        fuse.recordFailure(ContextTrigger.EMERGENCY);
        fuse.recordFailure(ContextTrigger.MANUAL);
        assertFalse(fuse.isTripped());

        fuse.recordFailure(ContextTrigger.AUTO);
        fuse.recordFailure(ContextTrigger.AUTO);
        assertTrue(fuse.isTripped());

        fuse.recordSuccess(ContextTrigger.MANUAL);

        assertFalse(fuse.isTripped());
    }
}
