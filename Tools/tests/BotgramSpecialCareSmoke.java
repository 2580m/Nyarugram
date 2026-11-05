package org.telegram.botgram;

import java.util.Arrays;
import java.util.Collections;

/** Production state regression: failure rollback, confirmed cleanup, overlap, restart and late disk. */
public final class BotgramSpecialCareSmoke {
    private static int checks;
    private static void check(boolean value, String name) {
        checks++;
        if (!value) throw new AssertionError(name);
    }
    public static void main(String[] args) throws Exception {
        BotgramSpecialCareState state = new BotgramSpecialCareState();
        check(!state.isSpecial(7), "unknown is false");
        String owner = BotgramSpecialCareState.owner(0, "https://a", "u", "secret");
        check(owner.length() == 64 && !owner.contains("secret"), "no raw token in cache key");
        check(!owner.equals(BotgramSpecialCareState.owner(1, "https://a", "u", "secret")), "account isolation");
        check(!owner.equals(BotgramSpecialCareState.owner(0, "https://b", "u", "secret")), "server isolation");
        check(!owner.equals(BotgramSpecialCareState.owner(0, "https://a", "v", "secret")), "user isolation");
        check(!owner.equals(BotgramSpecialCareState.owner(0, "https://a", "u", "new")), "token isolation");
        long generation = state.bind(owner);
        check(state.apply(generation, 3, Arrays.asList(7L, 8L)), "server snapshot");
        check(state.isSpecial(7) && state.isSpecial(8), "server snapshot special");

        // Required regression: enabled B -> delete request fails -> backend still contains B.
        long deleteB = state.beginSuppress(generation, 7);
        check(deleteB != 0 && state.isPending(7) && !state.isSpecial(7), "delete start hides contact");
        check(state.apply(generation, 4, Arrays.asList(7L, 8L)), "newer snapshot still contains B");
        check(!state.isSpecial(7), "pending suppression cannot be upgraded by refresh");
        check(state.cancelSuppress(generation, 7, deleteB), "failed delete cancels its own suppression");
        check(state.isSpecial(7), "failed delete restores special care immediately");
        check(state.apply(generation, 5, Arrays.asList(7L, 8L)), "server still contains B");
        check(state.isSpecial(7), "restored contact routes enhanced alerts");

        // Successful delete: stale in-flight snapshot cannot restore; authoritative absence clears.
        long confirmedDelete = state.beginSuppress(generation, 7);
        check(state.confirmSuppress(generation, 7, confirmedDelete), "successful delete confirms");
        check(!state.isSpecial(7), "successful delete keeps suppression");
        check(state.apply(generation, 6, Arrays.asList(7L, 8L)), "stale snapshot still lists B");
        check(!state.isSpecial(7), "old snapshot cannot restore after success");
        check(state.apply(generation, 7, Collections.singletonList(8L)), "authoritative deletion");
        check(!state.isSpecial(7), "confirmed deletion remains gone");
        check(state.suppressed().isEmpty(), "confirmed suppression cleaned by authority");
        check(state.apply(generation, 8, Arrays.asList(7L, 8L)), "server-confirmed explicit enable");
        check(state.isSpecial(7), "server truth wins after re-add");

        // Overlapping operations: old failure must not lift a newer valid suppression.
        long firstDelete = state.beginSuppress(generation, 7);
        long secondDelete = state.beginSuppress(generation, 7);
        check(firstDelete != secondDelete && state.isPending(7), "both operations have independent tokens");
        check(state.cancelSuppress(generation, 7, firstDelete), "first operation failure ends only itself");
        check(!state.isSpecial(7), "old failure cannot lift newer suppression");
        check(state.cancelSuppress(generation, 7, secondDelete), "current operation can cancel");
        check(state.isSpecial(7), "current failure restores special care");
        check(!state.cancelSuppress(generation, 7, firstDelete), "duplicate result is ignored");
        long thirdDelete = state.beginSuppress(generation, 7);
        check(state.confirmSuppress(generation, 7, thirdDelete), "newer operation success");
        check(!state.isSpecial(7), "newer success keeps suppression");
        check(state.apply(generation, 9, Collections.singletonList(8L)), "authoritative removal clears confirmed 7");
        check(!state.isSpecial(7), "confirmed contact stays gone");
        check(state.apply(generation, 10, Arrays.asList(7L, 8L)), "server re-adds both contacts");
        check(state.isSpecial(7) && state.isSpecial(8), "re-added contacts are ordinary again");

        // Different contacts and interleaved delete/block behave independently.
        long deleteSeven = state.beginSuppress(generation, 7);
        long blockEight = state.beginSuppress(generation, 8);
        check(state.cancelSuppress(generation, 7, deleteSeven), "cancel first contact");
        check(state.isSpecial(7) && !state.isSpecial(8), "other pending contact unaffected");
        check(state.confirmSuppress(generation, 8, blockEight), "confirm second contact");
        check(!state.isSpecial(8), "second contact confirmed hidden");
        check(state.apply(generation, 11, Collections.singletonList(7L)), "server removed 8");
        check(state.isSpecial(7) && !state.isSpecial(8), "confirmed state follows server");

        // Explicit re-enable wins over local suppression and stale operation results.
        long staleEnable = state.beginSuppress(generation, 7);
        check(state.confirmedEnable(generation, 7), "explicit enable");
        check(state.isSpecial(7) && !state.isPending(7), "explicit enable clears suppression");
        check(!state.confirmSuppress(generation, 7, staleEnable), "stale operation cannot re-suppress");

        // Restart: confirmed cleanup persists, pending operation does not.
        BotgramSpecialCareState persisted = new BotgramSpecialCareState();
        long persistedGeneration = persisted.bind(owner);
        check(persisted.apply(persistedGeneration, 2, Collections.singletonList(7L)), "persisted snapshot");
        long persistedOperation = persisted.beginSuppress(persistedGeneration, 7);
        check(persisted.confirmSuppress(persistedGeneration, 7, persistedOperation), "persisted confirmation");
        BotgramSpecialCareState restartedConfirmed = new BotgramSpecialCareState();
        long restartedConfirmedGeneration = restartedConfirmed.bind(owner);
        check(restartedConfirmed.restoreDisk(restartedConfirmedGeneration, restartedConfirmed.localMark(), 2,
                Collections.emptyList(), persisted.suppressed()), "confirmed disk restore");
        check(!restartedConfirmed.isSpecial(7), "confirmed delete survives restart");
        check(restartedConfirmed.apply(restartedConfirmedGeneration, 3, Collections.singletonList(7L)), "post-restart snapshot");
        check(!restartedConfirmed.isSpecial(7), "confirmed delete/block is not restored by a later snapshot");
        BotgramSpecialCareState restartedPending = new BotgramSpecialCareState();
        long restartedPendingGeneration = restartedPending.bind(owner);
        check(restartedPending.restoreDisk(restartedPendingGeneration, restartedPending.localMark(), 2,
                Collections.singletonList(7L), Collections.emptyList()), "pending disk has no suppression");
        check(restartedPending.isSpecial(7), "interrupted delete cannot suppress forever across restart");

        // Late disk read: rejected snapshot must not change users, revision, pending or confirmed.
        BotgramSpecialCareState lateBefore = new BotgramSpecialCareState();
        long lateBeforeGeneration = lateBefore.bind(owner);
        long beforeServerMark = lateBefore.localMark();
        check(lateBefore.apply(lateBeforeGeneration, 7, Collections.singletonList(7L)), "revision 7 enabled");
        check(lateBefore.isSpecial(7), "server enabled before late disk callback");
        check(!lateBefore.restoreDisk(lateBeforeGeneration, beforeServerMark, 5,
                Collections.singletonList(7L), Collections.singletonList(7L)), "stale revision 5 disk rejected");
        check(lateBefore.isSpecial(7), "late disk cannot suppress confirmed special care");
        check(lateBefore.revision() == 7, "late disk cannot roll revision back");
        check(lateBefore.suppressed().isEmpty(), "late disk writes no suppression");

        BotgramSpecialCareState late = new BotgramSpecialCareState();
        long lateGeneration = late.bind(owner);
        check(late.apply(lateGeneration, 7, Collections.singletonList(7L)), "authoritative revision 7");
        long diskMark = late.localMark();
        long localDelete = late.beginSuppress(lateGeneration, 7);
        check(!late.restoreDisk(lateGeneration, diskMark, 5, Arrays.asList(7L, 8L), Collections.emptyList()),
                "late disk rejected after local operation");
        check(late.isPending(7) && !late.isSpecial(7), "late disk cannot overwrite pending suppression");
        check(late.revision() == 7 && !late.isSpecial(8), "rejected disk changed no user state");
        check(late.suppressed().isEmpty(), "rejected disk changed no confirmed suppression");
        check(!late.restoreDisk(lateGeneration, late.localMark(), 9, Collections.singletonList(8L), Collections.emptyList()),
                "disk cannot beat authoritative revision");
        check(late.revision() == 7, "revision preserved after rejected disk");
        check(late.cancelSuppress(lateGeneration, 7, localDelete), "late local delete cleanup");

        BotgramSpecialCareState lateEnable = new BotgramSpecialCareState();
        long lateEnableGeneration = lateEnable.bind(owner);
        lateEnable.apply(lateEnableGeneration, 1, Collections.singletonList(7L));
        long enableDiskMark = lateEnable.localMark();
        long enableOperation = lateEnable.beginSuppress(lateEnableGeneration, 7);
        lateEnable.confirmedEnable(lateEnableGeneration, 7);
        check(!lateEnable.restoreDisk(lateEnableGeneration, enableDiskMark, 1, Collections.singletonList(7L),
                Collections.emptyList()), "late disk rejected after explicit enable");
        check(lateEnable.isSpecial(7), "explicit enable survives late disk");
        check(!lateEnable.confirmSuppress(lateEnableGeneration, 7, enableOperation), "cleared operation cannot suppress");

        // Session lifecycle and generation isolation.
        state.bind(null);
        check(!state.isSpecial(7), "logout clears memory immediately");
        check(!state.apply(generation, 90, Collections.singletonList(7L)), "closed session late callback discarded");
        check(!state.restoreDisk(generation, 0, 1, Collections.singletonList(7L), Collections.emptyList()),
                "closed session disk restore rejected");
        long next = state.bind(BotgramSpecialCareState.owner(0, "https://a", "u", "new"));
        check(next != generation && !state.isSpecial(7), "new authentication does not inherit");
        check(!state.apply(generation, 100, Collections.singletonList(7L)), "old generation cannot write new session");
        check(!state.restoreDisk(generation, 0, 1, Collections.singletonList(7L), Collections.emptyList()),
                "old generation disk restore rejected");

        Thread completion = new Thread(() -> state.apply(next, 1, Collections.singletonList(10L)));
        completion.start(); completion.join();
        check(state.isSpecial(10), "shared state updates with no page");
        BotgramSpecialCareState other = new BotgramSpecialCareState();
        other.bind("other");
        check(!other.isSpecial(10), "independent account cache");
        coldStartOperations(owner);
        overlappingOperations(owner);
        System.out.println("Special care production state: " + checks + " checks passed");
    }

