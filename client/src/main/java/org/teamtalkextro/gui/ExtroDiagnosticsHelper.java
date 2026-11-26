package org.teamtalkextro.gui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import dk.bearware.ClientFlag;
import dk.bearware.ClientStatistics;
import dk.bearware.TeamTalkBase;
import org.teamtalkextro.BuildConfig;
import org.teamtalkextro.R;
import org.teamtalkextro.backend.TeamTalkService;
import org.teamtalkextro.bot.BotConfig;
import org.teamtalkextro.bot.EmbeddedMediaBot;
import org.teamtalkextro.data.ServerEntry;

/**
 * Built-in "Extro Doctor" — System, Network, Audio & DJ Bot diagnostics dialog.
 */
public final class ExtroDiagnosticsHelper {

    private ExtroDiagnosticsHelper() {
    }

    public static void showDialog(Context context, TeamTalkService service) {
        if (context == null) return;
        String report = buildReport(context, service);
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.doctor_dialog_title)
                .setMessage(report)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.doctor_btn_copy, (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("Extro Doctor", report));
                        Toast.makeText(context, R.string.doctor_copied_toast, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    public static String buildReport(Context context, TeamTalkService service) {
        StringBuilder sb = new StringBuilder();
        int swDp = ResponsiveLayoutHelper.getSmallestWidthDp(context);
        int wDp = ResponsiveLayoutHelper.getScreenWidthDp(context);
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int hDp = dm != null && dm.density > 0 ? Math.round(dm.heightPixels / dm.density) : 0;
        boolean isTablet = ResponsiveLayoutHelper.isTabletWidth(swDp);
        int gridCols = ResponsiveLayoutHelper.computeServerGridSpan(wDp);

        String abis = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                ? TextUtils.join(", ", Build.SUPPORTED_ABIS) : "unknown";

        sb.append("📱 ").append(context.getString(R.string.doctor_section_device)).append("\n");
        sb.append("• App: TeamTalk Extro v").append(BuildConfig.VERSION_NAME)
                .append(" (code ").append(BuildConfig.VERSION_CODE).append(")\n");
        sb.append("• ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                .append(" • Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("• ").append(context.getString(isTablet ? R.string.doctor_form_tablet : R.string.doctor_form_phone))
                .append(" (").append(wDp).append("×").append(hDp)
                .append(" dp, sw").append(swDp).append("dp, grid ").append(gridCols).append(" col)\n");
        sb.append("• ABI: ").append(abis).append("\n\n");

        // Network & Active Server
        sb.append("🌐 ").append(context.getString(R.string.doctor_section_network)).append("\n");
        sb.append("• Transport: ").append(detectNetworkTransport(context)).append("\n");
        TeamTalkBase client = service != null ? service.getTTInstance() : null;
        ServerEntry entry = service != null ? service.getServerEntry() : null;
        if (entry != null && !TextUtils.isEmpty(entry.ipaddr)) {
            int flags = client != null ? client.getFlags() : 0;
            boolean connected = (flags & ClientFlag.CLIENT_CONNECTED) != 0;
            sb.append("• Server: ").append(entry.ipaddr).append(":").append(entry.tcpport)
                    .append(entry.encrypted ? " (🔒 TLS)" : " (⚠️ No TLS)")
                    .append(connected ? " — ONLINE" : " — OFFLINE").append("\n");
            if (client != null && connected) {
                ClientStatistics stats = new ClientStatistics();
                if (client.getClientStatistics(stats)) {
                    sb.append("• Ping: UDP ").append(stats.nUdpPingTimeMs >= 0 ? (stats.nUdpPingTimeMs + " ms") : "n/a")
                            .append(" • TCP ").append(stats.nTcpPingTimeMs >= 0 ? (stats.nTcpPingTimeMs + " ms") : "n/a")
                            .append("\n");
                }
            }
        } else {
            sb.append("• Server: ").append(context.getString(R.string.doctor_not_connected)).append("\n");
        }
        sb.append("\n");

        // Audio & Microphone
        sb.append("🎙️ ").append(context.getString(R.string.doctor_section_audio)).append("\n");
        boolean micPerm = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        String route = "Normal";
        if (am != null) {
            if (am.isBluetoothScoOn()) route = "Bluetooth SCO";
            else if (am.isSpeakerphoneOn()) route = "Speakerphone";
            else route = "Earpiece / Default";
        }
        sb.append("• Mic permission: ").append(micPerm ? "OK" : "MISSING").append("\n");
        sb.append("• Audio route: ").append(route).append("\n\n");

        // DJ Bot Engine
        sb.append("🎧 ").append(context.getString(R.string.doctor_section_djbot)).append("\n");
        EmbeddedMediaBot bot = service != null ? service.getEmbeddedBot() : null;
        BotConfig activeCfg = bot != null && bot.isRunning()
                ? bot.getActiveConfig() : BotConfig.getActiveProfile(context);
        if (bot != null && bot.isRunning()) {
            EmbeddedMediaBot.Snapshot snap = bot.getSnapshot();
            sb.append("• Status: ").append(snap.state.name());
            if (!TextUtils.isEmpty(snap.channelName)) sb.append(" (").append(snap.channelName).append(")");
            sb.append("\n");
            sb.append("• LocalMediaRelay: ")
                    .append(snap.relayPort > 0 ? ("127.0.0.1:" + snap.relayPort) : "idle").append("\n");
            sb.append("• Stream: ")
                    .append(TextUtils.isEmpty(snap.nowPlaying) ? "idle" : snap.nowPlaying)
                    .append(snap.streamingVideo ? " [📺 Video + Audio]" : (snap.playing ? " [🎵 Audio]" : ""))
                    .append("\n");
            sb.append("• Telegram Bridge: ")
                    .append(snap.telegramConnected
                            ? ("CONNECTED (@" + snap.telegramBotUsername + ")")
                            : (!TextUtils.isEmpty(snap.telegramStatusText) ? snap.telegramStatusText : "disabled"))
                    .append("\n");
        } else {
            sb.append("• Status: STOPPED\n");
        }
        boolean hasYm = activeCfg != null && !TextUtils.isEmpty(activeCfg.yandexToken);
        boolean hasTg = activeCfg != null && !TextUtils.isEmpty(activeCfg.telegramBotToken);
        sb.append("• Yandex Music token: ").append(hasYm ? "CONFIGURED" : "not set").append("\n");
        sb.append("• Telegram Bot token: ").append(hasTg ? "CONFIGURED" : "not set").append("\n");
        if (activeCfg != null) {
            sb.append("• TV Video: ").append(activeCfg.tvAudioOnly ? "Audio-only" : (activeCfg.tvVideoBitrate + " kbps VP8"));
        }
        return sb.toString().trim();
    }

    private static String detectNetworkTransport(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "unknown";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network net = cm.getActiveNetwork();
                if (net == null) return "offline";
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) return "unknown";
                StringBuilder t = new StringBuilder();
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) t.append("Wi-Fi");
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) t.append("Cellular");
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) t.append("Ethernet");
                else t.append("Other");
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) t.append(" + VPN");
                return t.toString();
            }
        } catch (Throwable ignored) {
        }
        return "connected";
    }
}
