package org.telegram.botgram;

import java.util.ArrayList;
import java.util.HashMap;
import com.google.protobuf.ByteString;

import org.telegram.botgram.protocol.v1.BotgramRpc;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

/** Explicit mapping for the original privacy, blacklist and retention screens. */
public final class BotgramProtobufPrivacy {
    private static final HashMap<Class<?>, BotgramRpc.PrivacyKey> keys = new HashMap<>();
    private static final HashMap<Class<?>, BotgramRpc.PrivacyRuleKind> kinds = new HashMap<>();
    static {
        keys.put(TLRPC.TL_inputPrivacyKeyStatusTimestamp.class, BotgramRpc.PrivacyKey.STATUS_TIMESTAMP);
        keys.put(TLRPC.TL_inputPrivacyKeyChatInvite.class, BotgramRpc.PrivacyKey.CHAT_INVITE);
        keys.put(TLRPC.TL_inputPrivacyKeyPhoneCall.class, BotgramRpc.PrivacyKey.PHONE_CALL);
        keys.put(TLRPC.TL_inputPrivacyKeyPhoneP2P.class, BotgramRpc.PrivacyKey.PHONE_P2P);
        keys.put(TLRPC.TL_inputPrivacyKeyProfilePhoto.class, BotgramRpc.PrivacyKey.PROFILE_PHOTO);
        keys.put(TLRPC.TL_inputPrivacyKeyForwards.class, BotgramRpc.PrivacyKey.FORWARDS);
        keys.put(TLRPC.TL_inputPrivacyKeyPhoneNumber.class, BotgramRpc.PrivacyKey.PHONE_NUMBER);
        keys.put(TLRPC.TL_inputPrivacyKeyAddedByPhone.class, BotgramRpc.PrivacyKey.ADDED_BY_PHONE);
        keys.put(TLRPC.TL_inputPrivacyKeyVoiceMessages.class, BotgramRpc.PrivacyKey.VOICE_MESSAGES);
        keys.put(TLRPC.TL_inputPrivacyKeyAbout.class, BotgramRpc.PrivacyKey.ABOUT);
        keys.put(TLRPC.TL_inputPrivacyKeyBirthday.class, BotgramRpc.PrivacyKey.BIRTHDAY);
        keys.put(TLRPC.TL_inputPrivacyKeyStarGiftsAutoSave.class, BotgramRpc.PrivacyKey.STAR_GIFTS_AUTO_SAVE);
        keys.put(TLRPC.TL_inputPrivacyKeySavedMusic.class, BotgramRpc.PrivacyKey.SAVED_MUSIC);
        keys.put(TLRPC.TL_inputPrivacyKeyNoPaidMessages.class, BotgramRpc.PrivacyKey.NO_PAID_MESSAGES);
        kinds.put(TLRPC.TL_inputPrivacyValueAllowAll.class, BotgramRpc.PrivacyRuleKind.ALLOW_ALL);
        kinds.put(TLRPC.TL_inputPrivacyValueDisallowAll.class, BotgramRpc.PrivacyRuleKind.DISALLOW_ALL);
        kinds.put(TLRPC.TL_inputPrivacyValueAllowContacts.class, BotgramRpc.PrivacyRuleKind.ALLOW_CONTACTS);
        kinds.put(TLRPC.TL_inputPrivacyValueDisallowContacts.class, BotgramRpc.PrivacyRuleKind.DISALLOW_CONTACTS);
        kinds.put(TLRPC.TL_inputPrivacyValueAllowUsers.class, BotgramRpc.PrivacyRuleKind.ALLOW_USERS);
        kinds.put(TLRPC.TL_inputPrivacyValueDisallowUsers.class, BotgramRpc.PrivacyRuleKind.DISALLOW_USERS);
        kinds.put(TLRPC.TL_inputPrivacyValueAllowChatParticipants.class, BotgramRpc.PrivacyRuleKind.ALLOW_CHAT_PARTICIPANTS);
        kinds.put(TLRPC.TL_inputPrivacyValueDisallowChatParticipants.class, BotgramRpc.PrivacyRuleKind.DISALLOW_CHAT_PARTICIPANTS);
        kinds.put(TLRPC.TL_inputPrivacyValueAllowPremium.class, BotgramRpc.PrivacyRuleKind.ALLOW_PREMIUM);
        kinds.put(TLRPC.TL_inputPrivacyValueAllowBots.class, BotgramRpc.PrivacyRuleKind.ALLOW_BOTS);
        kinds.put(TLRPC.TL_inputPrivacyValueDisallowBots.class, BotgramRpc.PrivacyRuleKind.DISALLOW_BOTS);
        kinds.put(TLRPC.TL_inputPrivacyValueAllowCloseFriends.class, BotgramRpc.PrivacyRuleKind.ALLOW_CLOSE_FRIENDS);
    }

