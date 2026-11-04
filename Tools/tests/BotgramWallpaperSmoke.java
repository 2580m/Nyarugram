import org.telegram.botgram.BotgramApi;
import org.telegram.botgram.BotgramProtobufIdentity;
import org.telegram.botgram.BotgramProtobufResponseMapper;
import org.telegram.botgram.BotgramProtobufWallpaper;
import org.telegram.botgram.protocol.v1.BotgramRpc;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

public final class BotgramWallpaperSmoke {
    private static int checks;
    private interface Checked { void run() throws Exception; }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void fails(int status, Checked action) throws Exception {
        try { action.run(); throw new AssertionError("Expected HTTP " + status); }
        catch (BotgramApi.ApiError error) { check(error.status == status, "explicit failure status"); }
    }
    private static BotgramRpc.Wallpaper wallpaper(long id, BotgramRpc.WallpaperSettings.Builder settings) {
        return BotgramRpc.Wallpaper.newBuilder().setId(id)
                .setNoFile(BotgramRpc.NoFileWallpaper.newBuilder().setSettings(settings)).build();
    }
    public static void main(String[] args) throws Exception {
        for (long id : new long[]{0, -7, Long.MIN_VALUE, Long.MAX_VALUE}) {
            BotgramRpc.Wallpaper black = BotgramRpc.Wallpaper.parseFrom(
                    wallpaper(id, BotgramRpc.WallpaperSettings.newBuilder().setBackgroundColor(0)).toByteArray());
            TLRPC.WallPaper tl = BotgramProtobufWallpaper.decode(black);
            check(tl.id == id && (tl.settings.flags & 1) != 0 && tl.settings.background_color == 0,
                    "signed identity and black presence");
            check((tl.settings.flags & 16) == 0, "absent second color is not black");
            SerializedData output = new SerializedData();
            tl.serializeToStream(output);
            SerializedData input = new SerializedData(output.toByteArray());
            TLRPC.WallPaper restored = TLRPC.WallPaper.TLdeserialize(input, input.readInt32(true), true);
            check(restored instanceof TLRPC.TL_wallPaperNoFile && restored.id == id
                    && restored.settings.background_color == 0, "real TL binary persistence");
            input.cleanup();
            output.cleanup();
            TLRPC.TL_inputWallPaperNoFile selection = new TLRPC.TL_inputWallPaperNoFile();
            selection.id = id;
            check(BotgramProtobufWallpaper.encode(selection, restored.settings).equals(black), "wire/TL roundtrip");
        }
        TLRPC.WallPaper gradient = BotgramProtobufWallpaper.decode(wallpaper(-1,
                BotgramRpc.WallpaperSettings.newBuilder().setBackgroundColor(0xffffff)
                        .setSecondBackgroundColor(0).setRotation(0).setThirdBackgroundColor(0)
                        .setFourthBackgroundColor(0).setIntensity(-100).setBlur(true).setMotion(true)));
        check((gradient.settings.flags & (16 | 32 | 64 | 8 | 2 | 4)) == (16 | 32 | 64 | 8 | 2 | 4),
                "all zero gradient colors preserve their presence");
        fails(400, () -> BotgramProtobufWallpaper.decode(wallpaper(0, BotgramRpc.WallpaperSettings.newBuilder())));
        fails(400, () -> BotgramProtobufWallpaper.decode(wallpaper(0, BotgramRpc.WallpaperSettings.newBuilder().setBackgroundColor(-1))));
        fails(400, () -> BotgramProtobufWallpaper.decode(wallpaper(0, BotgramRpc.WallpaperSettings.newBuilder().setBackgroundColor(0).setSecondBackgroundColor(0))));
        fails(400, () -> BotgramProtobufWallpaper.decode(wallpaper(0, BotgramRpc.WallpaperSettings.newBuilder().setBackgroundColor(0).setRotation(0))));
        fails(400, () -> BotgramProtobufWallpaper.decode(wallpaper(0, BotgramRpc.WallpaperSettings.newBuilder().setBackgroundColor(0).setThirdBackgroundColor(1))));
        fails(400, () -> BotgramProtobufWallpaper.decode(wallpaper(0, BotgramRpc.WallpaperSettings.newBuilder().setBackgroundColor(0).setIntensity(101))));
        fails(501, () -> BotgramProtobufWallpaper.decode(wallpaper(0, BotgramRpc.WallpaperSettings.newBuilder().setBackgroundColor(0).setEmoticon("theme"))));
        fails(501, () -> BotgramProtobufWallpaper.encode(new TLRPC.TL_inputWallPaperSlug(), gradient.settings));
        fails(400, () -> BotgramProtobufWallpaper.state(BotgramRpc.WallpaperState.getDefaultInstance()));
        BotgramRpc.WallpaperState cleared = BotgramRpc.WallpaperState.newBuilder()
                .setCleared(BotgramRpc.Empty.getDefaultInstance()).setOverridden(true).build();
        check(BotgramProtobufWallpaper.state(cleared) == null, "explicit clear is valid");
        BotgramProtobufIdentity identities = new BotgramProtobufIdentity(null, "", () -> true);
        BotgramProtobufResponseMapper mapper = new BotgramProtobufResponseMapper(identities);
        BotgramRpc.ChatWallpaperResult.Builder reply = BotgramRpc.ChatWallpaperResult.newBuilder()
                .setState(cleared).setUpdates(BotgramRpc.Updates.getDefaultInstance());
        fails(400, () -> mapper.map(BotgramRpc.RpcResponse.newBuilder().setProtocolMajor(1)
                .setSetChatWallpaper(reply).build()));
        BotgramRpc.WallpaperState mismatched = cleared.toBuilder().setOverridden(false).build();
        reply.setUpdates(BotgramRpc.Updates.newBuilder().addUpdates(BotgramRpc.Update.newBuilder()
                .setPeerWallpaperChanged(BotgramRpc.PeerWallpaperChanged.newBuilder().setState(mismatched))));
        fails(400, () -> mapper.map(BotgramRpc.RpcResponse.newBuilder().setProtocolMajor(1)
                .setSetChatWallpaper(reply).build()));
        check(identities.wallpaper(7, null) == null, "absence remains unknown");
        identities.wallpaper(7, cleared);
        check(identities.wallpaper(7, null).hasCleared(), "absence retains prior explicit clear");
        check(identities.wallpaper(8, null) == null, "peer isolation");
        check(new BotgramProtobufIdentity(null, "", () -> true).wallpaper(7, null) == null, "session isolation");
        TLRPC.TL_userFull previous = new TLRPC.TL_userFull();
        previous.wallpaper = gradient;
        previous.flags = 16777216;
        previous.wallpaper_overridden = true;
        BotgramProtobufResponseMapper.FullUser unknown = new BotgramProtobufResponseMapper.FullUser();
        unknown.full_user = new TLRPC.TL_userFull();
        BotgramProtobufResponseMapper.preserveUnknownWallpaper(unknown, previous);
        check(unknown.full_user.wallpaper == gradient && unknown.full_user.wallpaper_overridden, "legacy absence preserves cache");
        unknown.wallpaperKnown = true;
        unknown.full_user.wallpaper = null;
        unknown.full_user.flags = 0;
        BotgramProtobufResponseMapper.preserveUnknownWallpaper(unknown, previous);
        check(unknown.full_user.wallpaper == null, "explicit server clear is not replaced with old cache");
        System.out.println("Wallpaper mapper/presence/TL checks passed: " + checks);
    }
}
