import org.telegram.botgram.BotgramNativeGiftRequests;

import java.util.concurrent.CountDownLatch;

public final class BotgramNativeGiftRequestsSmoke {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        BotgramNativeGiftRequests requests = new BotgramNativeGiftRequests();
        Object nativeOwner = new Object();
        Object nextSession = new Object();

        BotgramNativeGiftRequests.Pending first = requests.begin(1, nativeOwner);
        check(requests.canDeliver(first, nativeOwner), "native owner must not require a Botgram session");
        check(!requests.canDeliver(first, nextSession), "different session accepted");
        requests.finishResponse(first);
        check(requests.canDeliver(first, nativeOwner), "notice lost after response");
        check(requests.cancel(1), "pending token cancellation not recognized");
        check(!requests.canDeliver(first, nativeOwner), "cancelled notice escaped");
        requests.finishNotice(first);

        BotgramNativeGiftRequests.Pending beforeResponse = requests.begin(7, nativeOwner);
        check(requests.cancel(7), "early token cancellation not recognized");
        check(!requests.canDeliver(beforeResponse, nativeOwner), "cancelled response escaped");
        requests.finishResponse(beforeResponse);
        requests.finishNotice(beforeResponse);

        BotgramNativeGiftRequests.Pending complete = requests.begin(2, nativeOwner);
        requests.finishNotice(complete);
        check(requests.canDeliver(complete, nativeOwner), "response lost after notice");
        requests.finishResponse(complete);
        check(!requests.canDeliver(complete, nativeOwner), "completed request retained");
        check(!requests.cancel(2), "completed token still cancellable");

        BotgramNativeGiftRequests.Pending bound = requests.begin(3, nativeOwner);
        BotgramNativeGiftRequests.Pending other = requests.begin(4, nativeOwner);
        check(requests.bind(3, 100), "bind failed");
        check(requests.bind(4, 101), "second bind failed");
        requests.cancelGuid(100);
        check(!requests.canDeliver(bound, nativeOwner), "GUID cancellation escaped");
        check(requests.canDeliver(other, nativeOwner), "other GUID was cancelled");

        BotgramNativeGiftRequests.Pending notYetBound = requests.begin(5, nativeOwner);
        requests.cancelGuid(102);
        check(requests.bind(5, 102), "late binding not recognized");
        check(!requests.canDeliver(notYetBound, nativeOwner), "cancellation before bind escaped");
        check(requests.canDeliver(other, nativeOwner), "late bind cancelled an unrelated request");

        BotgramNativeGiftRequests.Pending reused = requests.begin(6, nextSession);
        check(requests.bind(6, 102), "reused GUID bind failed");
        check(requests.canDeliver(reused, nextSession), "completed GUID tombstone was retained");
        check(!requests.canDeliver(reused, nativeOwner), "old session accepted after reauthentication");

        BotgramNativeGiftRequests separateAccount = new BotgramNativeGiftRequests();
        BotgramNativeGiftRequests.Pending independent = separateAccount.begin(6, nativeOwner);
        requests.cancelAll();
        check(!requests.canDeliver(other, nativeOwner) && !requests.canDeliver(reused, nextSession), "cleanup failed");
        check(separateAccount.canDeliver(independent, nativeOwner), "another account was cancelled");
        separateAccount.finishResponse(independent);
        separateAccount.finishNotice(independent);

        for (int i = 0; i < 64; i++) {
            int token = 200 + i;
            BotgramNativeGiftRequests.Pending concurrent = requests.begin(token, nativeOwner);
            CountDownLatch start = new CountDownLatch(1);
            Thread canceller = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException error) {
                    throw new AssertionError(error);
                }
                requests.cancel(token);
            });
            canceller.start();
            start.countDown();
            canceller.join();
            check(!requests.canDeliver(concurrent, nativeOwner), "concurrent cancellation escaped");
            requests.finishResponse(concurrent);
            requests.finishNotice(concurrent);
        }

        System.out.println("PASS: native owner, token/GUID and concurrent cancellation, late bind, session and account isolation");
    }
}
