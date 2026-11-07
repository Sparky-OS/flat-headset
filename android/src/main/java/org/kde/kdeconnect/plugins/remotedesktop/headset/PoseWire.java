/* SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
 * SPDX-License-Identifier: Apache-2.0 */
package org.kde.kdeconnect.plugins.remotedesktop.headset;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The bounded v1 wire contract; a channel instance owns one parser. */
public final class PoseWire {
    public static final int HELLO=1, SELECT=2, POSE=3, CLOCK_REQUEST=4, CLOCK_RESPONSE=5, STATUS=6;
    public static final class Message {
        public final int type,epoch;
        public final long sequence,time;
        public final byte[] payload;
        Message(int type,int epoch,long sequence,long time,byte[] payload) {
            this.type=type;this.epoch=epoch;this.sequence=sequence;this.time=time;this.payload=payload;
        }
    }
    public static ByteBuffer buffer(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }
    public static ByteBuffer wrap(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    public static byte[] encode(int type,int epoch,long sequence,long time,byte[] payload) {
        if(payload.length>4064)throw new IllegalArgumentException("Message exceeds v1 limit");
        return buffer(32+payload.length).put(new byte[]{'S','D','P','1'}).putShort((short)1).putShort((short)type)
            .putInt(32+payload.length).putInt(epoch).putLong(sequence).putLong(time).put(payload).array();
    }
    private final byte[] storage=new byte[4096];
    private int used=0,expected=32;
    private boolean failed=false;
    public synchronized List<Message> receive(byte[] bytes) {
        if(failed)throw new IllegalArgumentException("Closed framing state");
        List<Message> messages=new ArrayList<>();int pos=0;
        try {
            while(pos<bytes.length) {
                int n=Math.min(expected-used,bytes.length-pos);
                System.arraycopy(bytes,pos,storage,used,n);used+=n;pos+=n;
                if(used!=expected)continue;
                ByteBuffer h=wrap(storage);
                if(expected==32) {
                    if(h.getInt(0)!=0x31504453)throw new IllegalArgumentException("Invalid pose magic");
                    if(h.getShort(4)!=1)throw new IllegalArgumentException("Unsupported pose version");
                    expected=h.getInt(8);
                    if(expected<32 || expected>4096)throw new IllegalArgumentException("Invalid total length");
                    if(expected>32)continue;
                }
                byte[] payload=new byte[expected-32];System.arraycopy(storage,32,payload,0,payload.length);
                int type=h.getShort(6)&65535;
                if(type!=SELECT && type!=CLOCK_REQUEST && type!=STATUS)throw new IllegalArgumentException("Invalid message direction");
                if((type==SELECT && payload.length!=64) || (type==CLOCK_REQUEST && payload.length!=8)
                    || (type==STATUS && (payload.length<8 || payload.length>520)))throw new IllegalArgumentException("Invalid payload size");
                if(type==STATUS) {
                    ByteBuffer p=wrap(payload);int state=p.getShort(0)&65535,reason=p.getShort(2)&65535;
                    if(state>5 || reason>12 || p.getInt(4)!=payload.length-8)throw new IllegalArgumentException("Invalid status");
                    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(payload,8,payload.length-8));
                }
                messages.add(new Message(type,h.getInt(12),h.getLong(16),h.getLong(24),payload));used=0;expected=32;
                if(messages.size()>128)throw new IllegalArgumentException("Control flood");
            }
        } catch(Exception error) { failed=true;throw new IllegalArgumentException("Invalid pose stream",error); }
        return messages;
    }
    public static boolean validQuaternion(float[] xyzw) {
        if(xyzw.length!=4)return false;
        double sum=0;for(float f:xyzw) { if(Float.isNaN(f)||Float.isInfinite(f))return false;sum+=(double)f*f; }
        return sum>=.99*.99 && sum<=1.01*1.01;
    }
    public PoseWire() {}
}