    private static BotgramApi.ApiError invalid() { return new BotgramApi.ApiError(400, "PRIVACY_RULES_INVALID"); }

    private static BotgramRpc.PrivacyKey key(TLRPC.InputPrivacyKey input) throws BotgramApi.ApiError {
        if (input == null || !keys.containsKey(input.getClass())) throw invalid();
        return keys.get(input.getClass());
    }

    private static BotgramRpc.UserRef user(TLRPC.InputUser input, BotgramProtobufIdentity identities) throws BotgramApi.ApiError {
        if (input instanceof TLRPC.TL_inputUserSelf) {
            TLRPC.TL_inputUser own = new TLRPC.TL_inputUser();
            own.user_id = identities.selfId();
            return identities.user(own).getUser();
        }
        return identities.user(input).getUser();
    }

    private static BotgramRpc.UserRef user(TLRPC.InputPeer input, BotgramProtobufIdentity identities) throws BotgramApi.ApiError {
        BotgramRpc.PeerRef peer = identities.peer(input);
        if (peer.hasUser()) return peer.getUser();
        if (peer.getSelf()) return user(new TLRPC.TL_inputUserSelf(), identities);
        throw invalid();
    }

    public static BotgramRpc.AccountPrivacyRequest encode(TLObject input, BotgramProtobufIdentity identities) throws BotgramApi.ApiError {
        BotgramRpc.AccountPrivacyRequest.Builder out = BotgramRpc.AccountPrivacyRequest.newBuilder();
        if (input instanceof TLRPC.TL_contacts_deleteContacts) {
            BotgramRpc.PrivacyUsers.Builder deleted = BotgramRpc.PrivacyUsers.newBuilder();
            for (TLRPC.InputUser value : ((TLRPC.TL_contacts_deleteContacts) input).id) deleted.addUsers(user(value, identities));
            out.setDeleteContacts(deleted);
        } else if (input instanceof TLRPC.TL_contacts_resetSaved) {
            out.setResetSavedContacts(BotgramRpc.Empty.getDefaultInstance());
        } else if (input instanceof TLRPC.TL_contacts_toggleTopPeers) {
            out.setToggleTopPeers(((TLRPC.TL_contacts_toggleTopPeers) input).enabled);
        } else if (input instanceof TL_account.getWebAuthorizations) {
            out.setGetWebAuthorizations(BotgramRpc.Empty.getDefaultInstance());
        } else if (input instanceof TL_account.resetWebAuthorization) {
            out.setResetWebAuthorization(BotgramRpc.AuthorizationId.newBuilder().setHash(((TL_account.resetWebAuthorization) input).hash));
        } else if (input instanceof TL_account.resetWebAuthorizations) {
            out.setResetWebAuthorizations(BotgramRpc.Empty.getDefaultInstance());
        } else if (input instanceof TLRPC.TL_payments_clearSavedInfo) {
            TLRPC.TL_payments_clearSavedInfo value = (TLRPC.TL_payments_clearSavedInfo) input;
            out.setClearPaymentInfo(BotgramRpc.ClearPaymentInfo.newBuilder().setInfo(value.info).setCredentials(value.credentials));
        } else if (input instanceof TL_account.getPasskeys) {
            out.setGetPasskeys(BotgramRpc.Empty.getDefaultInstance());
        } else if (input instanceof TL_account.getPassword) {
            out.setGetPassword(BotgramRpc.Empty.getDefaultInstance());
        } else if (input instanceof TL_account.getPasswordSettings) {
            out.setGetPasswordSettings(proof(((TL_account.getPasswordSettings) input).password));
        } else if (input instanceof TL_account.updatePasswordSettings) {
            TL_account.updatePasswordSettings value = (TL_account.updatePasswordSettings) input;
            TL_account.passwordInputSettings settings = value.new_settings;
            if (settings == null || (settings.flags & ~7) != 0) throw invalid();
            BotgramRpc.PasswordUpdate.Builder update = BotgramRpc.PasswordUpdate.newBuilder()
                    .setProof(proof(value.password)).setChangePassword((settings.flags & 1) != 0)
                    .setChangeEmail((settings.flags & 2) != 0).setChangeSecureSettings((settings.flags & 4) != 0);
            if ((settings.flags & 1) != 0) {
                update.setNewPasswordHash(ByteString.copyFrom(settings.new_password_hash));
                update.setHint(settings.hint == null ? "" : settings.hint);
                if (settings.new_algo instanceof TLRPC.TL_passwordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow) {
                    TLRPC.TL_passwordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow algo =
                            (TLRPC.TL_passwordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow) settings.new_algo;
                    update.setNewAlgo(BotgramRpc.PasswordKdf.newBuilder().setG(algo.g).setP(ByteString.copyFrom(algo.p))
                            .setSalt1(ByteString.copyFrom(algo.salt1)).setSalt2(ByteString.copyFrom(algo.salt2)));
                } else if (!(settings.new_algo instanceof TLRPC.TL_passwordKdfAlgoUnknown) || settings.new_password_hash.length != 0) {
                    throw invalid();
                }
            }
            if ((settings.flags & 2) != 0) update.setEmail(settings.email == null ? "" : settings.email);
            out.setUpdatePasswordSettings(update);
        } else if (input instanceof TL_account.getPrivacy) {
            out.setGetRules(BotgramRpc.PrivacyKeyRequest.newBuilder().setKey(key(((TL_account.getPrivacy) input).key)));
        } else if (input instanceof TL_account.setPrivacy) {
            TL_account.setPrivacy value = (TL_account.setPrivacy) input;
            BotgramRpc.PrivacyRuleSet.Builder rules = BotgramRpc.PrivacyRuleSet.newBuilder().setKey(key(value.key));
            if (value.rules.size() > 128) throw invalid();
            for (TLRPC.InputPrivacyRule rule : value.rules) {
                if (rule == null || !kinds.containsKey(rule.getClass())) throw invalid();
                BotgramRpc.PrivacyRule.Builder mapped = BotgramRpc.PrivacyRule.newBuilder().setKind(kinds.get(rule.getClass()));
                ArrayList<TLRPC.InputUser> ruleUsers = new ArrayList<>();
                ArrayList<Long> ruleChats = new ArrayList<>();
                if (rule instanceof TLRPC.TL_inputPrivacyValueAllowUsers) ruleUsers = ((TLRPC.TL_inputPrivacyValueAllowUsers) rule).users;
                if (rule instanceof TLRPC.TL_inputPrivacyValueDisallowUsers) ruleUsers = ((TLRPC.TL_inputPrivacyValueDisallowUsers) rule).users;
                if (rule instanceof TLRPC.TL_inputPrivacyValueAllowChatParticipants) ruleChats = ((TLRPC.TL_inputPrivacyValueAllowChatParticipants) rule).chats;
                if (rule instanceof TLRPC.TL_inputPrivacyValueDisallowChatParticipants) ruleChats = ((TLRPC.TL_inputPrivacyValueDisallowChatParticipants) rule).chats;
                if (ruleUsers.size() > 1000 || ruleChats.size() > 100) throw invalid();
                for (TLRPC.InputUser item : ruleUsers) mapped.addUsers(user(item, identities));
                for (Long chat : ruleChats) mapped.addGroupIds(identities.group(chat));
                rules.addRules(mapped);
            }
            out.setSetRules(rules);
        } else if (input instanceof TL_account.getGlobalPrivacySettings) {
            out.setGetGlobal(BotgramRpc.Empty.getDefaultInstance());
        } else if (input instanceof TL_account.setGlobalPrivacySettings) {
            out.setSetGlobal(global(((TL_account.setGlobalPrivacySettings) input).settings));
        } else if (input instanceof TL_account.getAccountTTL) {
            out.setGetAccountTtl(BotgramRpc.Empty.getDefaultInstance());
        } else if (input instanceof TL_account.setAccountTTL) {
            TL_account.setAccountTTL value = (TL_account.setAccountTTL) input;
            if (value.ttl == null) throw invalid();
            out.setSetAccountTtl(BotgramRpc.AccountTtl.newBuilder().setDays(value.ttl.days));
        } else if (input instanceof TLRPC.TL_messages_getDefaultHistoryTTL) {
            out.setGetDefaultHistoryTtl(BotgramRpc.Empty.getDefaultInstance());
        } else if (input instanceof TLRPC.TL_messages_setDefaultHistoryTTL) {
            out.setSetDefaultHistoryTtl(BotgramRpc.HistoryTtl.newBuilder().setSeconds(((TLRPC.TL_messages_setDefaultHistoryTTL) input).period));
        } else if (input instanceof TLRPC.TL_contacts_getBlocked) {
            TLRPC.TL_contacts_getBlocked value = (TLRPC.TL_contacts_getBlocked) input;
            if (value.my_stories_from || value.flags != 0) throw invalid();
            out.setGetBlocked(BotgramRpc.BlockedPageRequest.newBuilder().setOffset(value.offset).setLimit(value.limit));
        } else if (input instanceof TLRPC.TL_contacts_block) {
            TLRPC.TL_contacts_block value = (TLRPC.TL_contacts_block) input;
            if (value.my_stories_from || value.flags != 0) throw invalid();
            out.setBlockUser(user(value.id, identities));
        } else if (input instanceof TLRPC.TL_contacts_unblock) {
            TLRPC.TL_contacts_unblock value = (TLRPC.TL_contacts_unblock) input;
            if (value.my_stories_from || value.flags != 0) throw invalid();
            out.setUnblockUser(user(value.id, identities));
        } else if (input instanceof TL_account.getAuthorizations) {
            out.setGetAuthorizations(BotgramRpc.Empty.getDefaultInstance());
        } else if (input instanceof TL_account.resetAuthorization) {
            out.setResetAuthorization(BotgramRpc.AuthorizationId.newBuilder().setHash(((TL_account.resetAuthorization) input).hash));
        } else if (input instanceof TLRPC.TL_auth_resetAuthorizations) {
            out.setResetOtherAuthorizations(BotgramRpc.Empty.getDefaultInstance());
        } else {
            return null;
        }
        return out.build();
    }

