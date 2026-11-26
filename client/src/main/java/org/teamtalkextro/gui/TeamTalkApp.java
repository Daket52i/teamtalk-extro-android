package org.teamtalkextro.gui;

import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.util.Log;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import org.teamtalkextro.plugin.PluginManager;

public class TeamTalkApp extends Application {

    private static final String TAG = "TeamTalkApp";
    private static final long STALE_CACHE_AGE_MS = 2L * 60L * 60L * 1000L; // 2 hours
    private static final AtomicBoolean CLEANUP_IN_PROGRESS = new AtomicBoolean(false);

    @Override
    public void onCreate() {
        super.onCreate();
        CrashHandler.init(this);
        try {
            PluginManager.getInstance().init(this, null);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize PluginManager", t);
        }
        cleanupStaleBotCachesAsync(this);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                || level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            cleanupStaleBotCachesAsync(this);
        }
    }

    public static void cleanupStaleBotCachesAsync(Context context) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext();
        if (!CLEANUP_IN_PROGRESS.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                cleanupStaleBotCaches(appCtx);
            } catch (Throwable ignored) {
            } finally {
                CLEANUP_IN_PROGRESS.set(false);
            }
        }, "Extro-CacheCleanup");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Deletes stale temporary DJ Bot / StreamMedia / Telegram inbox files older than 2 hours
     * without touching currently active files. Returns freed bytes.
     */
    public static long cleanupStaleBotCaches(Context context) {
        if (context == null) return 0L;
        File cacheDir = context.getCacheDir();
        if (cacheDir == null || !cacheDir.isDirectory()) return 0L;
        long cutoffMs = System.currentTimeMillis() - STALE_CACHE_AGE_MS;
        long freed = 0L;
        File[] top = cacheDir.listFiles();
        if (top != null) {
            for (File f : top) {
                if (f == null) continue;
                String name = f.getName();
                if (f.isFile() && isBotOrStreamTempName(name) && f.lastModified() < cutoffMs) {
                    long len = f.length();
                    if (f.delete()) {
                        freed += Math.max(0L, len);
                    }
                } else if (f.isDirectory() && "djbot_tg_inbox".equals(name)) {
                    freed += deleteOldFilesInDir(f, cutoffMs);
                }
            }
        }
        if (freed > 0L) {
            Log.d(TAG, "Cleaned up stale temp caches: " + (freed / 1024L) + " KB");
        }
        return freed;
    }

    /**
     * Calculates total size in bytes of the application cache directory.
     */
    public static long calculateCacheSizeBytes(Context context) {
        if (context == null) return 0L;
        File cacheDir = context.getCacheDir();
        if (cacheDir == null || !cacheDir.exists()) return 0L;
        return computeDirSize(cacheDir);
    }

    /**
     * Clears temporary application caches (DJ Bot tracks, stream temps, Telegram inbox,
     * and safe cache files) on demand from Extro Doctor. Returns freed bytes.
     */
    public static long clearAppTempCaches(Context context) {
        if (context == null) return 0L;
        File cacheDir = context.getCacheDir();
        if (cacheDir == null || !cacheDir.isDirectory()) return 0L;
        return deleteDirContents(cacheDir);
    }

    private static boolean isBotOrStreamTempName(String name) {
        if (name == null) return false;
        return name.startsWith("extro_bot_track_")
                || name.startsWith("extro_bot_local_")
                || name.startsWith("stream_temp_");
    }

    private static long deleteOldFilesInDir(File dir, long cutoffMs) {
        long freed = 0L;
        File[] children = dir.listFiles();
        if (children == null) return 0L;
        for (File child : children) {
            if (child == null) continue;
            if (child.isDirectory()) {
                freed += deleteOldFilesInDir(child, cutoffMs);
            } else if (child.isFile() && child.lastModified() < cutoffMs) {
                long len = child.length();
                if (child.delete()) {
                    freed += Math.max(0L, len);
                }
            }
        }
        return freed;
    }

    private static long computeDirSize(File dir) {
        if (dir == null || !dir.exists()) return 0L;
        if (dir.isFile()) return Math.max(0L, dir.length());
        long total = 0L;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                total += computeDirSize(child);
            }
        }
        return total;
    }

    private static long deleteDirContents(File dir) {
        long freed = 0L;
        File[] children = dir.listFiles();
        if (children == null) return 0L;
        for (File child : children) {
            if (child == null) continue;
            if (child.isDirectory()) {
                freed += deleteDirContents(child);
                child.delete();
            } else if (child.isFile()) {
                long len = child.length();
                if (child.delete()) {
                    freed += Math.max(0L, len);
                }
            }
        }
        return freed;
    }
}
