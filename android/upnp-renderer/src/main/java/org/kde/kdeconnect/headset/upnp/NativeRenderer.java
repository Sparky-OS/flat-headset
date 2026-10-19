/* SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.headset.upnp;

/** Owns the process-wide pupnp device; lifecycle calls belong on a worker thread. */
public final class NativeRenderer implements AutoCloseable {
    static {
        if (android.os.Build.VERSION.SDK_INT < 24) throw new UnsupportedOperationException("UPnP requires Android 7 or later");
        System.loadLibrary("headset_upnp");
    }

    public interface Listener {
        /** Returns an action-response XML document, or ! followed by the UPnP error code. */
        String onAction(String serviceId, String action, String requestXml);
        /** Returns an event property-set XML document for an initial subscription. */
        String snapshot(String serviceId);
    }

    private final Listener listener;
    public NativeRenderer(Listener listener) { this.listener = listener; }
    public native String start(String networkInterface, String webRoot, String descriptionXml);
    @Override public native void close();
    public native void notifyChange(String deviceId, String serviceId, String lastChange);
    public native void notifyProperties(String deviceId, String serviceId, String propertiesXml);
    private String onAction(String service, String action, String xml) { return listener.onAction(service, action, xml); }
    private String snapshot(String service) { return listener.snapshot(service); }
}