    public static BotgramRpc.SrpProof proof(TLRPC.InputCheckPasswordSRP value) throws BotgramApi.ApiError {
        if (value instanceof TLRPC.TL_inputCheckPasswordEmpty) return BotgramRpc.SrpProof.newBuilder().setEmpty(true).build();
        if (!(value instanceof TLRPC.TL_inputCheckPasswordSRP)) throw invalid();
        TLRPC.TL_inputCheckPasswordSRP srp = (TLRPC.TL_inputCheckPasswordSRP) value;
        if (srp.A == null || srp.M1 == null) throw invalid();
        return BotgramRpc.SrpProof.newBuilder().setSrpId(srp.srp_id)
                .setA(ByteString.copyFrom(srp.A)).setM1(ByteString.copyFrom(srp.M1)).build();
    }

    public static TLRPC.TL_passwordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow algorithm(BotgramRpc.PasswordKdf value) {
        TLRPC.TL_passwordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow result =
                new TLRPC.TL_passwordKdfAlgoSHA256SHA256PBKDF2HMACSHA512iter100000SHA256ModPow();
        result.g = value.getG(); result.p = value.getP().toByteArray();
        result.salt1 = value.getSalt1().toByteArray(); result.salt2 = value.getSalt2().toByteArray();
        return result;
    }

