package org.telegram.messenger;
import java.util.*;
public final class AndroidUtilities {
    public static final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    public static final List<Runnable> delayed = new ArrayList<>();
    public static void runOnUIThread(Runnable r) { queue.add(r); }
    public static void runOnUIThread(Runnable r, long delay) { delayed.add(r); }
    public static void cancelRunOnUIThread(Runnable r) { queue.remove(r); delayed.remove(r); }
    public static void drain() { while (!queue.isEmpty()) queue.remove().run(); }
}
