// SPDX-FileCopyrightText: 2026 Daniel Campos Ramos
// SPDX-License-Identifier: Apache-2.0
import org.kde.kdeconnect.plugins.remotedesktop.headset.PoseWire;
import java.util.Arrays;
public final class PoseWireTest {
    static void check(boolean v,String s) { if(!v)throw new AssertionError(s); }
    static void rejects(byte[] bytes) { try { new PoseWire().receive(bytes);throw new AssertionError("bad message accepted"); }catch(IllegalArgumentException expected){} }
    public static void main(String[] args) {
        byte[] p=PoseWire.buffer(8).putLong(123456).array();byte[] wire=PoseWire.encode(4,7,9,321,p);
        check(wire[0]=='S' && wire[4]==1 && wire[6]==4 && wire[8]==40 && wire[12]==7 && wire[16]==9,"literal header bytes");
        PoseWire parser=new PoseWire();check(parser.receive(Arrays.copyOf(wire,13)).isEmpty(),"partial header");
        check(parser.receive(Arrays.copyOfRange(wire,13,wire.length)).get(0).time==321,"fragmented request");
        byte[] twice=new byte[80];System.arraycopy(wire,0,twice,0,40);System.arraycopy(wire,0,twice,40,40);check(new PoseWire().receive(twice).size()==2,"coalesced messages");
        byte[] bad=wire.clone();bad[8]=31;rejects(bad);bad=wire.clone();bad[9]=32;rejects(bad);
        bad=wire.clone();bad[4]=2;rejects(bad);bad=wire.clone();bad[6]=3;rejects(bad);
        rejects(PoseWire.encode(6,7,1,0,new byte[]{1,0,0,0,1,0,0,0,(byte)0xff}));
        check(PoseWire.validQuaternion(new float[]{0,0,0,1}),"identity");
        check(!PoseWire.validQuaternion(new float[]{Float.NaN,0,0,1}) && !PoseWire.validQuaternion(new float[]{0,0,0,2}),"invalid quaternion");
        System.out.println("PHONE_WIRE PASS independent literal header, fragmentation, bounds, direction, UTF-8, quaternion");
    }
}
