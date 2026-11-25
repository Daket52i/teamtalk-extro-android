package org.teamtalkextro.backend;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import androidx.preference.PreferenceManager;
import dk.bearware.Channel;
import dk.bearware.ClientEvent;
import dk.bearware.EncryptionContext;
import dk.bearware.IntPtr;
import dk.bearware.ServerProperties;
import dk.bearware.TTMessage;
import dk.bearware.TeamTalk5;
import dk.bearware.TeamTalkBase;
import dk.bearware.User;
import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.teamtalkextro.R;
import org.teamtalkextro.data.Preferences;
import org.teamtalkextro.data.ServerEntry;

/**
 * Inspects who is online on a TeamTalk server without joining any channel.
 *
 * If the user is already connected and authorized on the same host:port in
 * {@link TeamTalkService}, returns an instant 0ms snapshot from the active
 * connection (preventing ERR_ACCOUNT_IN_USE / ERR_MAX_LOGINS_PER_IP).
 * Otherwise, opens a dedicated temporary {@link TeamTalk5} instance with full
 * TLS {@link EncryptionContext} support, fresh {@link TTMessage} objects per
 * poll, waits for login completion, merges native server snapshots, and cleanly
 * closes the temporary instance.
 */
public final class ServerPeekHelper {

    private static final String TAG = "ServerPeekHelper";
    private static final String DEFAULT_PEEK_NICKNAME = "Extro-Peek";
    private static final long TOTAL_TIMEOUT_MS = 12000L;
    private static final long POST_COMPLETE_SETTLE_MS = 250L;
    private static final long POST_LOGIN_FALLBACK_MS = 2500L;
    private static final int CLIENT_AUTHORIZED = 32768;

    private static final AtomicBoolean NATIVE_LOADED = new AtomicBoolean(false);

    public static final class ChannelGroup {
        public final int channelId;
        public final String channelName;
        public final List<String> users;

        public ChannelGroup(int channelId, String channelName, List<String> users) {
            this.channelId = channelId;
            this.channelName = channelName;
            this.users = users;
        }
    }

    public static final class PeekResult {
        public final boolean success;
        public final String errorMessage;
        public final String serverName;
        public final int totalUsers;
        public final int totalChannels;
        public final List<ChannelGroup> channels;

        private PeekResult(boolean success, String errorMessage, String serverName,
                           int totalUsers, int totalChannels, List<ChannelGroup> channels) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.serverName = serverName;
            this.totalUsers = totalUsers;
            this.totalChannels = totalChannels;
            this.channels = channels;
        }

        public static PeekResult ok(String serverName, int totalUsers, int totalChannels, List<ChannelGroup> channels) {
            return new PeekResult(true, null, serverName, totalUsers, totalChannels, channels);
        }

        public static PeekResult error(String message) {
            return new PeekResult(false, message, "", 0, 0, Collections.emptyList());
        }