    private static BotgramRpc.GlobalPrivacySettings global(TLRPC.GlobalPrivacySettings value) throws BotgramApi.ApiError {
        if (value == null || (value.flags & ~255) != 0) throw invalid();
        BotgramRpc.GlobalPrivacySettings.Builder out = BotgramRpc.GlobalPrivacySettings.newBuilder()
                .setArchiveAndMuteNewNoncontactPeers(value.archive_and_mute_new_noncontact_peers)
                .setKeepArchivedUnmuted(value.keep_archived_unmuted).setKeepArchivedFolders(value.keep_archived_folders)
                .setHideReadMarks(value.hide_read_marks).setNewNoncontactPeersRequirePremium(value.new_noncontact_peers_require_premium)
                .setDisplayGiftsButton(value.display_gifts_button).setNoncontactPeersPaidStars(value.noncontact_peers_paid_stars);
        if (value.disallowed_stargifts != null) {
            TLRPC.DisallowedGiftsSettings gifts = value.disallowed_stargifts;
            out.setDisallowedGiftsUnlimited(gifts.disallow_unlimited_stargifts).setDisallowedGiftsLimited(gifts.disallow_limited_stargifts)
                    .setDisallowedGiftsUnique(gifts.disallow_unique_stargifts).setDisallowedGiftsPremium(gifts.disallow_premium_gifts)
                    .setDisallowedGiftsFromChannels(gifts.disallow_stargifts_from_channels);
        }
        return out.build();
    }

