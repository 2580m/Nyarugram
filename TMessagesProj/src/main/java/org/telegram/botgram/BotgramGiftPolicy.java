package org.telegram.botgram;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;

import org.telegram.messenger.R;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;

public final class BotgramGiftPolicy {
    private BotgramGiftPolicy() { }

    public static boolean isDisabled() {
        return true;
    }

    public static boolean block(Context context, int account, Theme.ResourcesProvider resources) {
        if (!isDisabled()) return false;
        if (context != null) {
            new AlertDialog.Builder(context, resources).setTitle(getString(R.string.SendAGift))
                    .setMessage(getString(R.string.BotgramNoGifts))
                    .setPositiveButton(getString(R.string.OK), null).show();
        }
        return true;
    }

    public static void notifyBlocked(int account) {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment != null && fragment.getCurrentAccount() == account) {
            block(fragment.getParentActivity(), account, fragment.getResourceProvider());
        }
    }

    public static boolean isGiftPurpose(TLRPC.InputStorePaymentPurpose purpose) {
        return purpose instanceof TLRPC.TL_inputStorePaymentGiftPremium
                || purpose instanceof TLRPC.TL_inputStorePaymentPremiumGiftCode
                || purpose instanceof TLRPC.TL_inputStorePaymentStarsGift;
    }

    public static boolean isGiftRequest(TLObject request) {
        if (request instanceof TL_stars.transferStarGift || request instanceof TL_stars.checkCanSendGift) return true;
        if (request instanceof TLRPC.TL_payments_canPurchaseStore)
            return isGiftPurpose(((TLRPC.TL_payments_canPurchaseStore) request).purpose);
        if (request instanceof TLRPC.TL_payments_assignPlayMarketTransaction)
            return isGiftPurpose(((TLRPC.TL_payments_assignPlayMarketTransaction) request).purpose);
        TLRPC.InputInvoice invoice = null;
        if (request instanceof TLRPC.TL_payments_getPaymentForm)
            invoice = ((TLRPC.TL_payments_getPaymentForm) request).invoice;
        else if (request instanceof TLRPC.TL_payments_sendPaymentForm)
            invoice = ((TLRPC.TL_payments_sendPaymentForm) request).invoice;
        else if (request instanceof TL_stars.TL_payments_sendStarsForm)
            invoice = ((TL_stars.TL_payments_sendStarsForm) request).invoice;
        if (invoice instanceof TLRPC.TL_inputInvoicePremiumGiftCode)
            return isGiftPurpose(((TLRPC.TL_inputInvoicePremiumGiftCode) invoice).purpose);
        if (invoice instanceof TLRPC.TL_inputInvoiceStars)
            return isGiftPurpose(((TLRPC.TL_inputInvoiceStars) invoice).purpose);
        return invoice instanceof TLRPC.TL_inputInvoiceStarGift
                || invoice instanceof TLRPC.TL_inputInvoiceStarGiftTransfer
                || invoice instanceof TLRPC.TL_inputInvoiceStarGiftResale
                || invoice instanceof TLRPC.TL_inputInvoiceStarGiftAuctionBid
                || invoice instanceof TLRPC.TL_inputInvoicePremiumGiftStars;
    }
}
