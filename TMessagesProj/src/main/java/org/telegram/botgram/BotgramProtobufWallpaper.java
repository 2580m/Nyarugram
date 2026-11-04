package org.telegram.botgram;

import org.telegram.botgram.protocol.v1.BotgramRpc;
import org.telegram.tgnet.TLRPC;

/** Explicit no-file subset; presence is preserved for black and optional gradients. */
public final class BotgramProtobufWallpaper {
    private BotgramProtobufWallpaper() { }

    private static void require(boolean valid) throws BotgramApi.ApiError {
        if (!valid) throw new BotgramApi.ApiError(400, "WALLPAPER_SETTINGS_INVALID");
    }

    public static BotgramRpc.Wallpaper encode(TLRPC.InputWallPaper input, TLRPC.WallPaperSettings value) throws BotgramApi.ApiError {
        if (!(input instanceof TLRPC.TL_inputWallPaperNoFile))
            throw new BotgramApi.ApiError(501, "BOTGRAM_WALLPAPER_FILE_UNSUPPORTED");
        require(value != null && (value.flags & 1) != 0 && (value.flags & ~255) == 0);
        BotgramRpc.WallpaperSettings.Builder settings = BotgramRpc.WallpaperSettings.newBuilder()
                .setBackgroundColor(value.background_color).setBlur(value.blur).setMotion(value.motion);
        if ((value.flags & 16) != 0) settings.setSecondBackgroundColor(value.second_background_color).setRotation(value.rotation);
        if ((value.flags & 32) != 0) settings.setThirdBackgroundColor(value.third_background_color);
        if ((value.flags & 64) != 0) settings.setFourthBackgroundColor(value.fourth_background_color);
        if ((value.flags & 8) != 0) settings.setIntensity(value.intensity);
        if ((value.flags & 128) != 0) settings.setEmoticon(value.emoticon == null ? "" : value.emoticon);
        validate(settings.build());
        return BotgramRpc.Wallpaper.newBuilder().setId(((TLRPC.TL_inputWallPaperNoFile) input).id)
                .setNoFile(BotgramRpc.NoFileWallpaper.newBuilder().setSettings(settings)).build();
    }

    private static void validate(BotgramRpc.WallpaperSettings settings) throws BotgramApi.ApiError {
        require(settings.hasBackgroundColor() && Integer.toUnsignedLong(settings.getBackgroundColor()) <= 0xffffffL);
        require(!settings.hasSecondBackgroundColor() || Integer.toUnsignedLong(settings.getSecondBackgroundColor()) <= 0xffffffL);
        require(!settings.hasThirdBackgroundColor() || settings.hasSecondBackgroundColor() && Integer.toUnsignedLong(settings.getThirdBackgroundColor()) <= 0xffffffL);
        require(!settings.hasFourthBackgroundColor() || settings.hasThirdBackgroundColor() && Integer.toUnsignedLong(settings.getFourthBackgroundColor()) <= 0xffffffL);
        require(settings.hasSecondBackgroundColor() == settings.hasRotation());
        require(!settings.hasRotation() || settings.getRotation() >= 0 && settings.getRotation() <= 359);
        require(!settings.hasIntensity() || settings.getIntensity() >= -100 && settings.getIntensity() <= 100);
        if (settings.hasEmoticon() && !settings.getEmoticon().isEmpty())
            throw new BotgramApi.ApiError(501, "BOTGRAM_WALLPAPER_THEME_UNSUPPORTED");
    }

    public static TLRPC.WallPaper decode(BotgramRpc.Wallpaper input) throws BotgramApi.ApiError {
        require(input.hasNoFile() && input.getNoFile().hasSettings());
        BotgramRpc.NoFileWallpaper noFile = input.getNoFile();
        BotgramRpc.WallpaperSettings value = noFile.getSettings();
        validate(value);
        TLRPC.TL_wallPaperNoFile wallpaper = new TLRPC.TL_wallPaperNoFile();
        wallpaper.id = input.getId();
        wallpaper.dark = noFile.getDark();
        wallpaper.isDefault = noFile.getIsDefault();
        wallpaper.flags = 4 | (wallpaper.dark ? 16 : 0) | (wallpaper.isDefault ? 2 : 0);
        TLRPC.TL_wallPaperSettings settings = new TLRPC.TL_wallPaperSettings();
        settings.flags = 1 | (value.getBlur() ? 2 : 0) | (value.getMotion() ? 4 : 0)
                | (value.hasIntensity() ? 8 : 0) | (value.hasSecondBackgroundColor() ? 16 : 0)
                | (value.hasThirdBackgroundColor() ? 32 : 0) | (value.hasFourthBackgroundColor() ? 64 : 0)
                | (value.hasEmoticon() ? 128 : 0);
        settings.background_color = value.getBackgroundColor();
        settings.second_background_color = value.getSecondBackgroundColor();
        settings.third_background_color = value.getThirdBackgroundColor();
        settings.fourth_background_color = value.getFourthBackgroundColor();
        settings.rotation = value.getRotation();
        settings.intensity = value.getIntensity();
        settings.blur = value.getBlur();
        settings.motion = value.getMotion();
        settings.emoticon = value.getEmoticon();
        wallpaper.settings = settings;
        return wallpaper;
    }

    public static TLRPC.WallPaper state(BotgramRpc.WallpaperState state) throws BotgramApi.ApiError {
        require(state.hasWallpaper() || state.hasCleared());
        return state.hasWallpaper() ? decode(state.getWallpaper()) : null;
    }
}