    public static TLObject decode(BotgramRpc.AccountPrivacyResponse input, BotgramProtobufIdentity identities,
                                 ArrayList<TLRPC.User> users, ArrayList<TLRPC.Chat> groups) throws BotgramApi.ApiError {
        switch (input.getResultCase()) {
            case CONTACTS_DELETED: {
                TLRPC.TL_updates result = new TLRPC.TL_updates();
                result.users = users;
                return result;
            }
            case WEB_AUTHORIZATIONS: return new TL_account.webAuthorizations();
            case PASSKEYS: return new TL_account.Passkeys();
            case PASSWORD: {
                BotgramRpc.PasswordInfo value = input.getPassword();
                TL_account.TL_password result = new TL_account.TL_password();
                result.has_password = value.getHasPassword();
                result.flags = value.getHint().isEmpty() ? 0 : 8;
                result.hint = value.getHint();
                if (result.has_password) {
                    result.current_algo = algorithm(value.getCurrentAlgo());
                    result.srp_B = value.getSrpB().toByteArray(); result.srp_id = value.getSrpId();
                }
                result.new_algo = algorithm(value.getNewAlgo());
                TLRPC.TL_securePasswordKdfAlgoPBKDF2HMACSHA512iter100000 secure = new TLRPC.TL_securePasswordKdfAlgoPBKDF2HMACSHA512iter100000();
                secure.salt = value.getNewSecureSalt().toByteArray();
                result.new_secure_algo = secure; result.secure_random = value.getSecureRandom().toByteArray();
                return result;
            }
            case PASSWORD_SETTINGS: {
                TL_account.passwordSettings result = new TL_account.passwordSettings();
                result.email = input.getPasswordSettings().getEmail();
                result.flags = result.email.isEmpty() ? 0 : 1;
                return result;
            }
            case RULES: {
                TL_account.privacyRules result = new TL_account.privacyRules();
                result.users = users;
                result.chats = groups;
                for (BotgramRpc.PrivacyRule value : input.getRules().getRulesList()) {
                    TLRPC.PrivacyRule rule;
                    switch (value.getKind()) {
                        case ALLOW_ALL: rule = new TLRPC.TL_privacyValueAllowAll(); break;
                        case DISALLOW_ALL: rule = new TLRPC.TL_privacyValueDisallowAll(); break;
                        case ALLOW_CONTACTS: rule = new TLRPC.TL_privacyValueAllowContacts(); break;
                        case DISALLOW_CONTACTS: rule = new TLRPC.TL_privacyValueDisallowContacts(); break;
                        case ALLOW_USERS: rule = new TLRPC.TL_privacyValueAllowUsers(); break;
                        case DISALLOW_USERS: rule = new TLRPC.TL_privacyValueDisallowUsers(); break;
                        case ALLOW_CHAT_PARTICIPANTS: rule = new TLRPC.TL_privacyValueAllowChatParticipants(); break;
                        case DISALLOW_CHAT_PARTICIPANTS: rule = new TLRPC.TL_privacyValueDisallowChatParticipants(); break;
                        case ALLOW_PREMIUM: rule = new TLRPC.TL_privacyValueAllowPremium(); break;
                        case ALLOW_BOTS: rule = new TLRPC.TL_privacyValueAllowBots(); break;
                        case DISALLOW_BOTS: rule = new TLRPC.TL_privacyValueDisallowBots(); break;
                        case ALLOW_CLOSE_FRIENDS: rule = new TLRPC.TL_privacyValueAllowCloseFriends(); break;
                        default: throw invalid();
                    }
                    ArrayList<Long> ruleUsers = new ArrayList<>();
                    ArrayList<Long> ruleChats = new ArrayList<>();
                    if (rule instanceof TLRPC.TL_privacyValueAllowUsers) ruleUsers = ((TLRPC.TL_privacyValueAllowUsers) rule).users;
                    if (rule instanceof TLRPC.TL_privacyValueDisallowUsers) ruleUsers = ((TLRPC.TL_privacyValueDisallowUsers) rule).users;
                    if (rule instanceof TLRPC.TL_privacyValueAllowChatParticipants) ruleChats = ((TLRPC.TL_privacyValueAllowChatParticipants) rule).chats;
                    if (rule instanceof TLRPC.TL_privacyValueDisallowChatParticipants) ruleChats = ((TLRPC.TL_privacyValueDisallowChatParticipants) rule).chats;
                    for (BotgramRpc.UserRef user : value.getUsersList()) ruleUsers.add(identities.userId(user));
                    for (String group : value.getGroupIdsList()) ruleChats.add(identities.groupId(group));
                    result.rules.add(rule);
                }
                return result;
            }
            case GLOBAL: return global(input.getGlobal());
            case ACCOUNT_TTL: {
                TLRPC.TL_accountDaysTTL result = new TLRPC.TL_accountDaysTTL();
                result.days = input.getAccountTtl().getDays();
                return result;
            }
            case HISTORY_TTL: {
                TLRPC.TL_defaultHistoryTTL result = new TLRPC.TL_defaultHistoryTTL();
                result.period = input.getHistoryTtl().getSeconds();
                return result;
            }
            case BLOCKED: {
                TLRPC.TL_contacts_blockedSlice result = new TLRPC.TL_contacts_blockedSlice();
                result.count = input.getBlocked().getCount();
                result.users = users;
                for (BotgramRpc.BlockedUser value : input.getBlocked().getBlockedList()) {
                    TLRPC.TL_peerBlocked blocked = new TLRPC.TL_peerBlocked();
                    TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
                    peer.user_id = identities.userId(value.getUser());
                    blocked.peer_id = peer;
                    blocked.date = seconds(value.getBlockedAtMs());
                    result.blocked.add(blocked);
                }
                return result;
            }
            case AUTHORIZATIONS: {
                TL_account.authorizations result = new TL_account.authorizations();
                result.authorization_ttl_days = input.getAuthorizations().getAuthorizationTtlDays();
                for (BotgramRpc.AuthorizationInfo value : input.getAuthorizations().getSessionsList()) {
                    TLRPC.TL_authorization item = new TLRPC.TL_authorization();
                    item.hash = value.getHash(); item.current = value.getCurrent();
                    item.device_model = value.getDeviceModel(); item.platform = value.getPlatform(); item.system_version = value.getSystemVersion();
                    item.app_name = value.getAppName(); item.app_version = value.getAppVersion();
                    item.date_created = seconds(value.getCreatedAtMs()); item.date_active = seconds(value.getActiveAtMs());
                    item.ip = value.getIp(); item.country = ""; item.region = "";
                    result.authorizations.add(item);
                }
                return result;
            }
            case APPLIED:
                return input.getApplied() ? new TLRPC.TL_boolTrue() : new TLRPC.TL_boolFalse();
            default: throw invalid();
        }
    }

