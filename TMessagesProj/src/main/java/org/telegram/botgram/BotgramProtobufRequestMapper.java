package org.telegram.botgram;

import com.google.protobuf.ByteString;
import org.telegram.botgram.protocol.v1.BotgramRpc;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_phone;

import java.util.Collection;

/** Explicit v1 request mapping. Call only on the requests background executor. */
public final class BotgramProtobufRequestMapper {
    private final BotgramProtobufIdentity identities;

    public BotgramProtobufRequestMapper(BotgramProtobufIdentity identities) {
        this.identities = identities;
    }

    public BotgramRpc.RpcRequest map(TLObject input, String requestId) throws BotgramApi.ApiError {
        if (input == null || requestId == null) throw invalid("BOTGRAM_REQUEST_INVALID");
        BotgramRpc.RpcRequest.Builder out = BotgramRpc.RpcRequest.newBuilder()
                .setProtocolMajor(1).setRequestId(requestId);
        Class<?> type = input.getClass();
        BotgramRpc.AccountPrivacyRequest privacy = BotgramProtobufPrivacy.encode(input, identities);
        if (privacy != null) {
            out.setAccountPrivacy(privacy);
        } else if (type == TL_account.getWallPapers.class) {
            out.setGetWallpapers(BotgramRpc.GetWallpapersRequest.newBuilder().setHash(((TL_account.getWallPapers) input).hash));
        } else if (type == TL_account.saveWallPaper.class) {
            TL_account.saveWallPaper value = (TL_account.saveWallPaper) input;
            out.setSaveWallpaper(BotgramRpc.SaveWallpaperRequest.newBuilder()
                    .setWallpaper(BotgramProtobufWallpaper.encode(value.wallpaper, value.settings)).setUnsave(value.unsave));
        } else if (type == TL_account.installWallPaper.class) {
            TL_account.installWallPaper value = (TL_account.installWallPaper) input;
            out.setInstallWallpaper(BotgramRpc.InstallWallpaperRequest.newBuilder()
                    .setWallpaper(BotgramProtobufWallpaper.encode(value.wallpaper, value.settings)));
        } else if (type == TLRPC.TL_messages_setChatWallPaper.class) {
            TLRPC.TL_messages_setChatWallPaper value = (TLRPC.TL_messages_setChatWallPaper) input;
            reject((value.flags & ~31) != 0 || (value.flags & 2) != 0, "wallpaper service message");
            BotgramRpc.SetChatWallpaperRequest.Builder query = BotgramRpc.SetChatWallpaperRequest.newBuilder()
                    .setPeer(peer(value.peer)).setForBoth(value.for_both).setRevert(value.revert);
            if (value.revert) {
                if ((value.flags & 7) != 0 || value.for_both) throw invalid("WALLPAPER_REVERT_INVALID");
            } else if ((value.flags & 1) != 0) {
                if ((value.flags & 4) == 0) throw invalid("WALLPAPER_SETTINGS_INVALID");
                query.setWallpaper(BotgramProtobufWallpaper.encode(value.wallpaper, value.settings));
            } else {
                if ((value.flags & 4) != 0) throw invalid("WALLPAPER_SETTINGS_INVALID");
                query.setClear(BotgramRpc.Empty.getDefaultInstance());
            }
            out.setSetChatWallpaper(query);
        } else if (type == TLRPC.TL_updates_getState.class) {
            out.setGetState(BotgramRpc.GetStateRequest.getDefaultInstance());
        } else if (type == TLRPC.TL_updates_getDifference.class) {
            TLRPC.TL_updates_getDifference value = (TLRPC.TL_updates_getDifference) input;
            reject(value.pts_total_limit != 0, "pts_total_limit");
            out.setGetDifference(BotgramRpc.GetDifferenceRequest.newBuilder().setPts(value.pts));
        } else if (type == TLRPC.TL_messages_getDialogs.class) {
            TLRPC.TL_messages_getDialogs value = (TLRPC.TL_messages_getDialogs) input;
            // v1 has no pinned dialogs, so excluding them does not change the result.
            BotgramRpc.GetDialogsRequest.Builder request = BotgramRpc.GetDialogsRequest.newBuilder()
                    .setLimit(value.limit).setOffsetMessageId(value.offset_id).setFolderId(value.folder_id);
            if (value.offset_peer == null) throw invalid("PEER_ID_INVALID");
            if (value.offset_peer.getClass() == TLRPC.TL_inputPeerEmpty.class) {
                validatePeerFields(value.offset_peer);
            } else {
                request.setOffsetPeer(peer(value.offset_peer));
            }
            out.setGetDialogs(request);
        } else if (type == TLRPC.TL_messages_getPinnedDialogs.class) {
            out.setGetPinnedDialogs(BotgramRpc.GetPinnedDialogsRequest.getDefaultInstance());
        } else if (type == TLRPC.TL_messages_getPeerDialogs.class) {
            TLRPC.TL_messages_getPeerDialogs value = (TLRPC.TL_messages_getPeerDialogs) input;
            BotgramRpc.GetPeerDialogsRequest.Builder request = BotgramRpc.GetPeerDialogsRequest.newBuilder();
            if (value.peers != null) {
                for (TLRPC.InputDialogPeer item : value.peers) {
                    if (item == null) throw invalid("PEER_ID_INVALID");
                    if (item.getClass() != TLRPC.TL_inputDialogPeer.class) throw unsupported("InputDialogPeer");
                    request.addPeers(peer(((TLRPC.TL_inputDialogPeer) item).peer));
                }
            }
            out.setGetPeerDialogs(request);
        } else if (type == TLRPC.TL_messages_getHistory.class) {
            TLRPC.TL_messages_getHistory value = (TLRPC.TL_messages_getHistory) input;
            out.setGetHistory(BotgramRpc.GetHistoryRequest.newBuilder().setPeer(peer(value.peer))
                    .setOffsetMessageId(value.offset_id).setOffsetDateMs(milliseconds(value.offset_date))
                    .setAddOffset(value.add_offset).setLimit(value.limit)
                    .setMaxMessageId(value.max_id).setMinMessageId(value.min_id));
        } else if (type == TLRPC.TL_messages_getMessages.class) {
            TLRPC.TL_messages_getMessages value = (TLRPC.TL_messages_getMessages) input;
            BotgramRpc.GetMessagesRequest.Builder request = BotgramRpc.GetMessagesRequest.newBuilder();
            if (value.id != null) {
                for (Integer id : value.id) {
                    if (id == null) throw invalid("MESSAGE_ID_INVALID");
                    request.addMessageIds(id.longValue());
                }
            }
            out.setGetMessages(request);
        } else if (type == TLRPC.TL_messages_sendMessage.class) {
            out.setSendMessage(send((TLRPC.TL_messages_sendMessage) input));
        } else if (type == TLRPC.TL_messages_readHistory.class) {
            TLRPC.TL_messages_readHistory value = (TLRPC.TL_messages_readHistory) input;
            out.setReadHistory(BotgramRpc.ReadHistoryRequest.newBuilder()
                    .setPeer(peer(value.peer)).setMaxMessageId(value.max_id));
        } else if (type == TLRPC.TL_contacts_search.class) {
            TLRPC.TL_contacts_search value = (TLRPC.TL_contacts_search) input;
            reject(value.broadcasts || value.bots, "search filters");
            out.setSearchContacts(BotgramRpc.SearchContactsRequest.newBuilder().setQuery(text(value.q)));
        } else if (type == TLRPC.TL_contacts_resolveUsername.class) {
            TLRPC.TL_contacts_resolveUsername value = (TLRPC.TL_contacts_resolveUsername) input;
            reject(nonempty(value.referer), "referer");
            out.setResolveUsername(BotgramRpc.ResolveUsernameRequest.newBuilder().setUsername(text(value.username)));
        } else if (type == TLRPC.TL_users_getUsers.class) {
            TLRPC.TL_users_getUsers value = (TLRPC.TL_users_getUsers) input;
            BotgramRpc.GetUsersRequest.Builder request = BotgramRpc.GetUsersRequest.newBuilder();
            if (value.id != null) for (TLRPC.InputUser item : value.id) request.addUsers(user(item));
            out.setGetUsers(request);
        } else if (type == TLRPC.TL_contacts_addContact.class) {
            TLRPC.TL_contacts_addContact value = (TLRPC.TL_contacts_addContact) input;
            // An empty note object is what the original editor produces when the note is untouched;
            // it must mean the same as no note at all. Only real content is rejected.
            reject(noteContent(value.note), "contact note");
            if ((value.flags & 2) != 0 && value.note == null) throw invalid("CONTACT_NOTE_INVALID");
            reject((value.flags & ~3) != 0, "contact flags");
            reject(value.add_phone_privacy_exception, "contact phone privacy exception");
            out.setAddContact(BotgramRpc.AddContactRequest.newBuilder().setUser(user(value.id))
                    .setFirstName(text(value.first_name)).setLastName(text(value.last_name)));
        } else if (type == TLRPC.TL_contacts_getContacts.class) {
            out.setGetContacts(BotgramRpc.GetContactsRequest.getDefaultInstance());
        } else if (type == TLRPC.TL_contacts_resolvePhone.class) {
            TLRPC.TL_contacts_resolvePhone value = (TLRPC.TL_contacts_resolvePhone) input;
            out.setResolveGid(BotgramRpc.ResolveGidRequest.newBuilder().setGid(text(value.phone)));
        } else if (type == TLRPC.TL_contacts_importContacts.class) {
            TLRPC.TL_contacts_importContacts value = (TLRPC.TL_contacts_importContacts) input;
            BotgramRpc.ImportContactsRequest.Builder request = BotgramRpc.ImportContactsRequest.newBuilder();
            if (value.contacts != null) {
                for (TLRPC.TL_inputPhoneContact item : value.contacts) {
                    if (item == null) throw invalid("CONTACT_INVALID");
                    if (item.getClass() != TLRPC.TL_inputPhoneContact.class) throw unsupported("TL_inputPhoneContact");
                    reject(noteContent(item.note), "contact note");
                    if ((item.flags & 1) != 0 && item.note == null) throw invalid("CONTACT_NOTE_INVALID");
                    reject((item.flags & ~1) != 0, "contact flags");
                    request.addContacts(BotgramRpc.ImportContact.newBuilder().setClientId(item.client_id)
                            .setGid(text(item.phone)).setFirstName(text(item.first_name)).setLastName(text(item.last_name)));
                }
            }
            out.setImportContacts(request);
        } else if (type == TLRPC.TL_messages_createChat.class) {
            TLRPC.TL_messages_createChat value = (TLRPC.TL_messages_createChat) input;
            reject(value.ttl_period != 0, "ttl_period");
            BotgramRpc.CreateGroupRequest.Builder request = BotgramRpc.CreateGroupRequest.newBuilder().setTitle(text(value.title));
            if (value.users != null) for (TLRPC.InputUser item : value.users) request.addUsers(user(item));
            out.setCreateGroup(request);
        } else if (type == TLRPC.TL_messages_getPeerSettings.class) {
            out.setGetPeerSettings(BotgramRpc.GetPeerSettingsRequest.newBuilder()
                    .setPeer(peer(((TLRPC.TL_messages_getPeerSettings) input).peer)));
        } else if (type == TLRPC.TL_users_getFullUser.class) {
            out.setGetFullUser(BotgramRpc.GetFullUserRequest.newBuilder()
                    .setUser(user(((TLRPC.TL_users_getFullUser) input).id)));
        } else if (type == TLRPC.TL_messages_getFullChat.class) {
            out.setGetFullGroup(BotgramRpc.GetFullGroupRequest.newBuilder()
                    .setGroupId(identities.group(((TLRPC.TL_messages_getFullChat) input).chat_id)));
        } else if (type == TLRPC.TL_messages_getChats.class) {
            TLRPC.TL_messages_getChats value = (TLRPC.TL_messages_getChats) input;
            BotgramRpc.GetGroupsRequest.Builder request = BotgramRpc.GetGroupsRequest.newBuilder();
            if (value.id != null) {
                for (Long id : value.id) {
                    if (id == null) throw invalid("CHAT_ID_INVALID");
                    request.addGroupIds(identities.group(id));
                }
            }
            out.setGetGroups(request);
        } else if (type == TL_account.getNotifySettings.class) {
            out.setGetNotifySettings(BotgramRpc.GetNotifySettingsRequest.newBuilder()
                    .setTarget(notifyTarget(((TL_account.getNotifySettings) input).peer)));
        } else if (type == TL_phone.requestCall.class) {
            TL_phone.requestCall value = (TL_phone.requestCall) input;
            reject((value.flags & ~2) != 0, "requestCall options");
            if (value.user_id == null || value.protocol == null) throw invalid("CALL_INVALID");
            out.setRequestCall(BotgramRpc.PhoneRequestCallRequest.newBuilder()
                    .setUser(user(value.user_id)).setRandomId(value.random_id).setVideo(value.video)
                    .setGAHash(bytes(value.g_a_hash)).setProtocol(protocol(value.protocol)));
        } else if (type == TL_phone.receivedCall.class) {
            out.setReceivedCall(BotgramRpc.PhoneReceivedCallRequest.newBuilder()
                    .setPeer(call(((TL_phone.receivedCall) input).peer)));
        } else if (type == TL_phone.acceptCall.class) {
            TL_phone.acceptCall value = (TL_phone.acceptCall) input;
            if (value.protocol == null) throw invalid("CALL_INVALID");
            out.setAcceptCall(BotgramRpc.PhoneAcceptCallRequest.newBuilder().setPeer(call(value.peer))
                    .setGB(bytes(value.g_b)).setProtocol(protocol(value.protocol)));
        } else if (type == TL_phone.confirmCall.class) {
            TL_phone.confirmCall value = (TL_phone.confirmCall) input;
            if (value.protocol == null) throw invalid("CALL_INVALID");
            out.setConfirmCall(BotgramRpc.PhoneConfirmCallRequest.newBuilder().setPeer(call(value.peer))
                    .setGA(bytes(value.g_a)).setKeyFingerprint(value.key_fingerprint)
                    .setProtocol(protocol(value.protocol)));
        } else if (type == TL_phone.discardCall.class) {
            TL_phone.discardCall value = (TL_phone.discardCall) input;
            reject((value.flags & ~2) != 0, "discardCall options");
            out.setDiscardCall(BotgramRpc.PhoneDiscardCallRequest.newBuilder().setPeer(call(value.peer))
                    .setDuration(value.duration).setReason(reason(value.reason))
                    .setConnectionId(value.connection_id).setVideo(value.video));
        } else if (type == TL_phone.sendSignalingData.class) {
            TL_phone.sendSignalingData value = (TL_phone.sendSignalingData) input;
            out.setSendSignalingData(BotgramRpc.PhoneSendSignalingDataRequest.newBuilder()
                    .setPeer(call(value.peer)).setData(bytes(value.data)));
        } else if (type == TL_phone.getCallConfig.class) {
            out.setGetCallConfig(BotgramRpc.PhoneGetCallConfigRequest.getDefaultInstance());
        } else if (type == TL_phone.saveCallDebug.class) {
            TL_phone.saveCallDebug value = (TL_phone.saveCallDebug) input;
            if (value.debug == null) throw invalid("CALL_INVALID");
            out.setSaveCallDebug(BotgramRpc.PhoneSaveCallDebugRequest.newBuilder()
                    .setPeer(call(value.peer)).setDebug(text(value.debug.data)));
        } else if (type == TLRPC.TL_messages_getDhConfig.class) {
            TLRPC.TL_messages_getDhConfig value = (TLRPC.TL_messages_getDhConfig) input;
            out.setGetDhConfig(BotgramRpc.GetDhConfigRequest.newBuilder()
                    .setVersion(value.version).setRandomLength(value.random_length));
        } else if (type == TLRPC.TL_messages_deleteMessages.class) {
            TLRPC.TL_messages_deleteMessages value = (TLRPC.TL_messages_deleteMessages) input;
            BotgramRpc.DeleteMessagesRequest.Builder request = BotgramRpc.DeleteMessagesRequest.newBuilder()
                    .setRevoke(value.revoke);
            if (value.id != null) for (Integer id : value.id) {
                if (id == null) throw invalid("MESSAGE_ID_INVALID");
                request.addMessageIds(id.longValue());
            }
            out.setDeleteMessages(request);
        } else if (type == TLRPC.TL_messages_deleteHistory.class) {
            TLRPC.TL_messages_deleteHistory value = (TLRPC.TL_messages_deleteHistory) input;
            reject(value.min_date != 0 || value.max_date != 0, "history date range");
            out.setDeleteHistory(BotgramRpc.DeleteHistoryRequest.newBuilder()
                    .setPeer(peer(value.peer)).setJustClear(value.just_clear).setRevoke(value.revoke)
                    .setMaxMessageId(value.max_id));
        } else if (type == TLRPC.TL_messages_deleteSavedHistory.class) {
            TLRPC.TL_messages_deleteSavedHistory value = (TLRPC.TL_messages_deleteSavedHistory) input;
            reject(value.parent_peer != null || value.min_date != 0 || value.max_date != 0, "saved history options");
            out.setDeleteSavedHistory(BotgramRpc.DeleteSavedHistoryRequest.newBuilder()
                    .setPeer(peer(value.peer)).setMaxMessageId(value.max_id));
        } else if (type == TLRPC.TL_messages_deletePhoneCallHistory.class) {
            out.setDeletePhoneCallHistory(BotgramRpc.DeletePhoneCallHistoryRequest.newBuilder()
                    .setRevoke(((TLRPC.TL_messages_deletePhoneCallHistory) input).revoke));
        } else if (type == TLRPC.TL_messages_editMessage.class) {
            TLRPC.TL_messages_editMessage value = (TLRPC.TL_messages_editMessage) input;
            reject(value.invert_media || value.media != null || value.reply_markup != null
                    || value.schedule_date != 0 || value.schedule_repeat_period != 0
                    || value.quick_reply_shortcut_id != 0 || value.rich_message != null, "edit options");
            if (value.message == null) throw unsupported("media edit");
            BotgramRpc.EditMessageRequest.Builder request = BotgramRpc.EditMessageRequest.newBuilder()
                    .setPeer(peer(value.peer)).setMessageId(value.id).setText(value.message);
            if (value.entities != null) {
                if (value.entities.size() > 100) throw invalid("ENTITIES_TOO_LONG");
                for (TLRPC.MessageEntity item : value.entities) request.addEntities(entity(item, value.message));
            }
            out.setEditMessage(request);
        } else if (type == TLRPC.TL_messages_getMessageEditData.class) {
            TLRPC.TL_messages_getMessageEditData value = (TLRPC.TL_messages_getMessageEditData) input;
            out.setGetMessageEditData(BotgramRpc.GetMessageEditDataRequest.newBuilder()
                    .setPeer(peer(value.peer)).setMessageId(value.id));
        } else if (type == TLRPC.TL_messages_forwardMessages.class) {
            TLRPC.TL_messages_forwardMessages value = (TLRPC.TL_messages_forwardMessages) input;
            reject(value.background || value.with_my_score || value.drop_media_captions || value.noforwards
                    || value.allow_paid_floodskip || value.from_ephemeral || value.top_msg_id != 0
                    || value.reply_to != null || value.schedule_date != 0 || value.schedule_repeat_period != 0
                    || value.send_as != null || value.quick_reply_shortcut != null || value.effect != 0
                    || value.video_timestamp != 0 || value.allow_paid_stars != 0 || value.suggested_post != null,
                    "forward options");
            BotgramRpc.ForwardMessagesRequest.Builder request = BotgramRpc.ForwardMessagesRequest.newBuilder()
                    .setFromPeer(peer(value.from_peer)).setToPeer(peer(value.to_peer))
                    .setSilent(value.silent).setDropAuthor(value.drop_author);
            if (value.id != null) for (Integer id : value.id) {
                if (id == null) throw invalid("MESSAGE_ID_INVALID");
                request.addMessageIds(id.longValue());
            }
            if (value.random_id != null) for (Long random : value.random_id) {
                if (random == null) throw invalid("BOTGRAM_RPC_INVALID_ARGUMENT");
                request.addRandomIds(random);
            }
            out.setForwardMessages(request);
        } else if (type == TLRPC.TL_messages_setTyping.class) {
            TLRPC.TL_messages_setTyping value = (TLRPC.TL_messages_setTyping) input;
            reject(value.top_msg_id != 0, "typing thread");
            out.setSetTyping(BotgramRpc.SetTypingRequest.newBuilder()
                    .setPeer(peer(value.peer)).setAction(typingAction(value.action)));
        } else if (type == TLRPC.TL_messages_saveDraft.class) {
            TLRPC.TL_messages_saveDraft value = (TLRPC.TL_messages_saveDraft) input;
            reject(value.invert_media || value.media != null || value.effect != 0
                    || value.suggested_post != null || value.rich_message != null, "draft options");
            String draft = text(value.message);
            BotgramRpc.SaveDraftRequest.Builder request = BotgramRpc.SaveDraftRequest.newBuilder()
                    .setPeer(peer(value.peer)).setText(draft);
            if (value.reply_to != null) request.setReplyToMessageId(replyId(value.reply_to));
            if (value.entities != null) {
                if (value.entities.size() > 100) throw invalid("ENTITIES_TOO_LONG");
                for (TLRPC.MessageEntity item : value.entities) request.addEntities(entity(item, draft));
            }
            out.setSaveDraft(request);
        } else if (type == TLRPC.TL_messages_clearAllDrafts.class) {
            out.setClearAllDrafts(BotgramRpc.ClearAllDraftsRequest.getDefaultInstance());
        } else if (type == TLRPC.TL_messages_getAllDrafts.class) {
            out.setGetAllDrafts(BotgramRpc.GetAllDraftsRequest.getDefaultInstance());
        } else if (type == TLRPC.TL_messages_updatePinnedMessage.class) {
            TLRPC.TL_messages_updatePinnedMessage value = (TLRPC.TL_messages_updatePinnedMessage) input;
            reject(value.pm_oneside, "one-sided pin");
            out.setUpdatePinnedMessage(BotgramRpc.UpdatePinnedMessageRequest.newBuilder()
                    .setPeer(peer(value.peer)).setMessageId(value.id)
                    .setUnpin(value.unpin).setSilent(value.silent));
        } else if (type == TLRPC.TL_messages_unpinAllMessages.class) {
            TLRPC.TL_messages_unpinAllMessages value = (TLRPC.TL_messages_unpinAllMessages) input;
            reject(value.top_msg_id != 0 || value.saved_peer_id != null, "unpin options");
            out.setUnpinAllMessages(BotgramRpc.UnpinAllMessagesRequest.newBuilder().setPeer(peer(value.peer)));
        } else if (type == TLRPC.TL_messages_toggleDialogPin.class) {
            TLRPC.TL_messages_toggleDialogPin value = (TLRPC.TL_messages_toggleDialogPin) input;
            out.setToggleDialogPin(BotgramRpc.ToggleDialogPinRequest.newBuilder()
                    .setPeer(dialogPeer(value.peer)).setPinned(value.pinned));
        } else if (type == TLRPC.TL_messages_reorderPinnedDialogs.class) {
            TLRPC.TL_messages_reorderPinnedDialogs value = (TLRPC.TL_messages_reorderPinnedDialogs) input;
            reject(value.folder_id != 0, "dialog folders");
            BotgramRpc.ReorderPinnedDialogsRequest.Builder request = BotgramRpc.ReorderPinnedDialogsRequest.newBuilder()
                    .setForce(value.force);
            if (value.order != null) for (TLRPC.InputDialogPeer item : value.order)
                request.addOrder(dialogPeer(item));
            out.setReorderPinnedDialogs(request);
        } else if (type == TLRPC.TL_messages_markDialogUnread.class) {
            TLRPC.TL_messages_markDialogUnread value = (TLRPC.TL_messages_markDialogUnread) input;
            reject(value.parentPeer != null, "dialog folder parent");
            out.setMarkDialogUnread(BotgramRpc.MarkDialogUnreadRequest.newBuilder()
                    .setPeer(dialogPeer(value.peer)).setUnread(value.unread));
        } else if (type == TLRPC.TL_messages_getDialogUnreadMarks.class) {
            TLRPC.TL_messages_getDialogUnreadMarks value = (TLRPC.TL_messages_getDialogUnreadMarks) input;
            reject(value.parentPeer != null, "dialog folder parent");
            out.setGetDialogUnreadMarks(BotgramRpc.GetDialogUnreadMarksRequest.getDefaultInstance());
        } else if (type == TLRPC.TL_messages_readMessageContents.class) {
            TLRPC.TL_messages_readMessageContents value = (TLRPC.TL_messages_readMessageContents) input;
            BotgramRpc.ReadMessageContentsRequest.Builder request = BotgramRpc.ReadMessageContentsRequest.newBuilder();
            if (value.id != null) for (Integer id : value.id) {
                if (id == null) throw invalid("MESSAGE_ID_INVALID");
                request.addMessageIds(id.longValue());
            }
            out.setReadMessageContents(request);
        } else if (type == TLRPC.TL_messages_getOnlines.class) {
            out.setGetOnlines(BotgramRpc.GetOnlinesRequest.newBuilder()
                    .setPeer(peer(((TLRPC.TL_messages_getOnlines) input).peer)));
        } else if (type == TLRPC.TL_messages_getCommonChats.class) {
            TLRPC.TL_messages_getCommonChats value = (TLRPC.TL_messages_getCommonChats) input;
            out.setGetCommonChats(BotgramRpc.GetCommonChatsRequest.newBuilder()
                    .setUser(user(value.user_id)).setMaxChatId(value.max_id).setLimit(value.limit));
        } else if (type == TLRPC.TL_messages_hidePeerSettingsBar.class) {
            out.setHidePeerSettingsBar(BotgramRpc.HidePeerSettingsBarRequest.newBuilder()
                    .setPeer(peer(((TLRPC.TL_messages_hidePeerSettingsBar) input).peer)));
        } else if (type == TLRPC.TL_messages_reportSpam.class) {
            out.setReportSpam(BotgramRpc.ReportSpamRequest.newBuilder()
                    .setPeer(peer(((TLRPC.TL_messages_reportSpam) input).peer)));
        } else if (type == TLRPC.TL_messages_getMessagesViews.class) {
            TLRPC.TL_messages_getMessagesViews value = (TLRPC.TL_messages_getMessagesViews) input;
            BotgramRpc.GetMessagesViewsRequest.Builder request = BotgramRpc.GetMessagesViewsRequest.newBuilder()
                    .setPeer(peer(value.peer)).setIncrement(value.increment);
            if (value.id != null) for (Integer id : value.id) {
                if (id == null) throw invalid("MESSAGE_ID_INVALID");
                request.addMessageIds(id.longValue());
            }
            out.setGetMessagesViews(request);
        } else if (type == TLRPC.TL_messages_getOutboxReadDate.class) {
            TLRPC.TL_messages_getOutboxReadDate value = (TLRPC.TL_messages_getOutboxReadDate) input;
            out.setGetOutboxReadDate(BotgramRpc.GetOutboxReadDateRequest.newBuilder()
                    .setPeer(peer(value.peer)).setMessageId(value.msg_id));
        } else if (type == TLRPC.TL_messages_search.class) {
            TLRPC.TL_messages_search value = (TLRPC.TL_messages_search) input;
            reject(value.saved_peer_id != null || (value.saved_reaction != null && !value.saved_reaction.isEmpty())
                    || value.top_msg_id != 0, "search scope");
            BotgramRpc.SearchRequest.Builder request = BotgramRpc.SearchRequest.newBuilder()
                    .setPeer(peer(value.peer)).setQuery(text(value.q)).setFilter(searchFilter(value.filter))
                    .setMinDateMs(milliseconds(value.min_date)).setMaxDateMs(milliseconds(value.max_date))
                    .setOffsetMessageId(value.offset_id).setAddOffset(value.add_offset).setLimit(value.limit)
                    .setMaxMessageId(value.max_id).setMinMessageId(value.min_id);
            if (value.from_id != null) request.setFromUser(userSelectorFromPeer(value.from_id));
            out.setSearch(request);
        } else if (type == TLRPC.TL_messages_getSearchCounters.class) {
            TLRPC.TL_messages_getSearchCounters value = (TLRPC.TL_messages_getSearchCounters) input;
            reject(value.saved_peer_id != null || value.top_msg_id != 0, "search scope");
            BotgramRpc.GetSearchCountersRequest.Builder request = BotgramRpc.GetSearchCountersRequest.newBuilder()
                    .setPeer(peer(value.peer));
            if (value.filters != null) for (TLRPC.MessagesFilter item : value.filters)
                request.addFilters(searchFilter(item));
            out.setGetSearchCounters(request);
        } else if (type == TLRPC.TL_messages_uploadMedia.class) {
            TLRPC.TL_messages_uploadMedia value = (TLRPC.TL_messages_uploadMedia) input;
            out.setUploadMedia(BotgramRpc.UploadMediaRequest.newBuilder()
                    .setPeer(peer(value.peer)).setMedia(mediaSource(value.media)));
        } else if (type == TLRPC.TL_messages_sendMedia.class) {
            TLRPC.TL_messages_sendMedia value = (TLRPC.TL_messages_sendMedia) input;
            reject(value.background || value.noforwards || value.update_stickersets_order
                    || value.invert_media || value.reply_markup != null || value.schedule_date != 0
                    || value.schedule_repeat_period != 0 || value.quick_reply_shortcut != null
                    || value.effect != 0 || value.allow_paid_stars != 0 || value.suggested_post != null
                    || value.ephemeralReceiverBotId != 0, "media send options");
            if (value.send_as != null) {
                peer(value.send_as);
                if (!isSelf(value.send_as)) throw new BotgramApi.ApiError(403, "SEND_AS_PEER_INVALID");
            }
            String caption = text(value.message);
            BotgramRpc.SendMediaRequest.Builder request = BotgramRpc.SendMediaRequest.newBuilder()
                    .setPeer(peer(value.peer)).setRandomId(value.random_id)
                    .setMedia(mediaSource(value.media)).setCaption(caption).setSilent(value.silent);
            if (value.reply_to != null) request.setReplyToMessageId(replyId(value.reply_to));
            if (value.entities != null) {
                if (value.entities.size() > 100) throw invalid("ENTITIES_TOO_LONG");
                for (TLRPC.MessageEntity item : value.entities) request.addEntities(entity(item, caption));
            }
            out.setSendMedia(request);
        } else if (type == TLRPC.TL_messages_sendMultiMedia.class) {
            TLRPC.TL_messages_sendMultiMedia value = (TLRPC.TL_messages_sendMultiMedia) input;
            reject(value.background || value.noforwards || value.update_stickersets_order
                    || value.invert_media || value.schedule_date != 0 || value.quick_reply_shortcut != null
                    || value.effect != 0 || value.allow_paid_stars != 0, "album send options");
            if (value.send_as != null) {
                peer(value.send_as);
                if (!isSelf(value.send_as)) throw new BotgramApi.ApiError(403, "SEND_AS_PEER_INVALID");
            }
            BotgramRpc.SendMultiMediaRequest.Builder request = BotgramRpc.SendMultiMediaRequest.newBuilder()
                    .setPeer(peer(value.peer));
            if (value.multi_media != null) {
                for (TLRPC.TL_inputSingleMedia item : value.multi_media) {
                    if (item == null || item.media == null) throw invalid("MEDIA_INVALID");
                    String itemCaption = text(item.message);
                    BotgramRpc.MediaItem.Builder entry = BotgramRpc.MediaItem.newBuilder()
                            .setRandomId(item.random_id).setMedia(mediaSource(item.media))
                            .setCaption(itemCaption).setSilent(value.silent);
                    if (value.reply_to != null) entry.setReplyToMessageId(replyId(value.reply_to));
                    if (item.entities != null) {
                        if (item.entities.size() > 100) throw invalid("ENTITIES_TOO_LONG");
                        for (TLRPC.MessageEntity entity : item.entities) entry.addEntities(entity(entity, itemCaption));
                    }
                    request.addItems(entry);
                }
            }
            out.setSendMultiMedia(request);
        } else if (type == TLRPC.TL_messages_getExtendedMedia.class) {
            TLRPC.TL_messages_getExtendedMedia value = (TLRPC.TL_messages_getExtendedMedia) input;
            BotgramRpc.GetExtendedMediaRequest.Builder request = BotgramRpc.GetExtendedMediaRequest.newBuilder()
                    .setPeer(peer(value.peer));
            if (value.id != null) for (Integer id : value.id) {
                if (id == null) throw invalid("MESSAGE_ID_INVALID");
                request.addMessageIds(id.longValue());
            }
            out.setGetExtendedMedia(request);
        } else {
            throw unsupported(type.getSimpleName());
        }
        return out.build();
    }

