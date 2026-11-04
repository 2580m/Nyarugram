package org.telegram.botgram;

import android.os.Debug;
import android.os.SystemClock;
import android.view.Choreographer;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded debug timing and UI-stack sampling for opening chat settings. */
public final class BotgramUiTrace implements Choreographer.FrameCallback {
    private static final AtomicInteger nextId = new AtomicInteger();
    private final boolean enabled = BuildVars.DEBUG_VERSION && BuildVars.LOGS_ENABLED;
    private final int id = nextId.incrementAndGet();
    private final long started = SystemClock.uptimeMillis();
    private final Thread uiThread = Thread.currentThread();
    private final String page;
    private volatile long heartbeat = started;
    private volatile String phase = "constructor";
    private volatile boolean finished;
    private long previousStage = started;
    private long previousCpu = Debug.threadCpuTimeNanos();
    private int samples;
    private ScheduledFuture<?> watcher;

    private static final class Sampler {
        static final java.util.concurrent.ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "BotgramUiTrace");
            thread.setDaemon(true);
            return thread;
        });
    }

    public BotgramUiTrace(String page) {
        this.page = page;
        if (enabled) {
            mark("constructor.begin");
            watcher = Sampler.executor.scheduleWithFixedDelay(this::sample, 100, 100, TimeUnit.MILLISECONDS);
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    public void mark(String stage) {
        if (!enabled || finished) return;
        long now = SystemClock.uptimeMillis();
        long cpu = Debug.threadCpuTimeNanos();
        FileLog.d("UI_TRACE page=" + page + " id=" + id + " stage=" + stage
                + " total_ms=" + (now - started) + " phase_ms=" + (now - previousStage)
                + " cpu_ms=" + ((cpu - previousCpu) / 1_000_000));
        previousStage = now;
        previousCpu = cpu;
        phase = stage;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (finished) return;
        long now = SystemClock.uptimeMillis();
        long gap = now - heartbeat;
        if (gap >= 50) {
            FileLog.d("UI_TRACE page=" + page + " id=" + id + " frame_gap_ms=" + gap + " stage=" + phase);
        }
        heartbeat = now;
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void sample() {
        if (finished) return;
        long now = SystemClock.uptimeMillis();
        if (now - started >= 5000) {
            close();
            return;
        }
        if (now - heartbeat < 100 || samples >= 5) return;
        samples++;
        StringBuilder stack = new StringBuilder("UI_TRACE page=").append(page).append(" id=").append(id)
                .append(" blocked_ms=").append(now - heartbeat).append(" stage=").append(phase);
        StackTraceElement[] frames = uiThread.getStackTrace();
        for (int i = 0; i < Math.min(frames.length, 16); i++) {
            stack.append("\n  at ").append(frames[i]);
        }
        FileLog.d(stack.toString());
    }

    public void close() {
        finished = true;
        ScheduledFuture<?> task = watcher;
        if (task != null) task.cancel(false);
    }
}
