/* SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
 * SPDX-License-Identifier: Apache-2.0 */
package org.kde.kdeconnect.plugins.remotedesktop.headset;

import java.util.concurrent.atomic.AtomicReference;

/** Replaces unsent samples while the single transport writer is busy. */
public final class LatestPoseSample<T> {
    private final AtomicReference<T> latest=new AtomicReference<>();
    public void replace(T sample) { latest.set(sample); }
    public T take() { return latest.getAndSet(null); }
    public void clear() { latest.set(null); }
}
