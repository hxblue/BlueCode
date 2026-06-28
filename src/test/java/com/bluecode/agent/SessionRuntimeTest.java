package com.bluecode.agent;

import com.bluecode.compact.Recovery;
import com.bluecode.compact.state.AutoCompactTrackingState;
import com.bluecode.compact.state.ContentReplacementState;
import com.bluecode.compact.state.SessionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SessionRuntimeTest {
    @TempDir
    Path tempDir;

    @Test
    void resetForNewSessionReplacesSessionAndClearsAnchors() throws Exception {
        SessionRuntime runtime = new SessionRuntime(
                new ContentReplacementState(),
                new Recovery.RecoveryState(),
                new AutoCompactTrackingState(),
                SessionContext.create(tempDir),
                1234);
        String oldSessionId = runtime.session.sessionId();
        runtime.updateAnchor(99, 7);

        SessionContext next = SessionContext.create(tempDir);
        runtime.resetForNewSession(next);

        assertNotEquals(oldSessionId, runtime.session.sessionId());
        assertEquals(next.sessionId(), runtime.session.sessionId());
        assertEquals(0, runtime.getUsageAnchor());
        assertEquals(0, runtime.getAnchorMsgLen());
        assertEquals(1234, runtime.contextWindow);
    }

    @Test
    void remindersAreTakenOnceAndResetClearsThem() throws Exception {
        SessionRuntime runtime = SessionRuntime.create(tempDir, 1234);

        runtime.appendReminders(List.of("一", "", "二"));

        assertEquals(List.of("一", "二"), runtime.takeReminders());
        assertEquals(List.of(), runtime.takeReminders());

        runtime.appendReminders(List.of("三"));
        runtime.resetForNewSession(SessionContext.create(tempDir));
        assertEquals(List.of(), runtime.takeReminders());
    }
}
