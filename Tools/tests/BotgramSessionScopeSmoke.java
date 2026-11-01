import org.telegram.botgram.BotgramSessionScope;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class BotgramSessionScopeSmoke {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        ArrayDeque<Runnable> ui = new ArrayDeque<>(), storage = new ArrayDeque<>(), stage = new ArrayDeque<>();
        AtomicInteger writes = new AtomicInteger();
        BotgramSessionScope old = new BotgramSessionScope();
        old.bind(() -> ui.add(BotgramSessionScope.wrap(() -> {
            writes.incrementAndGet();
            storage.add(BotgramSessionScope.wrap(writes::incrementAndGet));
        }))).run();
        old.close(() -> writes.addAndGet(100));
        ui.remove().run();
        check(writes.get() == 100 && storage.isEmpty(), "late UI and descendants escaped");
        new BotgramSessionScope().bind(writes::incrementAndGet).run();
        check(writes.get() == 101, "new/other account scope blocked");

        BotgramSessionScope running = new BotgramSessionScope();
        CountDownLatch entered = new CountDownLatch(1), query = new CountDownLatch(1), closed = new CountDownLatch(1);
        Runnable[] child = new Runnable[1];
        Thread parent = new Thread(running.bind(() -> {
            child[0] = BotgramSessionScope.wrap(query::countDown);
            entered.countDown();
            try { check(query.await(3, TimeUnit.SECONDS), "synchronous query deadlocked"); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
        }));
        parent.start();
        check(entered.await(3, TimeUnit.SECONDS), "parent did not start");
        running.close(closed::countDown);
        check(closed.getCount() == 1, "cleanup ran before active task finished");
        child[0].run();
        parent.join(3000);
        check(!parent.isAlive() && closed.getCount() == 0, "active task did not drain");
        running.bind(() -> { throw new AssertionError("closed scope restarted"); }).run();

        AtomicInteger cleaned = new AtomicInteger();
        BotgramSessionScope.drain(() -> storage.add(BotgramSessionScope.wrap(() ->
            stage.add(BotgramSessionScope.wrap(() -> ui.add(BotgramSessionScope.wrap(cleaned::incrementAndGet))))
        )), () -> cleaned.addAndGet(10));
        check(cleaned.get() == 0, "cleanup completed before storage");
        storage.remove().run();
        stage.remove().run();
        check(cleaned.get() == 0, "cleanup completed before transitive UI");
        ui.remove().run();
        check(cleaned.get() == 11, "transitive cleanup did not drain");
        BotgramSessionScope.Queue queue = new BotgramSessionScope.Queue();
        Runnable cancelled = () -> { throw new AssertionError("cancelled task executed"); };
        AtomicInteger cancelledDrain = new AtomicInteger();
        BotgramSessionScope.drain(() -> queue.post(cancelled, task -> { ui.add(task); return true; }), cancelledDrain::incrementAndGet);
        queue.cancel(cancelled, () -> { });
        check(cancelledDrain.get() == 1, "cancelled cleanup did not drain");
        ui.remove().run();
        check(cancelledDrain.get() == 1, "cancelled delivery released twice");
        BotgramSessionScope.drain(() -> queue.post(cancelled, task -> false), cancelledDrain::incrementAndGet);
        check(cancelledDrain.get() == 2, "rejected enqueue did not drain");
        BotgramSessionScope.drain(() -> {
            queue.post(cancelled, task -> { ui.add(task); return true; });
            queue.post(cancelled, task -> { ui.add(task); return true; });
        }, cancelledDrain::incrementAndGet);
        queue.cancel(null, ui::clear);
        check(cancelledDrain.get() == 3, "queue clear did not release all tasks");
        BotgramSessionScope.drain(() -> {
            try {
                queue.post(cancelled, task -> { throw new IllegalStateException("enqueue failed"); });
                throw new AssertionError("enqueue exception swallowed");
            } catch (IllegalStateException expected) { }
        }, cancelledDrain::incrementAndGet);
        check(cancelledDrain.get() == 4, "enqueue exception leaked drain work");
        Runnable[] delayed = new Runnable[1];
        BotgramSessionScope.drain(() -> delayed[0] = BotgramSessionScope.guard(cancelled), () -> { });
        delayed[0].run();
        BotgramSessionScope.wrap(writes::incrementAndGet).run();
        check(writes.get() == 102, "ThreadLocal leaked");
        System.out.println("PASS: stale UI/storage, independent generation, active-task drain, synchronous query, transitive cleanup, context restore");
    }
}
