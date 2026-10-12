package com.stbemu.host;

import org.junit.Test;

import static org.junit.Assert.*;

public class PortalChannelStateTest {
    @Test public void authReconnectAndChannelSelectionShareOneRequestPerOutage() {
        PortalChannelState state = new PortalChannelState();
        state.ready("session");
        assertFalse(state.requestReconnect()); // healthy portal
        state.unavailable();
        assertTrue(state.requestReconnect());
        assertFalse(state.selectWhileUnavailable("7")); // already reconnecting; the channel still waits
        assertEquals("7", state.pendingChannelId());
        state.unavailable(); // next outage
        assertTrue(state.requestReconnect());
    }

    @Test public void navigationInvalidatesOldChannelSourceWithoutDroppingSelection() {
        PortalChannelState state = new PortalChannelState();
        state.ready("session");
        state.select("A");
        state.connecting();
        assertFalse(state.canSendPending("session"));
        assertFalse(state.selectWhileUnavailable("B"));
        state.ready("session");
        assertTrue(state.canSendPending("session"));
        assertEquals("B", state.pendingChannelId());
    }

    @Test public void latestSelectionWinsAndReconnectIsDebounced() {
        PortalChannelState state = new PortalChannelState();
        assertTrue(state.selectWhileUnavailable("12"));
        assertFalse(state.selectWhileUnavailable("13"));
        assertEquals("13", state.pendingChannelId());
    }

    @Test public void pendingWaitsForReadyCurrentSession() {
        PortalChannelState state = new PortalChannelState();
        state.selectWhileUnavailable("12");
        state.connecting();
        assertFalse(state.canSendPending("new"));
        state.ready("new");
        assertFalse(state.canSendPending("old"));
        assertTrue(state.canSendPending("new"));
    }

    @Test public void helloConnectingDoesNotRequestSecondReconnect() {
        PortalChannelState state = new PortalChannelState();
        state.connecting();
        assertFalse(state.selectWhileUnavailable("12"));
        assertEquals("12", state.pendingChannelId());
    }

    @Test public void failureAllowsAnotherReconnectWithoutLosingPending() {
        PortalChannelState state = new PortalChannelState();
        state.selectWhileUnavailable("12");
        state.connecting();
        state.unavailable();
        assertEquals("12", state.pendingChannelId());
        assertTrue(state.selectWhileUnavailable("12"));
    }

    @Test public void acceptedOrRejectedRequestClearsPending() {
        PortalChannelState state = new PortalChannelState();
        state.selectWhileUnavailable("12");
        state.pendingHandled();
        assertFalse(state.hasPendingChannel());
    }

    @Test public void newerSelectionSurvivesFailureAndReconnectOfInFlightRequest() {
        PortalChannelState state = new PortalChannelState();
        state.ready("old");
        state.select("A");
        state.select("B");
        state.unavailable();
        assertEquals("B", state.pendingChannelId());
        assertTrue(state.selectWhileUnavailable(state.pendingChannelId()));
        state.connecting();
        state.ready("new");
        assertEquals("B", state.pendingChannelId());
        assertFalse(state.canSendPending("old"));
        assertTrue(state.canSendPending("new"));
    }

    @Test public void periodicChannelDataDoesNotEraseQueuedSelection() {
        PortalChannelState state = new PortalChannelState();
        state.ready("session");
        state.select("A");
        state.select("B");
        state.ready("session");
        assertEquals("B", state.pendingChannelId());
    }

    @Test public void stoppedSelectionCannotReplayWhenChannelDataArrives() {
        PortalChannelState state = new PortalChannelState();
        state.selectWhileUnavailable("A");
        state.connecting();
        state.pendingHandled();
        state.ready("new");
        assertFalse(state.canSendPending("new"));
    }
}
