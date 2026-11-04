import org.telegram.botgram.BotgramGiftPolicy;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;

public final class BotgramGiftPolicySmoke {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void checkInvoice(TLRPC.InputInvoice invoice, boolean gift, String name) {
        TLRPC.TL_payments_getPaymentForm form = new TLRPC.TL_payments_getPaymentForm();
        form.invoice = invoice;
        check(BotgramGiftPolicy.isGiftRequest(form) == gift, name + " form");
        TLRPC.TL_payments_sendPaymentForm send = new TLRPC.TL_payments_sendPaymentForm();
        send.invoice = invoice;
        check(BotgramGiftPolicy.isGiftRequest(send) == gift, name + " payment");
        TL_stars.TL_payments_sendStarsForm stars = new TL_stars.TL_payments_sendStarsForm();
        stars.invoice = invoice;
        check(BotgramGiftPolicy.isGiftRequest(stars) == gift, name + " stars payment");
    }

    private static void checkPurpose(TLRPC.InputStorePaymentPurpose purpose, boolean gift, String name) {
        check(BotgramGiftPolicy.isGiftPurpose(purpose) == gift, name + " purpose");
        TLRPC.TL_payments_canPurchaseStore canPurchase = new TLRPC.TL_payments_canPurchaseStore();
        canPurchase.purpose = purpose;
        check(BotgramGiftPolicy.isGiftRequest(canPurchase) == gift, name + " purchase");
        TLRPC.TL_payments_assignPlayMarketTransaction assign = new TLRPC.TL_payments_assignPlayMarketTransaction();
        assign.purpose = purpose;
        check(BotgramGiftPolicy.isGiftRequest(assign) == gift, name + " transaction");
    }

    public static void main(String[] args) {
        check(BotgramGiftPolicy.isDisabled(), "gift policy must be active");
        check(BotgramGiftPolicy.block(null, 0, null) && BotgramGiftPolicy.block(null, 3, null),
                "gift policy must apply to every account");
        check(BotgramGiftPolicy.isGiftRequest(new TL_stars.transferStarGift()), "gift transfer");
        check(BotgramGiftPolicy.isGiftRequest(new TL_stars.checkCanSendGift()), "gift send check");

        checkInvoice(new TLRPC.TL_inputInvoiceStarGift(), true, "star gift");
        checkInvoice(new TLRPC.TL_inputInvoiceStarGiftTransfer(), true, "gift transfer");
        checkInvoice(new TLRPC.TL_inputInvoiceStarGiftResale(), true, "resale");
        checkInvoice(new TLRPC.TL_inputInvoiceStarGiftAuctionBid(), true, "auction");
        checkInvoice(new TLRPC.TL_inputInvoicePremiumGiftStars(), true, "premium gift");
        checkInvoice(new TLRPC.TL_inputInvoiceStarGiftUpgrade(), false, "existing gift upgrade");
        checkInvoice(new TLRPC.TL_inputInvoiceMessage(), false, "ordinary invoice");
        checkInvoice(new TLRPC.TL_inputInvoiceChatInviteSubscription(), false, "chat subscription");

        TLRPC.TL_inputInvoicePremiumGiftCode code = new TLRPC.TL_inputInvoicePremiumGiftCode();
        code.purpose = new TLRPC.TL_inputStorePaymentPremiumGiftCode();
        checkInvoice(code, true, "premium gift code");
        code.purpose = new TLRPC.TL_inputStorePaymentPremiumGiveaway();
        checkInvoice(code, false, "premium giveaway");
        TLRPC.TL_inputInvoiceStars stars = new TLRPC.TL_inputInvoiceStars();
        stars.purpose = new TLRPC.TL_inputStorePaymentStarsGift();
        checkInvoice(stars, true, "stars gift");
        stars.purpose = new TLRPC.TL_inputStorePaymentStarsTopup();
        checkInvoice(stars, false, "stars top-up");
        stars.purpose = new TLRPC.TL_inputStorePaymentStarsGiveaway();
        checkInvoice(stars, false, "stars giveaway");

        checkPurpose(new TLRPC.TL_inputStorePaymentGiftPremium(), true, "store premium gift");
        checkPurpose(new TLRPC.TL_inputStorePaymentPremiumGiftCode(), true, "store gift code");
        checkPurpose(new TLRPC.TL_inputStorePaymentStarsGift(), true, "store stars gift");
        checkPurpose(new TLRPC.TL_inputStorePaymentPremiumSubscription(), false, "own premium");
        checkPurpose(new TLRPC.TL_inputStorePaymentStarsTopup(), false, "store stars top-up");
        checkPurpose(new TLRPC.TL_inputStorePaymentPremiumGiveaway(), false, "store premium giveaway");
        checkPurpose(new TLRPC.TL_inputStorePaymentStarsGiveaway(), false, "store stars giveaway");

        System.out.println("PASS: gift forms, store purchases and receipts; ordinary payments remain unblocked");
    }
}
