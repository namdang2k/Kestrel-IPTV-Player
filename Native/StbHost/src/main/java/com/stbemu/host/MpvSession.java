package com.stbemu.host;

import android.content.Context;
import android.view.Surface;
import dev.jdtech.mpv.MPVLib;

/** Java-17-compatible libmpv 0.5.1 exposes a process singleton. Own it explicitly per playback session. */
final class MpvSession {
    private static MpvSession active;
    private MPVLib.EventObserver observer;

    static synchronized MpvSession create(Context context) {
        if (active != null) throw new IllegalStateException("MPV already owned");
        MPVLib.create(context.getApplicationContext());
        active = new MpvSession();
        return active;
    }

    void init() { MPVLib.init(); }
    int setOptionString(String name, String value) { return MPVLib.setOptionString(name, value); }
    void setPropertyString(String name, String value) { MPVLib.setPropertyString(name, value); }
    String getPropertyString(String name) { return MPVLib.getPropertyString(name); }
    void setPropertyBoolean(String name, boolean value) { MPVLib.setPropertyBoolean(name, value); }
    Boolean getPropertyBoolean(String name) { return MPVLib.getPropertyBoolean(name); }
    void setPropertyDouble(String name, double value) { MPVLib.setPropertyDouble(name, value); }
    Double getPropertyDouble(String name) { return MPVLib.getPropertyDouble(name); }
    void observeProperty(String name, int format) { MPVLib.observeProperty(name, format); }
    void attachSurface(Surface surface) { MPVLib.attachSurface(surface); }
    void detachSurface() { MPVLib.detachSurface(); }
    void command(String[] command) { MPVLib.command(command); }
    void addObserver(MPVLib.EventObserver observer) {
        this.observer = observer;
        MPVLib.addObserver(observer);
    }
    void destroy() {
        synchronized (MpvSession.class) {
            if (active != this) return;
            if (observer != null) MPVLib.removeObserver(observer);
            MPVLib.destroy();
            active = null;
        }
    }
}