    private BotgramRpc.SendMessageRequest send(TLRPC.TL_messages_sendMessage value) throws BotgramApi.ApiError {
        // Drafts are local-only; SendMessagesHelper clears them when sending.
        String message = text(value.message);
        boolean encrypted = org.telegram.botgram.e2ee.BotgramE2eeEnvelopeCodec.isEnvelope(message);
        reject((value.no_webpage && !encrypted) || value.background || value.noforwards
                || value.update_stickersets_order || value.invert_media || value.allow_paid_floodskip
                || value.reply_markup != null || value.schedule_date != 0 || value.schedule_repeat_period != 0
                || value.quick_reply_shortcut != null || value.effect != 0 || value.allow_paid_stars != 0
                || value.suggested_post != null || value.rich_message != null || value.ephemeralReceiverBotId != 0,
                "send options");
        BotgramRpc.PeerRef target = peer(value.peer);
        if (value.send_as != null) {
            peer(value.send_as);
            if (!isSelf(value.send_as)) throw new BotgramApi.ApiError(403, "SEND_AS_PEER_INVALID");
        }
        BotgramRpc.SendMessageRequest.Builder request = BotgramRpc.SendMessageRequest.newBuilder()
                .setPeer(target).setRandomId(value.random_id).setText(encrypted ? "" : message).setSilent(value.silent);
        if (encrypted) {
            if (value.entities != null && !value.entities.isEmpty()) throw invalid("E2EE_ENTITIES_FORBIDDEN");
            try {
                request.setE2Ee(org.telegram.botgram.e2ee.BotgramE2eeEnvelopeCodec.unwrap(message));
            } catch (Exception error) {
                throw invalid("E2EE_ENVELOPE_INVALID");
            }
        }
        if (value.reply_to != null) {
            TLRPC.InputReplyTo reply = value.reply_to;
            if (reply.getClass() != TLRPC.TL_inputReplyToMessage.class) throw unsupported("InputReplyTo");
            reject(reply.top_msg_id != 0 || nonempty(reply.quote_text) || nonempty(reply.quote_entities)
                    || reply.quote_offset != 0 || reply.peer != null || reply.monoforum_peer_id != null
                    || reply.story_id != 0 || reply.todo_item_id != 0
                    || (reply.poll_option != null && reply.poll_option.length != 0) || nonempty(reply.quote_rich),
                    "reply options");
            if (reply.reply_to_peer_id != null) {
                BotgramRpc.PeerRef replyPeer = peer(reply.reply_to_peer_id);
                if (!target.equals(replyPeer) && !(isSelf(value.peer) && isSelf(reply.reply_to_peer_id))) {
                    throw new BotgramApi.ApiError(501, "BOTGRAM_REPLY_MODE_UNSUPPORTED");
                }
            }
            request.setReplyToMessageId(reply.reply_to_msg_id);
        }
        if (value.entities != null) {
            if (value.entities.size() > 100) throw invalid("ENTITIES_TOO_LONG");
            for (TLRPC.MessageEntity item : value.entities) request.addEntities(entity(item, message));
        }
        return request.build();
    }