    private static void coldStartOperations(String owner) {
        for (boolean success : new boolean[]{false, true}) {
            BotgramSpecialCareState cold = new BotgramSpecialCareState();
            long generation = cold.bind(owner);
            long diskMark = cold.localMark();
            long operation = cold.beginSuppress(generation, 7);
            check(operation != 0 && cold.isPending(7), "cold target operation recorded before cache exists");
            check(!cold.restoreDisk(generation, diskMark, 5, Collections.singletonList(7L), Collections.emptyList()),
                    "local cold operation rejects earlier disk read");
            check(cold.revision() == -1 && cold.cachedUsers().isEmpty() && cold.suppressed().isEmpty(),
                    "rejected cold cache has no side effects");
            cold.apply(generation, 5, Collections.singletonList(7L));
            check(!cold.isSpecial(7), "server snapshot cannot lift cold pending operation");
            check(cold.cachedUsers().contains(7L) && cold.suppressed().isEmpty(),
                    "persisted base snapshot retains pending target without persisting operation");
            BotgramSpecialCareState restarted = new BotgramSpecialCareState();
            long next = restarted.bind(owner);
            restarted.restoreDisk(next, restarted.localMark(), cold.revision(), cold.cachedUsers(), cold.suppressed());
            check(restarted.isSpecial(7), "interrupted pending operation does not hide cached contact on restart");
            check(success ? cold.confirmSuppress(generation, 7, operation) : cold.cancelSuppress(generation, 7, operation),
                    "cold operation result accepted");
            check(cold.isSpecial(7) != success, "cold operation follows its result");
        }
    }