    private static int seconds(long value) throws BotgramApi.ApiError {
        if (value < 0 || value / 1000 > Integer.MAX_VALUE) throw invalid();
        return (int) (value / 1000);
    }

    private static TLRPC.GlobalPrivacySettings global(BotgramRpc.GlobalPrivacySettings value) {
        TLRPC.TL_globalPrivacySettings result = new TLRPC.TL_globalPrivacySettings();
        result.archive_and_mute_new_noncontact_peers = value.getArchiveAndMuteNewNoncontactPeers();
        result.keep_archived_unmuted = value.getKeepArchivedUnmuted(); result.keep_archived_folders = value.getKeepArchivedFolders();
        result.hide_read_marks = value.getHideReadMarks(); result.new_noncontact_peers_require_premium = value.getNewNoncontactPeersRequirePremium();
        result.display_gifts_button = value.getDisplayGiftsButton(); result.noncontact_peers_paid_stars = value.getNoncontactPeersPaidStars();
        result.flags = result.noncontact_peers_paid_stars == 0 ? 0 : 32;
        result.disallowed_stargifts = new TLRPC.DisallowedGiftsSettings();
        result.disallowed_stargifts.disallow_unlimited_stargifts = value.getDisallowedGiftsUnlimited();
        result.disallowed_stargifts.disallow_limited_stargifts = value.getDisallowedGiftsLimited();
        result.disallowed_stargifts.disallow_unique_stargifts = value.getDisallowedGiftsUnique();
        result.disallowed_stargifts.disallow_premium_gifts = value.getDisallowedGiftsPremium();
        result.disallowed_stargifts.disallow_stargifts_from_channels = value.getDisallowedGiftsFromChannels();
        result.flags |= 64;
        return result;
    }
}
