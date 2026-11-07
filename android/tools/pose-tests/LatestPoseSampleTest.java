// SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
// SPDX-License-Identifier: Apache-2.0
import org.kde.kdeconnect.plugins.remotedesktop.headset.LatestPoseSample;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class LatestPoseSampleTest {
    public static void main(String[] args) throws Exception {
        LatestPoseSample<Integer> mailbox=new LatestPoseSample<>();
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        mailbox.replace(0);
        Thread writer=new Thread(()-> {
            if(mailbox.take()!=0)throw new AssertionError("First sample");
            started.countDown();
            try { if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Writer timeout"); }
            catch(InterruptedException e) { throw new AssertionError(e); }
        });
        writer.start();
        if(!started.await(5,TimeUnit.SECONDS))throw new AssertionError("Writer did not start");
        for(int sample=1;sample<=10000;++sample)mailbox.replace(sample);
        release.countDown();writer.join(5000);
        if(writer.isAlive() || mailbox.take()!=10000 || mailbox.take()!=null)throw new AssertionError("Blocked writer must retain exactly the latest unsent sample");
        mailbox.replace(10001);mailbox.clear();if(mailbox.take()!=null)throw new AssertionError("Lifecycle clear");
        System.out.println("LATEST_SAMPLE PASS 10000 arrivals during blocked write retain one newest sample");
    }
}
