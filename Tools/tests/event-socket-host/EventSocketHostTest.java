package org.telegram.botgram;

import com.neovisionaries.ws.client.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.telegram.botgram.protocol.v1.BotgramRpc;
import org.telegram.messenger.AndroidUtilities;

public class EventSocketHostTest {
    static final String PB = "botgram.event-hint.v1.protobuf", JSON = "botgram.events.v1.json";
    static int assertions;
    static final class Sink implements BotgramEventSocket.Listener {
        int wakes;
        public void wake(BotgramSession s) { wakes++; }
        public void wallpaper(BotgramSession s, long revision) { }
        public void unauthorized(BotgramSession s) { throw new AssertionError("unexpected unauthorized"); }
    }
    static final class Fixture {
        final Sink sink = new Sink();
        final BotgramEventSocket owner = new BotgramEventSocket(sink);
        final WebSocket ws;
        final WebSocketAdapter listener;
        Fixture(String protocol) throws Exception {
            AndroidUtilities.queue.clear(); AndroidUtilities.delayed.clear();
            set(owner,"active",true); set(owner,"generation",10L); set(owner,"cursor",3L);
            ws = new WebSocketFactory().createSocket("wss://example.invalid/api/ws");
            attachInput(ws, new byte[0]);
            Method agreed = WebSocket.class.getDeclaredMethod("setAgreedProtocol",String.class);
            agreed.setAccessible(true); agreed.invoke(ws,protocol);
            set(owner,"socket",ws);
            Constructor<?> ctor = Class.forName("org.telegram.botgram.BotgramEventSocket$1")
                .getDeclaredConstructor(BotgramEventSocket.class,long.class);
            ctor.setAccessible(true); listener = (WebSocketAdapter)ctor.newInstance(owner,10L);
        }
        long cursor() throws Exception { return (Long)get(owner,"cursor"); }
    }
    static void set(Object o,String n,Object v) throws Exception {
        Field f=o.getClass().getDeclaredField(n); f.setAccessible(true); f.set(o,v);
    }
    static Object get(Object o,String n) throws Exception {
        Field f=o.getClass().getDeclaredField(n); f.setAccessible(true); return f.get(o);
    }
    static void check(boolean value,String label) { assertions++; if(!value)throw new AssertionError(label); }
    static byte[] hint(int major,long cursor) {
        return BotgramRpc.EventHint.newBuilder().setProtocolMajor(major).setCursor(cursor).build().toByteArray();
    }
    static Object attachInput(WebSocket ws, byte[] bytes) throws Exception {
        Class<?> type = Class.forName("com.neovisionaries.ws.client.WebSocketInputStream");
        Constructor<?> ctor = type.getDeclaredConstructor(java.io.InputStream.class);
        ctor.setAccessible(true);
        Object input = ctor.newInstance(new java.io.ByteArrayInputStream(bytes));
        set(ws,"mInput",input);
        return input;
    }
    static void inboundLimit(Fixture f, boolean open) throws Exception {
        // Only a frame header is supplied: rejection must precede body allocation/read.
        int length = PB.equals(f.ws.getAgreedProtocol()) ? 1025 : 8*1024*1024+1;
        byte[] header = new byte[]{(byte)0x82,127,0,0,0,0,
            (byte)(length >>> 24),(byte)(length >>> 16),(byte)(length >>> 8),(byte)length};
        Object input = attachInput(f.ws,header);
        if (open) f.listener.onStateChanged(f.ws,WebSocketState.OPEN);
        else f.listener.onConnected(f.ws,Collections.emptyMap());
        Method read = input.getClass().getDeclaredMethod("readFrame"); read.setAccessible(true);
        try { read.invoke(input); throw new AssertionError("oversized inbound header accepted"); }
        catch (InvocationTargetException e) {
            check(e.getCause() instanceof WebSocketException
                && ((WebSocketException)e.getCause()).getError()==WebSocketError.TOO_LONG_PAYLOAD,
                "real inbound limit configured by " + (open ? "OPEN" : "onConnected"));
        }
    }
    static byte[] concat(byte[] first, byte[] second) {
        byte[] result=Arrays.copyOf(first,first.length+second.length);
        System.arraycopy(second,0,result,first.length,second.length); return result;
    }
    static byte[] groups(int depth) {
        byte[] wire=Arrays.copyOf(hint(1,99),4+depth*2);
        Arrays.fill(wire,4,4+depth,(byte)27);
        Arrays.fill(wire,4+depth,wire.length,(byte)28); return wire;
    }
    static void acceptsWire(String label,byte[] wire) throws Exception {
        BotgramEventSocket.validateHintWire(wire);
        check(true,label+" preflight accepts");
        Fixture f=new Fixture(PB); f.listener.onBinaryMessage(f.ws,wire);
        check(f.cursor()==3 && f.sink.wakes==0,label+" queued");
        AndroidUtilities.drain();
        check(f.cursor()==99 && f.sink.wakes==1,label+" accepted");
        check(f.owner.generation()==10 && AndroidUtilities.delayed.isEmpty(),label+" no retry");
    }
    static void rejectsWire(String label,byte[] wire) throws Exception {
        boolean rejected=false;
        try { BotgramEventSocket.validateHintWire(wire); }
        catch(java.io.IOException expected) { rejected=true; }
        check(rejected,label+" preflight rejects");
        rejects(label,PB,f->f.listener.onBinaryMessage(f.ws,wire));
    }
    interface Action { void apply(Fixture f) throws Exception; }
    static void rejects(String label,String protocol,Action action) throws Exception {
        Fixture f=new Fixture(protocol); action.apply(f); AndroidUtilities.drain();
        check(f.cursor()==3,label+" cursor unchanged");
        check(f.owner.generation()==11,label+" failed generation");
        check(f.sink.wakes==0,label+" no wake");
        check(get(f.owner,"socket")==null,label+" socket detached");
        check(AndroidUtilities.delayed.size()==1,label+" one retry");
    }
    public static void main(String[] args) throws Exception {
        try {
            byte[] golden = new byte[]{8,1,16,(byte)150,1};
            BotgramRpc.EventHint decoded = BotgramRpc.EventHint.parseFrom(golden);
            check(decoded.getProtocolMajor()==1 && decoded.getCursor()==150,"manual golden 0801109601 decode");
            check(Arrays.equals(golden,hint(1,150)),"manual golden encode");
            byte[] maxGolden = new byte[]{8,1,16,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,127};
            check(BotgramRpc.EventHint.parseFrom(maxGolden).getCursor()==Long.MAX_VALUE,"manual longMax decode");
            check(Arrays.equals(maxGolden,hint(1,Long.MAX_VALUE)),"manual longMax encode");
            byte[] unknown = new byte[]{8,1,16,(byte)150,1,24,42};
            decoded = BotgramRpc.EventHint.parseFrom(unknown);
            check(decoded.getCursor()==150,"unknown field parses");
            check(Arrays.equals(unknown,decoded.toByteArray()),"unknown field preserved");
            boolean truncated=false;
            try { BotgramRpc.EventHint.parseFrom(new byte[]{8,1,16,(byte)150}); }
            catch(com.google.protobuf.InvalidProtocolBufferException expected) { truncated=true; }
            check(truncated,"generated parser rejects truncated golden");
            for(String protocol:new String[]{null,"",JSON,PB}) {
                Fixture f=new Fixture(protocol); f.listener.onConnected(f.ws,Collections.emptyMap());
                check(f.sink.wakes==0,"connect queued on UI"); AndroidUtilities.drain();
                check(f.sink.wakes==1,"connect wake");
                inboundLimit(new Fixture(protocol),true);
                inboundLimit(new Fixture(protocol),false);
            }
            rejects("unknown protocol","unknown",f->f.listener.onConnected(f.ws,Collections.emptyMap()));
            for(String protocol:new String[]{null,"",JSON}) {
                Fixture f=new Fixture(protocol);
                f.listener.onTextMessage(f.ws,"{\"cursor\":20,\"events\":[]}");
                check(f.cursor()==3,"JSON cursor queued"); AndroidUtilities.drain();
                check(f.cursor()==20 && f.sink.wakes==1,"legacy JSON hint");
            }
            Fixture f=new Fixture(PB);
            f.listener.onBinaryMessage(f.ws,hint(1,20)); check(f.cursor()==3,"PB cursor queued");
            AndroidUtilities.drain(); check(f.cursor()==20 && f.sink.wakes==1,"PB hint");
            f.listener.onBinaryMessage(f.ws,hint(1,5)); AndroidUtilities.drain(); check(f.cursor()==20,"PB monotonic cursor");
            f.listener.onBinaryMessage(f.ws,hint(1,0)); AndroidUtilities.drain(); check(f.cursor()==20,"zero cursor allowed");
            f.listener.onBinaryMessage(f.ws,hint(1,Long.MAX_VALUE)); AndroidUtilities.drain(); check(f.cursor()==Long.MAX_VALUE,"int64 max accepted");
            rejects("PB text",PB,x->x.listener.onTextMessage(x.ws,"{\"cursor\":99,\"events\":[]}"));
            rejects("JSON binary",JSON,x->x.listener.onBinaryMessage(x.ws,hint(1,99)));
            rejects("legacy binary",null,x->x.listener.onBinaryMessage(x.ws,hint(1,99)));
            rejects("over limit",PB,x->x.listener.onBinaryMessage(x.ws,new byte[1025]));
            rejects("empty PB",PB,x->x.listener.onBinaryMessage(x.ws,new byte[0]));
            rejects("truncated PB",PB,x->x.listener.onBinaryMessage(x.ws,new byte[]{8,1,16,(byte)128}));
            rejects("bad major",PB,x->x.listener.onBinaryMessage(x.ws,hint(2,99)));
            rejects("negative cursor",PB,x->x.listener.onBinaryMessage(x.ws,hint(1,-1)));
            rejects("end group",PB,x->x.listener.onBinaryMessage(x.ws,new byte[]{8,1,28}));
            for(int depth:new int[]{0,31,32}) acceptsWire("group depth "+depth,groups(depth));
            for(int depth:new int[]{33,34,100}) rejectsWire("group depth "+depth,groups(depth));
            byte[] valid=hint(1,99);
            rejectsWire("mismatched end",concat(valid,new byte[]{27,36}));
            rejectsWire("unclosed group",concat(valid,new byte[]{27}));
            rejectsWire("zero tag",concat(valid,new byte[]{0}));
            rejectsWire("truncated tag varint",concat(valid,new byte[]{(byte)128}));
            rejectsWire("truncated scalar varint",concat(valid,new byte[]{24,(byte)128}));
            rejectsWire("malformed scalar varint",concat(valid,new byte[]{24,(byte)128,(byte)128,(byte)128,
                (byte)128,(byte)128,(byte)128,(byte)128,(byte)128,(byte)128,(byte)128,0}));
            rejectsWire("truncated fixed64",concat(valid,new byte[]{25,0}));
            rejectsWire("truncated fixed32",concat(valid,new byte[]{29,0}));
            rejectsWire("invalid wire6",concat(valid,new byte[]{30}));
            rejectsWire("invalid wire7",concat(valid,new byte[]{31}));
            rejectsWire("truncated length",concat(valid,new byte[]{26,(byte)128}));
            rejectsWire("truncated opaque",concat(valid,new byte[]{26,2,0}));
            rejectsWire("negative length",concat(valid,new byte[]{26,(byte)255,(byte)255,(byte)255,(byte)255,15}));
            acceptsWire("valid scalar wires",concat(valid,new byte[]{24,42,25,0,0,0,0,0,0,0,0,29,0,0,0,0}));
            byte[] opaque=new byte[103]; opaque[0]=26; opaque[1]=101;
            Arrays.fill(opaque,2,102,(byte)27); opaque[102]=0;
            acceptsWire("opaque group bytes and zero tag",concat(valid,opaque));
            acceptsWire("different nested field numbers",concat(valid,new byte[]{27,35,36,28}));
            for(String text:new String[]{"not-json","{}","{\"cursor\":-1,\"events\":[]}","{\"cursor\":99,\"events\":{}}"})
                rejects("bad JSON",JSON,x->x.listener.onTextMessage(x.ws,text));
            f=new Fixture(PB); f.listener.onBinaryMessage(f.ws,hint(1,99)); set(f.owner,"generation",11L);
            AndroidUtilities.drain(); check(f.cursor()==3 && f.sink.wakes==0,"PB late UI generation guard");
            f=new Fixture(JSON); f.listener.onTextMessage(f.ws,"{\"cursor\":99,\"events\":[]}"); f.owner.stop();
            AndroidUtilities.drain(); check(f.cursor()==3 && f.sink.wakes==0,"JSON stop isolation");
            f=new Fixture(PB); f.listener.onConnected(f.ws,Collections.emptyMap()); set(f.owner,"generation",11L);
            AndroidUtilities.drain(); check(f.sink.wakes==0,"connect late generation guard");
            f=new Fixture(PB); set(f.owner,"generation",11L); f.listener.onBinaryMessage(f.ws,new byte[0]);
            AndroidUtilities.drain(); check(f.owner.generation()==11 && AndroidUtilities.delayed.isEmpty(),"stale failure no retry");
            f=new Fixture(PB); f.listener.onBinaryMessage(f.ws,new byte[0]); f.listener.onBinaryMessage(f.ws,hint(1,99));
            AndroidUtilities.drain(); check(f.cursor()==3 && f.sink.wakes==0,"failure invalidates later queued hints");
            check(AndroidUtilities.delayed.size()==1,"failure then hint single retry");
            f=new Fixture(PB); f.listener.onDisconnected(f.ws,null,null,true); AndroidUtilities.drain();
            check(AndroidUtilities.delayed.size()==1,"disconnect retry scheduled"); f.owner.stop();
            check(AndroidUtilities.delayed.isEmpty(),"stop cancels retry");
            System.out.println("PASS: "+assertions+" assertions; real BotgramEventSocket listener, real nv/protobuf/JSON, queued UI substitute; no network");
        } finally {
            Field field=BotgramEventSocket.class.getDeclaredField("network"); field.setAccessible(true);
            ExecutorService executor=(ExecutorService)field.get(null); executor.shutdown();
            if(!executor.awaitTermination(10,TimeUnit.SECONDS))throw new AssertionError("network tasks did not finish");
        }
    }
}
