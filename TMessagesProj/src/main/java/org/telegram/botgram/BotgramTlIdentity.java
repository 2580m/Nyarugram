package org.telegram.botgram;

import org.json.JSONObject;
import org.telegram.botgram.protocol.v1.BotgramRpc.AccountProfile;
import org.telegram.tgnet.TLRPC;

/**
 * Builds the original TL user object from the typed Protobuf account profile.
 * The retired /api/telegram/me JSON route used to supply this object; the
 * shape, stable TL numeric id, GID wire value and flags are unchanged.
 */
public final class BotgramTlIdentity {
    private BotgramTlIdentity() { }

    public static TLRPC.User user(AccountProfile profile) throws Exception {
        boolean self = profile.getIsSelf();
        boolean contact = profile.getIsContact();
        boolean mutual = profile.getIsMutualContact();
        boolean premium = profile.hasNyarugramPremium() && profile.getNyarugramPremium();
        int flags = 1 | 2 | 4 | 8 | 16 | 32 | 64
                | (self ? 1024 : 0) | (contact ? 2048 : 0) | (mutual ? 4096 : 0)
                | (premium ? (1 << 28) : 0);

        JSONObject photo = new JSONObject();
        photo.put("$type", "org.telegram.tgnet.TLRPC$TL_userProfilePhotoEmpty");

        JSONObject status = new JSONObject();
        status.put("$type", "org.telegram.tgnet.TLRPC$TL_userStatusOnline");
        status.put("expires", (int) (System.currentTimeMillis() / 1000L) + 60);

        JSONObject value = new JSONObject();
        value.put("$type", "org.telegram.tgnet.TLRPC$TL_user");
        value.put("id", profile.getTlUserId());
        value.put("access_hash", 1L);
        value.put("first_name", profile.getFirstName());
        value.put("last_name", profile.getLastName());
        value.put("username", profile.getUsername());
        value.put("phone", profile.getGid());
        value.put("self", self);
        value.put("contact", contact);
        value.put("mutual_contact", mutual);
        value.put("premium", premium);
        value.put("flags", flags);
        value.put("photo", photo);
        value.put("status", status);
        return (TLRPC.User) BotgramTransport.decode(value, TLRPC.User.class, 0);
    }
}