    private BotgramRpc.UserSelector userSelectorFromPeer(TLRPC.InputPeer value) throws BotgramApi.ApiError {
        if (value == null) throw invalid("USER_ID_INVALID");
        if (value.getClass() == TLRPC.TL_inputPeerSelf.class) {
            validatePeerFields(value);
            return BotgramRpc.UserSelector.newBuilder().setSelf(true).build();
        }
        if (value.getClass() != TLRPC.TL_inputPeerUser.class) throw unsupported("InputPeer");
        BotgramRpc.UserRef reference = peer(value).getUser();
        return BotgramRpc.UserSelector.newBuilder().setUser(reference).build();
    }

    private BotgramRpc.PeerRef dialogPeer(TLRPC.InputDialogPeer value) throws BotgramApi.ApiError {
        if (value == null) throw invalid("PEER_ID_INVALID");
        if (value.getClass() != TLRPC.TL_inputDialogPeer.class) throw unsupported("InputDialogPeer");
        return peer(((TLRPC.TL_inputDialogPeer) value).peer);
    }

    private static long replyId(TLRPC.InputReplyTo reply) throws BotgramApi.ApiError {
        if (reply.getClass() != TLRPC.TL_inputReplyToMessage.class) throw unsupported("InputReplyTo");
        reject(reply.top_msg_id != 0 || nonempty(reply.quote_text) || nonempty(reply.quote_entities)
                || reply.quote_offset != 0 || reply.peer != null || reply.monoforum_peer_id != null
                || reply.story_id != 0 || reply.todo_item_id != 0
                || (reply.poll_option != null && reply.poll_option.length != 0) || nonempty(reply.quote_rich),
                "reply options");
        if (reply.reply_to_msg_id == 0) throw invalid("REPLY_MESSAGE_ID_INVALID");
        return reply.reply_to_msg_id;
    }

