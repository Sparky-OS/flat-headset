// SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
// SPDX-License-Identifier: Apache-2.0
package org.sparky.stereodroid.channelprobe;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Independent driver: the same test APK runs against the unchanged red and green apps. */
public final class ChannelProbe extends Instrumentation {
    private final CountDownLatch created = new CountDownLatch(1);
    private Bundle arguments;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        arguments = args == null ? new Bundle() : args;
        start();
    }

    @Override public void callApplicationOnCreate(Application app) {
        super.callApplicationOnCreate(app);
        created.countDown();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Activity activity = null;
        int code = Activity.RESULT_CANCELED;
        try {
            if (!created.await(30, TimeUnit.SECONDS)) throw new Exception("Application startup timeout");
            Class<?> api = getTargetContext().getClassLoader().loadClass("com.freerdp.freerdpcore.services.LibFreeRDP");
            boolean camera = (Boolean) api.getMethod("hasCameraRedirectionSupport").invoke(null);
            if (!Boolean.toString(camera).equals(arguments.getString("camera")))
                throw new Exception("Unexpected compiled camera support: " + camera);
            String fingerprint = arguments.getString("fingerprint", "");
            if (!fingerprint.matches("[a-fA-F0-9]{64}")) throw new Exception("Expected test certificate SHA256");
            Uri.Builder uri = new Uri.Builder().scheme("freerdp").encodedAuthority("fixture@10.0.2.2:3389")
                .appendPath("connect").appendQueryParameter("sec", "tls")
                .appendQueryParameter("p", "fixture") // Public dummy; isolated server does not authenticate accounts.
                .appendQueryParameter("cert", "fingerprint:sha256:" + fingerprint)
                .appendQueryParameter("size", "640x480").appendQueryParameter("log-level", "DEBUG");
            if (camera) uri.appendQueryParameter("dvc", "rdpecam");
            CountDownLatch connected = new CountDownLatch(1);
            StringBuffer states = new StringBuffer();
            ResultReceiver receiver = new ResultReceiver(new Handler(Looper.getMainLooper())) {
                @Override protected void onReceiveResult(int state, Bundle data) {
                    states.append("state=").append(state).append('\n');
                    if (state == 1) connected.countDown();
                }
            };
            String targetActivity = arguments.getString("activity", "org.kde.kdeconnect.SensorChannelProofActivity");
            if (!targetActivity.startsWith("org.kde.kdeconnect.")) throw new Exception("Expected target fixture activity");
            activity = startActivitySync(new Intent().setClassName(getTargetContext().getPackageName(), targetActivity)
                .setData(uri.build()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("com.freerdp.freerdpcore.CONNECTION_STATE_RECEIVER", receiver));
            if (!connected.await(35, TimeUnit.SECONDS)) throw new Exception("RDP connection timeout: " + states);
            SystemClock.sleep(10000);
            result.putString("stream", "PASS RDP_CONNECTED cameraCompiled=" + camera + "\n" + states);
            code = Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("stream", android.util.Log.getStackTraceString(failure));
        } finally {
            if (activity != null) {
                Activity target = activity;
                runOnMainSync(() -> { if (!target.isFinishing()) target.finish(); });
            }
        }
        finish(code, result);
    }
}
