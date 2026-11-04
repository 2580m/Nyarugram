package org.telegram.botgram;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class BotgramNativeGiftRequests {
    public static final class Pending {
        private final int token;
        private final Object owner;
        private int guid;
        private boolean cancelled;
        private boolean responseFinished;
        private boolean noticeFinished;

        private Pending(int token, Object owner) {
            this.token = token;
            this.owner = owner;
        }
    }

    private final Map<Integer, Pending> pending = new HashMap<>();
    // A GUID can be cancelled before its pending request is bound to it.
    private final Set<Integer> cancelledGuids = new HashSet<>();

    public synchronized Pending begin(int token, Object owner) {
        if (pending.containsKey(token)) throw new IllegalStateException("Duplicate request token");
        Pending request = new Pending(token, Objects.requireNonNull(owner));
        pending.put(token, request);
        return request;
    }

    public synchronized boolean canDeliver(Pending request, Object owner) {
        return !request.cancelled && request.owner == owner && pending.get(request.token) == request;
    }

    public synchronized boolean bind(int token, int guid) {
        Pending request = pending.get(token);
        if (request == null) return false;
        if (guid != 0) {
            if (cancelledGuids.contains(guid)) {
                request.cancelled = true;
                pending.remove(token);
            } else {
                request.guid = guid;
            }
            pruneCancelledGuids();
        }
        return true;
    }

    public synchronized boolean cancel(int token) {
        Pending request = pending.remove(token);
        if (request == null) return false;
        request.cancelled = true;
        pruneCancelledGuids();
        return true;
    }

    public synchronized void cancelGuid(int guid) {
        if (guid == 0) return;
        boolean hasUnbound = false;
        Iterator<Pending> iterator = pending.values().iterator();
        while (iterator.hasNext()) {
            Pending request = iterator.next();
            if (request.guid == guid) {
                request.cancelled = true;
                iterator.remove();
            } else if (request.guid == 0) {
                hasUnbound = true;
            }
        }
        if (hasUnbound) cancelledGuids.add(guid);
        pruneCancelledGuids();
    }

    public synchronized void cancelAll() {
        for (Pending request : pending.values()) request.cancelled = true;
        pending.clear();
        cancelledGuids.clear();
    }

    public synchronized void finishResponse(Pending request) {
        request.responseFinished = true;
        pruneFinished(request);
    }

    public synchronized void finishNotice(Pending request) {
        request.noticeFinished = true;
        pruneFinished(request);
    }

    private void pruneFinished(Pending request) {
        if (request.responseFinished && request.noticeFinished && pending.get(request.token) == request) {
            pending.remove(request.token);
            pruneCancelledGuids();
        }
    }

    private void pruneCancelledGuids() {
        for (Pending request : pending.values()) {
            if (request.guid == 0) return;
        }
        cancelledGuids.clear();
    }
}