    private static BotgramRpc.TypingAction typingAction(TLRPC.SendMessageAction action) throws BotgramApi.ApiError {
        if (action == null) throw invalid("BOTGRAM_RPC_INVALID_ARGUMENT");
        Class<?> type = action.getClass();
        if (type == TLRPC.TL_sendMessageTypingAction.class) return BotgramRpc.TypingAction.TYPING;
        if (type == TLRPC.TL_sendMessageCancelAction.class) return BotgramRpc.TypingAction.CANCEL;
        if (type == TLRPC.TL_sendMessageRecordAudioAction.class) return BotgramRpc.TypingAction.RECORD_AUDIO;
        if (type == TLRPC.TL_sendMessageRecordVideoAction.class) return BotgramRpc.TypingAction.RECORD_VIDEO;
        if (type == TLRPC.TL_sendMessageRecordRoundAction.class) return BotgramRpc.TypingAction.RECORD_ROUND;
        if (type == TLRPC.TL_sendMessageUploadPhotoAction.class) return BotgramRpc.TypingAction.UPLOAD_PHOTO;
        if (type == TLRPC.TL_sendMessageUploadVideoAction.class) return BotgramRpc.TypingAction.UPLOAD_VIDEO;
        if (type == TLRPC.TL_sendMessageUploadAudioAction.class) return BotgramRpc.TypingAction.UPLOAD_AUDIO;
        if (type == TLRPC.TL_sendMessageUploadDocumentAction.class) return BotgramRpc.TypingAction.UPLOAD_DOCUMENT;
        if (type == TLRPC.TL_sendMessageUploadRoundAction.class) return BotgramRpc.TypingAction.UPLOAD_ROUND;
        if (type == TLRPC.TL_sendMessageChooseStickerAction.class) return BotgramRpc.TypingAction.CHOOSE_STICKER;
        if (type == TLRPC.TL_sendMessageChooseContactAction.class) return BotgramRpc.TypingAction.CHOOSE_CONTACT;
        if (type == TLRPC.TL_sendMessageGeoLocationAction.class) return BotgramRpc.TypingAction.GEO_LOCATION;
        if (type == TLRPC.TL_sendMessageGamePlayAction.class) return BotgramRpc.TypingAction.GAME_PLAY;
        throw unsupported("SendMessageAction");
    }

