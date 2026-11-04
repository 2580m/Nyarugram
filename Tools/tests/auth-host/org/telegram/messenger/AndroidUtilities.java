package org.telegram.messenger;
import java.util.ArrayDeque;
public final class AndroidUtilities {
    private static final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    public static void runOnUIThread(Runnable runnable) { queue.add(runnable); }
    public static void drain() { while (!queue.isEmpty()) queue.remove().run(); }
}
