package org.teamtalkextro.gui;

import android.app.Application;
import android.util.Log;
import org.teamtalkextro.plugin.PluginManager;

public class TeamTalkApp extends Application {

    private static final String TAG = "TeamTalkApp";

    @Override
    public void onCreate() {
        super.onCreate();
        CrashHandler.init(this);
        try {
            PluginManager.getInstance().init(this, null);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize PluginManager", t);
        }
    }
}
