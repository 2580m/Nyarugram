package org.telegram.botgram;

import org.telegram.botgram.protocol.v1.BotgramRpc;
import org.telegram.tgnet.TLRPC;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Background-queue, session-owned bridge to existing persistent TL identities. */
public final class BotgramProtobufIdentity {
    private final BotgramApi api;
    private final String ownUuid;
    private final BooleanSupplier active;
    private final BotgramPremiumState premium;
    private final HashMap<Long, BotgramRpc.UserRef> users = new HashMap<>();
    private final HashMap<String, Long> userIds = new HashMap<>();
    private final HashMap<Long, String> groups = new HashMap<>();
    private final HashMap<String, Long> groupIds = new HashMap<>();
    private String domain;
    private long ownId;
    private final HashMap<Long, BotgramRpc.WallpaperState> wallpapers = new HashMap<>();

    public BotgramRpc.WallpaperState wallpaper(long dialogId, BotgramRpc.WallpaperState value) throws BotgramApi.ApiError {
        checkActive();
        if (value != null) {
            BotgramProtobufWallpaper.state(value);
            wallpapers.put(dialogId, value);
        }
        return wallpapers.get(dialogId);
    }

    public BotgramProtobufIdentity(BotgramApi api, String ownUuid, BooleanSupplier active) {
        this(api, ownUuid, active, new BotgramPremiumState());
    }

    public BotgramProtobufIdentity(BotgramApi api, String ownUuid, BooleanSupplier active, BotgramPremiumState premium) {
        this.api = api;
        this.ownUuid = ownUuid;
        this.active = active;
        this.premium = premium;
    }

    public boolean premium(BotgramRpc.UserSummary user) throws BotgramApi.ApiError {
        checkActive();
        return premium.resolve(userId(user.getIdentity()),
                user.hasNyarugramPremium() ? user.getNyarugramPremium() : null);
    }

    private static BotgramApi.ApiError invalid() { return new BotgramApi.ApiError(502, "BOTGRAM_IDENTITY_INVALID"); }
    private void checkActive() throws BotgramApi.ApiError {
        if (!active.getAsBoolean()) throw new BotgramApi.ApiError(401, "SESSION_EXPIRED");
    }
    private static void uuid(String value) throws BotgramApi.ApiError {
        if (!value.matches("[0-9a-f]{32}")) throw invalid();
    }
    private void reference(BotgramRpc.UserRef user) throws BotgramApi.ApiError {
        if (domain == null || !domain.equals(user.getServerDomain())) throw invalid();
        uuid(user.getUserId());
    }

    private void resolve(Long user, Long group) throws BotgramApi.ApiError {
        checkActive();
        BotgramRpc.ResolveIdentitiesRequest.Builder query = BotgramRpc.ResolveIdentitiesRequest.newBuilder();
        if (user != null) query.addTlUserIds(user);
        if (group != null) query.addTlChatIds(group);
        try {
            BotgramRpc.RpcResponse response = api.rpc(BotgramRpc.RpcRequest.newBuilder()
                    .setProtocolMajor(1).setRequestId(UUID.randomUUID().toString()).setResolveIdentities(query).build());
            checkActive();
            BotgramRpc.ResolvedIdentities resolved = response.getResolveIdentities();
            String authority = resolved.getServerDomain();
            if (authority.isEmpty() || (domain != null && !domain.equals(authority))) throw invalid();
            domain = authority;
            learn(resolved.getUsersList(), resolved.getGroupsList());
            if (ownId == 0 || (user != null && !users.containsKey(user))
                    || (group != null && !groups.containsKey(group))) throw invalid();
        } catch (BotgramApi.ApiError error) { throw error; }
        catch (Exception error) { throw new BotgramApi.ApiError(502, "BOTGRAM_IDENTITY_RESOLUTION_FAILED"); }
    }

    public void initialize() throws BotgramApi.ApiError {
        checkActive();
        if (domain == null) resolve(null, null);
    }