        public String formatDisplayText(Context context) {
            if (!success) {
                return errorMessage != null ? errorMessage : context.getString(R.string.server_peek_error_generic);
            }
            StringBuilder sb = new StringBuilder();
            if (!TextUtils.isEmpty(serverName)) {
                sb.append(serverName).append("\n");
            }
            sb.append(context.getString(R.string.server_peek_summary, totalUsers, totalChannels));
            sb.append("\n\n");

            if (totalUsers == 0) {
                sb.append(context.getString(R.string.server_peek_no_users));
                if (totalChannels > 0) {
                    sb.append("\n\n").append(context.getString(R.string.server_peek_channels_list)).append("\n");
                    for (ChannelGroup g : channels) {
                        sb.append("• ").append(g.channelName).append("\n");
                    }
                }
                return sb.toString().trim();
            }

            List<ChannelGroup> emptyGroups = new ArrayList<>();
            for (ChannelGroup g : channels) {
                if (g.users.isEmpty()) {
                    emptyGroups.add(g);
                    continue;
                }
                sb.append("📁 ").append(g.channelName)
                        .append(" (").append(g.users.size()).append(")\n");
                for (String u : g.users) {
                    sb.append("   • ").append(u).append("\n");
                }
                sb.append("\n");
            }

            if (!emptyGroups.isEmpty()) {
                sb.append(context.getString(R.string.server_peek_empty_channels, emptyGroups.size())).append(": ");
                for (int i = 0; i < Math.min(emptyGroups.size(), 8); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(emptyGroups.get(i).channelName);
                }
                if (emptyGroups.size() > 8) {
                    sb.append("…");
                }
            }
            return sb.toString().trim();
        }
    }

    public interface Callback {
        void onResult(PeekResult result);
    }

    public static final class PeekHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        public void cancel() {
            cancelled.set(true);
        }

        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    private ServerPeekHelper() {
    }

    public static PeekHandle peekAsync(Context context, ServerEntry entry, Callback callback) {
        return peekAsync(context, entry, TeamTalkService.getInstance(), callback);
    }

    public static PeekHandle peekAsync(Context context, ServerEntry entry, TeamTalkService activeService, Callback callback) {
        final Context appCtx = context.getApplicationContext();
        final PeekHandle handle = new PeekHandle();
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final TeamTalkService svc = activeService != null ? activeService : TeamTalkService.getInstance();

        // Instant 0ms path if already connected and authorized to the exact same host:port
        PeekResult instant = tryInstantPeekFromActiveService(appCtx, entry, svc);
        if (instant != null) {
            if (callback != null) {
                mainHandler.post(() -> {
                    if (!handle.isCancelled()) {
                        callback.onResult(instant);
                    }
                });
            }
            return handle;
        }

        Thread worker = new Thread(() -> {
            PeekResult result = performPeek(appCtx, entry, handle);
            if (!handle.isCancelled() && callback != null) {
                mainHandler.post(() -> {
                    if (!handle.isCancelled()) {
                        callback.onResult(result);
                    }
                });
            }
        }, "Extro-ServerPeek");
        worker.start();
        return handle;
    }

    private static PeekResult tryInstantPeekFromActiveService(Context context, ServerEntry entry, TeamTalkService service) {
        if (entry == null || TextUtils.isEmpty(entry.ipaddr) || service == null) {
            return null;
        }
        try {
            ServerEntry active = service.getServerEntry();
            TeamTalkBase activeClient = service.getTTInstance();
            if (active == null || activeClient == null || TextUtils.isEmpty(active.ipaddr)) {
                return null;
            }
            int flags = activeClient.getFlags();
            if ((flags & CLIENT_AUTHORIZED) == 0) {
                return null;
            }
            String reqHost = entry.ipaddr.trim();
            int reqTcp = entry.tcpport > 0 ? entry.tcpport : 10333;
            String actHost = active.ipaddr.trim();
            int actTcp = active.tcpport > 0 ? active.tcpport : 10333;
            if (!reqHost.equalsIgnoreCase(actHost) || reqTcp != actTcp) {
                return null;
            }

            Map<Integer, Channel> channelsMap = new HashMap<>();
            Map<Integer, User> usersMap = new HashMap<>();

            Map<Integer, Channel> svcChannels = service.getChannels();
            if (svcChannels != null) {
                for (Channel ch : new ArrayList<>(svcChannels.values())) {
                    if (ch != null && ch.nChannelID > 0) {
                        channelsMap.put(ch.nChannelID, copyChannel(ch));
                    }
                }
            }
            Map<Integer, User> svcUsers = service.getUsers();
            if (svcUsers != null) {
                for (User u : new ArrayList<>(svcUsers.values())) {
                    if (u != null && u.nUserID > 0) {
                        usersMap.put(u.nUserID, copyUser(u));
                    }
                }
            }

            mergeNativeSnapshots(activeClient, channelsMap, usersMap);

            String serverName = !TextUtils.isEmpty(active.servername) ? active.servername : reqHost;
            try {
                ServerProperties props = new ServerProperties();
                if (activeClient.getServerProperties(props) && !TextUtils.isEmpty(props.szServerName)) {
                    serverName = props.szServerName;
                }
            } catch (Throwable ignored) {
            }

            return buildPeekResult(context, serverName, channelsMap, usersMap, -1, null);
        } catch (Throwable t) {
            Log.w(TAG, "Instant peek fallback to temporary client", t);
            return null;
        }
    }

    public static void ensureNativeLibraryLoaded() {
        if (TeamTalkService.getInstance() != null) {
            NATIVE_LOADED.set(true);
            return;
        }
        if (NATIVE_LOADED.compareAndSet(false, true)) {
            try {
                TeamTalk5.loadLibrary();
                TeamTalk5.setLicenseInformation("", "");
            } catch (Throwable t) {
                Log.w(TAG, "TeamTalk5 library init note", t);
            }
        }
    }

    private static PeekResult performPeek(Context context, ServerEntry entry, PeekHandle handle) {
        if (entry == null || TextUtils.isEmpty(entry.ipaddr)) {
            return PeekResult.error(context.getString(R.string.server_peek_error_unreachable));
        }

        TeamTalkBase client = null;
        List<File> tempCertFiles = new ArrayList<>();
        try {
            ensureNativeLibraryLoaded();

            client = new TeamTalk5();
            String host = entry.ipaddr.trim();
            int tcpPort = entry.tcpport > 0 ? entry.tcpport : 10333;
            int udpPort = entry.udpport > 0 ? entry.udpport : tcpPort;

            if (entry.encrypted) {
                if (!configureEncryptionContext(context, client, entry, tempCertFiles)) {
                    return PeekResult.error(context.getString(R.string.server_peek_error_tls, "EncryptionContext init failed"));
                }
            }

            if (!client.connect(host, tcpPort, udpPort, 0, 0, entry.encrypted)) {
                return PeekResult.error(context.getString(R.string.server_peek_error_stage,
                        "TCP/Connect", context.getString(R.string.server_peek_error_unreachable)));
            }

            String nickname = resolvePeekNickname(context, entry);
            long startMs = SystemClock.uptimeMillis();
            boolean connected = false;
            boolean loggedIn = false;
            long loggedInAt = 0L;
            boolean loginCompleted = false;
            long loginCompletedAt = 0L;
            int myUserId = -1;
            int loginCmdId = -1;
            String serverName = !TextUtils.isEmpty(entry.servername) ? entry.servername : host;

            Map<Integer, Channel> channelsMap = new HashMap<>();
            Map<Integer, User> usersMap = new HashMap<>();

            while (!handle.isCancelled()) {
                long now = SystemClock.uptimeMillis();
                if (now - startMs >= TOTAL_TIMEOUT_MS) {
                    if (!loggedIn) {
                        String stage = connected ? "Login" : "TCP/TLS Connect";
                        return PeekResult.error(context.getString(R.string.server_peek_error_stage,
                                stage, context.getString(R.string.server_peek_error_timeout)));
                    }
                    break;
                }
                if (loginCompleted && (now - loginCompletedAt >= POST_COMPLETE_SETTLE_MS)) {
                    break;
                }
                if (loggedIn && !loginCompleted && (now - loggedInAt >= POST_LOGIN_FALLBACK_MS)) {
                    break;
                }

                // IMPORTANT: Instantiate a fresh TTMessage per poll because JNI setTTMessage()
                // does not clear fields left over from previous event types.
                TTMessage msg = new TTMessage();
                if (!client.getMessage(msg, 60)) {
                    continue;
                }

                switch (msg.nClientEvent) {
                    case ClientEvent.CLIENTEVENT_CON_SUCCESS: {
                        connected = true;
                        String user = entry.username != null ? entry.username : "";
                        String pass = entry.password != null ? entry.password : "";
                        loginCmdId = client.doLoginEx(nickname, user, pass, "TeamTalk Extro");
                        if (loginCmdId <= 0) {
                            return PeekResult.error(context.getString(R.string.server_peek_error_stage,
                                    "Login", context.getString(R.string.server_peek_error_auth)));
                        }
                        break;
                    }
                    case ClientEvent.CLIENTEVENT_CON_FAILED:
                        return PeekResult.error(context.getString(R.string.server_peek_error_stage,
                                "Connect", context.getString(R.string.server_peek_error_unreachable)));

                    case ClientEvent.CLIENTEVENT_CON_CRYPT_ERROR:
                        return PeekResult.error(context.getString(R.string.server_peek_error_tls,
                                "TLS handshake rejected"));

                    case ClientEvent.CLIENTEVENT_CON_LOST:
                        if (!loggedIn) {
                            return PeekResult.error(context.getString(R.string.server_peek_error_stage,
                                    connected ? "Login" : "Connect",
                                    context.getString(R.string.server_peek_error_unreachable)));
                        }
                        break;

                    case ClientEvent.CLIENTEVENT_CMD_ERROR:
                        if (!loggedIn || (loginCmdId > 0 && msg.nSource == loginCmdId)) {
                            int errNo = msg.clienterrormsg != null ? msg.clienterrormsg.nErrorNo : 0;
                            String errText = "";
                            if (msg.clienterrormsg != null) {
                                if (!TextUtils.isEmpty(msg.clienterrormsg.szErrorMsg)) {
                                    errText = msg.clienterrormsg.szErrorMsg;
                                } else if (errNo != 0) {
                                    errText = TeamTalkBase.getErrorMessage(errNo);
                                }
                            }
                            if (TextUtils.isEmpty(errText)) {
                                errText = context.getString(R.string.server_peek_error_auth);
                            }
                            String detail = errNo != 0 ? (errText + " (#" + errNo + ")") : errText;
                            return PeekResult.error(context.getString(R.string.server_peek_error_stage, "Login", detail));
                        }
                        break;

                    case ClientEvent.CLIENTEVENT_CMD_MYSELF_LOGGEDIN:
                        loggedIn = true;
                        loggedInAt = SystemClock.uptimeMillis();
                        myUserId = client.getMyUserID();
                        break;

                    case ClientEvent.CLIENTEVENT_CMD_SUCCESS:
                        if (loginCmdId > 0 && msg.nSource == loginCmdId) {
                            loginCompleted = true;
                            loginCompletedAt = SystemClock.uptimeMillis();
                            if (!loggedIn) {
                                loggedIn = true;
                                loggedInAt = loginCompletedAt;
                            }
                            if (myUserId <= 0) {
                                myUserId = client.getMyUserID();
                            }
                        }
                        break;

                    case ClientEvent.CLIENTEVENT_CMD_SERVER_UPDATE:
                        if (msg.serverproperties != null && !TextUtils.isEmpty(msg.serverproperties.szServerName)) {
                            serverName = msg.serverproperties.szServerName;
                        }
                        break;

                    case ClientEvent.CLIENTEVENT_CMD_CHANNEL_NEW:
                    case ClientEvent.CLIENTEVENT_CMD_CHANNEL_UPDATE:
                        if (msg.channel != null && msg.channel.nChannelID > 0) {
                            channelsMap.put(msg.channel.nChannelID, copyChannel(msg.channel));
                        }
                        break;

                    case ClientEvent.CLIENTEVENT_CMD_CHANNEL_REMOVE:
                        if (msg.channel != null) {
                            channelsMap.remove(msg.channel.nChannelID);
                        }
                        break;

                    case ClientEvent.CLIENTEVENT_CMD_USER_LOGGEDIN:
                    case ClientEvent.CLIENTEVENT_CMD_USER_JOINED:
                    case ClientEvent.CLIENTEVENT_CMD_USER_UPDATE:
                        if (msg.user != null && msg.user.nUserID > 0) {
                            usersMap.put(msg.user.nUserID, copyUser(msg.user));
                        }
                        break;

                    case ClientEvent.CLIENTEVENT_CMD_USER_LEFT:
                        if (msg.user != null) {
                            User existing = usersMap.get(msg.user.nUserID);
                            if (existing != null) {
                                existing.nChannelID = 0;
                            }
                        }
                        break;

                    case ClientEvent.CLIENTEVENT_CMD_USER_LOGGEDOUT:
                        if (msg.user != null) {
                            usersMap.remove(msg.user.nUserID);
                        }
                        break;

                    default:
                        break;
                }
            }

            if (handle.isCancelled()) {
                return PeekResult.error(context.getString(R.string.connection_cancelled));
            }

            if (!loggedIn) {
                return PeekResult.error(context.getString(R.string.server_peek_error_stage,
                        connected ? "Login" : "Connect",
                        context.getString(R.string.server_peek_error_timeout)));
            }

            // Merge authoritative native channel & user snapshots after login completes
            mergeNativeSnapshots(client, channelsMap, usersMap);

            try {
                ServerProperties props = new ServerProperties();
                if (client.getServerProperties(props) && !TextUtils.isEmpty(props.szServerName)) {
                    serverName = props.szServerName;
                }
            } catch (Throwable ignored) {
            }

            if (myUserId <= 0) {
                myUserId = client.getMyUserID();
            }

            return buildPeekResult(context, serverName, channelsMap, usersMap, myUserId, nickname);
        } catch (Throwable t) {
            Log.e(TAG, "Server peek failed", t);
            return PeekResult.error(t.getMessage() != null ? t.getMessage() : context.getString(R.string.server_peek_error_generic));
        } finally {
            if (client != null) {
                try {
                    client.disconnect();
                } catch (Throwable ignored) {
                }
                try {
                    client.closeTeamTalk();
                } catch (Throwable ignored) {
                }
            }
            for (File f : tempCertFiles) {
                try {
                    if (f != null && f.exists()) {
                        f.delete();
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean configureEncryptionContext(Context context, TeamTalkBase client,
                                                      ServerEntry entry, List<File> tempFiles) {
        try {
            EncryptionContext enc = new EncryptionContext();
            File cacheDir = context.getCacheDir();
            if (!TextUtils.isEmpty(entry.cacert)) {
                File f = File.createTempFile("peek_ca_", ".pem", cacheDir);
                tempFiles.add(f);
                try (FileWriter fw = new FileWriter(f)) {
                    fw.write(entry.cacert);
                }
                enc.szCAFile = f.getAbsolutePath();
            }
            if (!TextUtils.isEmpty(entry.clientcert)) {
                File f = File.createTempFile("peek_cert_", ".pem", cacheDir);
                tempFiles.add(f);
                try (FileWriter fw = new FileWriter(f)) {
                    fw.write(entry.clientcert);
                }
                enc.szCertificateFile = f.getAbsolutePath();
            }
            if (!TextUtils.isEmpty(entry.clientcertkey)) {
                File f = File.createTempFile("peek_key_", ".pem", cacheDir);
                tempFiles.add(f);
                try (FileWriter fw = new FileWriter(f)) {
                    fw.write(entry.clientcertkey);
                }
                enc.szPrivateKeyFile = f.getAbsolutePath();
            }
            enc.bVerifyPeer = entry.verifypeer;
            if (!enc.bVerifyPeer) {
                enc.nVerifyDepth = -1;
            }
            return client.setEncryptionContext(enc);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to setup EncryptionContext for peek", t);
            return false;
        }
    }

    private static void mergeNativeSnapshots(TeamTalkBase client,
                                             Map<Integer, Channel> channelsMap,
                                             Map<Integer, User> usersMap) {
        if (client == null) {
            return;
        }
        try {
            IntPtr chCount = new IntPtr(0);
            if (client.getServerChannels(null, chCount) && chCount.value > 0) {
                Channel[] chArr = new Channel[chCount.value];
                if (client.getServerChannels(chArr, chCount)) {
                    int n = Math.min(chCount.value, chArr.length);
                    for (int i = 0; i < n; i++) {
                        if (chArr[i] != null && chArr[i].nChannelID > 0) {
                            channelsMap.put(chArr[i].nChannelID, copyChannel(chArr[i]));
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getServerChannels snapshot warning", t);
        }

        try {
            IntPtr usrCount = new IntPtr(0);
            if (client.getServerUsers(null, usrCount) && usrCount.value > 0) {
                User[] usrArr = new User[usrCount.value];
                if (client.getServerUsers(usrArr, usrCount)) {
                    int n = Math.min(usrCount.value, usrArr.length);
                    for (int i = 0; i < n; i++) {
                        if (usrArr[i] != null && usrArr[i].nUserID > 0) {
                            usersMap.put(usrArr[i].nUserID, copyUser(usrArr[i]));
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getServerUsers snapshot warning", t);
        }
    }

    private static PeekResult buildPeekResult(Context context,
                                              String serverName,
                                              Map<Integer, Channel> channelsMap,
                                              Map<Integer, User> usersMap,
                                              int excludeUserId,
                                              String excludeTempNickname) {
        if (excludeUserId > 0) {
            usersMap.remove(excludeUserId);
        }

        Map<Integer, List<String>> usersByChannel = new LinkedHashMap<>();
        List<String> lobbyUsers = new ArrayList<>();
        int realUsersCount = 0;

        for (User u : usersMap.values()) {
            if (u == null) {
                continue;
            }
            if (excludeUserId > 0 && u.nUserID == excludeUserId) {
                continue;
            }
            if (excludeTempNickname != null && excludeTempNickname.equals(u.szNickname)
                    && u.nChannelID == 0 && usersMap.size() == 1 && excludeUserId <= 0) {
                continue;
            }
            String displayName = !TextUtils.isEmpty(u.szNickname) ? u.szNickname : u.szUsername;
            if (TextUtils.isEmpty(displayName)) {
                displayName = "#" + u.nUserID;
            }
            if (!TextUtils.isEmpty(u.szStatusMsg)) {
                displayName = displayName + " (" + u.szStatusMsg + ")";
            }
            realUsersCount++;
            if (u.nChannelID > 0 && channelsMap.containsKey(u.nChannelID)) {
                List<String> list = usersByChannel.get(u.nChannelID);
                if (list == null) {
                    list = new ArrayList<>();
                    usersByChannel.put(u.nChannelID, list);
                }
                list.add(displayName);
            } else {
                lobbyUsers.add(displayName);
            }
        }

        List<ChannelGroup> groups = new ArrayList<>();
        List<Channel> sortedChannels = new ArrayList<>(channelsMap.values());
        Collections.sort(sortedChannels, new Comparator<Channel>() {
            @Override
            public int compare(Channel a, Channel b) {
                return buildChannelPath(a, channelsMap).compareToIgnoreCase(buildChannelPath(b, channelsMap));
            }
        });

        for (Channel ch : sortedChannels) {
            String path = buildChannelPath(ch, channelsMap);
            List<String> list = usersByChannel.get(ch.nChannelID);
            if (list == null) {
                list = new ArrayList<>();
            } else {
                Collections.sort(list, String.CASE_INSENSITIVE_ORDER);
            }
            groups.add(new ChannelGroup(ch.nChannelID, path, list));
        }

        if (!lobbyUsers.isEmpty()) {
            Collections.sort(lobbyUsers, String.CASE_INSENSITIVE_ORDER);
            groups.add(new ChannelGroup(0, context.getString(R.string.server_peek_lobby), lobbyUsers));
        }

        return PeekResult.ok(serverName, realUsersCount, channelsMap.size(), groups);
    }

    private static String resolvePeekNickname(Context context, ServerEntry entry) {
        if (entry != null && !TextUtils.isEmpty(entry.nickname) && !entry.nickname.trim().isEmpty()) {
            return entry.nickname.trim();
        }
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            String prefNick = prefs.getString(Preferences.PREF_GENERAL_NICKNAME, DEFAULT_PEEK_NICKNAME);
            if (!TextUtils.isEmpty(prefNick) && !prefNick.trim().isEmpty()) {
                return prefNick.trim();
            }
        } catch (Throwable ignored) {
        }
        return DEFAULT_PEEK_NICKNAME;
    }

    private static Channel copyChannel(Channel src) {
        Channel c = new Channel();
        c.nChannelID = src.nChannelID;
        c.nParentID = src.nParentID;
        c.szName = src.szName != null ? src.szName : "";
        c.szTopic = src.szTopic != null ? src.szTopic : "";
        return c;
    }

    private static User copyUser(User src) {
        User u = new User();
        u.nUserID = src.nUserID;
        u.nChannelID = src.nChannelID;
        u.szNickname = src.szNickname != null ? src.szNickname : "";
        u.szUsername = src.szUsername != null ? src.szUsername : "";
        u.szStatusMsg = src.szStatusMsg != null ? src.szStatusMsg : "";
        return u;
    }

    private static String buildChannelPath(Channel ch, Map<Integer, Channel> all) {
        if (ch == null) {
            return "/";
        }
        List<String> parts = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        Channel cur = ch;
        while (cur != null && visited.add(cur.nChannelID)) {
            if (!TextUtils.isEmpty(cur.szName)) {
                parts.add(cur.szName);
            }
            if (cur.nParentID <= 0) {
                break;
            }
            cur = all.get(cur.nParentID);
        }
        if (parts.isEmpty()) {
            return "/";
        }
        Collections.reverse(parts);
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            sb.append("/").append(p);
        }
        return sb.toString();
    }
}
