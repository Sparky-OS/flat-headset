/* SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL */
package org.kde.kdeconnect.plugins.remotedesktop.headset;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;
import com.freerdp.freerdpcore.services.LibFreeRDP;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/** One latest sensor sample, one network worker, one write in flight. */
public final class StereoPoseController implements AutoCloseable {
    static float[] rotationVectorToWire(float[] values) {
        float[] wxyz=new float[4];SensorManager.getQuaternionFromVector(wxyz,values);
        float[] xyzw={wxyz[1],wxyz[2],wxyz[3],wxyz[0]};
        if(!PoseWire.validQuaternion(xyzw))throw new IllegalArgumentException("Invalid fused quaternion");
        return xyzw;
    }
    public interface StatusListener { void changed(int state,int reason); }
    private static final String TAG="StereoPose";
    private static final byte[] BOOT_NONCE=new byte[16];
    static { new SecureRandom().nextBytes(BOOT_NONCE); }
    private final Context context;
    private final SensorManager manager;
    private final StatusListener listener;
    private final boolean enabled;
    private volatile boolean resumed=false,closed=false;
    private Connection connection;
    private volatile boolean proofInjection=false;
    private volatile int proofWriteDelayMs=0;
    /** Debug fixture only. Release builds reject injection before accessing a channel. */
    public synchronized void proofSource(boolean enabled) {
        if(!org.kde.kdeconnect_tp.BuildConfig.DEBUG)throw new SecurityException("Debug fixture only");
        proofInjection=enabled;
    }
    public synchronized boolean proofReady() { return connection!=null && connection.selected && connection.sourceEpoch!=0; }
    public synchronized long proofRotation(float[] vector,long ageNs) {
        if(!org.kde.kdeconnect_tp.BuildConfig.DEBUG || !proofInjection)throw new SecurityException("Debug fixture only");
        if(connection==null || !connection.selected || connection.stopped)return -1;
        float[] xyzw=rotationVectorToWire(vector);
        long capture=SystemClock.elapsedRealtimeNanos()-ageNs;
        connection.latest.set(new Reading(xyzw,capture,3,connection.sourceEpoch));return capture;
    }
    public void proofWriteDelay(int milliseconds) {
        if(!org.kde.kdeconnect_tp.BuildConfig.DEBUG || milliseconds<0 || milliseconds>600)throw new SecurityException("Debug fixture only");
        proofWriteDelayMs=milliseconds;
    }

    public volatile int selectedSensor=15,selectedRate=60;
    private final Handler main=new Handler(android.os.Looper.getMainLooper());
    private boolean peerConnected=false;
    public synchronized void connected(boolean value) {
        peerConnected=value;
        if(!enabled)return;
        if(!value) { if(connection!=null){connection.close();connection=null;}listener.changed(3,11); }
        else main.postDelayed(()-> { synchronized(StereoPoseController.this) {
            if(!closed && peerConnected && connection==null)listener.changed(3,6);
        } },5000);
    }

