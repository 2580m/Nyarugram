package org.telegram.messenger;
import java.util.*;
import java.util.concurrent.*;
public final class AndroidUtilities {
    public static final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
    public static final List<Runnable> delayed = Collections.synchronizedList(new ArrayList<Runnable>());
    public static void runOnUIThread(Runnable r) { queue.add(r); }
    public static void runOnUIThread(Runnable r, long delay) { delayed.add(r); }
    public static void cancelRunOnUIThread(Runnable r) { queue.remove(r); delayed.remove(r); }
    public static void drain() { Runnable r; while ((r=queue.poll()) != null) r.run(); }
}
