package com.stbemu.host;

import androidx.media3.common.C;
import org.junit.Test;
import static org.junit.Assert.*;

public class StreamLoadErrorPolicyTest {
    @Test public void loadersCannotMultiplyCoordinatorBudget() {
        StreamLoadErrorPolicy policy = new StreamLoadErrorPolicy();
        assertEquals(0, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA));
        assertEquals(0, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE));
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(null));
        assertNull(policy.getFallbackSelectionFor(null, null));
    }
}