    public void learn(List<BotgramRpc.UserSummary> newUsers, List<BotgramRpc.GroupSummary> newGroups) throws BotgramApi.ApiError {
        checkActive();
        for (BotgramRpc.UserSummary user : newUsers) {
            reference(user.getIdentity());
            long id = user.getTlUserId();
            String uuid = user.getIdentity().getUserId();
            if (id <= 0 || (users.containsKey(id) && !users.get(id).equals(user.getIdentity()))
                    || (userIds.containsKey(uuid) && userIds.get(uuid) != id)
                    || (user.getIsSelf() != uuid.equals(ownUuid))) throw invalid();
            users.put(id, user.getIdentity());
            userIds.put(uuid, id);
            if (user.getIsSelf()) {
                if (ownId != 0 && ownId != id) throw invalid();
                ownId = id;
            }
        }
        for (BotgramRpc.GroupSummary group : newGroups) {
            uuid(group.getGroupId());
            long id = group.getTlChatId();
            if (id <= 0 || (groups.containsKey(id) && !groups.get(id).equals(group.getGroupId()))
                    || (groupIds.containsKey(group.getGroupId()) && groupIds.get(group.getGroupId()) != id)) throw invalid();
            groups.put(id, group.getGroupId());
            groupIds.put(group.getGroupId(), id);
        }
    }

    public long selfId() throws BotgramApi.ApiError { initialize(); return ownId; }
    public String serverDomain() throws BotgramApi.ApiError { initialize(); return domain; }
    public String ownUuid() { return ownUuid; }
    public BotgramRpc.UserRef userRef(long tlUserId) throws BotgramApi.ApiError { return user(tlUserId); }
    private BotgramRpc.UserRef user(long id) throws BotgramApi.ApiError {
        initialize();
        if (id <= 0) throw new BotgramApi.ApiError(400, "USER_ID_INVALID");
        if (!users.containsKey(id)) resolve(id, null);
        return users.get(id);
    }
    public String group(long id) throws BotgramApi.ApiError {
        initialize();
        if (id <= 0) throw new BotgramApi.ApiError(400, "PEER_ID_INVALID");
        if (!groups.containsKey(id)) resolve(null, id);
        return groups.get(id);
    }
    public long userId(BotgramRpc.UserRef user) throws BotgramApi.ApiError {
        checkActive();
        reference(user);
        Long id = userIds.get(user.getUserId());
        if (id == null) throw invalid();
        return id;
    }
    public long groupId(String group) throws BotgramApi.ApiError {
        checkActive();
        Long id = groupIds.get(group);
        if (id == null) throw invalid();
        return id;
    }
    public BotgramRpc.UserSelector user(TLRPC.InputUser user) throws BotgramApi.ApiError {
        if (user == null) throw new BotgramApi.ApiError(400, "USER_ID_INVALID");
        if (user.getClass() == TLRPC.TL_inputUserSelf.class) return BotgramRpc.UserSelector.newBuilder().setSelf(true).build();
        if (user.getClass() == TLRPC.TL_inputUser.class) return BotgramRpc.UserSelector.newBuilder().setUser(user(user.user_id)).build();
        throw new BotgramApi.ApiError(501, "BOTGRAM_INPUT_USER_UNSUPPORTED");
    }
    public BotgramRpc.PeerRef peer(TLRPC.InputPeer peer) throws BotgramApi.ApiError {
        if (peer == null) throw new BotgramApi.ApiError(400, "PEER_ID_INVALID");
        if (peer.getClass() == TLRPC.TL_inputPeerSelf.class) return BotgramRpc.PeerRef.newBuilder().setSelf(true).build();
        if (peer.getClass() == TLRPC.TL_inputPeerUser.class) return BotgramRpc.PeerRef.newBuilder().setUser(user(peer.user_id)).build();
        if (peer.getClass() == TLRPC.TL_inputPeerChat.class) return BotgramRpc.PeerRef.newBuilder().setGroupId(group(peer.chat_id)).build();
        throw new BotgramApi.ApiError(501, "BOTGRAM_INPUT_PEER_UNSUPPORTED");
    }
}
