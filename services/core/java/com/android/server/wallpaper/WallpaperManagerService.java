/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wallpaper;

import static android.app.WallpaperManager.FLAG_LOCK;
import static android.app.WallpaperManager.FLAG_SYSTEM;
import static android.os.ParcelFileDescriptor.MODE_CREATE;
import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;
import static android.os.ParcelFileDescriptor.MODE_READ_WRITE;
import static android.os.ParcelFileDescriptor.MODE_TRUNCATE;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.IWallpaperManager;
import android.app.IWallpaperManagerCallback;
import android.app.PendingIntent;
import android.app.UserSwitchObserver;
import android.app.WallpaperColors;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.app.admin.DevicePolicyManager;
import android.app.backup.WallpaperBackupHelper;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileObserver;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.IRemoteCallback;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.wallpaper.IWallpaperConnection;
import android.service.wallpaper.IWallpaperEngine;
import android.service.wallpaper.IWallpaperService;
import android.service.wallpaper.WallpaperService;
import android.system.ErrnoException;
import android.system.Os;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;
import android.view.Display;
import android.view.IWindowManager;
import android.view.WindowManager;

import com.android.internal.R;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.JournaledFile;
import com.android.server.EventLogTags;
import com.android.server.FgThread;
import com.android.server.SystemService;

import java.lang.reflect.InvocationTargetException;
import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import com.android.internal.R;

