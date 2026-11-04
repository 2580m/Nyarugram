package org.telegram.ui.Components;

import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DocumentObject;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SvgHelper;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

public class StickerImageView extends BackupImageView implements NotificationCenter.NotificationCenterDelegate {

    int currentAccount;
    int stickerNum;
    String stickerPackName = AndroidUtilities.STICKERS_PLACEHOLDER_PACK_NAME;
    private int localAnimationResource;
    private int localGeneration;
    private boolean localAnimationLoaded;

    public StickerImageView(Context context, int currentAccount) {
        super(context);
        this.currentAccount = currentAccount;
    }

    public void setStickerNum(int stickerNum) {
        if (this.stickerNum != stickerNum) {
            this.stickerNum = stickerNum;
            setSticker();
        }
    }

    public void setStickerPackName(String stickerPackName) {
        this.stickerPackName = stickerPackName;
    }

    public void setLocalAnimation(int resource) {
        localAnimationResource = resource;
        localAnimationLoaded = false;
        localGeneration++;
        if (isAttachedToWindow()) loadLocalAnimation();
    }

    private void loadLocalAnimation() {
        if (localAnimationLoaded) return;
        final int owner = ++localGeneration;
        final int resource = localAnimationResource;
        final Context context = getContext().getApplicationContext();
        Utilities.globalQueue.postRunnable(() -> {
            java.io.File temporary = null;
            try {
                long installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
                java.io.File file = new java.io.File(context.getCacheDir(), "offline-sticker-" + resource + "-" + installed + ".gif");
                if (!file.exists()) {
                    temporary = java.io.File.createTempFile("offline-sticker-", ".tmp", context.getCacheDir());
                    try (java.io.InputStream input = context.getResources().openRawResource(resource);
                         java.io.FileOutputStream output = new java.io.FileOutputStream(temporary)) {
                        byte[] bytes = new byte[8192];
                        int count;
                        while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
                    }
                    if (!temporary.renameTo(file) && !file.exists()) throw new java.io.IOException("Offline animation cache rename failed");
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (owner != localGeneration || !isAttachedToWindow()) return;
                    localAnimationLoaded = true;
                    imageReceiver.setAllowStartAnimation(true);
                    imageReceiver.setAspectFit(true);
                    setImage(ImageLocation.getForPath(file.getAbsolutePath()), "130_130", "gif", null, this);
                });
            } catch (Exception error) {
                FileLog.e(error);
            } finally {
                if (temporary != null) temporary.delete();
            }
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (localAnimationResource != 0) {
            loadLocalAnimation();
            return;
        }
        setSticker();
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.diceStickersDidLoad);
    }

    @Override
    protected void onDetachedFromWindow() {
        localGeneration++;
        super.onDetachedFromWindow();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.diceStickersDidLoad);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.diceStickersDidLoad) {
            String name = (String) args[0];
            if (stickerPackName.equals(name)) {
                setSticker();
            }
        }
    }

    public void setSticker() {
        if (localAnimationResource != 0) return;
        String imageFilter = null;
        TLRPC.Document document = null;
        TLRPC.TL_messages_stickerSet set = null;

        set = MediaDataController.getInstance(currentAccount).getStickerSetByName(stickerPackName);
        if (set == null) {
            set = MediaDataController.getInstance(currentAccount).getStickerSetByEmojiOrName(stickerPackName);
        }
        if (set != null && set.documents.size() > stickerNum) {
            document = set.documents.get(stickerNum);
        }
        imageFilter = "130_130";

        SvgHelper.SvgDrawable svgThumb = null;
        if (document != null) {
            svgThumb = DocumentObject.getSvgThumb(document.thumbs, Theme.key_emptyListPlaceholder, 0.2f);
        }
        if (svgThumb != null) {
            svgThumb.overrideWidthAndHeight(512, 512);
        }

        if (document != null) {
            ImageLocation imageLocation = ImageLocation.getForDocument(document);
            setImage(imageLocation, imageFilter, "tgs", svgThumb, set);
        } else {
            imageReceiver.clearImage();
            MediaDataController.getInstance(currentAccount).loadStickersByEmojiOrName(stickerPackName, false, set == null);
        }
    }
}
