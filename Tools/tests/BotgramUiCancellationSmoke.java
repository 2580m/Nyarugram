package org.telegram.messenger;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.telegram.botgram.BotgramSessionScope;

public final class BotgramUiCancellationSmoke {
    private static int assertions;

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        TestHandler handler = new TestHandler();
        ApplicationLoader.applicationHandler = handler;
        AtomicInteger frames = new AtomicInteger();
        AtomicInteger replies = new AtomicInteger();
        Runnable frame = frames::incrementAndGet;
        Runnable reply = replies::incrementAndGet;
        AndroidUtilities.runOnUIThread(frame, 16);
        AndroidUtilities.runOnUIThread(reply);
        AndroidUtilities.cancelRunOnUIThread(null);
        check(handler.tasks.size() == 2, "null cancellation removed animation or reply");
        handler.runAll();
        check(frames.get() == 1 && replies.get() == 1, "unrelated UI work did not run");

        AndroidUtilities.runOnUIThread(frame);
        AndroidUtilities.runOnUIThread(frame, 16);
        AndroidUtilities.runOnUIThread(reply);
        AndroidUtilities.cancelRunOnUIThread(frame);
        check(handler.tasks.size() == 1, "specific cancellation did not preserve unrelated tasks");
        handler.runAll();
        check(frames.get() == 1 && replies.get() == 2, "specific cancellation executed a removed task");

        AtomicInteger drained = new AtomicInteger();
        BotgramSessionScope.drain(() -> {
            AndroidUtilities.runOnUIThread(frame, 16);
            AndroidUtilities.runOnUIThread(reply);
            AndroidUtilities.cancelRunOnUIThread(null);
        }, drained::incrementAndGet);
        check(handler.tasks.size() == 2, "null cancellation removed scoped UI tasks");
        check(drained.get() == 0, "null cancellation prematurely drained a session scope");
        handler.runAll();
        check(frames.get() == 2 && replies.get() == 3, "scoped tasks failed to execute");
        check(drained.get() == 1, "completed scope failed to drain");

        BotgramSessionScope.drain(() -> {
            AndroidUtilities.runOnUIThread(frame);
            AndroidUtilities.runOnUIThread(frame, 16);
            AndroidUtilities.cancelRunOnUIThread(frame);
        }, drained::incrementAndGet);
        check(handler.tasks.isEmpty(), "specific cancellation retained scoped deliveries");
        check(drained.get() == 2, "specific cancellation leaked queued scope work");

        ApplicationLoader.applicationHandler = null;
        AndroidUtilities.cancelRunOnUIThread(frame);
        AndroidUtilities.cancelRunOnUIThread(null);
        AndroidUtilities.runOnUIThread(frame);
        check(frames.get() == 2, "missing handler changed execution state");
        System.out.println("UI cancellation host assertions passed: " + assertions);
    }
}

final class ApplicationLoader {
    static TestHandler applicationHandler;
}

final class TestHandler {
    static final class Entry {
        final Runnable delivery;
        final Object token;

        Entry(Runnable delivery, Object token) {
            this.delivery = delivery;
            this.token = token;
        }
    }

    final ArrayList<Entry> tasks = new ArrayList<>();

    boolean postAtTime(Runnable delivery, Object token, long time) {
        tasks.add(new Entry(delivery, token));
        return true;
    }

    void removeCallbacks(Runnable task) {
        tasks.removeIf(entry -> entry.delivery == task);
    }

    void removeCallbacksAndMessages(Object token) {
        tasks.removeIf(entry -> token == null || entry.token == token);
    }

    void runAll() {
        while (!tasks.isEmpty()) tasks.remove(0).delivery.run();
    }
}