public class WallpaperManagerService extends IWallpaperManager.Stub
        implements IWallpaperManagerService {
    static final String TAG = "WallpaperManagerService";
    static final boolean DEBUG = false;
    static final boolean DEBUG_LIVE = DEBUG || true;

    // This 100MB limitation is defined in DisplayListCanvas.
    private static final int MAX_BITMAP_SIZE = 100 * 1024 * 1024;

    public static class Lifecycle extends SystemService {
        private IWallpaperManagerService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            try {
                final Class<? extends IWallpaperManagerService> klass =
                        (Class<? extends IWallpaperManagerService>)Class.forName(
                                getContext().getResources().getString(
                                        R.string.config_wallpaperManagerServiceName));
                mService = klass.getConstructor(Context.class).newInstance(getContext());
                publishBinderService(Context.WALLPAPER_SERVICE, mService);
            } catch (Exception exp) {
                Slog.wtf(TAG, "Failed to instantiate WallpaperManagerService", exp);
            }
        }

        @Override
        public void onBootPhase(int phase) {
            if (mService != null) {
                mService.onBootPhase(phase);
            }
        }

        @Override
        public void onUnlockUser(int userHandle) {
            if (mService != null) {
                mService.onUnlockUser(userHandle);
            }
        }
    }

    final Object mLock = new Object();

    /**
     * Minimum time between crashes of a wallpaper service for us to consider
     * restarting it vs. just reverting to the static wallpaper.
     */
    static final long MIN_WALLPAPER_CRASH_TIME = 10000;
    static final int MAX_WALLPAPER_COMPONENT_LOG_LENGTH = 128;
    static final String WALLPAPER = "wallpaper_orig";
    static final String WALLPAPER_CROP = "wallpaper";
    static final String WALLPAPER_LOCK_ORIG = "wallpaper_lock_orig";
    static final String WALLPAPER_LOCK_CROP = "wallpaper_lock";
    static final String WALLPAPER_INFO = "wallpaper_info.xml";

    // All the various per-user state files we need to be aware of
    static final String[] sPerUserFiles = new String[] {
        WALLPAPER, WALLPAPER_CROP,
        WALLPAPER_LOCK_ORIG, WALLPAPER_LOCK_CROP,
        WALLPAPER_INFO
    };

    /**
     * Observes the wallpaper for changes and notifies all IWallpaperServiceCallbacks
     * that the wallpaper has changed. The CREATE is triggered when there is no
     * wallpaper set and is created for the first time. The CLOSE_WRITE is triggered
     * every time the wallpaper is changed.
     */
    private class WallpaperObserver extends FileObserver {

        final int mUserId;
        final WallpaperData mWallpaper;
        final File mWallpaperDir;
        final File mWallpaperFile;
        final File mWallpaperLockFile;

        public WallpaperObserver(WallpaperData wallpaper) {
            super(getWallpaperDir(wallpaper.userId).getAbsolutePath(),
                    CLOSE_WRITE | MOVED_TO | DELETE | DELETE_SELF);
            mUserId = wallpaper.userId;
            mWallpaperDir = getWallpaperDir(wallpaper.userId);
            mWallpaper = wallpaper;
            mWallpaperFile = new File(mWallpaperDir, WALLPAPER);
            mWallpaperLockFile = new File(mWallpaperDir, WALLPAPER_LOCK_ORIG);
        }

        private WallpaperData dataForEvent(boolean sysChanged, boolean lockChanged) {
            WallpaperData wallpaper = null;
            synchronized (mLock) {
                if (lockChanged) {
                    wallpaper = mLockWallpaperMap.get(mUserId);
                }
                if (wallpaper == null) {
                    // no lock-specific wallpaper exists, or sys case, handled together
                    wallpaper = mWallpaperMap.get(mUserId);
                }
            }
            return (wallpaper != null) ? wallpaper : mWallpaper;
        }

        @Override
        public void onEvent(int event, String path) {
            if (path == null) {
                return;
            }
            final boolean moved = (event == MOVED_TO);
            final boolean written = (event == CLOSE_WRITE || moved);
            final File changedFile = new File(mWallpaperDir, path);

            // System and system+lock changes happen on the system wallpaper input file;
            // lock-only changes happen on the dedicated lock wallpaper input file
            final boolean sysWallpaperChanged = (mWallpaperFile.equals(changedFile));
            final boolean lockWallpaperChanged = (mWallpaperLockFile.equals(changedFile));
            int notifyColorsWhich = 0;
            WallpaperData wallpaper = dataForEvent(sysWallpaperChanged, lockWallpaperChanged);

            if (DEBUG) {
                Slog.v(TAG, "Wallpaper file change: evt=" + event
                        + " path=" + path
                        + " sys=" + sysWallpaperChanged
                        + " lock=" + lockWallpaperChanged
                        + " imagePending=" + wallpaper.imageWallpaperPending
                        + " whichPending=0x" + Integer.toHexString(wallpaper.whichPending)
                        + " written=" + written);
            }

            if (moved && lockWallpaperChanged) {
                // We just migrated sys -> lock to preserve imagery for an impending
                // new system-only wallpaper.  Tell keyguard about it and make sure it
                // has the right SELinux label.
                if (DEBUG) {
                    Slog.i(TAG, "Sys -> lock MOVED_TO");
                }
                SELinux.restorecon(changedFile);
                notifyLockWallpaperChanged();
                notifyWallpaperColorsChanged(wallpaper, FLAG_LOCK);
                return;
            }

            synchronized (mLock) {
                if (sysWallpaperChanged || lockWallpaperChanged) {
                    notifyCallbacksLocked(wallpaper);
                    if (wallpaper.wallpaperComponent == null
                            || event != CLOSE_WRITE // includes the MOVED_TO case
                            || wallpaper.imageWallpaperPending) {
                        if (written) {
                            // The image source has finished writing the source image,
                            // so we now produce the crop rect (in the background), and
                            // only publish the new displayable (sub)image as a result
                            // of that work.
                            if (DEBUG) {
                                Slog.v(TAG, "Wallpaper written; generating crop");
                            }
                            SELinux.restorecon(changedFile);
                            if (moved) {
                                // This is a restore, so generate the crop using any just-restored new
                                // crop guidelines, making sure to preserve our local dimension hints.
                                // We also make sure to reapply the correct SELinux label.
                                if (DEBUG) {
                                    Slog.v(TAG, "moved-to, therefore restore; reloading metadata");
                                }
                                loadSettingsLocked(wallpaper.userId, true);
                            }
                            generateCrop(wallpaper);
                            if (DEBUG) {
                                Slog.v(TAG, "Crop done; invoking completion callback");
                            }
                            wallpaper.imageWallpaperPending = false;
                            if (sysWallpaperChanged) {
                                // If this was the system wallpaper, rebind...
                                bindWallpaperComponentLocked(mImageWallpaper, true,
                                        false, wallpaper, null);
                                notifyColorsWhich |= FLAG_SYSTEM;
                            }
                            if (lockWallpaperChanged
                                    || (wallpaper.whichPending & FLAG_LOCK) != 0) {
                                if (DEBUG) {
                                    Slog.i(TAG, "Lock-relevant wallpaper changed");
                                }
                                // either a lock-only wallpaper commit or a system+lock event.
                                // if it's system-plus-lock we need to wipe the lock bookkeeping;
                                // we're falling back to displaying the system wallpaper there.
                                if (!lockWallpaperChanged) {
                                    mLockWallpaperMap.remove(wallpaper.userId);
                                }
                                // and in any case, tell keyguard about it
                                notifyLockWallpaperChanged();
                                notifyColorsWhich |= FLAG_LOCK;
                            }

                            saveSettingsLocked(wallpaper.userId);

                            // Publish completion *after* we've persisted the changes
                            if (wallpaper.setComplete != null) {
                                try {
                                    wallpaper.setComplete.onWallpaperChanged();
                                } catch (RemoteException e) {
                                    // if this fails we don't really care; the setting app may just
                                    // have crashed and that sort of thing is a fact of life.
                                }
                            }
                        }
                    }
                }
            }

            // Outside of the lock since it will synchronize itself
            if (notifyColorsWhich != 0) {
                notifyWallpaperColorsChanged(wallpaper, notifyColorsWhich);
            }
        }
    }

    /**
     * Observes changes of theme settings. It will check whether to call
     * notifyWallpaperColorsChanged by the current theme and updated theme.
     * The light theme and dark theme are controlled by the hint values in Wallpaper colors,
     * threrfore, if light theme mode is chosen, HINT_SUPPORTS_DARK_THEME in hint will be
     * removed and then notify listeners.
     */
    private class ThemeSettingsObserver extends ContentObserver {

        public ThemeSettingsObserver(Handler handler) {
            super(handler);
        }

        public void startObserving(Context context) {
            context.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.THEME_MODE),
                    false,
                    this);
        }

        public void stopObserving(Context context) {
            context.getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange) {
            onThemeSettingsChanged();
        }
    }

    /**
     * Check whether to call notifyWallpaperColorsChanged. Assumed that the theme mode
     * was wallpaper theme mode and dark wallpaper was set, therefoe, the theme was dark.
     * Then theme mode changing to dark theme mode, however, theme should not update since
     * theme was dark already.
     */
    private boolean needUpdateLocked(WallpaperColors colors, int themeMode) {
        if (colors == null) {
            colors = new WallpaperColors(Color.valueOf(Color.WHITE), null, null, 0);
        }

        if (themeMode == mThemeMode) {
            return false;
        }

        boolean result = true;
        boolean supportDarkTheme =
                (colors.getColorHints() & WallpaperColors.HINT_SUPPORTS_DARK_THEME) != 0;
        switch (themeMode) {
            case Settings.Secure.THEME_MODE_WALLPAPER:
                if (mThemeMode == Settings.Secure.THEME_MODE_LIGHT) {
                    result = supportDarkTheme;
                } else {
                    result = !supportDarkTheme;
                }
                break;
            case Settings.Secure.THEME_MODE_LIGHT:
                if (mThemeMode == Settings.Secure.THEME_MODE_WALLPAPER) {
                    result = supportDarkTheme;
                }
                break;
            case Settings.Secure.THEME_MODE_DARK:
                if (mThemeMode == Settings.Secure.THEME_MODE_WALLPAPER) {
                    result = !supportDarkTheme;
                }
                break;
            default:
                Slog.w(TAG, "unkonwn theme mode " + themeMode);
                return false;
        }
        mThemeMode = themeMode;
        return result;
    }

    void onThemeSettingsChanged() {
        WallpaperData wallpaper;
        synchronized (mLock) {
            wallpaper = mWallpaperMap.get(mCurrentUserId);
            int updatedThemeMode = Settings.Secure.getInt(
                    mContext.getContentResolver(), Settings.Secure.THEME_MODE,
                    Settings.Secure.THEME_MODE_WALLPAPER);

            if (DEBUG) {
                Slog.v(TAG, "onThemeSettingsChanged, mode = " + updatedThemeMode);
            }

            if (!needUpdateLocked(wallpaper.primaryColors, updatedThemeMode)) {
                return;
            }
        }

        if (wallpaper != null) {
            notifyWallpaperColorsChanged(wallpaper, FLAG_SYSTEM);
        }
    }

    void notifyLockWallpaperChanged() {
        final IWallpaperManagerCallback cb = mKeyguardListener;
        if (cb != null) {
            try {
                cb.onWallpaperChanged();
            } catch (RemoteException e) {
                // Oh well it went away; no big deal
            }
        }
    }

    private void notifyWallpaperColorsChanged(@NonNull WallpaperData wallpaper, int which) {
        boolean needsExtraction;
        synchronized (mLock) {
            final RemoteCallbackList<IWallpaperManagerCallback> currentUserColorListeners =
                    mColorsChangedListeners.get(wallpaper.userId);
            final RemoteCallbackList<IWallpaperManagerCallback> userAllColorListeners =
                    mColorsChangedListeners.get(UserHandle.USER_ALL);
            // No-op until someone is listening to it.
            if (emptyCallbackList(currentUserColorListeners)  &&
                    emptyCallbackList(userAllColorListeners)) {
                return;
            }

            if (DEBUG) {
                Slog.v(TAG, "notifyWallpaperColorsChanged " + which);
            }

            needsExtraction = wallpaper.primaryColors == null;
        }

        // Let's notify the current values, it's fine if it's null, it just means
        // that we don't know yet.
        notifyColorListeners(wallpaper.primaryColors, which, wallpaper.userId);

        if (needsExtraction) {
            extractColors(wallpaper);
            synchronized (mLock) {
                // Don't need to notify if nothing changed.
                if (wallpaper.primaryColors == null) {
                    return;
                }
            }
            notifyColorListeners(wallpaper.primaryColors, which, wallpaper.userId);
        }
    }

    private static <T extends IInterface> boolean emptyCallbackList(RemoteCallbackList<T> list) {
        return (list == null || list.getRegisteredCallbackCount() == 0);
    }

    private void notifyColorListeners(@NonNull WallpaperColors wallpaperColors, int which,
            int userId) {
        final IWallpaperManagerCallback keyguardListener;
        final ArrayList<IWallpaperManagerCallback> colorListeners = new ArrayList<>();
        synchronized (mLock) {
            final RemoteCallbackList<IWallpaperManagerCallback> currentUserColorListeners =
                    mColorsChangedListeners.get(userId);
            final RemoteCallbackList<IWallpaperManagerCallback> userAllColorListeners =
                    mColorsChangedListeners.get(UserHandle.USER_ALL);
            keyguardListener = mKeyguardListener;

            if (currentUserColorListeners != null) {
                final int count = currentUserColorListeners.beginBroadcast();
                for (int i = 0; i < count; i++) {
                    colorListeners.add(currentUserColorListeners.getBroadcastItem(i));
                }
                currentUserColorListeners.finishBroadcast();
            }

            if (userAllColorListeners != null) {
                final int count = userAllColorListeners.beginBroadcast();
                for (int i = 0; i < count; i++) {
                    colorListeners.add(userAllColorListeners.getBroadcastItem(i));
                }
                userAllColorListeners.finishBroadcast();
            }
            wallpaperColors = getThemeColorsLocked(wallpaperColors);
        }

        final int count = colorListeners.size();
        for (int i = 0; i < count; i++) {
            try {
                colorListeners.get(i).onWallpaperColorsChanged(wallpaperColors, which, userId);
            } catch (RemoteException e) {
                // Callback is gone, it's not necessary to unregister it since
                // RemoteCallbackList#getBroadcastItem will take care of it.
            }
        }

        if (keyguardListener != null) {
            try {
                keyguardListener.onWallpaperColorsChanged(wallpaperColors, which, userId);
            } catch (RemoteException e) {
                // Oh well it went away; no big deal
            }
        }
    }

    /**
     * We can easily extract colors from an ImageWallpaper since it's only a bitmap.
     * In this case, using the crop is more than enough. Live wallpapers are just ignored.
     *
     * @param wallpaper a wallpaper representation
     */
    private void extractColors(WallpaperData wallpaper) {
        String cropFile = null;
        boolean defaultImageWallpaper = false;
        int wallpaperId;

        synchronized (mLock) {
            // Not having a wallpaperComponent means it's a lock screen wallpaper.
            final boolean imageWallpaper = mImageWallpaper.equals(wallpaper.wallpaperComponent)
                    || wallpaper.wallpaperComponent == null;
            if (imageWallpaper && wallpaper.cropFile != null && wallpaper.cropFile.exists()) {
                cropFile = wallpaper.cropFile.getAbsolutePath();
            } else if (imageWallpaper && !wallpaper.cropExists() && !wallpaper.sourceExists()) {
                defaultImageWallpaper = true;
            }
            wallpaperId = wallpaper.wallpaperId;
        }

        WallpaperColors colors = null;
        if (cropFile != null) {
            Bitmap bitmap = BitmapFactory.decodeFile(cropFile);
            if (bitmap != null) {
                colors = WallpaperColors.fromBitmap(bitmap);
                bitmap.recycle();
            }
        } else if (defaultImageWallpaper) {
            // There is no crop and source file because this is default image wallpaper.
            try (final InputStream is =
                         WallpaperManager.openDefaultWallpaper(mContext, FLAG_SYSTEM)) {
                if (is != null) {
                    try {
                        final BitmapFactory.Options options = new BitmapFactory.Options();
                        final Bitmap bitmap = BitmapFactory.decodeStream(is, null, options);
                        if (bitmap != null) {
                            colors = WallpaperColors.fromBitmap(bitmap);
                            bitmap.recycle();
                        }
                    } catch (OutOfMemoryError e) {
                        Slog.w(TAG, "Can't decode default wallpaper stream", e);
                    }
                }
            } catch (IOException e) {
                Slog.w(TAG, "Can't close default wallpaper stream", e);
            }
        }

        if (colors == null) {
            Slog.w(TAG, "Cannot extract colors because wallpaper could not be read.");
            return;
        }

        synchronized (mLock) {
            if (wallpaper.wallpaperId == wallpaperId) {
                wallpaper.primaryColors = colors;
                // Now that we have the colors, let's save them into the xml
                // to avoid having to run this again.
                saveSettingsLocked(wallpaper.userId);
            } else {
                Slog.w(TAG, "Not setting primary colors since wallpaper changed");
            }
        }
    }

    /**
     * We can easily change theme by modified colors hint. This function will check
     * current theme mode and return the WallpaperColors fit current theme mode.
     * If color need modified, it will return a copied WallpaperColors which
     * its ColorsHint is modified to fit current theme mode.
     *
     * @param colors a wallpaper primary colors representation
     */
    private WallpaperColors getThemeColorsLocked(WallpaperColors colors) {
        if (colors == null) {
            Slog.w(TAG, "Cannot get theme colors because WallpaperColors is null. Assuming Color.WHITE");
            colors = new WallpaperColors(Color.valueOf(Color.WHITE), null, null, 0);
        }

        int colorHints = colors.getColorHints();
        boolean supportDarkTheme = (colorHints & WallpaperColors.HINT_SUPPORTS_DARK_THEME) != 0;
        if (mThemeMode == Settings.Secure.THEME_MODE_WALLPAPER ||
                (mThemeMode == Settings.Secure.THEME_MODE_LIGHT && !supportDarkTheme) ||
                (mThemeMode == Settings.Secure.THEME_MODE_DARK && supportDarkTheme)) {
            return colors;
        }

        WallpaperColors themeColors = new WallpaperColors(colors.getPrimaryColor(),
                colors.getSecondaryColor(), colors.getTertiaryColor());

        if (mThemeMode == Settings.Secure.THEME_MODE_LIGHT) {
            colorHints &= ~WallpaperColors.HINT_SUPPORTS_DARK_THEME;
        } else if (mThemeMode == Settings.Secure.THEME_MODE_DARK) {
            colorHints |= WallpaperColors.HINT_SUPPORTS_DARK_THEME;
        }
        themeColors.setColorHints(colorHints);
        return themeColors;
    }

    /**
     * Once a new wallpaper has been written via setWallpaper(...), it needs to be cropped
     * for display.
     */
    private void generateCrop(WallpaperData wallpaper) {
        boolean success = false;

        Rect cropHint = new Rect(wallpaper.cropHint);

        if (DEBUG) {
            Slog.v(TAG, "Generating crop for new wallpaper(s): 0x"
                    + Integer.toHexString(wallpaper.whichPending)
                    + " to " + wallpaper.cropFile.getName()
                    + " crop=(" + cropHint.width() + 'x' + cropHint.height()
                    + ") dim=(" + wallpaper.width + 'x' + wallpaper.height + ')');
        }

        // Analyse the source; needed in multiple cases
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(wallpaper.wallpaperFile.getAbsolutePath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            Slog.w(TAG, "Invalid wallpaper data");
            success = false;
        } else {
            boolean needCrop = false;
            boolean needScale = false;

            // Empty crop means use the full image
            if (cropHint.isEmpty()) {
                cropHint.left = cropHint.top = 0;
                cropHint.right = options.outWidth;
                cropHint.bottom = options.outHeight;
            } else {
                // force the crop rect to lie within the measured bounds
                cropHint.offset(
                        (cropHint.right > options.outWidth ? options.outWidth - cropHint.right : 0),
                        (cropHint.bottom > options.outHeight ? options.outHeight - cropHint.bottom : 0));

                // If the crop hint was larger than the image we just overshot. Patch things up.
                if (cropHint.left < 0) {
                    cropHint.left = 0;
                }
                if (cropHint.top < 0) {
                    cropHint.top = 0;
                }

                // Don't bother cropping if what we're left with is identity
                needCrop = (options.outHeight > cropHint.height()
                        || options.outWidth > cropHint.width());
            }

            // scale if the crop height winds up not matching the recommended metrics
            // also take care of invalid dimensions.
            needScale = wallpaper.height != cropHint.height()
                    || cropHint.height() > GLHelper.getMaxTextureSize()
                    || cropHint.width() > GLHelper.getMaxTextureSize();

            if (DEBUG) {
                Slog.v(TAG, "crop: w=" + cropHint.width() + " h=" + cropHint.height());
                Slog.v(TAG, "dims: w=" + wallpaper.width + " h=" + wallpaper.height);
                Slog.v(TAG, "meas: w=" + options.outWidth + " h=" + options.outHeight);
                Slog.v(TAG, "crop?=" + needCrop + " scale?=" + needScale);
            }

            if (!needCrop && !needScale) {
                // Simple case:  the nominal crop fits what we want, so we take
                // the whole thing and just copy the image file directly.

                // TODO: It is not accurate to estimate bitmap size without decoding it,
                //  may be we can try to remove this optimized way in the future,
                //  that means, we will always go into the 'else' block.

                // This is just a quick estimation, may be smaller than it is.
                long estimateSize = options.outWidth * options.outHeight * 4;

                // A bitmap over than MAX_BITMAP_SIZE will make drawBitmap() fail.
                // Please see: DisplayListCanvas#throwIfCannotDraw.
                if (estimateSize < MAX_BITMAP_SIZE) {
                    success = FileUtils.copyFile(wallpaper.wallpaperFile, wallpaper.cropFile);
                }

                if (!success) {
                    wallpaper.cropFile.delete();
                    // TODO: fall back to default wallpaper in this case
                }

                if (DEBUG) {
                    Slog.v(TAG, "Null crop of new wallpaper, estimate size=" + estimateSize
                            + ", success=" + success);
                }
            } else {
                // Fancy case: crop and scale.  First, we decode and scale down if appropriate.
                FileOutputStream f = null;
                BufferedOutputStream bos = null;
                try {
                    BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(
                            wallpaper.wallpaperFile.getAbsolutePath(), false);

                    // This actually downsamples only by powers of two, but that's okay; we do
                    // a proper scaling blit later.  This is to minimize transient RAM use.
                    // We calculate the largest power-of-two under the actual ratio rather than
                    // just let the decode take care of it because we also want to remap where the
                    // cropHint rectangle lies in the decoded [super]rect.
                    final int actualScale = cropHint.height() / wallpaper.height;
                    int scale = 1;
                    while (2*scale <= actualScale) {
                        scale *= 2;
                    }
                    options.inSampleSize = scale;
                    options.inJustDecodeBounds = false;

                    final Rect estimateCrop = new Rect(cropHint);
                    estimateCrop.scale(1f / options.inSampleSize);
                    final float hRatio = (float) wallpaper.height / estimateCrop.height();
                    final int destHeight = (int) (estimateCrop.height() * hRatio);
                    final int destWidth = (int) (estimateCrop.width() * hRatio);

                    // We estimated an invalid crop, try to adjust the cropHint to get a valid one.
                    if (destWidth > GLHelper.getMaxTextureSize()) {
                        int newHeight = (int) (wallpaper.height / hRatio);
                        int newWidth = (int) (wallpaper.width / hRatio);

                        if (DEBUG) {
                            Slog.v(TAG, "Invalid crop dimensions, trying to adjust.");
                        }

                        estimateCrop.set(cropHint);
                        estimateCrop.left += (cropHint.width() - newWidth) / 2;
                        estimateCrop.top += (cropHint.height() - newHeight) / 2;
                        estimateCrop.right = estimateCrop.left + newWidth;
                        estimateCrop.bottom = estimateCrop.top + newHeight;
                        cropHint.set(estimateCrop);
                        estimateCrop.scale(1f / options.inSampleSize);
                    }

                    // We've got the safe cropHint; now we want to scale it properly to
                    // the desired rectangle.
                    // That's a height-biased operation: make it fit the hinted height.
                    final int safeHeight = (int) (estimateCrop.height() * hRatio);
                    final int safeWidth = (int) (estimateCrop.width() * hRatio);

                    if (DEBUG) {
                        Slog.v(TAG, "Decode parameters:");
                        Slog.v(TAG, "  cropHint=" + cropHint + ", estimateCrop=" + estimateCrop);
                        Slog.v(TAG, "  down sampling=" + options.inSampleSize
                                + ", hRatio=" + hRatio);
                        Slog.v(TAG, "  dest=" + destWidth + "x" + destHeight);
                        Slog.v(TAG, "  safe=" + safeWidth + "x" + safeHeight);
                        Slog.v(TAG, "  maxTextureSize=" + GLHelper.getMaxTextureSize());
                    }

                    Bitmap cropped = decoder.decodeRegion(cropHint, options);
                    decoder.recycle();

                    if (cropped == null) {
                        Slog.e(TAG, "Could not decode new wallpaper");
                    } else {
                        // We are safe to create final crop with safe dimensions now.
                        final Bitmap finalCrop = Bitmap.createScaledBitmap(cropped,
                                safeWidth, safeHeight, true);
                        if (DEBUG) {
                            Slog.v(TAG, "Final extract:");
                            Slog.v(TAG, "  dims: w=" + wallpaper.width
                                    + " h=" + wallpaper.height);
                            Slog.v(TAG, "  out: w=" + finalCrop.getWidth()
                                    + " h=" + finalCrop.getHeight());
                        }

                        // A bitmap over than MAX_BITMAP_SIZE will make drawBitmap() fail.
                        // Please see: DisplayListCanvas#throwIfCannotDraw.
                        if (finalCrop.getByteCount() > MAX_BITMAP_SIZE) {
                            throw new RuntimeException(
                                    "Too large bitmap, limit=" + MAX_BITMAP_SIZE);
                        }

                        f = new FileOutputStream(wallpaper.cropFile);
                        bos = new BufferedOutputStream(f, 32*1024);
                        finalCrop.compress(Bitmap.CompressFormat.JPEG, 100, bos);
                        bos.flush();  // don't rely on the implicit flush-at-close when noting success
                        success = true;
                    }
                } catch (Exception e) {
                    if (DEBUG) {
                        Slog.e(TAG, "Error decoding crop", e);
                    }
                } finally {
                    IoUtils.closeQuietly(bos);
                    IoUtils.closeQuietly(f);
                }
            }
        }

        if (!success) {
            Slog.e(TAG, "Unable to apply new wallpaper");
            wallpaper.cropFile.delete();
        }

        if (wallpaper.cropFile.exists()) {
            boolean didRestorecon = SELinux.restorecon(wallpaper.cropFile.getAbsoluteFile());
            if (DEBUG) {
                Slog.v(TAG, "restorecon() of crop file returned " + didRestorecon);
            }
        }
    }

    final Context mContext;
    final IWindowManager mIWindowManager;
    final IPackageManager mIPackageManager;
    final MyPackageMonitor mMonitor;
    final AppOpsManager mAppOpsManager;

    /**
     * Map of color listeners per user id.
     * The key will be the id of a user or UserHandle.USER_ALL - for wildcard listeners.
     */
    final SparseArray<RemoteCallbackList<IWallpaperManagerCallback>> mColorsChangedListeners;
    WallpaperData mLastWallpaper;
    IWallpaperManagerCallback mKeyguardListener;
    boolean mWaitingForUnlock;
    boolean mShuttingDown;

    /**
     * ID of the current wallpaper, changed every time anything sets a wallpaper.
     * This is used for external detection of wallpaper update activity.
     */
    int mWallpaperId;

    /**
     * Name of the component used to display bitmap wallpapers from either the gallery or
     * built-in wallpapers.
     */
    final ComponentName mImageWallpaper;

    /**
     * Name of the default wallpaper component; might be different from mImageWallpaper
     */
    final ComponentName mDefaultWallpaperComponent;

    final SparseArray<WallpaperData> mWallpaperMap = new SparseArray<WallpaperData>();
    final SparseArray<WallpaperData> mLockWallpaperMap = new SparseArray<WallpaperData>();

    final SparseArray<Boolean> mUserRestorecon = new SparseArray<Boolean>();
    int mCurrentUserId = UserHandle.USER_NULL;
    boolean mInAmbientMode;
    int mThemeMode;

    static class WallpaperData {

        int userId;

        final File wallpaperFile;   // source image
        final File cropFile;        // eventual destination

        /**
         * True while the client is writing a new wallpaper
         */
        boolean imageWallpaperPending;

        /**
         * Which new wallpapers are being written; mirrors the 'which'
         * selector bit field to setWallpaper().
         */
        int whichPending;

        /**
         * Callback once the set + crop is finished
         */
        IWallpaperManagerCallback setComplete;

        /**
         * Is the OS allowed to back up this wallpaper imagery?
         */
        boolean allowBackup;

        /**
         * Resource name if using a picture from the wallpaper gallery
         */
        String name = "";

        /**
         * The component name of the currently set live wallpaper.
         */
        ComponentName wallpaperComponent;

        /**
         * The component name of the wallpaper that should be set next.
         */
        ComponentName nextWallpaperComponent;

        /**
         * The ID of this wallpaper
         */
        int wallpaperId;

        /**
         * Primary colors histogram
         */
        WallpaperColors primaryColors;

        WallpaperConnection connection;
        long lastDiedTime;
        boolean wallpaperUpdating;
        WallpaperObserver wallpaperObserver;
        ThemeSettingsObserver themeSettingsObserver;

        /**
         * List of callbacks registered they should each be notified when the wallpaper is changed.
         */
        private RemoteCallbackList<IWallpaperManagerCallback> callbacks
                = new RemoteCallbackList<IWallpaperManagerCallback>();

        int width = -1;
        int height = -1;

        /**
         * The crop hint supplied for displaying a subset of the source image
         */
        final Rect cropHint = new Rect(0, 0, 0, 0);

        final Rect padding = new Rect(0, 0, 0, 0);

        WallpaperData(int userId, String inputFileName, String cropFileName) {
            this.userId = userId;
            final File wallpaperDir = getWallpaperDir(userId);
            wallpaperFile = new File(wallpaperDir, inputFileName);
            cropFile = new File(wallpaperDir, cropFileName);
        }

        // Called during initialization of a given user's wallpaper bookkeeping
        boolean cropExists() {
            return cropFile.exists();
        }

        boolean sourceExists() {
            return wallpaperFile.exists();
        }
    }

    int makeWallpaperIdLocked() {
        do {
            ++mWallpaperId;
        } while (mWallpaperId == 0);
        return mWallpaperId;
    }

    class WallpaperConnection extends IWallpaperConnection.Stub
            implements ServiceConnection {

        /** Time in milliseconds until we expect the wallpaper to reconnect (unless we're in the
         *  middle of an update). If exceeded, the wallpaper gets reset to the system default. */
        private static final long WALLPAPER_RECONNECT_TIMEOUT_MS = 10000;

        final WallpaperInfo mInfo;
        final Binder mToken = new Binder();
        IWallpaperService mService;
        IWallpaperEngine mEngine;
        WallpaperData mWallpaper;
        IRemoteCallback mReply;

        boolean mDimensionsChanged = false;
        boolean mPaddingChanged = false;

        private Runnable mResetRunnable = () -> {
            synchronized (mLock) {
                if (mShuttingDown) {
                    // Don't expect wallpaper services to relaunch during shutdown
                    if (DEBUG_LIVE) {
                        Slog.i(TAG, "Ignoring relaunch timeout during shutdown");
                    }
                    return;
                }

                if (!mWallpaper.wallpaperUpdating
                        && mWallpaper.userId == mCurrentUserId) {
                    Slog.w(TAG, "Wallpaper reconnect timed out for " + mWallpaper.wallpaperComponent
                            + ", reverting to built-in wallpaper!");
                    clearWallpaperLocked(true, FLAG_SYSTEM, mWallpaper.userId,
                            null);
                }
            }
        };

        public WallpaperConnection(WallpaperInfo info, WallpaperData wallpaper) {
            mInfo = info;
            mWallpaper = wallpaper;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                if (mWallpaper.connection == this) {
                    mService = IWallpaperService.Stub.asInterface(service);
                    attachServiceLocked(this, mWallpaper);
                    // XXX should probably do saveSettingsLocked() later
                    // when we have an engine, but I'm not sure about
                    // locking there and anyway we always need to be able to
                    // recover if there is something wrong.
                    saveSettingsLocked(mWallpaper.userId);
                    FgThread.getHandler().removeCallbacks(mResetRunnable);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                Slog.w(TAG, "Wallpaper service gone: " + name);
                if (!Objects.equals(name, mWallpaper.wallpaperComponent)) {
                    Slog.e(TAG, "Does not match expected wallpaper component "
                            + mWallpaper.wallpaperComponent);
                }
                mService = null;
                mEngine = null;
                if (mWallpaper.connection == this) {
                    // There is an inherent ordering race between this callback and the
                    // package monitor that receives notice that a package is being updated,
                    // so we cannot quite trust at this moment that we know for sure that
                    // this is not an update.  If we think this is a genuine non-update
                    // wallpaper outage, we do our "wait for reset" work as a continuation,
                    // a short time in the future, specifically to allow any pending package
                    // update message on this same looper thread to be processed.
                    if (!mWallpaper.wallpaperUpdating) {
                        mContext.getMainThreadHandler().postDelayed(() -> processDisconnect(this),
                                1000);
                    }
                }
            }
        }

        public void scheduleTimeoutLocked() {
            // If we didn't reset it right away, do so after we couldn't connect to
            // it for an extended amount of time to avoid having a black wallpaper.
            final Handler fgHandler = FgThread.getHandler();
            fgHandler.removeCallbacks(mResetRunnable);
            fgHandler.postDelayed(mResetRunnable, WALLPAPER_RECONNECT_TIMEOUT_MS);
            if (DEBUG_LIVE) {
                Slog.i(TAG, "Started wallpaper reconnect timeout for " + mWallpaper.wallpaperComponent);
            }
        }

        private void processDisconnect(final ServiceConnection connection) {
            synchronized (mLock) {
                // The wallpaper disappeared.  If this isn't a system-default one, track
                // crashes and fall back to default if it continues to misbehave.
                if (connection == mWallpaper.connection) {
                    final ComponentName wpService = mWallpaper.wallpaperComponent;
                    if (!mWallpaper.wallpaperUpdating
                            && mWallpaper.userId == mCurrentUserId
                            && !Objects.equals(mDefaultWallpaperComponent, wpService)
                            && !Objects.equals(mImageWallpaper, wpService)) {
                        // There is a race condition which causes
                        // {@link #mWallpaper.wallpaperUpdating} to be false even if it is
                        // currently updating since the broadcast notifying us is async.
                        // This race is overcome by the general rule that we only reset the
                        // wallpaper if its service was shut down twice
                        // during {@link #MIN_WALLPAPER_CRASH_TIME} millis.
                        if (mWallpaper.lastDiedTime != 0
                                && mWallpaper.lastDiedTime + MIN_WALLPAPER_CRASH_TIME
                                > SystemClock.uptimeMillis()) {
                            Slog.w(TAG, "Reverting to built-in wallpaper!");
                            clearWallpaperLocked(true, FLAG_SYSTEM, mWallpaper.userId, null);
                        } else {
                            mWallpaper.lastDiedTime = SystemClock.uptimeMillis();

                            clearWallpaperComponentLocked(mWallpaper);
                            if (bindWallpaperComponentLocked(
                                    wpService, false, false, mWallpaper, null)) {
                                mWallpaper.connection.scheduleTimeoutLocked();
                            } else {
                                Slog.w(TAG, "Reverting to built-in wallpaper!");
                                clearWallpaperLocked(true, FLAG_SYSTEM, mWallpaper.userId, null);
                            }
                        }
                        final String flattened = wpService.flattenToString();
                        EventLog.writeEvent(EventLogTags.WP_WALLPAPER_CRASHED,
                                flattened.substring(0, Math.min(flattened.length(),
                                        MAX_WALLPAPER_COMPONENT_LOG_LENGTH)));
                    }
                } else {
                    if (DEBUG_LIVE) {
                        Slog.i(TAG, "Wallpaper changed during disconnect tracking; ignoring");
                    }
                }
            }
        }

        /**
         * Called by a live wallpaper if its colors have changed.
         * @param primaryColors representation of wallpaper primary colors
         */
        @Override
        public void onWallpaperColorsChanged(WallpaperColors primaryColors) {
            int which;
            synchronized (mLock) {
                // Do not broadcast changes on ImageWallpaper since it's handled
                // internally by this class.
                if (mImageWallpaper.equals(mWallpaper.wallpaperComponent)) {
                    return;
                }

                mWallpaper.primaryColors = primaryColors;

                // Live wallpapers always are system wallpapers.
                which = FLAG_SYSTEM;
                // It's also the lock screen wallpaper when we don't have a bitmap in there
                WallpaperData lockedWallpaper = mLockWallpaperMap.get(mWallpaper.userId);
                if (lockedWallpaper == null) {
                    which |= FLAG_LOCK;
                }
            }
            if (which != 0) {
                notifyWallpaperColorsChanged(mWallpaper, which);
            }
        }

        @Override
        public void attachEngine(IWallpaperEngine engine) {
            synchronized (mLock) {
                mEngine = engine;
                if (mDimensionsChanged) {
                    try {
                        mEngine.setDesiredSize(mWallpaper.width, mWallpaper.height);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Failed to set wallpaper dimensions", e);
                    }
                    mDimensionsChanged = false;
                }
                if (mPaddingChanged) {
                    try {
                        mEngine.setDisplayPadding(mWallpaper.padding);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Failed to set wallpaper padding", e);
                    }
                    mPaddingChanged = false;
                }
                if (mInfo != null && mInfo.getSupportsAmbientMode()) {
                    try {
                        mEngine.setInAmbientMode(mInAmbientMode, false /* animated */);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Failed to set ambient mode state", e);
                    }
                }
                try {
                    // This will trigger onComputeColors in the wallpaper engine.
                    // It's fine to be locked in here since the binder is oneway.
                    mEngine.requestWallpaperColors();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to request wallpaper colors", e);
                }
            }
        }

        @Override
        public void engineShown(IWallpaperEngine engine) {
            synchronized (mLock) {
                if (mReply != null) {
                    long ident = Binder.clearCallingIdentity();
                    try {
                        mReply.sendResult(null);
                    } catch (RemoteException e) {
                        Binder.restoreCallingIdentity(ident);
                    }
                    mReply = null;
                }
            }
        }

        @Override
        public ParcelFileDescriptor setWallpaper(String name) {
            synchronized (mLock) {
                if (mWallpaper.connection == this) {
                    return updateWallpaperBitmapLocked(name, mWallpaper, null);
                }
                return null;
            }
        }
    }

    class MyPackageMonitor extends PackageMonitor {
        @Override
        public void onPackageUpdateFinished(String packageName, int uid) {
            synchronized (mLock) {
                if (mCurrentUserId != getChangingUserId()) {
                    return;
                }
                WallpaperData wallpaper = mWallpaperMap.get(mCurrentUserId);
                if (wallpaper != null) {
                    final ComponentName wpService = wallpaper.wallpaperComponent;
                    if (wpService != null && wpService.getPackageName().equals(packageName)) {
                        if (DEBUG_LIVE) {
                            Slog.i(TAG, "Wallpaper " + wpService + " update has finished");
                        }
                        wallpaper.wallpaperUpdating = false;
                        clearWallpaperComponentLocked(wallpaper);
                        if (!bindWallpaperComponentLocked(wpService, false, false,
                                wallpaper, null)) {
                            Slog.w(TAG, "Wallpaper " + wpService
                                    + " no longer available; reverting to default");
                            clearWallpaperLocked(false, FLAG_SYSTEM, wallpaper.userId, null);
                        }
                    }
                }
            }
        }

        @Override
        public void onPackageModified(String packageName) {
            synchronized (mLock) {
                if (mCurrentUserId != getChangingUserId()) {
                    return;
                }
                WallpaperData wallpaper = mWallpaperMap.get(mCurrentUserId);
                if (wallpaper != null) {
                    if (wallpaper.wallpaperComponent == null
                            || !wallpaper.wallpaperComponent.getPackageName().equals(packageName)) {
                        return;
                    }
                    doPackagesChangedLocked(true, wallpaper);
                }
            }
        }

        @Override
        public void onPackageUpdateStarted(String packageName, int uid) {
            synchronized (mLock) {
                if (mCurrentUserId != getChangingUserId()) {
                    return;
                }
                WallpaperData wallpaper = mWallpaperMap.get(mCurrentUserId);
                if (wallpaper != null) {
                    if (wallpaper.wallpaperComponent != null
                            && wallpaper.wallpaperComponent.getPackageName().equals(packageName)) {
                        if (DEBUG_LIVE) {
                            Slog.i(TAG, "Wallpaper service " + wallpaper.wallpaperComponent
                                    + " is updating");
                        }
                        wallpaper.wallpaperUpdating = true;
                        if (wallpaper.connection != null) {
                            FgThread.getHandler().removeCallbacks(
                                    wallpaper.connection.mResetRunnable);
                        }
                    }
                }
            }
        }

        @Override
        public boolean onHandleForceStop(Intent intent, String[] packages, int uid, boolean doit) {
            synchronized (mLock) {
                boolean changed = false;
                if (mCurrentUserId != getChangingUserId()) {
                    return false;
                }
                WallpaperData wallpaper = mWallpaperMap.get(mCurrentUserId);
                if (wallpaper != null) {
                    boolean res = doPackagesChangedLocked(doit, wallpaper);
                    changed |= res;
                }
                return changed;
            }
        }

        @Override
        public void onSomePackagesChanged() {
            synchronized (mLock) {
                if (mCurrentUserId != getChangingUserId()) {
                    return;
                }
                WallpaperData wallpaper = mWallpaperMap.get(mCurrentUserId);
                if (wallpaper != null) {
                    doPackagesChangedLocked(true, wallpaper);
                }
            }
        }

        boolean doPackagesChangedLocked(boolean doit, WallpaperData wallpaper) {
            boolean changed = false;
            if (wallpaper.wallpaperComponent != null) {
                int change = isPackageDisappearing(wallpaper.wallpaperComponent
                        .getPackageName());
                if (change == PACKAGE_PERMANENT_CHANGE
                        || change == PACKAGE_TEMPORARY_CHANGE) {
                    changed = true;
                    if (doit) {
                        Slog.w(TAG, "Wallpaper uninstalled, removing: "
                                + wallpaper.wallpaperComponent);
                        clearWallpaperLocked(false, FLAG_SYSTEM, wallpaper.userId, null);
                    }
                }
            }
            if (wallpaper.nextWallpaperComponent != null) {
                int change = isPackageDisappearing(wallpaper.nextWallpaperComponent
                        .getPackageName());
                if (change == PACKAGE_PERMANENT_CHANGE
                        || change == PACKAGE_TEMPORARY_CHANGE) {
                    wallpaper.nextWallpaperComponent = null;
                }
            }
            if (wallpaper.wallpaperComponent != null
                    && isPackageModified(wallpaper.wallpaperComponent.getPackageName())) {
                try {
                    mContext.getPackageManager().getServiceInfo(wallpaper.wallpaperComponent,
                            PackageManager.MATCH_DIRECT_BOOT_AWARE
                                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
                } catch (NameNotFoundException e) {
                    Slog.w(TAG, "Wallpaper component gone, removing: "
                            + wallpaper.wallpaperComponent);
                    clearWallpaperLocked(false, FLAG_SYSTEM, wallpaper.userId, null);
                }
            }
            if (wallpaper.nextWallpaperComponent != null
                    && isPackageModified(wallpaper.nextWallpaperComponent.getPackageName())) {
                try {
                    mContext.getPackageManager().getServiceInfo(wallpaper.nextWallpaperComponent,
                            PackageManager.MATCH_DIRECT_BOOT_AWARE
                                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
                } catch (NameNotFoundException e) {
                    wallpaper.nextWallpaperComponent = null;
                }
            }
            return changed;
        }
    }

    public WallpaperManagerService(Context context) {
        if (DEBUG) Slog.v(TAG, "WallpaperService startup");
        mContext = context;
        mShuttingDown = false;
        mImageWallpaper = ComponentName.unflattenFromString(
                context.getResources().getString(R.string.image_wallpaper_component));
        mDefaultWallpaperComponent = WallpaperManager.getDefaultWallpaperComponent(context);
        mIWindowManager = IWindowManager.Stub.asInterface(
                ServiceManager.getService(Context.WINDOW_SERVICE));
        mIPackageManager = AppGlobals.getPackageManager();
        mAppOpsManager = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        mMonitor = new MyPackageMonitor();
        mColorsChangedListeners = new SparseArray<>();
    }

    void initialize() {
        mMonitor.register(mContext, null, UserHandle.ALL, true);
        getWallpaperDir(UserHandle.USER_SYSTEM).mkdirs();

        // Initialize state from the persistent store, then guarantee that the
        // WallpaperData for the system imagery is instantiated & active, creating
        // it from defaults if necessary.
        loadSettingsLocked(UserHandle.USER_SYSTEM, false);
        getWallpaperSafeLocked(UserHandle.USER_SYSTEM, FLAG_SYSTEM);
    }

    private static File getWallpaperDir(int userId) {
        return Environment.getUserSystemDirectory(userId);
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        for (int i = 0; i < mWallpaperMap.size(); i++) {
            WallpaperData wallpaper = mWallpaperMap.valueAt(i);
            wallpaper.wallpaperObserver.stopWatching();
        }
    }

    void systemReady() {
        if (DEBUG) Slog.v(TAG, "systemReady");
        initialize();

        WallpaperData wallpaper = mWallpaperMap.get(UserHandle.USER_SYSTEM);
        // If we think we're going to be using the system image wallpaper imagery, make
        // sure we have something to render
        if (mImageWallpaper.equals(wallpaper.nextWallpaperComponent)) {
            // No crop file? Make sure we've finished the processing sequence if necessary
            if (!wallpaper.cropExists()) {
                if (DEBUG) {
                    Slog.i(TAG, "No crop; regenerating from source");
                }
                generateCrop(wallpaper);
            }
            // Still nothing?  Fall back to default.
            if (!wallpaper.cropExists()) {
                if (DEBUG) {
                    Slog.i(TAG, "Unable to regenerate crop; resetting");
                }
                clearWallpaperLocked(false, FLAG_SYSTEM, UserHandle.USER_SYSTEM, null);
            }
        } else {
            if (DEBUG) {
                Slog.i(TAG, "Nondefault wallpaper component; gracefully ignoring");
            }
        }

        IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();
                if (Intent.ACTION_USER_REMOVED.equals(action)) {
                    onRemoveUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                            UserHandle.USER_NULL));
                }
            }
        }, userFilter);

        final IntentFilter shutdownFilter = new IntentFilter(Intent.ACTION_SHUTDOWN);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SHUTDOWN.equals(intent.getAction())) {
                    if (DEBUG) {
                        Slog.i(TAG, "Shutting down");
                    }
                    synchronized (mLock) {
                        mShuttingDown = true;
                    }
                }
            }
        }, shutdownFilter);

        try {
            ActivityManager.getService().registerUserSwitchObserver(
                    new UserSwitchObserver() {
                        @Override
                        public void onUserSwitching(int newUserId, IRemoteCallback reply) {
                            switchUser(newUserId, reply);
                        }
                    }, TAG);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /** Called by SystemBackupAgent */
    public String getName() {
        // Verify caller is the system
        if (Binder.getCallingUid() != android.os.Process.SYSTEM_UID) {
            throw new RuntimeException("getName() can only be called from the system process");
        }
        synchronized (mLock) {
            return mWallpaperMap.get(0).name;
        }
    }

    void stopObserver(WallpaperData wallpaper) {
        if (wallpaper != null) {
            if (wallpaper.wallpaperObserver != null) {
                wallpaper.wallpaperObserver.stopWatching();
                wallpaper.wallpaperObserver = null;
            }
            if (wallpaper.themeSettingsObserver != null) {
                wallpaper.themeSettingsObserver.stopObserving(mContext);
                wallpaper.themeSettingsObserver = null;
            }
        }
    }

    void stopObserversLocked(int userId) {
        stopObserver(mWallpaperMap.get(userId));
        stopObserver(mLockWallpaperMap.get(userId));
        mWallpaperMap.remove(userId);
        mLockWallpaperMap.remove(userId);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
            systemReady();
        } else if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            switchUser(UserHandle.USER_SYSTEM, null);
        }
    }

    @Override
    public void onUnlockUser(final int userId) {
        synchronized (mLock) {
            if (mCurrentUserId == userId) {
                if (mWaitingForUnlock) {
                    // the desired wallpaper is not direct-boot aware, load it now
                    final WallpaperData systemWallpaper =
                            getWallpaperSafeLocked(userId, FLAG_SYSTEM);
                    switchWallpaper(systemWallpaper, null);
                }

                // Make sure that the SELinux labeling of all the relevant files is correct.
                // This corrects for mislabeling bugs that might have arisen from move-to
                // operations involving the wallpaper files.  This isn't timing-critical,
                // so we do it in the background to avoid holding up the user unlock operation.
                if (mUserRestorecon.get(userId) != Boolean.TRUE) {
                    mUserRestorecon.put(userId, Boolean.TRUE);
                    Runnable relabeler = new Runnable() {
                        @Override
                        public void run() {
                            final File wallpaperDir = getWallpaperDir(userId);
                            for (String filename : sPerUserFiles) {
                                File f = new File(wallpaperDir, filename);
                                if (f.exists()) {
                                    SELinux.restorecon(f);
                                }
                            }
                        }
                    };
                    BackgroundThread.getHandler().post(relabeler);
                }
            }
        }
    }

    void onRemoveUser(int userId) {
        if (userId < 1) return;

        final File wallpaperDir = getWallpaperDir(userId);
        synchronized (mLock) {
            stopObserversLocked(userId);
            for (String filename : sPerUserFiles) {
                new File(wallpaperDir, filename).delete();
            }
            mUserRestorecon.remove(userId);
        }
    }

    void switchUser(int userId, IRemoteCallback reply) {
        final WallpaperData systemWallpaper;
        final WallpaperData lockWallpaper;
        synchronized (mLock) {
            if (mCurrentUserId == userId) {
                return;
            }
            mCurrentUserId = userId;
            systemWallpaper = getWallpaperSafeLocked(userId, FLAG_SYSTEM);
            final WallpaperData tmpLockWallpaper = mLockWallpaperMap.get(userId);
            lockWallpaper = tmpLockWallpaper == null ? systemWallpaper : tmpLockWallpaper;
            // Not started watching yet, in case wallpaper data was loaded for other reasons.
            if (systemWallpaper.wallpaperObserver == null) {
                systemWallpaper.wallpaperObserver = new WallpaperObserver(systemWallpaper);
                systemWallpaper.wallpaperObserver.startWatching();
            }
            if (systemWallpaper.themeSettingsObserver == null) {
                systemWallpaper.themeSettingsObserver = new ThemeSettingsObserver(null);
                systemWallpaper.themeSettingsObserver.startObserving(mContext);
            }
            mThemeMode = Settings.Secure.getInt(
                    mContext.getContentResolver(), Settings.Secure.THEME_MODE,
                    Settings.Secure.THEME_MODE_WALLPAPER);
            switchWallpaper(systemWallpaper, reply);
        }

        // Offload color extraction to another thread since switchUser will be called
        // from the main thread.
        FgThread.getHandler().post(() -> {
            notifyWallpaperColorsChanged(systemWallpaper, FLAG_SYSTEM);
            notifyWallpaperColorsChanged(lockWallpaper, FLAG_LOCK);
        });
    }

    void switchWallpaper(WallpaperData wallpaper, IRemoteCallback reply) {
        synchronized (mLock) {
            mWaitingForUnlock = false;
            final ComponentName cname = wallpaper.wallpaperComponent != null ?
                    wallpaper.wallpaperComponent : wallpaper.nextWallpaperComponent;
            if (!bindWallpaperComponentLocked(cname, true, false, wallpaper, reply)) {
                // We failed to bind the desired wallpaper, but that might
                // happen if the wallpaper isn't direct-boot aware
                ServiceInfo si = null;
                try {
                    si = mIPackageManager.getServiceInfo(cname,
                            PackageManager.MATCH_DIRECT_BOOT_UNAWARE, wallpaper.userId);
                } catch (RemoteException ignored) {
                }

                if (si == null) {
                    Slog.w(TAG, "Failure starting previous wallpaper; clearing");
                    clearWallpaperLocked(false, FLAG_SYSTEM, wallpaper.userId, reply);
                } else {
                    Slog.w(TAG, "Wallpaper isn't direct boot aware; using fallback until unlocked");
                    // We might end up persisting the current wallpaper data
                    // while locked, so pretend like the component was actually
                    // bound into place
                    wallpaper.wallpaperComponent = wallpaper.nextWallpaperComponent;
                    final WallpaperData fallback = new WallpaperData(wallpaper.userId,
                            WALLPAPER_LOCK_ORIG, WALLPAPER_LOCK_CROP);
                    ensureSaneWallpaperData(fallback);
                    bindWallpaperComponentLocked(mImageWallpaper, true, false, fallback, reply);
                    mWaitingForUnlock = true;
                }
            }
        }
    }

    @Override
    public void clearWallpaper(String callingPackage, int which, int userId) {
        if (DEBUG) Slog.v(TAG, "clearWallpaper");
        checkPermission(android.Manifest.permission.SET_WALLPAPER);
        if (!isWallpaperSupported(callingPackage) || !isSetWallpaperAllowed(callingPackage)) {
            return;
        }
        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, true, "clearWallpaper", null);

        WallpaperData data = null;
        synchronized (mLock) {
            clearWallpaperLocked(false, which, userId, null);

            if (which == FLAG_LOCK) {
                data = mLockWallpaperMap.get(userId);
            }
            if (which == FLAG_SYSTEM || data == null) {
                data = mWallpaperMap.get(userId);
            }
        }

        // When clearing a wallpaper, broadcast new valid colors
        if (data != null) {
            notifyWallpaperColorsChanged(data, which);
        }
    }

    void clearWallpaperLocked(boolean defaultFailed, int which, int userId, IRemoteCallback reply) {
        if (which != FLAG_SYSTEM && which != FLAG_LOCK) {
            throw new IllegalArgumentException("Must specify exactly one kind of wallpaper to clear");
        }

        WallpaperData wallpaper = null;
        if (which == FLAG_LOCK) {
            wallpaper = mLockWallpaperMap.get(userId);
            if (wallpaper == null) {
                // It's already gone; we're done.
                if (DEBUG) {
                    Slog.i(TAG, "Lock wallpaper already cleared");
                }
                return;
            }
        } else {
            wallpaper = mWallpaperMap.get(userId);
            if (wallpaper == null) {
                // Might need to bring it in the first time to establish our rewrite
                loadSettingsLocked(userId, false);
                wallpaper = mWallpaperMap.get(userId);
            }
        }
        if (wallpaper == null) {
            return;
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            if (wallpaper.wallpaperFile.exists()) {
                wallpaper.wallpaperFile.delete();
                wallpaper.cropFile.delete();
                if (which == FLAG_LOCK) {
                    mLockWallpaperMap.remove(userId);
                    final IWallpaperManagerCallback cb = mKeyguardListener;
                    if (cb != null) {
                        if (DEBUG) {
                            Slog.i(TAG, "Notifying keyguard of lock wallpaper clear");
                        }
                        try {
                            cb.onWallpaperChanged();
                        } catch (RemoteException e) {
                            // Oh well it went away; no big deal
                        }
                    }
                    saveSettingsLocked(userId);
                    return;
                }
            }

            RuntimeException e = null;
            try {
                wallpaper.primaryColors = null;
                wallpaper.imageWallpaperPending = false;
                if (userId != mCurrentUserId) return;
                if (bindWallpaperComponentLocked(defaultFailed
                        ? mImageWallpaper
                                : null, true, false, wallpaper, reply)) {
                    return;
                }
            } catch (IllegalArgumentException e1) {
                e = e1;
            }

            // This can happen if the default wallpaper component doesn't
            // exist.  This should be a system configuration problem, but
            // let's not let it crash the system and just live with no
            // wallpaper.
            Slog.e(TAG, "Default wallpaper component not found!", e);
            clearWallpaperComponentLocked(wallpaper);
            if (reply != null) {
                try {
                    reply.sendResult(null);
                } catch (RemoteException e1) {
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public boolean hasNamedWallpaper(String name) {
        synchronized (mLock) {
            List<UserInfo> users;
            long ident = Binder.clearCallingIdentity();
            try {
                users = ((UserManager) mContext.getSystemService(Context.USER_SERVICE)).getUsers();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            for (UserInfo user: users) {
                // ignore managed profiles
                if (user.isManagedProfile()) {
                    continue;
                }
                WallpaperData wd = mWallpaperMap.get(user.id);
                if (wd == null) {
                    // User hasn't started yet, so load her settings to peek at the wallpaper
                    loadSettingsLocked(user.id, false);
                    wd = mWallpaperMap.get(user.id);
                }
                if (wd != null && name.equals(wd.name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Point getDefaultDisplaySize() {
        Point p = new Point();
        WindowManager wm = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);
        Display d = wm.getDefaultDisplay();
        d.getRealSize(p);
        return p;
    }

    public void setDimensionHints(int width, int height, String callingPackage)
            throws RemoteException {
        checkPermission(android.Manifest.permission.SET_WALLPAPER_HINTS);
        if (!isWallpaperSupported(callingPackage)) {
            return;
        }

        // Make sure both width and height are not larger than max texture size.
        width = Math.min(width, GLHelper.getMaxTextureSize());
        height = Math.min(height, GLHelper.getMaxTextureSize());

        synchronized (mLock) {
            int userId = UserHandle.getCallingUserId();
            WallpaperData wallpaper = getWallpaperSafeLocked(userId, FLAG_SYSTEM);
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("width and height must be > 0");
            }
            // Make sure it is at least as large as the display.
            Point displaySize = getDefaultDisplaySize();
            width = Math.max(width, displaySize.x);
            height = Math.max(height, displaySize.y);

            if (width != wallpaper.width || height != wallpaper.height) {
                wallpaper.width = width;
                wallpaper.height = height;
                saveSettingsLocked(userId);
                if (mCurrentUserId != userId) return; // Don't change the properties now
                if (wallpaper.connection != null) {
                    if (wallpaper.connection.mEngine != null) {
                        try {
                            wallpaper.connection.mEngine.setDesiredSize(
                                    width, height);
                        } catch (RemoteException e) {
                        }
                        notifyCallbacksLocked(wallpaper);
                    } else if (wallpaper.connection.mService != null) {
                        // We've attached to the service but the engine hasn't attached back to us
                        // yet. This means it will be created with the previous dimensions, so we
                        // need to update it to the new dimensions once it attaches.
                        wallpaper.connection.mDimensionsChanged = true;
                    }
                }
            }
        }
    }

    public int getWidthHint() throws RemoteException {
        synchronized (mLock) {
            WallpaperData wallpaper = mWallpaperMap.get(UserHandle.getCallingUserId());
            if (wallpaper != null) {
                return wallpaper.width;
            } else {
                return 0;
            }
        }
    }

    public int getHeightHint() throws RemoteException {
        synchronized (mLock) {
            WallpaperData wallpaper = mWallpaperMap.get(UserHandle.getCallingUserId());
            if (wallpaper != null) {
                return wallpaper.height;
            } else {
                return 0;
            }
        }
    }

    public void setDisplayPadding(Rect padding, String callingPackage) {
        checkPermission(android.Manifest.permission.SET_WALLPAPER_HINTS);
        if (!isWallpaperSupported(callingPackage)) {
            return;
        }
        synchronized (mLock) {
            int userId = UserHandle.getCallingUserId();
            WallpaperData wallpaper = getWallpaperSafeLocked(userId, FLAG_SYSTEM);
            if (padding.left < 0 || padding.top < 0 || padding.right < 0 || padding.bottom < 0) {
                throw new IllegalArgumentException("padding must be positive: " + padding);
            }

            if (!padding.equals(wallpaper.padding)) {
                wallpaper.padding.set(padding);
                saveSettingsLocked(userId);
                if (mCurrentUserId != userId) return; // Don't change the properties now
                if (wallpaper.connection != null) {
                    if (wallpaper.connection.mEngine != null) {
                        try {
                            wallpaper.connection.mEngine.setDisplayPadding(padding);
                        } catch (RemoteException e) {
                        }
                        notifyCallbacksLocked(wallpaper);
                    } else if (wallpaper.connection.mService != null) {
                        // We've attached to the service but the engine hasn't attached back to us
                        // yet. This means it will be created with the previous dimensions, so we
                        // need to update it to the new dimensions once it attaches.
                        wallpaper.connection.mPaddingChanged = true;
                    }
                }
            }
        }
    }

    private void enforceCallingOrSelfPermissionAndAppOp(String permission, final String callingPkg,
            final int callingUid, String message) {
        mContext.enforceCallingOrSelfPermission(permission, message);

        final String opName = AppOpsManager.permissionToOp(permission);
        if (opName != null) {
            final int appOpMode = mAppOpsManager.noteOp(opName, callingUid, callingPkg);
            if (appOpMode != AppOpsManager.MODE_ALLOWED) {
                throw new SecurityException(
                        message + ": " + callingPkg + " is not allowed to " + permission);
            }
        }
    }

    @Override
    public ParcelFileDescriptor getWallpaper(String callingPkg, IWallpaperManagerCallback cb,
            final int which, Bundle outParams, int wallpaperUserId) {
        final int hasPrivilege = mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.READ_WALLPAPER_INTERNAL);
        if (hasPrivilege != PackageManager.PERMISSION_GRANTED) {
            enforceCallingOrSelfPermissionAndAppOp(android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    callingPkg, Binder.getCallingUid(), "read wallpaper");
        }

        wallpaperUserId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), wallpaperUserId, false, true, "getWallpaper", null);

        if (which != FLAG_SYSTEM && which != FLAG_LOCK) {
            throw new IllegalArgumentException("Must specify exactly one kind of wallpaper to read");
        }

        synchronized (mLock) {
            final SparseArray<WallpaperData> whichSet =
                    (which == FLAG_LOCK) ? mLockWallpaperMap : mWallpaperMap;
            WallpaperData wallpaper = whichSet.get(wallpaperUserId);
            if (wallpaper == null) {
                // There is no established wallpaper imagery of this type (expected
                // only for lock wallpapers; a system WallpaperData is established at
                // user switch)
                return null;
            }
            try {
                if (outParams != null) {
                    outParams.putInt("width", wallpaper.width);
                    outParams.putInt("height", wallpaper.height);
                }
                if (cb != null) {
                    wallpaper.callbacks.register(cb);
                }
                if (!wallpaper.cropFile.exists()) {
                    return null;
                }
                return ParcelFileDescriptor.open(wallpaper.cropFile, MODE_READ_ONLY);
            } catch (FileNotFoundException e) {
                /* Shouldn't happen as we check to see if the file exists */
                Slog.w(TAG, "Error getting wallpaper", e);
            }
            return null;
        }
    }

    @Override
    public WallpaperInfo getWallpaperInfo(int userId) {
        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, true, "getWallpaperInfo", null);
        synchronized (mLock) {
            WallpaperData wallpaper = mWallpaperMap.get(userId);
            if (wallpaper != null && wallpaper.connection != null) {
                return wallpaper.connection.mInfo;
            }
            return null;
        }
    }

    @Override
    public int getWallpaperIdForUser(int which, int userId) {
        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, true, "getWallpaperIdForUser", null);

        if (which != FLAG_SYSTEM && which != FLAG_LOCK) {
            throw new IllegalArgumentException("Must specify exactly one kind of wallpaper");
        }

        final SparseArray<WallpaperData> map =
                (which == FLAG_LOCK) ? mLockWallpaperMap : mWallpaperMap;
        synchronized (mLock) {
            WallpaperData wallpaper = map.get(userId);
            if (wallpaper != null) {
                return wallpaper.wallpaperId;
            }
        }
        return -1;
    }

    @Override
    public void registerWallpaperColorsCallback(IWallpaperManagerCallback cb, int userId) {
        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, true, true, "registerWallpaperColorsCallback", null);
        synchronized (mLock) {
            RemoteCallbackList<IWallpaperManagerCallback> userColorsChangedListeners =
                    mColorsChangedListeners.get(userId);
            if (userColorsChangedListeners == null) {
                userColorsChangedListeners = new RemoteCallbackList<>();
                mColorsChangedListeners.put(userId, userColorsChangedListeners);
            }
            userColorsChangedListeners.register(cb);
        }
    }

    @Override
    public void unregisterWallpaperColorsCallback(IWallpaperManagerCallback cb, int userId) {
        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, true, true, "unregisterWallpaperColorsCallback", null);
        synchronized (mLock) {
            final RemoteCallbackList<IWallpaperManagerCallback> userColorsChangedListeners =
                    mColorsChangedListeners.get(userId);
            if (userColorsChangedListeners != null) {
                userColorsChangedListeners.unregister(cb);
            }
        }
    }

    public void setInAmbientMode(boolean inAmbienMode, boolean animated) {
        final IWallpaperEngine engine;
        synchronized (mLock) {
            mInAmbientMode = inAmbienMode;
            final WallpaperData data = mWallpaperMap.get(mCurrentUserId);
            if (data != null && data.connection != null && data.connection.mInfo != null
                    && data.connection.mInfo.getSupportsAmbientMode()) {
                engine = data.connection.mEngine;
            } else {
                engine = null;
            }
        }

        if (engine != null) {
            try {
                engine.setInAmbientMode(inAmbienMode, animated);
            } catch (RemoteException e) {
                // Cannot talk to wallpaper engine.
            }
        }
    }

    @Override
    public boolean setLockWallpaperCallback(IWallpaperManagerCallback cb) {
        checkPermission(android.Manifest.permission.INTERNAL_SYSTEM_WINDOW);
        synchronized (mLock) {
            mKeyguardListener = cb;
        }
        return true;
    }

    @Override
    public WallpaperColors getWallpaperColors(int which, int userId) throws RemoteException {
        if (which != FLAG_LOCK && which != FLAG_SYSTEM) {
            throw new IllegalArgumentException("which should be either FLAG_LOCK or FLAG_SYSTEM");
        }
        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, false, true, "getWallpaperColors", null);

        WallpaperData wallpaperData = null;
        boolean shouldExtract;

        synchronized (mLock) {
            if (which == FLAG_LOCK) {
                wallpaperData = mLockWallpaperMap.get(userId);
            }

            // Try to get the system wallpaper anyway since it might
            // also be the lock screen wallpaper
            if (wallpaperData == null) {
                wallpaperData = mWallpaperMap.get(userId);
            }

            if (wallpaperData == null) {
                return null;
            }
            shouldExtract = wallpaperData.primaryColors == null;
        }

        if (shouldExtract) {
            extractColors(wallpaperData);
        }

        synchronized (mLock) {
            return getThemeColorsLocked(wallpaperData.primaryColors);
        }
    }

    @Override
    public ParcelFileDescriptor setWallpaper(String name, String callingPackage,
            Rect cropHint, boolean allowBackup, Bundle extras, int which,
            IWallpaperManagerCallback completion, int userId) {
        userId = ActivityManager.handleIncomingUser(getCallingPid(), getCallingUid(), userId,
                false /* all */, true /* full */, "changing wallpaper", null /* pkg */);
        checkPermission(android.Manifest.permission.SET_WALLPAPER);

        if ((which & (FLAG_LOCK|FLAG_SYSTEM)) == 0) {
            final String msg = "Must specify a valid wallpaper category to set";
            Slog.e(TAG, msg);
            throw new IllegalArgumentException(msg);
        }

        if (!isWallpaperSupported(callingPackage) || !isSetWallpaperAllowed(callingPackage)) {
            return null;
        }

        // "null" means the no-op crop, preserving the full input image
        if (cropHint == null) {
            cropHint = new Rect(0, 0, 0, 0);
        } else {
            if (cropHint.isEmpty()
                    || cropHint.left < 0
                    || cropHint.top < 0) {
                throw new IllegalArgumentException("Invalid crop rect supplied: " + cropHint);
            }
        }

        synchronized (mLock) {
            if (DEBUG) Slog.v(TAG, "setWallpaper which=0x" + Integer.toHexString(which));
            WallpaperData wallpaper;

            /* If we're setting system but not lock, and lock is currently sharing the system
             * wallpaper, we need to migrate that image over to being lock-only before
             * the caller here writes new bitmap data.
             */
            if (which == FLAG_SYSTEM && mLockWallpaperMap.get(userId) == null) {
                if (DEBUG) {
                    Slog.i(TAG, "Migrating system->lock to preserve");
                }
                migrateSystemToLockWallpaperLocked(userId);
            }

            wallpaper = getWallpaperSafeLocked(userId, which);
            final long ident = Binder.clearCallingIdentity();
            try {
                ParcelFileDescriptor pfd = updateWallpaperBitmapLocked(name, wallpaper, extras);
                if (pfd != null) {
                    wallpaper.imageWallpaperPending = true;
                    wallpaper.whichPending = which;
                    wallpaper.setComplete = completion;
                    wallpaper.cropHint.set(cropHint);
                    wallpaper.allowBackup = allowBackup;
                }
                return pfd;
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    private void migrateSystemToLockWallpaperLocked(int userId) {
        WallpaperData sysWP = mWallpaperMap.get(userId);
        if (sysWP == null) {
            if (DEBUG) {
                Slog.i(TAG, "No system wallpaper?  Not tracking for lock-only");
            }
            return;
        }

        // We know a-priori that there is no lock-only wallpaper currently
        WallpaperData lockWP = new WallpaperData(userId,
                WALLPAPER_LOCK_ORIG, WALLPAPER_LOCK_CROP);
        lockWP.wallpaperId = sysWP.wallpaperId;
        lockWP.cropHint.set(sysWP.cropHint);
        lockWP.width = sysWP.width;
        lockWP.height = sysWP.height;
        lockWP.allowBackup = sysWP.allowBackup;
        lockWP.primaryColors = sysWP.primaryColors;

        // Migrate the bitmap files outright; no need to copy
        try {
            Os.rename(sysWP.wallpaperFile.getAbsolutePath(), lockWP.wallpaperFile.getAbsolutePath());
            Os.rename(sysWP.cropFile.getAbsolutePath(), lockWP.cropFile.getAbsolutePath());
        } catch (ErrnoException e) {
            Slog.e(TAG, "Can't migrate system wallpaper: " + e.getMessage());
            lockWP.wallpaperFile.delete();
            lockWP.cropFile.delete();
            return;
        }

        mLockWallpaperMap.put(userId, lockWP);
    }

    ParcelFileDescriptor updateWallpaperBitmapLocked(String name, WallpaperData wallpaper,
            Bundle extras) {
        if (name == null) name = "";
        try {
            File dir = getWallpaperDir(wallpaper.userId);
            if (!dir.exists()) {
                dir.mkdir();
                FileUtils.setPermissions(
                        dir.getPath(),
                        FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IXOTH,
                        -1, -1);
            }
            ParcelFileDescriptor fd = ParcelFileDescriptor.open(wallpaper.wallpaperFile,
                    MODE_CREATE|MODE_READ_WRITE|MODE_TRUNCATE);
            if (!SELinux.restorecon(wallpaper.wallpaperFile)) {
                return null;
            }
            wallpaper.name = name;
            wallpaper.wallpaperId = makeWallpaperIdLocked();
            if (extras != null) {
                extras.putInt(WallpaperManager.EXTRA_NEW_WALLPAPER_ID, wallpaper.wallpaperId);
            }
            // Nullify field to require new computation
            wallpaper.primaryColors = null;
            if (DEBUG) {
                Slog.v(TAG, "updateWallpaperBitmapLocked() : id=" + wallpaper.wallpaperId
                        + " name=" + name + " file=" + wallpaper.wallpaperFile.getName());
            }
            return fd;
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "Error setting wallpaper", e);
        }
        return null;
    }

    @Override
    public void setWallpaperComponentChecked(ComponentName name, String callingPackage,
            int userId) {

        if (isWallpaperSupported(callingPackage) && isSetWallpaperAllowed(callingPackage)) {
            setWallpaperComponent(name, userId);
        }
    }

    // ToDo: Remove this version of the function
    @Override
    public void setWallpaperComponent(ComponentName name) {
        setWallpaperComponent(name, UserHandle.getCallingUserId());
    }

    private void setWallpaperComponent(ComponentName name, int userId) {
        userId = ActivityManager.handleIncomingUser(getCallingPid(), getCallingUid(), userId,
                false /* all */, true /* full */, "changing live wallpaper", null /* pkg */);
        checkPermission(android.Manifest.permission.SET_WALLPAPER_COMPONENT);

        int which = FLAG_SYSTEM;
        boolean shouldNotifyColors = false;
        WallpaperData wallpaper;

        synchronized (mLock) {
            if (DEBUG) Slog.v(TAG, "setWallpaperComponent name=" + name);
            wallpaper = mWallpaperMap.get(userId);
            if (wallpaper == null) {
                throw new IllegalStateException("Wallpaper not yet initialized for user " + userId);
            }
            final long ident = Binder.clearCallingIdentity();

            // Live wallpapers can't be specified for keyguard.  If we're using a static
            // system+lock image currently, migrate the system wallpaper to be a lock-only
            // image as part of making a different live component active as the system
            // wallpaper.
            if (mImageWallpaper.equals(wallpaper.wallpaperComponent)) {
                if (mLockWallpaperMap.get(userId) == null) {
                    // We're using the static imagery and there is no lock-specific image in place,
                    // therefore it's a shared system+lock image that we need to migrate.
                    migrateSystemToLockWallpaperLocked(userId);
                }
            }

            // New live wallpaper is also a lock wallpaper if nothing is set
            if (mLockWallpaperMap.get(userId) == null) {
                which |= FLAG_LOCK;
            }

            try {
                wallpaper.imageWallpaperPending = false;
                boolean same = changingToSame(name, wallpaper);
                if (bindWallpaperComponentLocked(name, false, true, wallpaper, null)) {
                    if (!same) {
                        wallpaper.primaryColors = null;
                    }
                    wallpaper.wallpaperId = makeWallpaperIdLocked();
                    notifyCallbacksLocked(wallpaper);
                    shouldNotifyColors = true;
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        if (shouldNotifyColors) {
            notifyWallpaperColorsChanged(wallpaper, which);
        }
    }

    private boolean changingToSame(ComponentName componentName, WallpaperData wallpaper) {
        if (wallpaper.connection != null) {
            if (wallpaper.wallpaperComponent == null) {
                if (componentName == null) {
                    if (DEBUG) Slog.v(TAG, "changingToSame: still using default");
                    // Still using default wallpaper.
                    return true;
                }
            } else if (wallpaper.wallpaperComponent.equals(componentName)) {
                // Changing to same wallpaper.
                if (DEBUG) Slog.v(TAG, "same wallpaper");
                return true;
            }
        }
        return false;
    }

    boolean bindWallpaperComponentLocked(ComponentName componentName, boolean force,
            boolean fromUser, WallpaperData wallpaper, IRemoteCallback reply) {
        if (DEBUG_LIVE) {
            Slog.v(TAG, "bindWallpaperComponentLocked: componentName=" + componentName);
        }
        // Has the component changed?
        if (!force && changingToSame(componentName, wallpaper)) {
            return true;
        }

        try {
            if (componentName == null) {
                componentName = mDefaultWallpaperComponent;
                if (componentName == null) {
                    // Fall back to static image wallpaper
                    componentName = mImageWallpaper;
                    //clearWallpaperComponentLocked();
                    //return;
                    if (DEBUG_LIVE) Slog.v(TAG, "No default component; using image wallpaper");
                }
            }
            int serviceUserId = wallpaper.userId;
            ServiceInfo si = mIPackageManager.getServiceInfo(componentName,
                    PackageManager.GET_META_DATA | PackageManager.GET_PERMISSIONS, serviceUserId);
            if (si == null) {
                // The wallpaper component we're trying to use doesn't exist
                Slog.w(TAG, "Attempted wallpaper " + componentName + " is unavailable");
                return false;
            }
            if (!android.Manifest.permission.BIND_WALLPAPER.equals(si.permission)) {
                String msg = "Selected service does not require "
                        + android.Manifest.permission.BIND_WALLPAPER
                        + ": " + componentName;
                if (fromUser) {
                    throw new SecurityException(msg);
                }
                Slog.w(TAG, msg);
                return false;
            }

            WallpaperInfo wi = null;

            Intent intent = new Intent(WallpaperService.SERVICE_INTERFACE);
            if (componentName != null && !componentName.equals(mImageWallpaper)) {
                // Make sure the selected service is actually a wallpaper service.
                List<ResolveInfo> ris =
                        mIPackageManager.queryIntentServices(intent,
                                intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                                PackageManager.GET_META_DATA, serviceUserId).getList();
                for (int i=0; i<ris.size(); i++) {
                    ServiceInfo rsi = ris.get(i).serviceInfo;
                    if (rsi.name.equals(si.name) &&
                            rsi.packageName.equals(si.packageName)) {
                        try {
                            wi = new WallpaperInfo(mContext, ris.get(i));
                        } catch (XmlPullParserException e) {
                            if (fromUser) {
                                throw new IllegalArgumentException(e);
                            }
                            Slog.w(TAG, e);
                            return false;
                        } catch (IOException e) {
                            if (fromUser) {
                                throw new IllegalArgumentException(e);
                            }
                            Slog.w(TAG, e);
                            return false;
                        }
                        break;
                    }
                }
                if (wi == null) {
                    String msg = "Selected service is not a wallpaper: "
                            + componentName;
                    if (fromUser) {
                        throw new SecurityException(msg);
                    }
                    Slog.w(TAG, msg);
                    return false;
                }
            }
            // remove window token and unbind first, then bind and add window token
            if (wallpaper.userId == mCurrentUserId && mLastWallpaper != null) {
                detachWallpaperLocked(mLastWallpaper);
            }
            // Bind the service!
            if (DEBUG) Slog.v(TAG, "Binding to:" + componentName);
            WallpaperConnection newConn = new WallpaperConnection(wi, wallpaper);
            intent.setComponent(componentName);
            intent.putExtra(Intent.EXTRA_CLIENT_LABEL,
                    com.android.internal.R.string.wallpaper_binding_label);
            intent.putExtra(Intent.EXTRA_CLIENT_INTENT, PendingIntent.getActivityAsUser(
                    mContext, 0,
                    Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER),
                            mContext.getText(com.android.internal.R.string.chooser_wallpaper)),
                    0, null, new UserHandle(serviceUserId)));
            if (!mContext.bindServiceAsUser(intent, newConn,
                    Context.BIND_AUTO_CREATE | Context.BIND_SHOWING_UI
                            | Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE,
                    new UserHandle(serviceUserId))) {
                String msg = "Unable to bind service: "
                        + componentName;
                if (fromUser) {
                    throw new IllegalArgumentException(msg);
                }
                Slog.w(TAG, msg);
                return false;
            }
            // Adding window token
            wallpaper.wallpaperComponent = componentName;
            wallpaper.connection = newConn;
            newConn.mReply = reply;
            try {
                if (wallpaper.userId == mCurrentUserId) {
                    if (DEBUG)
                        Slog.v(TAG, "Adding window token: " + newConn.mToken);
                    mIWindowManager.addWindowToken(newConn.mToken, TYPE_WALLPAPER, DEFAULT_DISPLAY);
                    mLastWallpaper = wallpaper;
                }
            } catch (RemoteException e) {
            }
        } catch (RemoteException e) {
            String msg = "Remote exception for " + componentName + "\n" + e;
            if (fromUser) {
                throw new IllegalArgumentException(msg);
            }
            Slog.w(TAG, msg);
            return false;
        }
        return true;
    }

    void detachWallpaperLocked(WallpaperData wallpaper) {
        if (wallpaper.connection != null) {
            if (wallpaper.connection.mReply != null) {
                try {
                    wallpaper.connection.mReply.sendResult(null);
                } catch (RemoteException e) {
                }
                wallpaper.connection.mReply = null;
            }
            if (wallpaper.connection.mEngine != null) {
                try {
                    wallpaper.connection.mEngine.destroy();
                } catch (RemoteException e) {
                }
            }
            mContext.unbindService(wallpaper.connection);
            try {
                if (DEBUG)
                    Slog.v(TAG, "Removing window token: " + wallpaper.connection.mToken);
                mIWindowManager.removeWindowToken(wallpaper.connection.mToken, DEFAULT_DISPLAY);
            } catch (RemoteException e) {
            }
            wallpaper.connection.mService = null;
            wallpaper.connection.mEngine = null;
            wallpaper.connection = null;
        }
    }

    void clearWallpaperComponentLocked(WallpaperData wallpaper) {
        wallpaper.wallpaperComponent = null;
        detachWallpaperLocked(wallpaper);
    }

    void attachServiceLocked(WallpaperConnection conn, WallpaperData wallpaper) {
        try {
            conn.mService.attach(conn, conn.mToken,
                    TYPE_WALLPAPER, false,
                    wallpaper.width, wallpaper.height, wallpaper.padding);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed attaching wallpaper; clearing", e);
            if (!wallpaper.wallpaperUpdating) {
                bindWallpaperComponentLocked(null, false, false, wallpaper, null);
            }
        }
    }

    private void notifyCallbacksLocked(WallpaperData wallpaper) {
        final int n = wallpaper.callbacks.beginBroadcast();
        for (int i = 0; i < n; i++) {
            try {
                wallpaper.callbacks.getBroadcastItem(i).onWallpaperChanged();
            } catch (RemoteException e) {

                // The RemoteCallbackList will take care of removing
                // the dead object for us.
            }
        }
        wallpaper.callbacks.finishBroadcast();

        final Intent intent = new Intent(Intent.ACTION_WALLPAPER_CHANGED);
        mContext.sendBroadcastAsUser(intent, new UserHandle(mCurrentUserId));
    }

    private void checkPermission(String permission) {
        if (PackageManager.PERMISSION_GRANTED!= mContext.checkCallingOrSelfPermission(permission)) {
            throw new SecurityException("Access denied to process: " + Binder.getCallingPid()
                    + ", must have permission " + permission);
        }
    }

    /**
     * Certain user types do not support wallpapers (e.g. managed profiles). The check is
     * implemented through through the OP_WRITE_WALLPAPER AppOp.
     */
    public boolean isWallpaperSupported(String callingPackage) {
        return mAppOpsManager.checkOpNoThrow(AppOpsManager.OP_WRITE_WALLPAPER, Binder.getCallingUid(),
                callingPackage) == AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public boolean isSetWallpaperAllowed(String callingPackage) {
        final PackageManager pm = mContext.getPackageManager();
        String[] uidPackages = pm.getPackagesForUid(Binder.getCallingUid());
        boolean uidMatchPackage = Arrays.asList(uidPackages).contains(callingPackage);
        if (!uidMatchPackage) {
            return false;   // callingPackage was faked.
        }

        final DevicePolicyManager dpm = mContext.getSystemService(DevicePolicyManager.class);
        if (dpm.isDeviceOwnerApp(callingPackage) || dpm.isProfileOwnerApp(callingPackage)) {
            return true;
        }
        final UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        return !um.hasUserRestriction(UserManager.DISALLOW_SET_WALLPAPER);
    }

    @Override
    public boolean isWallpaperBackupEligible(int which, int userId) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Only the system may call isWallpaperBackupEligible");
        }

        WallpaperData wallpaper = (which == FLAG_LOCK)
                ? mLockWallpaperMap.get(userId)
                : mWallpaperMap.get(userId);
        return (wallpaper != null) ? wallpaper.allowBackup : false;
    }

    private static JournaledFile makeJournaledFile(int userId) {
        final String base = new File(getWallpaperDir(userId), WALLPAPER_INFO).getAbsolutePath();
        return new JournaledFile(new File(base), new File(base + ".tmp"));
    }

    private void saveSettingsLocked(int userId) {
        JournaledFile journal = makeJournaledFile(userId);
        FileOutputStream fstream = null;
        BufferedOutputStream stream = null;
        try {
            XmlSerializer out = new FastXmlSerializer();
            fstream = new FileOutputStream(journal.chooseForWrite(), false);
            stream = new BufferedOutputStream(fstream);
            out.setOutput(stream, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);

            WallpaperData wallpaper;

            wallpaper = mWallpaperMap.get(userId);
            if (wallpaper != null) {
                writeWallpaperAttributes(out, "wp", wallpaper);
            }
            wallpaper = mLockWallpaperMap.get(userId);
            if (wallpaper != null) {
                writeWallpaperAttributes(out, "kwp", wallpaper);
            }

            out.endDocument();

            stream.flush(); // also flushes fstream
            FileUtils.sync(fstream);
            stream.close(); // also closes fstream
            journal.commit();
        } catch (IOException e) {
            IoUtils.closeQuietly(stream);
            journal.rollback();
        }
    }

    private void writeWallpaperAttributes(XmlSerializer out, String tag, WallpaperData wallpaper)
            throws IllegalArgumentException, IllegalStateException, IOException {
        if (DEBUG) {
            Slog.v(TAG, "writeWallpaperAttributes id=" + wallpaper.wallpaperId);
        }
        out.startTag(null, tag);
        out.attribute(null, "id", Integer.toString(wallpaper.wallpaperId));
        out.attribute(null, "width", Integer.toString(wallpaper.width));
        out.attribute(null, "height", Integer.toString(wallpaper.height));

        out.attribute(null, "cropLeft", Integer.toString(wallpaper.cropHint.left));
        out.attribute(null, "cropTop", Integer.toString(wallpaper.cropHint.top));
        out.attribute(null, "cropRight", Integer.toString(wallpaper.cropHint.right));
        out.attribute(null, "cropBottom", Integer.toString(wallpaper.cropHint.bottom));

        if (wallpaper.padding.left != 0) {
            out.attribute(null, "paddingLeft", Integer.toString(wallpaper.padding.left));
        }
        if (wallpaper.padding.top != 0) {
            out.attribute(null, "paddingTop", Integer.toString(wallpaper.padding.top));
        }
        if (wallpaper.padding.right != 0) {
            out.attribute(null, "paddingRight", Integer.toString(wallpaper.padding.right));
        }
        if (wallpaper.padding.bottom != 0) {
            out.attribute(null, "paddingBottom", Integer.toString(wallpaper.padding.bottom));
        }

        if (wallpaper.primaryColors != null) {
            int colorsCount = wallpaper.primaryColors.getMainColors().size();
            out.attribute(null, "colorsCount", Integer.toString(colorsCount));
            if (colorsCount > 0) {
                for (int i = 0; i < colorsCount; i++) {
                    final Color wc = wallpaper.primaryColors.getMainColors().get(i);
                    out.attribute(null, "colorValue"+i, Integer.toString(wc.toArgb()));
                }
            }
            out.attribute(null, "colorHints",
                    Integer.toString(wallpaper.primaryColors.getColorHints()));
        }

        out.attribute(null, "name", wallpaper.name);
        if (wallpaper.wallpaperComponent != null
                && !wallpaper.wallpaperComponent.equals(mImageWallpaper)) {
            out.attribute(null, "component",
                    wallpaper.wallpaperComponent.flattenToShortString());
        }

        if (wallpaper.allowBackup) {
            out.attribute(null, "backup", "true");
        }

        out.endTag(null, tag);
    }

    private void migrateFromOld() {
        // Pre-N, what existed is the one we're now using as the display crop
        File preNWallpaper = new File(getWallpaperDir(0), WALLPAPER_CROP);
        // In the very-long-ago, imagery lived with the settings app
        File originalWallpaper = new File(WallpaperBackupHelper.WALLPAPER_IMAGE_KEY);
        File newWallpaper = new File(getWallpaperDir(0), WALLPAPER);

        // Migrations from earlier wallpaper image storage schemas
        if (preNWallpaper.exists()) {
            if (!newWallpaper.exists()) {
                // we've got the 'wallpaper' crop file but not the nominal source image,
                // so do the simple "just take everything" straight copy of legacy data
                if (DEBUG) {
                    Slog.i(TAG, "Migrating wallpaper schema");
                }
                FileUtils.copyFile(preNWallpaper, newWallpaper);
            } // else we're in the usual modern case: both source & crop exist
        } else if (originalWallpaper.exists()) {
            // VERY old schema; make sure things exist and are in the right place
            if (DEBUG) {
                Slog.i(TAG, "Migrating antique wallpaper schema");
            }
            File oldInfo = new File(WallpaperBackupHelper.WALLPAPER_INFO_KEY);
            if (oldInfo.exists()) {
                File newInfo = new File(getWallpaperDir(0), WALLPAPER_INFO);
                oldInfo.renameTo(newInfo);
            }

            FileUtils.copyFile(originalWallpaper, preNWallpaper);
            originalWallpaper.renameTo(newWallpaper);
        }
    }

    private int getAttributeInt(XmlPullParser parser, String name, int defValue) {
        String value = parser.getAttributeValue(null, name);
        if (value == null) {
            return defValue;
        }
        return Integer.parseInt(value);
    }

    /**
     * Sometimes it is expected the wallpaper map may not have a user's data.  E.g. This could
     * happen during user switch.  The async user switch observer may not have received
     * the event yet.  We use this safe method when we don't care about this ordering and just
     * want to update the data.  The data is going to be applied when the user switch observer
     * is eventually executed.
     *
     * Important: this method loads settings to initialize the given user's wallpaper data if
     * there is no current in-memory state.
     */
    private WallpaperData getWallpaperSafeLocked(int userId, int which) {
        // We're setting either just system (work with the system wallpaper),
        // both (also work with the system wallpaper), or just the lock
        // wallpaper (update against the existing lock wallpaper if any).
        // Combined or just-system operations use the 'system' WallpaperData
        // for this use; lock-only operations use the dedicated one.
        final SparseArray<WallpaperData> whichSet =
                (which == FLAG_LOCK) ? mLockWallpaperMap : mWallpaperMap;
        WallpaperData wallpaper = whichSet.get(userId);
        if (wallpaper == null) {
            // common case, this is the first lookup post-boot of the system or
            // unified lock, so we bring up the saved state lazily now and recheck.
            loadSettingsLocked(userId, false);
            wallpaper = whichSet.get(userId);
            // if it's still null here, this is a lock-only operation and there is not
            // yet a lock-only wallpaper set for this user, so we need to establish
            // it now.
            if (wallpaper == null) {
                if (which == FLAG_LOCK) {
                    wallpaper = new WallpaperData(userId,
                            WALLPAPER_LOCK_ORIG, WALLPAPER_LOCK_CROP);
                    mLockWallpaperMap.put(userId, wallpaper);
                    ensureSaneWallpaperData(wallpaper);
                } else {
                    // sanity fallback: we're in bad shape, but establishing a known
                    // valid system+lock WallpaperData will keep us from dying.
                    Slog.wtf(TAG, "Didn't find wallpaper in non-lock case!");
                    wallpaper = new WallpaperData(userId, WALLPAPER, WALLPAPER_CROP);
                    mWallpaperMap.put(userId, wallpaper);
                    ensureSaneWallpaperData(wallpaper);
                }
            }
        }
        return wallpaper;
    }

    private void loadSettingsLocked(int userId, boolean keepDimensionHints) {
        JournaledFile journal = makeJournaledFile(userId);
        FileInputStream stream = null;
        File file = journal.chooseForRead();

        WallpaperData wallpaper = mWallpaperMap.get(userId);
        if (wallpaper == null) {
            // Do this once per boot
            migrateFromOld();

            wallpaper = new WallpaperData(userId, WALLPAPER, WALLPAPER_CROP);
            wallpaper.allowBackup = true;
            mWallpaperMap.put(userId, wallpaper);
            if (!wallpaper.cropExists()) {
                if (wallpaper.sourceExists()) {
                    generateCrop(wallpaper);
                } else {
                    Slog.i(TAG, "No static wallpaper imagery; defaults will be shown");
                }
            }
        }
        boolean success = false;
        try {
            stream = new FileInputStream(file);
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, StandardCharsets.UTF_8.name());

            int type;
            do {
                type = parser.next();
                if (type == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("wp".equals(tag)) {
                        // Common to system + lock wallpapers
                        parseWallpaperAttributes(parser, wallpaper, keepDimensionHints);

                        // A system wallpaper might also be a live wallpaper
                        String comp = parser.getAttributeValue(null, "component");
                        wallpaper.nextWallpaperComponent = comp != null
                                ? ComponentName.unflattenFromString(comp)
                                : null;
                        if (wallpaper.nextWallpaperComponent == null
                                || "android".equals(wallpaper.nextWallpaperComponent
                                        .getPackageName())) {
                            wallpaper.nextWallpaperComponent = mImageWallpaper;
                        }

                        if (DEBUG) {
                            Slog.v(TAG, "mWidth:" + wallpaper.width);
                            Slog.v(TAG, "mHeight:" + wallpaper.height);
                            Slog.v(TAG, "cropRect:" + wallpaper.cropHint);
                            Slog.v(TAG, "primaryColors:" + wallpaper.primaryColors);
                            Slog.v(TAG, "mName:" + wallpaper.name);
                            Slog.v(TAG, "mNextWallpaperComponent:"
                                    + wallpaper.nextWallpaperComponent);
                        }
                    } else if ("kwp".equals(tag)) {
                        // keyguard-specific wallpaper for this user
                        WallpaperData lockWallpaper = mLockWallpaperMap.get(userId);
                        if (lockWallpaper == null) {
                            lockWallpaper = new WallpaperData(userId,
                                    WALLPAPER_LOCK_ORIG, WALLPAPER_LOCK_CROP);
                            mLockWallpaperMap.put(userId, lockWallpaper);
                        }
                        parseWallpaperAttributes(parser, lockWallpaper, false);
                    }
                }
            } while (type != XmlPullParser.END_DOCUMENT);
            success = true;
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "no current wallpaper -- first boot?");
        } catch (NullPointerException e) {
            Slog.w(TAG, "failed parsing " + file + " " + e);
        } catch (NumberFormatException e) {
            Slog.w(TAG, "failed parsing " + file + " " + e);
        } catch (XmlPullParserException e) {
            Slog.w(TAG, "failed parsing " + file + " " + e);
        } catch (IOException e) {
            Slog.w(TAG, "failed parsing " + file + " " + e);
        } catch (IndexOutOfBoundsException e) {
            Slog.w(TAG, "failed parsing " + file + " " + e);
        }
        IoUtils.closeQuietly(stream);

        if (!success) {
            wallpaper.width = -1;
            wallpaper.height = -1;
            wallpaper.cropHint.set(0, 0, 0, 0);
            wallpaper.padding.set(0, 0, 0, 0);
            wallpaper.name = "";

            mLockWallpaperMap.remove(userId);
        } else {
            if (wallpaper.wallpaperId <= 0) {
                wallpaper.wallpaperId = makeWallpaperIdLocked();
                if (DEBUG) {
                    Slog.w(TAG, "Didn't set wallpaper id in loadSettingsLocked(" + userId
                            + "); now " + wallpaper.wallpaperId);
                }
            }
        }

        ensureSaneWallpaperData(wallpaper);
        WallpaperData lockWallpaper = mLockWallpaperMap.get(userId);
        if (lockWallpaper != null) {
            ensureSaneWallpaperData(lockWallpaper);
        }
    }

    private void ensureSaneWallpaperData(WallpaperData wallpaper) {
        // We always want to have some reasonable width hint.
        int baseSize = getMaximumSizeDimension();
        if (wallpaper.width < baseSize) {
            wallpaper.width = baseSize;
        }
        if (wallpaper.height < baseSize) {
            wallpaper.height = baseSize;
        }
        // and crop, if not previously specified
        if (wallpaper.cropHint.width() <= 0
                || wallpaper.cropHint.height() <= 0) {
            wallpaper.cropHint.set(0, 0, wallpaper.width, wallpaper.height);
        }
    }

    private void parseWallpaperAttributes(XmlPullParser parser, WallpaperData wallpaper,
            boolean keepDimensionHints) {
        final String idString = parser.getAttributeValue(null, "id");
        if (idString != null) {
            final int id = wallpaper.wallpaperId = Integer.parseInt(idString);
            if (id > mWallpaperId) {
                mWallpaperId = id;
            }
        } else {
            wallpaper.wallpaperId = makeWallpaperIdLocked();
        }

        if (!keepDimensionHints) {
            wallpaper.width = Integer.parseInt(parser.getAttributeValue(null, "width"));
            wallpaper.height = Integer.parseInt(parser
                    .getAttributeValue(null, "height"));
        }
        wallpaper.cropHint.left = getAttributeInt(parser, "cropLeft", 0);
        wallpaper.cropHint.top = getAttributeInt(parser, "cropTop", 0);
        wallpaper.cropHint.right = getAttributeInt(parser, "cropRight", 0);
        wallpaper.cropHint.bottom = getAttributeInt(parser, "cropBottom", 0);
        wallpaper.padding.left = getAttributeInt(parser, "paddingLeft", 0);
        wallpaper.padding.top = getAttributeInt(parser, "paddingTop", 0);
        wallpaper.padding.right = getAttributeInt(parser, "paddingRight", 0);
        wallpaper.padding.bottom = getAttributeInt(parser, "paddingBottom", 0);
        int colorsCount = getAttributeInt(parser, "colorsCount", 0);
        if (colorsCount > 0) {
            Color primary = null, secondary = null, tertiary = null;
            for (int i = 0; i < colorsCount; i++) {
                Color color = Color.valueOf(getAttributeInt(parser, "colorValue" + i, 0));
                if (i == 0) {
                    primary = color;
                } else if (i == 1) {
                    secondary = color;
                } else if (i == 2) {
                    tertiary = color;
                } else {
                    break;
                }
            }
            int colorHints = getAttributeInt(parser, "colorHints", 0);
            wallpaper.primaryColors = new WallpaperColors(primary, secondary, tertiary, colorHints);
        }
        wallpaper.name = parser.getAttributeValue(null, "name");
        wallpaper.allowBackup = "true".equals(parser.getAttributeValue(null, "backup"));
    }

    private int getMaximumSizeDimension() {
        WindowManager wm = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);
        Display d = wm.getDefaultDisplay();
        return d.getMaximumSizeDimension();
    }

    // Called by SystemBackupAgent after files are restored to disk.
    public void settingsRestored() {
        // Verify caller is the system
        if (Binder.getCallingUid() != android.os.Process.SYSTEM_UID) {
            throw new RuntimeException("settingsRestored() can only be called from the system process");
        }
        // TODO: If necessary, make it work for secondary users as well. This currently assumes
        // restores only to the primary user
        if (DEBUG) Slog.v(TAG, "settingsRestored");
        WallpaperData wallpaper = null;
        boolean success = false;
        synchronized (mLock) {
            loadSettingsLocked(UserHandle.USER_SYSTEM, false);
            wallpaper = mWallpaperMap.get(UserHandle.USER_SYSTEM);
            wallpaper.wallpaperId = makeWallpaperIdLocked();    // always bump id at restore
            wallpaper.allowBackup = true;   // by definition if it was restored
            if (wallpaper.nextWallpaperComponent != null
                    && !wallpaper.nextWallpaperComponent.equals(mImageWallpaper)) {
                if (!bindWallpaperComponentLocked(wallpaper.nextWallpaperComponent, false, false,
                        wallpaper, null)) {
                    // No such live wallpaper or other failure; fall back to the default
                    // live wallpaper (since the profile being restored indicated that the
                    // user had selected a live rather than static one).
                    bindWallpaperComponentLocked(null, false, false, wallpaper, null);
                }
                success = true;
            } else {
                // If there's a wallpaper name, we use that.  If that can't be loaded, then we
                // use the default.
                if ("".equals(wallpaper.name)) {
                    if (DEBUG) Slog.v(TAG, "settingsRestored: name is empty");
                    success = true;
                } else {
                    if (DEBUG) Slog.v(TAG, "settingsRestored: attempting to restore named resource");
                    success = restoreNamedResourceLocked(wallpaper);
                }
                if (DEBUG) Slog.v(TAG, "settingsRestored: success=" + success
                        + " id=" + wallpaper.wallpaperId);
                if (success) {
                    generateCrop(wallpaper);    // based on the new image + metadata
                    bindWallpaperComponentLocked(wallpaper.nextWallpaperComponent, true, false,
                            wallpaper, null);
                }
            }
        }

        if (!success) {
            Slog.e(TAG, "Failed to restore wallpaper: '" + wallpaper.name + "'");
            wallpaper.name = "";
            getWallpaperDir(UserHandle.USER_SYSTEM).delete();
        }

        synchronized (mLock) {
            saveSettingsLocked(UserHandle.USER_SYSTEM);
        }
    }

    // Restore the named resource bitmap to both source + crop files
    boolean restoreNamedResourceLocked(WallpaperData wallpaper) {
        if (wallpaper.name.length() > 4 && "res:".equals(wallpaper.name.substring(0, 4))) {
            String resName = wallpaper.name.substring(4);

            String pkg = null;
            int colon = resName.indexOf(':');
            if (colon > 0) {
                pkg = resName.substring(0, colon);
            }

            String ident = null;
            int slash = resName.lastIndexOf('/');
            if (slash > 0) {
                ident = resName.substring(slash+1);
            }

            String type = null;
            if (colon > 0 && slash > 0 && (slash-colon) > 1) {
                type = resName.substring(colon+1, slash);
            }

            if (pkg != null && ident != null && type != null) {
                int resId = -1;
                InputStream res = null;
                FileOutputStream fos = null;
                FileOutputStream cos = null;
                try {
                    Context c = mContext.createPackageContext(pkg, Context.CONTEXT_RESTRICTED);
                    Resources r = c.getResources();
                    resId = r.getIdentifier(resName, null, null);
                    if (resId == 0) {
                        Slog.e(TAG, "couldn't resolve identifier pkg=" + pkg + " type=" + type
                                + " ident=" + ident);
                        return false;
                    }

                    res = r.openRawResource(resId);
                    if (wallpaper.wallpaperFile.exists()) {
                        wallpaper.wallpaperFile.delete();
                        wallpaper.cropFile.delete();
                    }
                    fos = new FileOutputStream(wallpaper.wallpaperFile);
                    cos = new FileOutputStream(wallpaper.cropFile);

                    byte[] buffer = new byte[32768];
                    int amt;
                    while ((amt=res.read(buffer)) > 0) {
                        fos.write(buffer, 0, amt);
                        cos.write(buffer, 0, amt);
                    }
                    // mWallpaperObserver will notice the close and send the change broadcast

                    Slog.v(TAG, "Restored wallpaper: " + resName);
                    return true;
                } catch (NameNotFoundException e) {
                    Slog.e(TAG, "Package name " + pkg + " not found");
                } catch (Resources.NotFoundException e) {
                    Slog.e(TAG, "Resource not found: " + resId);
                } catch (IOException e) {
                    Slog.e(TAG, "IOException while restoring wallpaper ", e);
                } finally {
                    IoUtils.closeQuietly(res);
                    if (fos != null) {
                        FileUtils.sync(fos);
                    }
                    if (cos != null) {
                        FileUtils.sync(cos);
                    }
                    IoUtils.closeQuietly(fos);
                    IoUtils.closeQuietly(cos);
                }
            }
        }
        return false;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        synchronized (mLock) {
            pw.println("System wallpaper state:");
            for (int i = 0; i < mWallpaperMap.size(); i++) {
                WallpaperData wallpaper = mWallpaperMap.valueAt(i);
                pw.print(" User "); pw.print(wallpaper.userId);
                    pw.print(": id="); pw.println(wallpaper.wallpaperId);
                pw.print("  mWidth=");
                    pw.print(wallpaper.width);
                    pw.print(" mHeight=");
                    pw.println(wallpaper.height);
                pw.print("  mCropHint="); pw.println(wallpaper.cropHint);
                pw.print("  mPadding="); pw.println(wallpaper.padding);
                pw.print("  mName=");  pw.println(wallpaper.name);
                pw.print("  mAllowBackup="); pw.println(wallpaper.allowBackup);
                pw.print("  mWallpaperComponent="); pw.println(wallpaper.wallpaperComponent);
                if (wallpaper.connection != null) {
                    WallpaperConnection conn = wallpaper.connection;
                    pw.print("  Wallpaper connection ");
                    pw.print(conn);
                    pw.println(":");
                    if (conn.mInfo != null) {
                        pw.print("    mInfo.component=");
                        pw.println(conn.mInfo.getComponent());
                    }
                    pw.print("    mToken=");
                    pw.println(conn.mToken);
                    pw.print("    mService=");
                    pw.println(conn.mService);
                    pw.print("    mEngine=");
                    pw.println(conn.mEngine);
                    pw.print("    mLastDiedTime=");
                    pw.println(wallpaper.lastDiedTime - SystemClock.uptimeMillis());
                }
            }
            pw.println("Lock wallpaper state:");
            for (int i = 0; i < mLockWallpaperMap.size(); i++) {
                WallpaperData wallpaper = mLockWallpaperMap.valueAt(i);
                pw.print(" User "); pw.print(wallpaper.userId);
                    pw.print(": id="); pw.println(wallpaper.wallpaperId);
                pw.print("  mWidth="); pw.print(wallpaper.width);
                    pw.print(" mHeight="); pw.println(wallpaper.height);
                pw.print("  mCropHint="); pw.println(wallpaper.cropHint);
                pw.print("  mPadding="); pw.println(wallpaper.padding);
                pw.print("  mName=");  pw.println(wallpaper.name);
                pw.print("  mAllowBackup="); pw.println(wallpaper.allowBackup);
            }

        }
    }
}
