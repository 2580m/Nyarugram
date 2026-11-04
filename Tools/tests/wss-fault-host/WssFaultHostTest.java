package org.telegram.botgram;

import com.neovisionaries.ws.client.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.botgram.protocol.v1.BotgramRpc;

/** Real loopback TLS -> nv ReadingThread -> production listener; JVM UI substitute, not Android. */
public final class WssFaultHostTest {
    static final String PB="botgram.event-hint.v1.protobuf", JSON="botgram.events.v1.json";
    static SSLContext tls;
    static int checks, connections;
    interface Send { void apply(OutputStream out) throws Exception; }
    static void check(boolean v,String s) { checks++; if(!v)throw new AssertionError(s); }
    static void set(Object o,String n,Object v)throws Exception { Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v); }
    static Object get(Object o,String n)throws Exception {Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    static byte[] hint(long cursor) {return BotgramRpc.EventHint.newBuilder().setProtocolMajor(1).setCursor(cursor).build().toByteArray();}
    static byte[] groups(int depth) {byte[] head=hint(99), b=Arrays.copyOf(head,head.length+2*depth);Arrays.fill(b,head.length,head.length+depth,(byte)27);Arrays.fill(b,head.length+depth,b.length,(byte)28);return b;}
    static void header(OutputStream out,int first,long n)throws IOException {
        DataOutputStream d=new DataOutputStream(out);d.writeByte(first);
        if(n<126)d.writeByte((int)n);else if(n<65536){d.writeByte(126);d.writeShort((int)n);}else{d.writeByte(127);d.writeLong(n);}
    }
    static void frame(OutputStream out,int first,byte[] payload)throws IOException {header(out,first,payload.length);out.write(payload);out.flush();}
    static String readHttp(InputStream in)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int prev=0,found=0;
        while(bytes.size()<16384){int c=in.read();if(c<0)throw new EOFException();bytes.write(c); if((prev=='\r'&&c=='\n'))found++;else if(c!='\r')found=0;if(found==2)return bytes.toString("US-ASCII");prev=c;}
        throw new IOException("HTTP header bound");
    }
    static final class Sink implements BotgramEventSocket.Listener {int wakes;public void wake(BotgramSession s){wakes++;}public void unauthorized(BotgramSession s){throw new AssertionError("unauthorized");}}
    static final class Fixture implements AutoCloseable {
        final SSLServerSocket server;
        final ExecutorService worker=Executors.newSingleThreadExecutor();
        final Future<?> peer;
        final Sink sink=new Sink();
        final BotgramEventSocket owner=new BotgramEventSocket(sink);
        final WebSocket ws;
        volatile boolean peerFinished, upgraded;
        volatile String target;
        Fixture(String protocol,long cursor,Send send)throws Exception {this(protocol,cursor,send,1000);}
        Fixture(String protocol,long cursor,Send send,int socketTimeout)throws Exception {
            AndroidUtilities.drain();AndroidUtilities.delayed.clear();
            server=(SSLServerSocket)tls.getServerSocketFactory().createServerSocket(0,1,InetAddress.getByName("127.0.0.1"));server.setSoTimeout(3000);
            peer=worker.submit(()->{
                try(SSLSocket socket=(SSLSocket)server.accept()) {
                    socket.setSoTimeout(socketTimeout+1500);socket.startHandshake();String http=readHttp(socket.getInputStream());
                    target=http.split("\r\n")[0];String key=null;
                    for(String line:http.split("\r\n"))if(line.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:"))key=line.substring(line.indexOf(':')+1).trim();
                    if(key==null)throw new IOException("No websocket key");
                    String accept=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                    OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+accept+"\r\nSec-WebSocket-Protocol: "+protocol+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();upgraded=true;
                    send.apply(out);
                    // Bounded no-response/half-open interval. No data after handshake; wait for peer release.
                    while(socket.getInputStream().read()!=-1) { }
                }catch(SocketTimeoutException expected) { }catch(Exception e){throw new RuntimeException(e);}finally{peerFinished=true;}
            });
            set(owner,"active",true);set(owner,"generation",10L);set(owner,"cursor",cursor);
            ws=new WebSocketFactory().setConnectionTimeout(2000).setSocketTimeout(socketTimeout).setSSLSocketFactory(tls.getSocketFactory()).setVerifyHostname(true).createSocket("wss://localhost:"+server.getLocalPort()+"/api/ws?after="+cursor);
            ws.setPingInterval(15000);
            ws.addProtocol(protocol);set(owner,"socket",ws);
            Constructor<?> ctor=Class.forName("org.telegram.botgram.BotgramEventSocket$1").getDeclaredConstructor(BotgramEventSocket.class,long.class);ctor.setAccessible(true);
            ws.addListener((WebSocketAdapter)ctor.newInstance(owner,10L));
            ws.connect();connections++;
            // onConnected wake must be observed before attack assertions; listener callbacks may race.
        }
        long cursor()throws Exception {return (Long)get(owner,"cursor");}
        void pump(long millis)throws Exception {long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);while(System.nanoTime()<end){AndroidUtilities.drain();Thread.sleep(5);}AndroidUtilities.drain();}
        public void close()throws Exception {
            owner.stop();if(ws.getSocket()!=null)ws.getSocket().close();
            server.close();worker.shutdown();
            try{peer.get(4,TimeUnit.SECONDS);}catch(ExecutionException e){Throwable cause=e.getCause(); if(!(cause instanceof RuntimeException) || !(cause.getCause() instanceof SocketException))throw e;}
            check(worker.awaitTermination(1,TimeUnit.SECONDS),"malicious server worker exits");
            AndroidUtilities.drain();AndroidUtilities.delayed.clear();
        }
    }
    static void rejects(String label,String protocol,Send attack)throws Exception {
        try(Fixture f=new Fixture(protocol,3,attack)) {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(System.nanoTime()<end && f.owner.generation()==10)f.pump(10);
            check(f.upgraded,label+" real TLS/WSS101");check(f.cursor()==3,label+" cursor unchanged");
            check(f.owner.generation()>10,label+" rejected/disconnected");
            check(get(f.owner,"socket")==null,label+" socket detached");
            check(AndroidUtilities.delayed.size()==1,label+" one bounded retry");
            System.out.println("PASS network "+label+" cursor=3 generation="+f.owner.generation());
        }
    }
    public static void main(String[] args)throws Exception {
        char[] password=System.getenv("BOTGRAM_WSS_TEST_PASSWORD").toCharArray();
        KeyStore store=KeyStore.getInstance("PKCS12");try(InputStream in=new FileInputStream(args[0])){store.load(in,password);}
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(store,password);
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(store);
        tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),tm.getTrustManagers(),null);Arrays.fill(password,'\0');
        int threadsBefore=Thread.activeCount();long memoryBefore=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        com.sun.management.OperatingSystemMXBean os=(com.sun.management.OperatingSystemMXBean)java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        long cpuBefore=os.getProcessCpuTime();
        try {
            rejects("PB declared 1025 bytes",PB,out->{header(out,0x82,1025);out.flush();});
            rejects("JSON declared 8MiB+1",JSON,out->{header(out,0x81,8*1024*1024+1L);out.flush();});
            rejects("fragment cumulative 1025",PB,out->{frame(out,0x02,new byte[1024]);header(out,0x80,1);out.flush();});
            rejects("1025 empty fragments",PB,out->{for(int i=0;i<1025;i++)header(out,i==0?0x02:0x00,0);out.flush();});
            for(int depth:new int[]{33,34,100})rejects("unknown group depth "+depth,PB,out->frame(out,0x82,groups(depth)));
            rejects("incomplete protobuf",PB,out->frame(out,0x82,new byte[]{8,1,16,(byte)128}));
            rejects("PB text frame",PB,out->frame(out,0x81,"{}".getBytes(StandardCharsets.UTF_8)));
            rejects("JSON binary frame",JSON,out->frame(out,0x82,hint(99)));
            rejects("abnormal one-byte close",PB,out->frame(out,0x88,new byte[]{0}));
            rejects("truncated frame midstream",PB,out->{header(out,0x82,10);out.write(8);out.flush();throw new SocketException("intentional synthetic abort");});
            // Accepted depth 32 traverses TLS, nv reader and the production wire guard/parser.
            long resume;
            try(Fixture f=new Fixture(PB,3,out->frame(out,0x82,groups(32)))) {
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
                while(System.nanoTime()<end&&f.cursor()!=99)f.pump(10);
                check(f.cursor()==99,"depth32 accepted through network");check(f.sink.wakes>=1,"valid hint wake");resume=f.cursor();
            }
            try(Fixture f=new Fixture(PB,resume,out->frame(out,0x82,hint(100)))) {
                f.pump(100);check(f.target.contains("after=99"),"manual reconnect request carries last accepted cursor");check(f.cursor()==100,"reconnect advances cursor");
            }
            try(Fixture f=new Fixture(PB,3,out->{})) {
                f.pump(300);check(f.cursor()==3,"silent half-open no cursor progress");check(f.upgraded,"silent real TLS/WSS101");
                f.owner.stop();f.pump(100);check(get(f.owner,"socket")==null,"explicit stop releases silent socket");check(AndroidUtilities.delayed.isEmpty(),"explicit stop cancels retry");
            }
            try(Fixture f=new Fixture(PB,3,out->{},20000)) {
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(22);
                while(System.nanoTime()<end&&f.owner.generation()==10)f.pump(25);
                check(f.cursor()==3,"20s silent peer cannot advance cursor");
                System.out.println("Production timeout observation: socketTimeoutMs=20000 pingMs=15000 generation="+f.owner.generation()+" detached="+(get(f.owner,"socket")==null)+"; socket read timeout, not OS packet-drop blackhole");
                check(f.owner.generation()>10,"20s no-response socket read expires");
                check(get(f.owner,"socket")==null,"20s no-response socket detached");
            }
            ThreadPoolExecutor network=(ThreadPoolExecutor)getStatic(BotgramEventSocket.class,"network");
            for(int i=0;i<5;i++) {
                Thread.sleep(200);AndroidUtilities.drain();
                System.out.println("Resource window "+i+" activeCloseTasks="+network.getActiveCount()+" queuedCloseTasks="+network.getQueue().size()+" threads="+Thread.activeCount()+" heapUsed="+(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()));
                check(network.getActiveCount()==0&&network.getQueue().isEmpty(),"no pending production close tasks in observation window");
            }
            System.out.println("Resource observation: connections="+connections+" threadsBefore="+threadsBefore+" threadsAfter="+Thread.activeCount()+" heapUsedBefore="+memoryBefore+" heapUsedAfter="+(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())+"; bounded short sample, not leak proof");
            System.out.println("Process CPU delta ns="+(os.getProcessCpuTime()-cpuBefore)+"; includes TLS setup/tests/waits, no protocol performance claim");
            System.out.println("PASS "+checks+" assertions; real loopback TLS/WSS -> production nv reader/listener; test leaf trust context only; JVM UI substitute, NOT Android; manual reconnect, not timer/backoff test; short attacks use1s timeout; separate silent test uses production20s/15s-ping; no OS TCP-blackhole proof; backend/Caddy send-task cancellation NOT TESTED");
        } finally {
            ExecutorService network=(ExecutorService)getStatic(BotgramEventSocket.class,"network");network.shutdown();
            check(network.awaitTermination(10,TimeUnit.SECONDS),"production close executor terminates");
        }
    }
    static Object getStatic(Class<?> c,String name)throws Exception{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
}
