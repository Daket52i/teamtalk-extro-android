package org.teamtalkextro.backend;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import dk.bearware.Channel;
import dk.bearware.ClientEvent;
import dk.bearware.ServerProperties;
import dk.bearware.TTMessage;
import dk.bearware.TeamTalk5;
import dk.bearware.TeamTalkBase;
import dk.bearware.User;
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
import org.teamtalkextro.data.ServerEntry;

/**
 * Connects to a TeamTalk server with a dedicated temporary {@link TeamTalk5}
 * instance (never disturbing the active {@link TeamTalkService} connection),
 * logs in without joining any channel, collects channels & online users, and
 * cleanly disconnects and closes the temporary instance.
 */
public final class ServerPeekHelper {

    private static final String TAG = "ServerPeekHelper";
    private static final String PEEK_NICKNAME = "Extro-Peek";
    private static final long TOTAL_TIMEOUT_MS = 5000L;
    private static final long POST_LOGIN_COLLECT_MS = 700L;

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
        final Context appCtx = context.getApplicationContext();
        final PeekHandle handle = new PeekHandle();
        final Handler mainHandler = new Handler(Looper.getMainLooper());

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

    private static PeekResult performPeek(Context context, ServerEntry entry, PeekHandle handle) {
        if (entry == null || TextUtils.isEmpty(entry.ipaddr)) {
            return PeekResult.error(context.getString(R.string.server_peek_error_unreachable));
        }

        TeamTalkBase client = null;
        try {
            try {
                TeamTalk5.loadLibrary();
                TeamTalk5.setLicenseInformation("", "");
            } catch (Throwable t) {
                Log.w(TAG, "TeamTalk5 library init note", t);
            }

            client = new TeamTalk5();
            String host = entry.ipaddr.trim();
            int tcpPort = entry.tcpport > 0 ? entry.tcpport : 10333;
            int udpPort = entry.udpport > 0 ? entry.udpport : tcpPort;

            if (!client.connect(host, tcpPort, udpPort, 0, 0, entry.encrypted)) {
                return PeekResult.error(context.getString(R.string.server_peek_error_unreachable));
            }

            long startMs = SystemClock.uptimeMillis();
            boolean loggedIn = false;
            long loggedInAt = 0L;
            int myUserId = -1;
            int loginCmdId = -1;
            String serverName = !TextUtils.isEmpty(entry.servername) ? entry.servername : host;

            Map<Integer, Channel> channelsMap = new HashMap<>();
            Map<Integer, User> usersMap = new HashMap<>();
            TTMessage msg = new TTMessage();

            while (!handle.isCancelled()) {
                long now = SystemClock.uptimeMillis();
                if (now - startMs >= TOTAL_TIMEOUT_MS) {
                    if (!loggedIn) {
                        return PeekResult.error(context.getString(R.string.server_peek_error_timeout));
                    }
                    break;
                }
                if (loggedIn && (now - loggedInAt >= POST_LOGIN_COLLECT_MS)) {
                    break;
                }

                if (!client.getMessage(msg, 80)) {
                    continue;
                }

                switch (msg.nClientEvent) {
                    case ClientEvent.CLIENTEVENT_CON_SUCCESS: {
                        String user = entry.username != null ? entry.username : "";
                        String pass = entry.password != null ? entry.password : "";
                        loginCmdId = client.doLoginEx(PEEK_NICKNAME, user, pass, "TeamTalk Extro");
                        if (loginCmdId <= 0) {
                            return PeekResult.error(context.getString(R.string.server_peek_error_auth));
                        }
                        break;
                    }
                    case ClientEvent.CLIENTEVENT_CON_FAILED:
                    case ClientEvent.CLIENTEVENT_CON_CRYPT_ERROR:
                        return PeekResult.error(context.getString(R.string.server_peek_error_unreachable));

                    case ClientEvent.CLIENTEVENT_CON_LOST:
                        if (!loggedIn) {
                            return PeekResult.error(context.getString(R.string.server_peek_error_unreachable));
                        }
                        break;

                    case ClientEvent.CLIENTEVENT_CMD_ERROR:
                        if (!loggedIn) {
                            String errText = "";
                            if (msg.clienterrormsg != null) {
                                if (!TextUtils.isEmpty(msg.clienterrormsg.szErrorMsg)) {
                                    errText = msg.clienterrormsg.szErrorMsg;
                                } else {
                                    errText = TeamTalkBase.getErrorMessage(msg.clienterrormsg.nErrorNo);
                                }
                            }
                            if (TextUtils.isEmpty(errText)) {
                                errText = context.getString(R.string.server_peek_error_auth);
                            }
                            return PeekResult.error(errText);
                        }
                        break;

                    case ClientEvent.CLIENTEVENT_CMD_MYSELF_LOGGEDIN:
                        loggedIn = true;
                        loggedInAt = SystemClock.uptimeMillis();
                        myUserId = msg.nSource > 0 ? msg.nSource : client.getMyUserID();
                        break;

                    case ClientEvent.CLIENTEVENT_CMD_SERVER_UPDATE:
                        if (msg.serverproperties != null && !TextUtils.isEmpty(msg.serverproperties.szServerName)) {
                            serverName = msg.serverproperties.szServerName;
                        }
                        break;

                    case ClientEvent.CLIENTEVENT_CMD_CHANNEL_NEW:
                    case ClientEvent.CLIENTEVENT_CMD_CHANNEL_UPDATE:
                        if (msg.channel != null) {
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
                        if (msg.user != null) {
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
                return PeekResult.error(context.getString(R.string.server_peek_error_timeout));
            }

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
            usersMap.remove(myUserId);

            // Group users by channel ID
            Map<Integer, List<String>> usersByChannel = new LinkedHashMap<>();
            List<String> lobbyUsers = new ArrayList<>();
            int realUsersCount = 0;

            for (User u : usersMap.values()) {
                if (u.nUserID == myUserId) {
                    continue;
                }
                if (PEEK_NICKNAME.equals(u.szNickname) && u.nChannelID == 0 && usersMap.size() == 1) {
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
        }
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