    // Only the four concrete media inputs the app produces are accepted; every other
    // subclass (geo, poll, venue, web page, paid media, ...) fails explicitly.
    private static BotgramRpc.MediaSource mediaSource(TLRPC.InputMedia value) throws BotgramApi.ApiError {
        if (value == null) throw invalid("MEDIA_INVALID");
        BotgramRpc.MediaSource.Builder result = BotgramRpc.MediaSource.newBuilder();
        if (value instanceof TLRPC.TL_inputMediaUploadedPhoto) {
            TLRPC.TL_inputMediaUploadedPhoto photo = (TLRPC.TL_inputMediaUploadedPhoto) value;
            if (photo.file == null || photo.ttl_seconds != 0 || photo.live_photo)
                throw unsupported("InputMediaUploadedPhoto");
            result.setClientFileId(photo.file.id).setKind(BotgramRpc.MediaKind.PHOTO)
                    .setMime("image/jpeg").setFileName(text(photo.file.name))
                    .setTotalParts(Math.max(0, photo.file.parts));
            return result.build();
        }
        if (value instanceof TLRPC.TL_inputMediaUploadedDocument) {
            TLRPC.TL_inputMediaUploadedDocument document = (TLRPC.TL_inputMediaUploadedDocument) value;
            if (document.file == null || document.ttl_seconds != 0 || document.live_photo)
                throw unsupported("InputMediaUploadedDocument");
            result.setClientFileId(document.file.id).setKind(BotgramRpc.MediaKind.DOCUMENT)
                    .setMime(text(document.mime_type)).setFileName(text(document.file.name))
                    .setTotalParts(Math.max(0, document.file.parts));
            if (document.attributes != null) {
                for (TLRPC.DocumentAttribute attribute : document.attributes) {
                    if (attribute instanceof TLRPC.TL_documentAttributeFilename) {
                        result.setFileName(text(((TLRPC.TL_documentAttributeFilename) attribute).file_name));
                    } else if (attribute instanceof TLRPC.TL_documentAttributeImageSize) {
                        TLRPC.TL_documentAttributeImageSize size = (TLRPC.TL_documentAttributeImageSize) attribute;
                        result.setWidth(size.w).setHeight(size.h);
                    } else if (attribute instanceof TLRPC.TL_documentAttributeVideo) {
                        TLRPC.TL_documentAttributeVideo size = (TLRPC.TL_documentAttributeVideo) attribute;
                        result.setWidth(size.w).setHeight(size.h).setDuration((int) size.duration);
                    } else if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                        result.setDuration((int) ((TLRPC.TL_documentAttributeAudio) attribute).duration);
                    }
                }
            }
            return result.build();
        }
        if (value instanceof TLRPC.TL_inputMediaPhoto) {
            TLRPC.TL_inputMediaPhoto photo = (TLRPC.TL_inputMediaPhoto) value;
            if (photo.id == null || photo.ttl_seconds != 0) throw unsupported("InputMediaPhoto");
            result.setMediaId(photo.id.id).setKind(BotgramRpc.MediaKind.PHOTO);
            return result.build();
        }
        if (value instanceof TLRPC.TL_inputMediaDocument) {
            TLRPC.TL_inputMediaDocument document = (TLRPC.TL_inputMediaDocument) value;
            if (document.id == null || document.ttl_seconds != 0) throw unsupported("InputMediaDocument");
            result.setMediaId(document.id.id).setKind(BotgramRpc.MediaKind.DOCUMENT);
            return result.build();
        }
        throw new BotgramApi.ApiError(501, "BOTGRAM_MEDIA_UNSUPPORTED");
    }

    private static BotgramRpc.SearchFilter searchFilter(TLRPC.MessagesFilter filter) throws BotgramApi.ApiError {
        if (filter == null || filter.getClass() == TLRPC.TL_inputMessagesFilterEmpty.class)
            return BotgramRpc.SearchFilter.EMPTY;
        if (filter.getClass() == TLRPC.TL_inputMessagesFilterPinned.class)
            return BotgramRpc.SearchFilter.PINNED;
        // Media filters require media storage, which this backend does not have yet.
        throw new BotgramApi.ApiError(501, "BOTGRAM_SEARCH_FILTER_UNSUPPORTED");
    }

    private BotgramRpc.UserSelector user(TLRPC.InputUser value) throws BotgramApi.ApiError {
        if (value == null) throw invalid("USER_ID_INVALID");
        if (value.getClass() == TLRPC.TL_inputUserSelf.class) {
            reject(value.user_id != 0, "InputUser.user_id");
        } else if (value.getClass() != TLRPC.TL_inputUser.class) {
            throw unsupported("InputUser");
        }
        return identities.user(value);
    }

    private BotgramRpc.PeerRef peer(TLRPC.InputPeer value) throws BotgramApi.ApiError {
        if (value == null) throw invalid("PEER_ID_INVALID");
        Class<?> type = value.getClass();
        if (type == TLRPC.TL_inputPeerEmpty.class) throw invalid("PEER_ID_INVALID");
        if (type != TLRPC.TL_inputPeerSelf.class && type != TLRPC.TL_inputPeerUser.class
                && type != TLRPC.TL_inputPeerChat.class) throw unsupported("InputPeer");
        validatePeerFields(value);
        return identities.peer(value);
    }

    private static void validatePeerFields(TLRPC.InputPeer value) throws BotgramApi.ApiError {
        reject(value.peer != null || value.msg_id != 0 || value.channel_id != 0
                || (value.getClass() != TLRPC.TL_inputPeerUser.class && value.user_id != 0)
                || (value.getClass() != TLRPC.TL_inputPeerChat.class && value.chat_id != 0), "InputPeer fields");
    }

    private boolean isSelf(TLRPC.InputPeer value) throws BotgramApi.ApiError {
        return value.getClass() == TLRPC.TL_inputPeerSelf.class
                || (value.getClass() == TLRPC.TL_inputPeerUser.class && value.user_id == identities.selfId());
    }

    private BotgramRpc.NotifyTarget notifyTarget(TLRPC.InputNotifyPeer value) throws BotgramApi.ApiError {
        if (value == null) throw invalid("NOTIFY_TARGET_INVALID");
        BotgramRpc.NotifyTarget.Builder target = BotgramRpc.NotifyTarget.newBuilder();
        Class<?> type = value.getClass();
        if (type == TLRPC.TL_inputNotifyPeer.class) {
            target.setPeer(peer(((TLRPC.TL_inputNotifyPeer) value).peer));
        } else if (type == TLRPC.TL_inputNotifyUsers.class) {
            target.setAllUsers(BotgramRpc.Empty.getDefaultInstance());
        } else if (type == TLRPC.TL_inputNotifyChats.class) {
            target.setAllGroups(BotgramRpc.Empty.getDefaultInstance());
        } else if (type == TLRPC.TL_inputNotifyBroadcasts.class) {
            target.setAllBroadcasts(BotgramRpc.Empty.getDefaultInstance());
        } else {
            throw unsupported("InputNotifyPeer");
        }
        return target.build();
    }

    private static BotgramRpc.MessageEntity entity(TLRPC.MessageEntity value, String text) throws BotgramApi.ApiError {
        if (value == null) throw invalid("ENTITY_INVALID");
        Class<?> type = value.getClass();
        BotgramRpc.MessageEntity.Builder entity = BotgramRpc.MessageEntity.newBuilder();
        BotgramRpc.Empty empty = BotgramRpc.Empty.getDefaultInstance();
        if (type == TLRPC.TL_messageEntityBold.class) entity.setBold(empty);
        else if (type == TLRPC.TL_messageEntityItalic.class) entity.setItalic(empty);
        else if (type == TLRPC.TL_messageEntityPre.class) {
            entity.setPre(BotgramRpc.PreEntity.newBuilder().setLanguage(text(value.language)));
        } else if (type == TLRPC.TL_messageEntityTextUrl.class) {
            String url = text(value.url);
            if (url.isEmpty() || url.length() > 2048) throw invalid("ENTITY_URL_INVALID");
            entity.setTextUrl(BotgramRpc.TextUrlEntity.newBuilder().setUrl(url));
        } else if (type == TLRPC.TL_messageEntityUnderline.class) entity.setUnderline(empty);
        else if (type == TLRPC.TL_messageEntityStrike.class) entity.setStrike(empty);
        else if (type == TLRPC.TL_messageEntitySpoiler.class) entity.setSpoiler(empty);
        else if (type == TLRPC.TL_messageEntityCode.class) entity.setCode(empty);
        else if (type == TLRPC.TL_messageEntityUrl.class) entity.setUrl(empty);
        else if (type == TLRPC.TL_messageEntityEmail.class) entity.setEmail(empty);
        else if (type == TLRPC.TL_messageEntityPhone.class) entity.setPhone(empty);
        else if (type == TLRPC.TL_messageEntityMention.class) entity.setMention(empty);
        else if (type == TLRPC.TL_messageEntityHashtag.class) entity.setHashtag(empty);
        else if (type == TLRPC.TL_messageEntityCashtag.class) entity.setCashtag(empty);
        else if (type == TLRPC.TL_messageEntityBotCommand.class) entity.setBotCommand(empty);
        else throw new BotgramApi.ApiError(501, "BOTGRAM_ENTITY_UNSUPPORTED");
        reject(value.collapsed || (type != TLRPC.TL_messageEntityPre.class && nonempty(value.language))
                || (type != TLRPC.TL_messageEntityTextUrl.class && nonempty(value.url)), "entity fields");
        if (value.offset < 0 || value.length <= 0 || value.length > text.length()
                || value.offset > text.length() - value.length) throw invalid("ENTITY_BOUNDS_INVALID");
        int end = value.offset + value.length;
        if (splitsSurrogate(text, value.offset) || splitsSurrogate(text, end)) throw invalid("ENTITY_BOUNDS_INVALID");
        return entity.setOffsetUtf16(value.offset).setLengthUtf16(value.length).build();
    }

    private static boolean splitsSurrogate(String text, int offset) {
        return offset > 0 && offset < text.length() && Character.isLowSurrogate(text.charAt(offset))
                && Character.isHighSurrogate(text.charAt(offset - 1));
    }

    private static BotgramRpc.PhoneCallRef call(TLRPC.TL_inputPhoneCall value) throws BotgramApi.ApiError {
        if (value == null) throw invalid("CALL_NOT_FOUND");
        return BotgramRpc.PhoneCallRef.newBuilder()
                .setCallId(value.id).setAccessHash(value.access_hash).build();
    }

    private static BotgramRpc.PhoneCallProtocol protocol(TL_phone.PhoneCallProtocol value) throws BotgramApi.ApiError {
        if (value == null || value.getClass() != TL_phone.TL_phoneCallProtocol.class) {
            throw unsupported("PhoneCallProtocol");
        }
        BotgramRpc.PhoneCallProtocol.Builder result = BotgramRpc.PhoneCallProtocol.newBuilder()
                .setUdpP2P(value.udp_p2p).setUdpReflector(value.udp_reflector)
                .setMinLayer(value.min_layer).setMaxLayer(value.max_layer);
        if (value.library_versions != null) result.addAllLibraryVersions(value.library_versions);
        return result.build();
    }

    private static BotgramRpc.PhoneCallDiscardReason reason(TLRPC.PhoneCallDiscardReason value) throws BotgramApi.ApiError {
        if (value == null || value.getClass() == TLRPC.TL_phoneCallDiscardReasonHangup.class) {
            return BotgramRpc.PhoneCallDiscardReason.PHONE_CALL_DISCARD_REASON_HANGUP;
        }
        if (value.getClass() == TLRPC.TL_phoneCallDiscardReasonMissed.class) {
            return BotgramRpc.PhoneCallDiscardReason.PHONE_CALL_DISCARD_REASON_MISSED;
        }
        if (value.getClass() == TLRPC.TL_phoneCallDiscardReasonBusy.class) {
            return BotgramRpc.PhoneCallDiscardReason.PHONE_CALL_DISCARD_REASON_BUSY;
        }
        if (value.getClass() == TLRPC.TL_phoneCallDiscardReasonDisconnect.class) {
            return BotgramRpc.PhoneCallDiscardReason.PHONE_CALL_DISCARD_REASON_DISCONNECT;
        }
        throw unsupported("PhoneCallDiscardReason");
    }

    private static ByteString bytes(byte[] value) {
        return value == null ? ByteString.EMPTY : ByteString.copyFrom(value);
    }

    private static long milliseconds(int seconds) throws BotgramApi.ApiError {
        try {
            return Math.multiplyExact((long) seconds, 1000L);
        } catch (ArithmeticException error) {
            throw invalid("BOTGRAM_TIME_INVALID");
        }
    }

    private static String text(String value) { return value == null ? "" : value; }
    private static boolean nonempty(String value) { return value != null && !value.isEmpty(); }
    private static boolean nonempty(Collection<?> value) { return value != null && !value.isEmpty(); }
    /** An empty note object carries no user input and is equivalent to a missing note. */
    private static boolean noteContent(TLRPC.TL_textWithEntities note) {
        return note != null && (nonempty(note.text) || nonempty(note.entities));
    }

    private static void reject(boolean nondefault, String field) throws BotgramApi.ApiError {
        if (nondefault) throw unsupported(field);
    }

    private static BotgramApi.ApiError unsupported(String field) {
        return new BotgramApi.ApiError(501, "BOTGRAM_RPC_UNSUPPORTED:" + field);
    }

    private static BotgramApi.ApiError invalid(String code) { return new BotgramApi.ApiError(400, code); }
}

