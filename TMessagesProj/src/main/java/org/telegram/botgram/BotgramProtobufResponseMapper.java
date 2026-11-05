package org.telegram.botgram;

import org.telegram.botgram.protocol.v1.BotgramRpc;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.tgnet.tl.TL_update;
import org.telegram.tgnet.tl.TL_phone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BotgramProtobufResponseMapper {
    public static final class FullUser extends TLRPC.TL_users_userFull {
        public boolean wallpaperKnown;
    }

    public static final class FullGroup extends TLRPC.TL_messages_chatFull {
        public boolean wallpaperKnown;
    }

    public static void preserveUnknownWallpaper(TLRPC.TL_users_userFull result, TLRPC.UserFull previous) {
        if (result instanceof FullUser && !((FullUser) result).wallpaperKnown && previous != null) {
            result.full_user.wallpaper = previous.wallpaper;
            result.full_user.wallpaper_overridden = previous.wallpaper_overridden;
            result.full_user.flags = (result.full_user.flags & ~16777216) | (previous.flags & 16777216);
        }
    }

    public static void preserveUnknownWallpaper(TLRPC.TL_messages_chatFull result, TLRPC.ChatFull previous) {
        if (result instanceof FullGroup && !((FullGroup) result).wallpaperKnown && previous != null) {
            result.full_chat.wallpaper = previous.wallpaper;
            result.full_chat.flags2 = (result.full_chat.flags2 & ~128) | (previous.flags2 & 128);
        }
    }
    private final BotgramProtobufIdentity identities;

    public BotgramProtobufResponseMapper(BotgramProtobufIdentity identities) {
        this.identities = identities;
    }

    public TLObject map(BotgramRpc.RpcResponse response) throws BotgramApi.ApiError {
        require(response != null, "response");
        require(response.getProtocolMajor() == 1, "protocol_major");
        switch (response.getOutcomeCase()) {
            case ACCOUNT_PRIVACY: {
                BotgramRpc.AccountPrivacyResponse value = response.getAccountPrivacy();
                learn(value.getUsersList(), value.getGroupsList());
                return BotgramProtobufPrivacy.decode(value, identities, users(value.getUsersList()), groups(value.getGroupsList()));
            }
            case GET_WALLPAPERS: {
                BotgramRpc.WallpaperList list = response.getGetWallpapers();
                require(list.hasInstalled() && list.getRevision() >= 0, "wallpapers.state");
                BotgramProtobufWallpaper.state(list.getInstalled());
                if (list.getNotModified()) return new org.telegram.tgnet.tl.TL_account.TL_wallPapersNotModified();
                org.telegram.tgnet.tl.TL_account.TL_wallPapers result = new org.telegram.tgnet.tl.TL_account.TL_wallPapers();
                result.hash = list.getHash();
                for (BotgramRpc.Wallpaper wallpaper : list.getWallpapersList()) result.wallpapers.add(BotgramProtobufWallpaper.decode(wallpaper));
                return result;
            }
            case SAVE_WALLPAPER:
            case INSTALL_WALLPAPER: {
                BotgramRpc.WallpaperMutationResult result = response.getOutcomeCase() == BotgramRpc.RpcResponse.OutcomeCase.SAVE_WALLPAPER
                        ? response.getSaveWallpaper() : response.getInstallWallpaper();
                require(result.getApplied() && result.hasInstalled() && result.getRevision() >= 0, "wallpaper.mutation");
                BotgramProtobufWallpaper.state(result.getInstalled());
                return new TLRPC.TL_boolTrue();
            }
            case SET_CHAT_WALLPAPER: {
                BotgramRpc.ChatWallpaperResult result = response.getSetChatWallpaper();
                require(result.hasUpdates() && result.hasState() && result.getRevision() >= 0, "wallpaper.result");
                BotgramProtobufWallpaper.state(result.getState());
                int changes = 0;
                for (BotgramRpc.Update update : result.getUpdates().getUpdatesList()) {
                    if (update.hasPeerWallpaperChanged()) {
                        changes++;
                        BotgramRpc.PeerWallpaperChanged change = update.getPeerWallpaperChanged();
                        require(change.hasState() && change.getState().equals(result.getState()), "wallpaper.result.state");
                    }
                }
                require(changes == 1, "wallpaper.result.update");
                return updates(result.getUpdates());
            }
            case ERROR: {
                BotgramRpc.RpcError error = response.getError();
                int status = error.getHttpStatus();
                require(status >= 400 && status <= 599, "error.http_status");
                throw new BotgramApi.ApiError(status, error.getCode() + ":" + error.getMessage());
            }
            case GET_STATE: return state(response.getGetState());
            case GET_DIFFERENCE: return difference(response.getGetDifference());
            case GET_DIALOGS: return dialogs(response.getGetDialogs());
            case GET_PINNED_DIALOGS: return peerDialogs(response.getGetPinnedDialogs());
            case GET_PEER_DIALOGS: return peerDialogs(response.getGetPeerDialogs());
            case GET_HISTORY: return messages(response.getGetHistory());
            case GET_MESSAGES: return messages(response.getGetMessages());
            case SEND_MESSAGE: {
                BotgramRpc.MessageSent sent = response.getSendMessage();
                require(sent.hasMessage() && sent.hasMapping() && sent.hasUpdates(), "send_message");
                learn(sent.getUpdates().getUsersList(), sent.getUpdates().getGroupsList());
                // Validate the redundant envelope fields as well as the Updates returned to Telegram.
                message(sent.getMessage());
                mapping(sent.getMapping());
                return updates(sent.getUpdates());
            }
            case READ_HISTORY: {
                TLRPC.TL_messages_affectedMessages result = new TLRPC.TL_messages_affectedMessages();
                result.pts = response.getReadHistory().getPts();
                result.pts_count = response.getReadHistory().getPtsCount();
                return result;
            }
            case SEARCH_CONTACTS: return found(response.getSearchContacts());
            case RESOLVE_USERNAME: return resolved(response.getResolveUsername());
            case RESOLVE_GID: return resolved(response.getResolveGid());
            case GET_USERS: {
                learn(response.getGetUsers().getUsersList(), Collections.emptyList());
                Vector<TLRPC.User> result = new Vector<>(TLRPC.User::TLdeserialize);
                result.objects.addAll(users(response.getGetUsers().getUsersList()));
                return result;
            }
            case ADD_CONTACT: return updates(response.getAddContact());
            case GET_CONTACTS: return contacts(response.getGetContacts());
            case IMPORT_CONTACTS: return imported(response.getImportContacts());
            case CREATE_GROUP: return groupCreated(response.getCreateGroup());
            case GET_PEER_SETTINGS: {
                BotgramRpc.PeerSettingsResult input = response.getGetPeerSettings();
                require(input.hasSettings(), "peer_settings.settings");
                learn(input.getUsersList(), input.getGroupsList());
                TLRPC.TL_messages_peerSettings result = new TLRPC.TL_messages_peerSettings();
                result.settings = new TLRPC.TL_peerSettings();
                result.users = users(input.getUsersList());
                result.chats = groups(input.getGroupsList());
                return result;
            }
            case GET_FULL_USER: return fullUser(response.getGetFullUser());
            case GET_FULL_GROUP: return fullGroup(response.getGetFullGroup());
            case GET_GROUPS: {
                learn(Collections.emptyList(), response.getGetGroups().getGroupsList());
                TLRPC.TL_messages_chats result = new TLRPC.TL_messages_chats();
                result.chats = groups(response.getGetGroups().getGroupsList());
                return result;
            }
            case GET_NOTIFY_SETTINGS: return notifySettings(response.getGetNotifySettings());
            case REQUEST_CALL: return phoneResult(response.getRequestCall());
            case RECEIVED_CALL: return bool(response.getReceivedCall());
            case ACCEPT_CALL: return phoneResult(response.getAcceptCall());
            case CONFIRM_CALL: return phoneResult(response.getConfirmCall());
            case DISCARD_CALL: return updates(response.getDiscardCall());
            case SEND_SIGNALING_DATA: return bool(response.getSendSignalingData());
            case GET_CALL_CONFIG: {
                TLRPC.TL_dataJSON result = new TLRPC.TL_dataJSON();
                result.data = response.getGetCallConfig().getData();
                return result;
            }
            case SAVE_CALL_DEBUG: return bool(response.getSaveCallDebug());
            case GET_DH_CONFIG: return dhConfig(response.getGetDhConfig());
            case OUTCOME_NOT_SET: throw invalid("outcome");
            default: throw unsupported("outcome");
        }
    }

    private void learn(List<BotgramRpc.UserSummary> users, List<BotgramRpc.GroupSummary> groups) throws BotgramApi.ApiError {
        identities.learn(users, groups);
    }

    private static BotgramApi.ApiError invalid(String field) {
        return new BotgramApi.ApiError(400, "BOTGRAM_PROTOBUF_RESPONSE_INVALID:" + field);
    }

    private static BotgramApi.ApiError unsupported(String field) {
        return new BotgramApi.ApiError(501, "BOTGRAM_PROTOBUF_RESPONSE_UNSUPPORTED:" + field);
    }

    private static void require(boolean condition, String field) throws BotgramApi.ApiError {
        if (!condition) throw invalid(field);
    }

    private static int int32(long value, String field) throws BotgramApi.ApiError {
        require(value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE, field);
        return (int) value;
    }

    private static int messageId(long value) throws BotgramApi.ApiError {
        require(value >= 0, "message_id");
        return int32(value, "message_id");
    }

    private static int seconds(long milliseconds) throws BotgramApi.ApiError {
        return int32(Math.floorDiv(milliseconds, 1000L), "timestamp");
    }

    private static TLRPC.Bool bool(BotgramRpc.BoolValue input) {
        return input.getValue() ? new TLRPC.TL_boolTrue() : new TLRPC.TL_boolFalse();
    }

    private static TLRPC.messages_DhConfig dhConfig(BotgramRpc.DhConfig input) {
        if (input.getNotModified()) {
            TLRPC.TL_messages_dhConfigNotModified result = new TLRPC.TL_messages_dhConfigNotModified();
            result.random = input.getRandom().toByteArray();
            return result;
        }
        TLRPC.TL_messages_dhConfig result = new TLRPC.TL_messages_dhConfig();
        result.g = input.getG();
        result.p = input.getP().toByteArray();
        result.version = input.getVersion();
        result.random = input.getRandom().toByteArray();
        return result;
    }

    private TL_phone.TL_phone_phoneCall phoneResult(BotgramRpc.PhoneCallResult input) throws BotgramApi.ApiError {
        require(input.hasPhoneCall(), "phone_call");
        learn(input.getUsersList(), Collections.emptyList());
        TL_phone.TL_phone_phoneCall result = new TL_phone.TL_phone_phoneCall();
        result.phone_call = phoneCall(input.getPhoneCall());
        result.users = users(input.getUsersList());
        return result;
    }

    private TL_phone.PhoneCall phoneCall(BotgramRpc.PhoneCall input) throws BotgramApi.ApiError {
        int video = input.getVideo() ? 64 : 0;
        switch (input.getState()) {
            case PHONE_CALL_STATE_WAITING: {
                TL_phone.TL_phoneCallWaiting result = new TL_phone.TL_phoneCallWaiting();
                base(result, input);
                result.flags = video | (input.getReceiveDateMs() != 0 ? 1 : 0);
                result.receive_date = input.getReceiveDateMs() != 0 ? seconds(input.getReceiveDateMs()) : 0;
                return result;
            }
            case PHONE_CALL_STATE_REQUESTED: {
                TL_phone.phoneCallRequested result = new TL_phone.phoneCallRequested();
                base(result, input);
                result.flags = video;
                result.g_a_hash = input.getGAHash().toByteArray();
                return result;
            }
            case PHONE_CALL_STATE_ACCEPTED: {
                TL_phone.TL_phoneCallAccepted result = new TL_phone.TL_phoneCallAccepted();
                base(result, input);
                result.flags = video;
                result.g_b = input.getGB().toByteArray();
                return result;
            }
            case PHONE_CALL_STATE_ACTIVE: {
                TL_phone.TL_phoneCall result = new TL_phone.TL_phoneCall();
                base(result, input);
                result.p2p_allowed = input.getP2PAllowed();
                result.flags = video | (result.p2p_allowed ? 32 : 0);
                result.g_a_or_b = input.getGAOrB().toByteArray();
                result.key_fingerprint = input.getKeyFingerprint();
                result.start_date = seconds(input.getStartDateMs());
                for (BotgramRpc.PhoneConnection item : input.getConnectionsList()) {
                    result.connections.add(connection(item));
                }
                return result;
            }
            case PHONE_CALL_STATE_DISCARDED: {
                TL_phone.TL_phoneCallDiscarded result = new TL_phone.TL_phoneCallDiscarded();
                result.id = input.getCallId();
                result.video = input.getVideo();
                // Local notification metadata; discarded TL serialization intentionally omits these fields.
                result.admin_id = input.getAdminTlUserId();
                result.participant_id = input.getParticipantTlUserId();
                result.date = seconds(input.getDateMs());
                result.start_date = seconds(input.getStartDateMs());
                result.g_b = input.getGB().toByteArray();
                result.reason = reason(input.getDiscardReason());
                result.duration = input.getDuration();
                result.flags = video | 1 | 2;
                return result;
            }
            default: throw invalid("phone_call.state");
        }
    }

    private void base(TL_phone.PhoneCall result, BotgramRpc.PhoneCall input) throws BotgramApi.ApiError {
        require(input.hasProtocol(), "phone_call.protocol");
        result.id = input.getCallId();
        result.access_hash = input.getAccessHash();
        result.date = seconds(input.getDateMs());
        result.admin_id = input.getAdminTlUserId();
        result.participant_id = input.getParticipantTlUserId();
        result.video = input.getVideo();
        result.protocol = protocol(input.getProtocol());
    }

    private static TL_phone.TL_phoneCallProtocol protocol(BotgramRpc.PhoneCallProtocol input) {
        TL_phone.TL_phoneCallProtocol result = new TL_phone.TL_phoneCallProtocol();
        result.udp_p2p = input.getUdpP2P();
        result.udp_reflector = input.getUdpReflector();
        result.min_layer = input.getMinLayer();
        result.max_layer = input.getMaxLayer();
        result.library_versions = new ArrayList<>(input.getLibraryVersionsList());
        result.flags = (result.udp_p2p ? 1 : 0) | (result.udp_reflector ? 2 : 0);
        return result;
    }

    private static TLRPC.PhoneConnection connection(BotgramRpc.PhoneConnection input) {
        TLRPC.TL_phoneConnectionWebrtc result = new TLRPC.TL_phoneConnectionWebrtc();
        result.id = input.getId();
        result.ip = input.getIp();
        result.ipv6 = input.getIpv6();
        result.port = input.getPort();
        result.turn = input.getTurn();
        result.stun = input.getStun();
        result.username = input.getUsername();
        result.password = input.getPassword();
        result.flags = (result.turn ? 1 : 0) | (result.stun ? 2 : 0);
        return result;
    }

    private static TLRPC.PhoneCallDiscardReason reason(BotgramRpc.PhoneCallDiscardReason input) throws BotgramApi.ApiError {
        switch (input) {
            case PHONE_CALL_DISCARD_REASON_HANGUP: return new TLRPC.TL_phoneCallDiscardReasonHangup();
            case PHONE_CALL_DISCARD_REASON_MISSED: return new TLRPC.TL_phoneCallDiscardReasonMissed();
            case PHONE_CALL_DISCARD_REASON_BUSY: return new TLRPC.TL_phoneCallDiscardReasonBusy();
            case PHONE_CALL_DISCARD_REASON_DISCONNECT: return new TLRPC.TL_phoneCallDiscardReasonDisconnect();
            default: throw invalid("phone_call.discard_reason");
        }
    }

    private TLRPC.Peer userPeer(BotgramRpc.UserRef input) throws BotgramApi.ApiError {
        TLRPC.TL_peerUser result = new TLRPC.TL_peerUser();
        result.user_id = identities.userId(input);
        return result;
    }

    private TLRPC.Peer peer(BotgramRpc.PeerRef input) throws BotgramApi.ApiError {
        switch (input.getPeerCase()) {
            case USER: return userPeer(input.getUser());
            case GROUP_ID: {
                TLRPC.TL_peerChat result = new TLRPC.TL_peerChat();
                result.chat_id = identities.groupId(input.getGroupId());
                return result;
            }
            case SELF: {
                require(input.getSelf(), "peer.self");
                TLRPC.TL_peerUser result = new TLRPC.TL_peerUser();
                result.user_id = identities.selfId();
                return result;
            }
            case PEER_NOT_SET: throw invalid("peer");
            default: throw unsupported("peer");
        }
    }

    private static long dialogId(TLRPC.Peer peer) {
        return peer instanceof TLRPC.TL_peerChat ? -peer.chat_id : peer.user_id;
    }

    private static TLRPC.UserStatus status(BotgramRpc.UserStatus input) throws BotgramApi.ApiError {
        switch (input.getKindCase()) {
            case ONLINE_UNTIL_MS: {
                TLRPC.TL_userStatusOnline result = new TLRPC.TL_userStatusOnline();
                result.expires = seconds(input.getOnlineUntilMs());
                return result;
            }
            case LAST_SEEN_MS: {
                TLRPC.TL_userStatusOffline result = new TLRPC.TL_userStatusOffline();
                result.expires = seconds(input.getLastSeenMs());
                return result;
            }
            case KIND_NOT_SET: return new TLRPC.TL_userStatusEmpty();
            default: throw unsupported("user.status");
        }
    }

    private ArrayList<TLRPC.User> users(List<BotgramRpc.UserSummary> input) throws BotgramApi.ApiError {
        ArrayList<TLRPC.User> result = new ArrayList<>();
        for (BotgramRpc.UserSummary value : input) {
            require(value.hasIdentity(), "user.identity");
            TLRPC.TL_user user = new TLRPC.TL_user();
            user.id = identities.userId(value.getIdentity());
            user.access_hash = 1;
            user.first_name = value.getFirstName();
            user.last_name = value.getLastName();
            user.username = value.getUsername();
            user.phone = value.getGid();
            user.self = value.getIsSelf();
            user.contact = value.getIsContact();
            user.mutual_contact = value.getIsMutualContact();
            user.premium = identities.premium(value);
            user.photo = new TLRPC.TL_userProfilePhotoEmpty();
            user.status = status(value.getStatus());
            user.flags = 127 | (user.self ? 1024 : 0) | (user.contact ? 2048 : 0) | (user.mutual_contact ? 4096 : 0)
                    | (user.premium ? (1 << 28) : 0);
            result.add(user);
        }
        return result;
    }

    private TLRPC.Chat group(BotgramRpc.GroupSummary input) throws BotgramApi.ApiError {
        TLRPC.TL_chat result = new TLRPC.TL_chat();
        result.id = identities.groupId(input.getGroupId());
        result.title = input.getTitle();
        result.photo = new TLRPC.TL_chatPhotoEmpty();
        result.participants_count = input.getParticipantCount();
        result.date = seconds(input.getCreatedAtMs());
        result.version = input.getVersion();
        return result;
    }

    private ArrayList<TLRPC.Chat> groups(List<BotgramRpc.GroupSummary> input) throws BotgramApi.ApiError {
        ArrayList<TLRPC.Chat> result = new ArrayList<>();
        for (BotgramRpc.GroupSummary value : input) result.add(group(value));
        return result;
    }

    private TLRPC.MessageEntity entity(BotgramRpc.MessageEntity input, String text) throws BotgramApi.ApiError {
        TLRPC.MessageEntity result;
        switch (input.getKindCase()) {
            case BOLD: result = new TLRPC.TL_messageEntityBold(); break;
            case ITALIC: result = new TLRPC.TL_messageEntityItalic(); break;
            case PRE:
                result = new TLRPC.TL_messageEntityPre();
                result.language = input.getPre().getLanguage();
                break;
            case TEXT_URL:
                result = new TLRPC.TL_messageEntityTextUrl();
                result.url = input.getTextUrl().getUrl();
                break;
            case UNDERLINE: result = new TLRPC.TL_messageEntityUnderline(); break;
            case STRIKE: result = new TLRPC.TL_messageEntityStrike(); break;
            case SPOILER: result = new TLRPC.TL_messageEntitySpoiler(); break;
            case CODE: result = new TLRPC.TL_messageEntityCode(); break;
            case URL: result = new TLRPC.TL_messageEntityUrl(); break;
            case EMAIL: result = new TLRPC.TL_messageEntityEmail(); break;
            case PHONE: result = new TLRPC.TL_messageEntityPhone(); break;
            case MENTION: result = new TLRPC.TL_messageEntityMention(); break;
            case HASHTAG: result = new TLRPC.TL_messageEntityHashtag(); break;
            case CASHTAG: result = new TLRPC.TL_messageEntityCashtag(); break;
            case BOT_COMMAND: result = new TLRPC.TL_messageEntityBotCommand(); break;
            case KIND_NOT_SET: throw invalid("entity.kind");
            default: throw unsupported("entity.kind");
        }
        result.offset = int32(Integer.toUnsignedLong(input.getOffsetUtf16()), "entity.offset");
        result.length = int32(Integer.toUnsignedLong(input.getLengthUtf16()), "entity.length");
        require(result.length > 0 && (long) result.offset + result.length <= text.length(), "entity.bounds");
        return result;
    }

    private TLRPC.Message message(BotgramRpc.Message input) throws BotgramApi.ApiError {
        require(input.hasPeer() && input.hasSender(), "message.peer_sender");
        TLRPC.Message result;
        switch (input.getBodyCase()) {
            case TEXT: {
                TLRPC.TL_message value = new TLRPC.TL_message();
                BotgramRpc.TextMessage text = input.getText();
                value.message = text.getText();
                value.silent = text.getSilent();
                value.media = new TLRPC.TL_messageMediaEmpty();
                value.flags = 512 | (value.silent ? 8192 : 0);
                for (BotgramRpc.MessageEntity item : text.getEntitiesList()) value.entities.add(entity(item, text.getText()));
                if (!value.entities.isEmpty()) value.flags |= 128;
                if (text.hasReply()) {
                    TLRPC.TL_messageReplyHeader reply = new TLRPC.TL_messageReplyHeader();
                    reply.flags = 16;
                    reply.reply_to_msg_id = messageId(text.getReply().getMessageId());
                    value.reply_to = reply;
                    value.flags |= 8;
                }
                result = value;
                break;
            }
            case SERVICE: {
                TLRPC.TL_messageService value = new TLRPC.TL_messageService();
                switch (input.getService().getActionCase()) {
                    case GROUP_CREATED: {
                        BotgramRpc.GroupCreatedAction action = input.getService().getGroupCreated();
                        TLRPC.TL_messageActionChatCreate create = new TLRPC.TL_messageActionChatCreate();
                        create.title = action.getTitle();
                        for (BotgramRpc.UserRef member : action.getMembersList()) create.users.add(identities.userId(member));
                        value.action = create;
                        break;
                    }
                    case ACTION_NOT_SET: throw invalid("service.action");
                    default: throw unsupported("service.action");
                }
                result = value;
                break;
            }
            case BODY_NOT_SET: throw invalid("message.body");
            default: throw unsupported("message.body");
        }
        result.id = messageId(input.getMessageId());
        result.date = seconds(input.getCreatedAtMs());
        result.out = input.getOutgoing();
        result.unread = input.getUnread();
        result.flags |= 256 | (result.out ? 2 : 0);
        result.from_id = userPeer(input.getSender());
        result.peer_id = peer(input.getPeer());
        result.dialog_id = dialogId(result.peer_id);
        return result;
    }

    private ArrayList<TLRPC.Message> messageList(List<BotgramRpc.Message> input) throws BotgramApi.ApiError {
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        for (BotgramRpc.Message value : input) result.add(message(value));
        return result;
    }

    private TLRPC.TL_peerNotifySettings notifySettings(BotgramRpc.NotifySettings input) throws BotgramApi.ApiError {
        TLRPC.TL_peerNotifySettings result = new TLRPC.TL_peerNotifySettings();
        if (input.hasShowPreviews()) {
            result.flags |= 1;
            result.show_previews = input.getShowPreviews();
        }
        if (input.hasSilent()) {
            result.flags |= 2;
            result.silent = input.getSilent();
        }
        if (input.hasMuteUntilMs()) {
            result.flags |= 4;
            result.mute_until = seconds(input.getMuteUntilMs());
        }
        return result;
    }

    private TLRPC.Dialog dialog(BotgramRpc.Dialog input) throws BotgramApi.ApiError {
        require(input.hasPeer() && input.hasNotifySettings(), "dialog");
        TLRPC.TL_dialog result = new TLRPC.TL_dialog();
        result.peer = peer(input.getPeer());
        result.id = dialogId(result.peer);
        result.top_message = messageId(input.getTopMessageId());
        result.read_inbox_max_id = messageId(input.getReadInboxMaxId());
        result.read_outbox_max_id = messageId(input.getReadOutboxMaxId());
        result.unread_count = input.getUnreadCount();
        result.notify_settings = notifySettings(input.getNotifySettings());
        return result;
    }

    private ArrayList<TLRPC.Dialog> dialogList(List<BotgramRpc.Dialog> input) throws BotgramApi.ApiError {
        ArrayList<TLRPC.Dialog> result = new ArrayList<>();
        for (BotgramRpc.Dialog value : input) result.add(dialog(value));
        return result;
    }

    private TLRPC.TL_updates_state state(BotgramRpc.SyncState input) throws BotgramApi.ApiError {
        TLRPC.TL_updates_state result = new TLRPC.TL_updates_state();
        result.pts = input.getPts();
        result.qts = input.getQts();
        result.date = seconds(input.getDateMs());
        result.seq = input.getSeq();
        result.unread_count = input.getUnreadCount();
        return result;
    }

    private TL_update.TL_updateMessageID mapping(BotgramRpc.MessageIdMapping input) throws BotgramApi.ApiError {
        TL_update.TL_updateMessageID result = new TL_update.TL_updateMessageID();
        result.id = messageId(input.getMessageId());
        result.random_id = input.getRandomId();
        return result;
    }

    private TLRPC.Update update(BotgramRpc.Update input) throws BotgramApi.ApiError {
        switch (input.getKindCase()) {
            case PEER_WALLPAPER_CHANGED: {
                BotgramRpc.PeerWallpaperChanged value = input.getPeerWallpaperChanged();
                require(value.hasPeer() && value.hasState(), "wallpaper.update");
                TL_update.TL_updatePeerWallpaper result = new TL_update.TL_updatePeerWallpaper();
                result.peer = peer(value.getPeer());
                BotgramRpc.WallpaperState state = identities.wallpaper(dialogId(result.peer), value.getState());
                result.wallpaper = BotgramProtobufWallpaper.state(state);
                result.wallpaper_overridden = state.getOverridden();
                result.flags = (result.wallpaper != null ? 1 : 0) | (result.wallpaper_overridden ? 2 : 0);
                return result;
            }
            case NEW_MESSAGE: {
                BotgramRpc.NewMessage value = input.getNewMessage();
                require(value.hasMessage(), "update.message");
                TL_update.TL_updateNewMessage result = new TL_update.TL_updateNewMessage();
                result.message = message(value.getMessage());
                result.pts = value.getPts();
                result.pts_count = value.getPtsCount();
                return result;
            }
            case MESSAGE_ID: return mapping(input.getMessageId());
            case READ_INBOX: {
                BotgramRpc.ReadReceipt value = input.getReadInbox();
                require(value.hasPeer(), "read_inbox.peer");
                TL_update.TL_updateReadHistoryInbox result = new TL_update.TL_updateReadHistoryInbox();
                result.peer = peer(value.getPeer());
                result.max_id = messageId(value.getMaxMessageId());
                result.pts = value.getPts();
                result.pts_count = value.getPtsCount();
                return result;
            }
            case READ_OUTBOX: {
                BotgramRpc.ReadReceipt value = input.getReadOutbox();
                require(value.hasPeer(), "read_outbox.peer");
                TL_update.TL_updateReadHistoryOutbox result = new TL_update.TL_updateReadHistoryOutbox();
                result.peer = peer(value.getPeer());
                result.max_id = messageId(value.getMaxMessageId());
                result.pts = value.getPts();
                result.pts_count = value.getPtsCount();
                return result;
            }
            case PRESENCE_CHANGED: {
                BotgramRpc.PresenceChanged value = input.getPresenceChanged();
                require(value.hasUser() && value.hasStatus(), "presence_changed");
                TL_update.TL_updateUserStatus result = new TL_update.TL_updateUserStatus();
                result.user_id = identities.userId(value.getUser());
                result.status = status(value.getStatus());
                return result;
            }
            case GID_CHANGED: {
                require(input.getGidChanged().hasUser(), "gid_changed.user");
                TL_update.TL_updateUserPhone result = new TL_update.TL_updateUserPhone();
                result.user_id = identities.userId(input.getGidChanged().getUser());
                result.phone = input.getGidChanged().getGid();
                return result;
            }
            case PHONE_CALL: {
                require(input.getPhoneCall().hasPhoneCall(), "phone_call.update");
                TL_update.TL_updatePhoneCall result = new TL_update.TL_updatePhoneCall();
                result.phone_call = phoneCall(input.getPhoneCall().getPhoneCall());
                return result;
            }
            case PHONE_CALL_SIGNALING: {
                TL_update.TL_updatePhoneCallSignalingData result = new TL_update.TL_updatePhoneCallSignalingData();
                result.phone_call_id = input.getPhoneCallSignaling().getCallId();
                result.data = input.getPhoneCallSignaling().getData().toByteArray();
                return result;
            }
            case KIND_NOT_SET: throw invalid("update.kind");
            default: throw unsupported("update.kind");
        }
    }

    private ArrayList<TLRPC.Update> updateList(List<BotgramRpc.Update> input) throws BotgramApi.ApiError {
        ArrayList<TLRPC.Update> result = new ArrayList<>();
        for (BotgramRpc.Update value : input) result.add(update(value));
        return result;
    }

    private TLRPC.TL_updates updates(BotgramRpc.Updates input) throws BotgramApi.ApiError {
        learn(input.getUsersList(), input.getGroupsList());
        TLRPC.TL_updates result = new TLRPC.TL_updates();
        result.users = users(input.getUsersList());
        result.chats = groups(input.getGroupsList());
        result.updates = updateList(input.getUpdatesList());
        result.date = seconds(input.getDateMs());
        result.seq = input.getSeq();
        return result;
    }

    private TLRPC.updates_Difference difference(BotgramRpc.Difference input) throws BotgramApi.ApiError {
        switch (input.getKindCase()) {
            case EMPTY: {
                TLRPC.TL_updates_differenceEmpty result = new TLRPC.TL_updates_differenceEmpty();
                result.date = seconds(input.getEmpty().getDateMs());
                result.seq = input.getEmpty().getSeq();
                return result;
            }
            case TOO_LONG: {
                TLRPC.TL_updates_differenceTooLong result = new TLRPC.TL_updates_differenceTooLong();
                result.pts = input.getTooLong().getPts();
                return result;
            }
            case COMPLETE:
            case SLICE: {
                boolean slice = input.getKindCase() == BotgramRpc.Difference.KindCase.SLICE;
                BotgramRpc.DifferencePage page = slice ? input.getSlice() : input.getComplete();
                require(page.hasState(), "difference.state");
                learn(page.getUsersList(), page.getGroupsList());
                TLRPC.updates_Difference result = slice ? new TLRPC.TL_updates_differenceSlice() : new TLRPC.TL_updates_difference();
                result.users = users(page.getUsersList());
                result.chats = groups(page.getGroupsList());
                result.new_messages = messageList(page.getMessagesList());
                result.other_updates = updateList(page.getUpdatesList());
                if (slice) result.intermediate_state = state(page.getState());
                else result.state = state(page.getState());
                return result;
            }
            case KIND_NOT_SET: throw invalid("difference.kind");
            default: throw unsupported("difference.kind");
        }
    }

    private TLRPC.messages_Dialogs dialogs(BotgramRpc.DialogPage input) throws BotgramApi.ApiError {
        learn(input.getUsersList(), input.getGroupsList());
        TLRPC.messages_Dialogs result = input.getIsSlice() ? new TLRPC.TL_messages_dialogsSlice() : new TLRPC.TL_messages_dialogs();
        result.count = input.getTotalCount();
        result.users = users(input.getUsersList());
        result.chats = groups(input.getGroupsList());
        result.dialogs = dialogList(input.getDialogsList());
        result.messages = messageList(input.getMessagesList());
        return result;
    }

    private TLRPC.TL_messages_peerDialogs peerDialogs(BotgramRpc.PeerDialogs input) throws BotgramApi.ApiError {
        require(input.hasState(), "peer_dialogs.state");
        learn(input.getUsersList(), input.getGroupsList());
        TLRPC.TL_messages_peerDialogs result = new TLRPC.TL_messages_peerDialogs();
        result.users = users(input.getUsersList());
        result.chats = groups(input.getGroupsList());
        result.dialogs = dialogList(input.getDialogsList());
        result.messages = messageList(input.getMessagesList());
        result.state = state(input.getState());
        return result;
    }

    private TLRPC.TL_messages_messages messages(BotgramRpc.MessagePage input) throws BotgramApi.ApiError {
        learn(input.getUsersList(), input.getGroupsList());
        TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
        result.users = users(input.getUsersList());
        result.chats = groups(input.getGroupsList());
        result.messages = messageList(input.getMessagesList());
        return result;
    }

    private TLRPC.TL_contacts_found found(BotgramRpc.FoundPeers input) throws BotgramApi.ApiError {
        learn(input.getUsersList(), input.getGroupsList());
        TLRPC.TL_contacts_found result = new TLRPC.TL_contacts_found();
        result.users = users(input.getUsersList());
        result.chats = groups(input.getGroupsList());
        for (BotgramRpc.PeerRef value : input.getResultsList()) result.results.add(peer(value));
        for (BotgramRpc.PeerRef value : input.getMyResultsList()) result.my_results.add(peer(value));
        return result;
    }

    private TLRPC.TL_contacts_resolvedPeer resolved(BotgramRpc.ResolvedPeer input) throws BotgramApi.ApiError {
        require(input.hasPeer(), "resolved.peer");
        learn(input.getUsersList(), input.getGroupsList());
        TLRPC.TL_contacts_resolvedPeer result = new TLRPC.TL_contacts_resolvedPeer();
        result.users = users(input.getUsersList());
        result.chats = groups(input.getGroupsList());
        result.peer = peer(input.getPeer());
        return result;
    }

    private TLRPC.TL_contacts_contacts contacts(BotgramRpc.ContactList input) throws BotgramApi.ApiError {
        learn(input.getUsersList(), Collections.emptyList());
        TLRPC.TL_contacts_contacts result = new TLRPC.TL_contacts_contacts();
        result.users = users(input.getUsersList());
        result.saved_count = input.getSavedCount();
        for (BotgramRpc.Contact value : input.getContactsList()) {
            require(value.hasUser(), "contact.user");
            TLRPC.TL_contact contact = new TLRPC.TL_contact();
            contact.user_id = identities.userId(value.getUser());
            contact.mutual = value.getMutual();
            result.contacts.add(contact);
        }
        return result;
    }

    private TLRPC.TL_contacts_importedContacts imported(BotgramRpc.ImportedContacts input) throws BotgramApi.ApiError {
        learn(input.getUsersList(), Collections.emptyList());
        TLRPC.TL_contacts_importedContacts result = new TLRPC.TL_contacts_importedContacts();
        result.users = users(input.getUsersList());
        for (BotgramRpc.ImportedContact value : input.getImportedList()) {
            require(value.hasUser(), "imported.user");
            TLRPC.TL_importedContact contact = new TLRPC.TL_importedContact();
            contact.user_id = identities.userId(value.getUser());
            contact.client_id = value.getClientId();
            result.imported.add(contact);
        }
        result.retry_contacts.addAll(input.getRetryContactsList());
        for (BotgramRpc.PopularInvite value : input.getPopularInvitesList()) {
            TLRPC.TL_popularContact contact = new TLRPC.TL_popularContact();
            contact.client_id = value.getClientId();
            contact.importers = value.getImporters();
            result.popular_invites.add(contact);
        }
        return result;
    }

    private TLRPC.TL_messages_invitedUsers groupCreated(BotgramRpc.GroupCreated input) throws BotgramApi.ApiError {
        require(input.hasGroup() && input.hasUpdates(), "group_created");
        ArrayList<BotgramRpc.GroupSummary> summaries = new ArrayList<>(input.getUpdates().getGroupsList());
        summaries.add(input.getGroup());
        learn(input.getUpdates().getUsersList(), summaries);
        TLRPC.TL_messages_invitedUsers result = new TLRPC.TL_messages_invitedUsers();
        TLRPC.TL_updates values = updates(input.getUpdates());
        long groupId = identities.groupId(input.getGroup().getGroupId());
        boolean included = false;
        for (TLRPC.Chat chat : values.chats) if (chat.id == groupId) included = true;
        if (!included) values.chats.add(group(input.getGroup()));
        result.updates = values;
        for (BotgramRpc.MissingInvitee value : input.getMissingInviteesList()) {
            require(value.hasUser(), "missing_invitee.user");
            TLRPC.TL_missingInvitee invitee = new TLRPC.TL_missingInvitee();
            invitee.user_id = identities.userId(value.getUser());
            invitee.premium_would_allow_invite = value.getPremiumWouldAllowInvite();
            invitee.premium_required_for_pm = value.getPremiumRequiredForPm();
            invitee.flags = (invitee.premium_would_allow_invite ? 1 : 0) | (invitee.premium_required_for_pm ? 2 : 0);
            result.missing_invitees.add(invitee);
        }
        return result;
    }

    private TLRPC.TL_users_userFull fullUser(BotgramRpc.UserFullResult input) throws BotgramApi.ApiError {
        require(input.hasUser() && input.hasSettings() && input.hasNotifySettings(), "full_user");
        learn(input.getUsersList(), input.getGroupsList());
        FullUser result = new FullUser();
        result.users = users(input.getUsersList());
        result.chats = groups(input.getGroupsList());
        TLRPC.TL_userFull full = new TLRPC.TL_userFull();
        full.id = identities.userId(input.getUser());
        full.settings = new TLRPC.TL_peerSettings();
        full.notify_settings = notifySettings(input.getNotifySettings());
        full.about = input.getAbout();
        full.common_chats_count = input.getCommonGroupsCount();
        full.phone_calls_available = input.getPhoneCallsAvailable();
        full.video_calls_available = input.getVideoCallsAvailable();
        full.phone_calls_private = input.getPhoneCallsPrivate();
        full.flags = (!full.about.isEmpty() ? 2 : 0) | (full.phone_calls_available ? 16 : 0)
                | (full.video_calls_available ? 8192 : 0) | (full.phone_calls_private ? 32 : 0);
        BotgramRpc.WallpaperState wallpaper = identities.wallpaper(full.id, input.hasWallpaper() ? input.getWallpaper() : null);
        result.wallpaperKnown = wallpaper != null;
        if (wallpaper != null) {
            full.wallpaper = BotgramProtobufWallpaper.state(wallpaper);
            full.wallpaper_overridden = wallpaper.getOverridden();
            if (full.wallpaper != null) full.flags |= 16777216;
        }
        result.full_user = full;
        return result;
    }

    private TLRPC.TL_messages_chatFull fullGroup(BotgramRpc.GroupFullResult input) throws BotgramApi.ApiError {
        require(input.hasGroup() && input.hasNotifySettings(), "full_group");
        ArrayList<BotgramRpc.GroupSummary> summaries = new ArrayList<>(input.getGroupsList());
        summaries.add(input.getGroup());
        learn(input.getUsersList(), summaries);
        FullGroup result = new FullGroup();
        result.users = users(input.getUsersList());
        result.chats = groups(input.getGroupsList());
        long groupId = identities.groupId(input.getGroup().getGroupId());
        boolean included = false;
        for (TLRPC.Chat chat : result.chats) if (chat.id == groupId) included = true;
        if (!included) result.chats.add(group(input.getGroup()));
        TLRPC.TL_chatFull full = new TLRPC.TL_chatFull();
        full.id = groupId;
        full.about = input.getAbout();
        full.chat_photo = new TLRPC.TL_photoEmpty();
        full.flags = 4;
        full.notify_settings = notifySettings(input.getNotifySettings());
        TLRPC.TL_chatParticipants participants = new TLRPC.TL_chatParticipants();
        participants.chat_id = groupId;
        participants.version = input.getParticipantsVersion();
        for (BotgramRpc.Participant value : input.getParticipantsList()) {
            require(value.hasUser() && value.hasInviter(), "participant");
            TLRPC.TL_chatParticipant participant = new TLRPC.TL_chatParticipant();
            participant.user_id = identities.userId(value.getUser());
            participant.inviter_id = identities.userId(value.getInviter());
            participant.date = seconds(value.getJoinedAtMs());
            participants.participants.add(participant);
        }
        full.participants = participants;
        BotgramRpc.WallpaperState wallpaper = identities.wallpaper(-full.id, input.hasWallpaper() ? input.getWallpaper() : null);
        result.wallpaperKnown = wallpaper != null;
        if (wallpaper != null) {
            full.wallpaper = BotgramProtobufWallpaper.state(wallpaper);
            if (full.wallpaper != null) full.flags2 |= 128;
        }
        result.full_chat = full;
        return result;
    }
}
