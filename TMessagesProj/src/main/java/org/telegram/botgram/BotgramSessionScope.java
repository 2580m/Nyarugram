package org.telegram.botgram;

/** Carries response ownership across the controller's UI, stage and storage queues. */
public final class BotgramSessionScope {
    private static final ThreadLocal<BotgramSessionScope> current = new ThreadLocal<>();
    private boolean closed;
    private int running;
    private Runnable drained;
    private boolean trackQueued;

    public static void drain(Runnable task, Runnable completion) {
        BotgramSessionScope scope = new BotgramSessionScope();
        scope.trackQueued = true;
        scope.drained = completion;
        scope.bind(task).run();
    }

    public static Runnable wrap(Runnable task) {
        BotgramSessionScope scope = current.get();
        return scope == null ? task : scope.bind(task);
    }

    public static Runnable guard(Runnable task) {
        BotgramSessionScope scope = current.get();
        if (scope == null) return task;
        return () -> {
            synchronized (scope) {
                if (scope.closed) return;
                scope.running++;
            }
            BotgramSessionScope previous = current.get();
            current.set(scope);
            try { task.run(); }
            finally {
                current.set(previous);
                scope.release();
            }
        };
    }

    public static final class Queue {
        private final java.util.IdentityHashMap<Task, Runnable> tasks = new java.util.IdentityHashMap<>();

        public boolean post(Runnable task, java.util.function.Function<Runnable, Boolean> enqueue) {
            Runnable bound = wrap(task);
            if (bound == task) return enqueue.apply(task);
            Runnable delivery = new Runnable() {
                @Override public void run() {
                    synchronized (Queue.this) { tasks.remove((Task) bound); }
                    bound.run();
                }
            };
            boolean accepted;
            synchronized (this) {
                tasks.put((Task) bound, task);
                try {
                    accepted = enqueue.apply(delivery);
                } catch (RuntimeException error) {
                    tasks.remove((Task) bound);
                    ((Task) bound).cancel();
                    throw error;
                }
                if (!accepted) tasks.remove((Task) bound);
            }
            if (!accepted) ((Task) bound).cancel();
            return accepted;
        }

        public void cancel(Runnable token, Runnable remove) {
            java.util.ArrayList<Task> cancelled = new java.util.ArrayList<>();
            synchronized (this) {
                remove.run();
                for (java.util.Iterator<java.util.Map.Entry<Task, Runnable>> it = tasks.entrySet().iterator(); it.hasNext();) {
                    java.util.Map.Entry<Task, Runnable> entry = it.next();
                    if (token == null || entry.getValue() == token) {
                        cancelled.add(entry.getKey());
                        it.remove();
                    }
                }
            }
            for (Task task : cancelled) task.cancel();
        }
    }

    private final class Task implements Runnable {
        private final Runnable task;
        private boolean claimed;
        Task(Runnable task) { this.task = task; }
        void cancel() { finish(false); }
        private void finish(boolean execute) {
            synchronized (this) {
                if (claimed) return;
                claimed = true;
            }
            if (!execute) {
                if (trackQueued) release();
                return;
            }
            execute();
        }
        @Override public void run() { finish(true); }
        private void execute() {
            synchronized (BotgramSessionScope.this) {
                // Let synchronous child queries finish while an old parent is still running.
                // Once quiescent, no queued work from this generation can start again.
                if (!trackQueued) {
                    if (closed && running == 0) return;
                    running++;
                }
            }
            BotgramSessionScope previous = current.get();
            current.set(BotgramSessionScope.this);
            try {
                task.run();
            } finally {
                current.set(previous);
                release();
            }
        }
    }

    public Runnable bind(Runnable task) {
        synchronized (this) {
            if (trackQueued) running++;
        }
        return new Task(task);
    }

    private void release() {
        Runnable completion = null;
        synchronized (this) {
            if (--running == 0 && (closed || trackQueued)) {
                closed = true;
                completion = drained;
                drained = null;
            }
        }
        if (completion != null) completion.run();
    }

    public void close(Runnable completion) {
        boolean idle;
        synchronized (this) {
            closed = true;
            idle = running == 0;
            if (!idle) drained = completion;
        }
        if (idle) completion.run();
    }

    public static void detached(Runnable task) {
        BotgramSessionScope previous = current.get();
        current.remove();
        try {
            task.run();
        } finally {
            current.set(previous);
        }
    }
}