    private static final class Reading {
        final float[] xyzw;
        final long time;
        final int accuracy,epoch;
        Reading(float[] q,long t,int a,int e) { xyzw=q;time=t;accuracy=a;epoch=e; }
    }
    public StereoPoseController(Context context,boolean enabled,StatusListener listener) {
        this.context=context;this.enabled=enabled;this.listener=listener;
        manager=(SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
        if(enabled)listener.changed(0,0);
    }
    public synchronized int nativeEvent(long instance,long cookie,int event,byte[] packet) {
        if(event==2) {
            if(connection!=null && connection.cookie==cookie) { if(!connection.stopped)listener.changed(3,11);connection.close();connection=null; }
            return 1;
        }
        if(!enabled || closed)return 0;
        if(event==0) {
            if(connection!=null)connection.close();
            connection=new Connection(instance,cookie);return 1;
        }
        if(connection==null || connection.cookie!=cookie)return 0;
        if(event==3) { connection.protocolFailed=true;return 1; }
        if(event!=1)return 0;
        try {
            for(PoseWire.Message msg:connection.parser.receive(packet))
                if(!connection.controls.offer(new Control(msg,SystemClock.elapsedRealtimeNanos())))throw new IllegalArgumentException("Control queue full");
            return 1;
        } catch(IllegalArgumentException error) {
            connection.protocolReason=error.getCause()!=null && "Unsupported pose version".equals(error.getCause().getMessage()) ? 2 : 9;
            connection.protocolFailed=true;return 0;
        }
    }
    public synchronized void setResumed(boolean value) {
        resumed=value;
        if(connection!=null)connection.lifecycleChanged=true;
        // Unregister immediately even if the network worker is stalled.
        if(!value && connection!=null)connection.stopSensor();
    }
    public synchronized void requestRecenter() { if(connection!=null)connection.recenterRequested=true; }
    @Override public synchronized void close() {
        closed=true;main.removeCallbacksAndMessages(null);if(connection!=null) { connection.close();connection=null; }
    }
    private static final class Control {
        final PoseWire.Message message;final long received;
        Control(PoseWire.Message m,long t) { message=m;received=t; }
    }
    private final class Connection implements AutoCloseable {
        final long instance,cookie;
        final PoseWire parser=new PoseWire();
        final ArrayBlockingQueue<Control> controls=new ArrayBlockingQueue<>(16);
        final AtomicReference<Reading> latest=new AtomicReference<>();
        final HandlerThread workerThread=new HandlerThread("StereoPoseNetwork"),sensorThread=new HandlerThread("StereoPoseSensor");
        final Handler worker,sensors;
        final byte[] nonce=new byte[16];
        volatile boolean stopped=false,lifecycleChanged=true,recenterRequested=false,protocolFailed=false;
        volatile int protocolReason=9;
        int epoch=0,rate=60,type=15,mount=1;
        volatile int sourceEpoch=0;
        long sequence=0,lastIncoming=0,lastPublished=Long.MIN_VALUE,nextPublish=0,samples=0,firstPublication=0;
        boolean haveIncoming=false,selected=false,wasResumed=false;
        SensorEventListener sensorListener;
        Connection(long instance,long cookie) {
            this.instance=instance;this.cookie=cookie;new SecureRandom().nextBytes(nonce);
            workerThread.start();sensorThread.start();worker=new Handler(workerThread.getLooper());sensors=new Handler(sensorThread.getLooper());
            worker.post(()-> { hello();pump(); });
        }
        boolean send(int kind,byte[] payload,long time) {
            if(stopped)return false;
            if(kind==PoseWire.POSE && proofWriteDelayMs>0)SystemClock.sleep(proofWriteDelayMs);
            boolean result=LibFreeRDP.sendStereoPose(instance,cookie,PoseWire.encode(kind,epoch,sequence++,time,payload));
            if(!result) { listener.changed(3,11);close(); }
            return result;
        }
        void status(int state,int reason) {
            listener.changed(state,reason);
            send(PoseWire.STATUS,PoseWire.buffer(8).putShort((short)state).putShort((short)reason).putInt(0).array(),SystemClock.elapsedRealtimeNanos());
        }
        Sensor sensor(int t) { return manager==null ? null : manager.getDefaultSensor(t); }
        int maximum(Sensor s) { return s==null ? 0 : Math.min(120,s.getMinDelay()>0 ? 1000000/s.getMinDelay() : 100); }
        void hello() {
            Sensor game=sensor(15),compass=sensor(11);
            int mask=(game!=null?1:0)|(compass!=null?2:0);
            int max=Math.max(maximum(game),maximum(compass));
            int period=Integer.MAX_VALUE;
            if(game!=null)period=Math.min(period,Math.max(1,game.getMinDelay()));
            if(compass!=null)period=Math.min(period,Math.max(1,compass.getMinDelay()));
            WindowManager wm=(WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
            int rotation=wm.getDefaultDisplay().getRotation();
            ByteBuffer p=PoseWire.buffer(64).put(nonce).putLong(mask!=0?1:0).putInt(mask).putShort((short)max).putShort((short)rotation)
                .putInt(mask==0?0:period).putInt(4096).putInt(Build.VERSION.SDK_INT).putShort((short)0).putShort((short)0).put(BOOT_NONCE);
            send(PoseWire.HELLO,p.array(),SystemClock.elapsedRealtimeNanos());
            Log.i(TAG,"HELLO sources="+mask+" maximumHz="+max+" camera=false");
            if(mask==0)listener.changed(3,1);
        }
        void selected(PoseWire.Message msg) {
            ByteBuffer p=PoseWire.wrap(msg.payload);
            byte[] echo=Arrays.copyOfRange(msg.payload,16,32);
            long capabilities=p.getLong(32);int hz=p.getShort(40)&65535,source=p.getShort(42)&65535;
            int stale=p.getShort(44)&65535,lost=p.getShort(46)&65535,mounting=p.getShort(48)&65535,mode=p.getShort(50)&65535;
            boolean reserved=p.getLong(56)==0;
            if(selected || msg.epoch==0 || p.getInt(52)!=msg.epoch || !Arrays.equals(echo,nonce) || capabilities!=1 || stale!=100 || lost!=250
                || mounting>3 || mode!=1 || !reserved || (hz!=30 && hz!=60 && hz!=90 && hz!=120) || (source!=11 && source!=15))
                throw new IllegalArgumentException("Invalid selection");
            epoch=msg.epoch;
            if(sensor(source)==null || maximum(sensor(source))<hz) { status(3,1);close();return; }
            rate=hz;type=source;mount=mounting;selectedSensor=source;selectedRate=hz;selected=true;lifecycleChanged=true;
            Log.i(TAG,"SELECT epoch="+epoch+" source="+type+" rate="+rate+" mount="+mount);
            status(resumed?1:2,0);
        }
        void control(Control control) {
            PoseWire.Message msg=control.message;
            if(haveIncoming && Long.compareUnsigned(msg.sequence,lastIncoming)<=0)throw new IllegalArgumentException("Sequence replay");
            lastIncoming=msg.sequence;haveIncoming=true;
            if(msg.type==PoseWire.SELECT) { selected(msg);return; }
            if(msg.type==PoseWire.STATUS && !selected && msg.epoch==0) {
                ByteBuffer p=PoseWire.wrap(msg.payload);listener.changed(p.getShort(0)&65535,p.getShort(2)&65535);close();return;
            }
            if(!selected || msg.epoch!=epoch)throw new IllegalArgumentException("Epoch mismatch");
            if(msg.type==PoseWire.CLOCK_REQUEST) {
                long t0=PoseWire.wrap(msg.payload).getLong();long t2=SystemClock.elapsedRealtimeNanos();
                send(PoseWire.CLOCK_RESPONSE,PoseWire.buffer(24).putLong(t0).putLong(control.received).putLong(t2).array(),t2);
            } else if(msg.type==PoseWire.STATUS) {
                ByteBuffer p=PoseWire.wrap(msg.payload);int state=p.getShort(0)&65535,reason=p.getShort(2)&65535;
                listener.changed(state,reason);
                if(state==4 || state==5)close();
            }
        }
        synchronized void stopSensor() {
            if(sensorListener!=null && manager!=null)manager.unregisterListener(sensorListener);
            sensorListener=null;latest.set(null);
        }
        synchronized void startSensor() {
            stopSensor();final int source=++sourceEpoch;
            if(proofInjection) { Log.i(TAG,"INJECTED_SOURCE epoch="+source+" not a physical sensor claim");return; }
            Sensor s=sensor(type);
            sensorListener=new SensorEventListener() {
                @Override public void onAccuracyChanged(Sensor sensor,int accuracy) {}
                @Override public void onSensorChanged(SensorEvent event) {
                    if(stopped || !resumed || source!=sourceEpoch)return;
                    try {
                        float[] xyzw=rotationVectorToWire(event.values);
                        if(event.accuracy>=-1 && event.accuracy<=3)
                            latest.set(new Reading(xyzw,event.timestamp,event.accuracy,source));
                        else protocolFailed=true;
                    } catch(IllegalArgumentException invalid) { latest.set(null);protocolFailed=true; }
                }
            };
            int period=Math.min(10000,1000000/rate);
            if(s==null || !manager.registerListener(sensorListener,s,period,0,sensors)) { stopSensor();status(3,1);close(); }
            else Log.i(TAG,"SENSOR_START type="+type+" sourceEpoch="+source+" requestedPeriodUs="+period+" fused=true");
        }
        void pump() {
            if(stopped)return;
            if(protocolFailed) { status(4,protocolReason);close();return; }
            try {
                Control c;for(int i=0;i<16 && (c=controls.poll())!=null;++i)control(c);
                if(stopped)return;
                if(selected && lifecycleChanged) {
                    lifecycleChanged=false;
                    if(resumed && (!wasResumed || sensorListener==null)) { startSensor();status(1,0); }
                    else if(!resumed && wasResumed) { stopSensor();status(2,0); }
                    wasResumed=resumed;
                }
                if(selected && recenterRequested) { recenterRequested=false;status(0,10); }
                long now=SystemClock.elapsedRealtimeNanos();
                if(selected && resumed && now>=nextPublish) {
                    Reading reading=latest.getAndSet(null);
                    if(reading!=null && reading.time>lastPublished && now-reading.time<=100000000 && reading.epoch==sourceEpoch) {
                        ByteBuffer p=PoseWire.buffer(32);
                        for(float f:reading.xyzw)p.putFloat(f);
                        p.putShort((short)type).putShort((short)reading.accuracy).putInt(type==15?3:1).putInt(reading.epoch).putInt(mount);
                        if(send(PoseWire.POSE,p.array(),reading.time)) {
                            lastPublished=reading.time;nextPublish=now+1000000000L/rate;
                            if(samples++==0)firstPublication=now;
                            if(samples%60==0)Log.i(TAG,"POSE_CADENCE fresh="+samples+" elapsedNs="+(now-firstPublication)+" sensorNs="+reading.time);
                        }
                    }
                }
            } catch(RuntimeException error) {
                Log.e(TAG,"Pose channel failed",error);status(4,9);close();
            }
            if(!stopped)worker.postDelayed(this::pump,2);
        }
        @Override public synchronized void close() {
            if(stopped)return;
            stopped=true;stopSensor();controls.clear();worker.removeCallbacksAndMessages(null);
            workerThread.quitSafely();sensorThread.quitSafely();latest.set(null);
            Log.i(TAG,"CHANNEL_STOP cookie="+cookie+" sensorReleased=true fresh="+samples);
        }
    }
}