    private static void overlappingOperations(String owner) {
        // Both result orders and all success/failure combinations for delete + block of one user.
        for (boolean firstSuccess : new boolean[]{false, true}) {
            for (boolean secondSuccess : new boolean[]{false, true}) {
                for (boolean reverse : new boolean[]{false, true}) {
                    BotgramSpecialCareState overlap = new BotgramSpecialCareState();
                    long generation = overlap.bind(owner);
                    overlap.apply(generation, 5, Collections.singletonList(7L));
                    long first = overlap.beginSuppress(generation, 7);
                    long second = overlap.beginSuppress(generation, 7);
                    long early = reverse ? second : first;
                    long late = reverse ? first : second;
                    boolean earlySuccess = reverse ? secondSuccess : firstSuccess;
                    boolean lateSuccess = reverse ? firstSuccess : secondSuccess;
                    check(earlySuccess ? overlap.confirmSuppress(generation, 7, early) : overlap.cancelSuppress(generation, 7, early),
                            "either operation can resolve first");
                    check(overlap.isPending(7) && !overlap.isSpecial(7), "remaining operation still suppresses");
                    overlap.apply(generation, 6, Collections.singletonList(7L));
                    check(!overlap.isSpecial(7), "refresh cannot restore overlapping target");
                    check(lateSuccess ? overlap.confirmSuppress(generation, 7, late) : overlap.cancelSuppress(generation, 7, late),
                            "remaining operation result remains valid");
                    check(!overlap.isPending(7) && overlap.isSpecial(7) == !(firstSuccess || secondSuccess),
                            "any confirmed success survives other failure; both failures restore");
                }
            }
        }
    }
}
