package com.stbemu.host;

/** Portal connection state and the channel request that must survive a portal reconnect. */
final class PortalChannelState {
    enum PortalState { CONNECTING, READY, UNAVAILABLE }

    private PortalState portalState = PortalState.UNAVAILABLE;
    private String pendingChannelId;
    private boolean reconnectRequested;
    private String readySessionId = "";

    PortalState portalState() { return portalState; }
    String pendingChannelId() { return pendingChannelId; }
    boolean hasPendingChannel() { return pendingChannelId != null; }

    /** A bridge hello only proves JS is running, not that the portal has loaded. */
    boolean canPresent(boolean hasMedia) {
        return portalState != PortalState.UNAVAILABLE
                && (!hasMedia || portalState == PortalState.READY);
    }

    void connecting() {
        portalState = PortalState.CONNECTING;
        readySessionId = "";
    }

    void ready(String sessionId) {
        portalState = PortalState.READY;
        readySessionId = sessionId;
        reconnectRequested = false;
    }

    void unavailable() {
        portalState = PortalState.UNAVAILABLE;
        readySessionId = "";
        reconnectRequested = false;
    }

    /** Latest selection wins. Returns true only for the first reconnect request in this outage. */
    boolean selectWhileUnavailable(String channelId) {
        pendingChannelId = channelId;
        if (portalState != PortalState.UNAVAILABLE) return false;
        if (reconnectRequested) return false;
        reconnectRequested = true;
        return true;
    }

    boolean canSendPending(String sessionId) {
        return pendingChannelId != null && portalState == PortalState.READY && readySessionId.equals(sessionId);
    }

    void pendingHandled() { pendingChannelId = null; }

    void select(String channelId) { pendingChannelId = channelId; }

    void reset() {
        unavailable();
        pendingChannelId = null;
    }
}
