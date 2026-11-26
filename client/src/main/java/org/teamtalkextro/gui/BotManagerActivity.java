package org.teamtalkextro.gui;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import dk.bearware.Channel;
import dk.bearware.TeamTalkBase;
import java.util.ArrayList;
import java.util.List;
import org.teamtalkextro.R;
import org.teamtalkextro.backend.TeamTalkConnection;
import org.teamtalkextro.backend.TeamTalkConnectionListener;
import org.teamtalkextro.backend.TeamTalkService;
import org.teamtalkextro.bot.BotConfig;
import org.teamtalkextro.bot.BotMusicSearch;
import org.teamtalkextro.bot.DjBotCatalogs;
import org.teamtalkextro.bot.DjBotEpg;
import org.teamtalkextro.bot.EmbeddedMediaBot;
import org.teamtalkextro.data.ServerEntry;

/**
 * UI controller for the embedded Android DJ Bot (TT-DJbot analog: Welcome, Guard,
 * Music, FM Radio, Volnorez, Russian Live TV + XMLTV EPG, and TTS /vo).
 */
public class BotManagerActivity extends AppCompatActivity
        implements TeamTalkConnectionListener, EmbeddedMediaBot.StateListener {

    private static final int REQUEST_PICK_AUDIO = 401;
    private static final String[] SERVICE_KEYS = {
            BotMusicSearch.SERVICE_HITMO,
            BotMusicSearch.SERVICE_MF,
            BotMusicSearch.SERVICE_RUTUBE
    };

    private TeamTalkConnection mConnection;
    private EmbeddedMediaBot boundBot;
    private List<BotConfig> profiles = new ArrayList<>();
    private BotConfig selectedProfile;
    private boolean updatingProfileSpinner = false;
    private boolean updatingServiceSpinner = false;
    private boolean passwordVisible = false;

    private View cardUpgradeNotice;
    private TextView tvStatusBadge;
    private TextView tvNowPlaying;
    private Button btnToggleBot;
    private Button btnFillCurrent;

    private Button btnPause;
    private Button btnNext;
    private Button btnStopTrack;
    private Button btnQuickRadio;
    private Button btnQuickVoln;
    private Button btnQuickTv;
    private Button btnQuickPop;
    private TextView tvVolumeLabel;
    private SeekBar seekVolume;
    private Spinner spinnerQuickService;
    private EditText etSearch;
    private Button btnSearchPlay;
    private Button btnPickFile;

    private Spinner spinnerProfile;
    private Button btnProfileNew;
    private Button btnProfileDelete;
    private EditText etProfileName;
    private EditText etHost;
    private EditText etTcpPort;
    private EditText etUdpPort;
    private MaterialSwitch switchEncrypted;
    private EditText etUsername;
    private EditText etPassword;
    private ImageButton btnTogglePass;
    private EditText etNickname;
    private EditText etChannel;
    private EditText etChannelPass;
    private Spinner spinnerDefaultService;

    private MaterialSwitch switchWelcomeEnabled;
    private EditText etWelcome;

    private MaterialSwitch switchGuardEnabled;
    private MaterialSwitch switchGuardEmptyNick;
    private MaterialSwitch switchGuardMat;
    private MaterialSwitch switchGuardBotnet;
    private MaterialSwitch switchGuardBan;
    private EditText etTrustedUsers;

    private MaterialSwitch switchVoiceAnnounce;
    private MaterialSwitch switchAutoStatus;
    private MaterialSwitch switchChannelCmds;
    private EditText etAdmins;
    private Button btnSaveProfile;

    private TextView tvLog;
    private Button btnClearLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bot_manager);
        EdgeToEdgeHelper.enableEdgeToEdge(this);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_activity_bot_manager);
        }

        initViews();
        updateUpgradeNoticeVisibility();
        setupServiceSpinners();
        loadProfilesAndPopulate(null);
        wireListeners();

        mConnection = new TeamTalkConnection(this);
        Intent intent = new Intent(getApplicationContext(), TeamTalkService.class);
        startService(intent);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    private void initViews() {
        cardUpgradeNotice = findViewById(R.id.card_djbot_upgrade_notice);
        tvStatusBadge = findViewById(R.id.bot_status_badge);
        tvNowPlaying = findViewById(R.id.bot_now_playing);
        btnToggleBot = findViewById(R.id.btn_bot_toggle);
        btnFillCurrent = findViewById(R.id.btn_bot_fill_current);

        btnPause = findViewById(R.id.btn_bot_pause);
        btnNext = findViewById(R.id.btn_bot_next);
        btnStopTrack = findViewById(R.id.btn_bot_stop);
        btnQuickRadio = findViewById(R.id.btn_bot_quick_radio);
        btnQuickVoln = findViewById(R.id.btn_bot_quick_voln);
        btnQuickTv = findViewById(R.id.btn_bot_quick_tv);
        btnQuickPop = findViewById(R.id.btn_bot_quick_pop);
        tvVolumeLabel = findViewById(R.id.tv_bot_volume_label);
        seekVolume = findViewById(R.id.seek_bot_volume);
        spinnerQuickService = findViewById(R.id.spinner_bot_quick_service);
        etSearch = findViewById(R.id.et_bot_search);
        btnSearchPlay = findViewById(R.id.btn_bot_search_play);
        btnPickFile = findViewById(R.id.btn_bot_pick_file);

        spinnerProfile = findViewById(R.id.spinner_bot_profile);
        btnProfileNew = findViewById(R.id.btn_bot_profile_new);
        btnProfileDelete = findViewById(R.id.btn_bot_profile_delete);
        etProfileName = findViewById(R.id.et_bot_profile_name);
        etHost = findViewById(R.id.et_bot_host);
        etTcpPort = findViewById(R.id.et_bot_tcp_port);
        etUdpPort = findViewById(R.id.et_bot_udp_port);
        switchEncrypted = findViewById(R.id.switch_bot_encrypted);
        etUsername = findViewById(R.id.et_bot_username);
        etPassword = findViewById(R.id.et_bot_password);
        btnTogglePass = findViewById(R.id.btn_bot_toggle_pass);
        etNickname = findViewById(R.id.et_bot_nickname);
        etChannel = findViewById(R.id.et_bot_channel);
        etChannelPass = findViewById(R.id.et_bot_channel_pass);
        spinnerDefaultService = findViewById(R.id.spinner_bot_default_service);

        switchWelcomeEnabled = findViewById(R.id.switch_bot_welcome_enabled);
        etWelcome = findViewById(R.id.et_bot_welcome);

        switchGuardEnabled = findViewById(R.id.switch_bot_guard_enabled);
        switchGuardEmptyNick = findViewById(R.id.switch_bot_guard_empty_nick);
        switchGuardMat = findViewById(R.id.switch_bot_guard_mat);
        switchGuardBotnet = findViewById(R.id.switch_bot_guard_botnet);
        switchGuardBan = findViewById(R.id.switch_bot_guard_ban);
        etTrustedUsers = findViewById(R.id.et_bot_trusted_users);

        switchVoiceAnnounce = findViewById(R.id.switch_bot_voice_announce);
        switchAutoStatus = findViewById(R.id.switch_bot_auto_status);
        switchChannelCmds = findViewById(R.id.switch_bot_channel_cmds);
        etAdmins = findViewById(R.id.et_bot_admins);
        btnSaveProfile = findViewById(R.id.btn_bot_save_profile);

        tvLog = findViewById(R.id.tv_bot_log);
        btnClearLog = findViewById(R.id.btn_bot_clear_log);
    }

    private void updateUpgradeNoticeVisibility() {
        if (cardUpgradeNotice != null) {
            cardUpgradeNotice.setVisibility(BotConfig.hasSavedProfiles(this) ? View.GONE : View.VISIBLE);
        }
    }

    private void setupServiceSpinners() {
        String[] labels = {
                getString(R.string.bot_service_hitmo),
                getString(R.string.bot_service_mf),
                getString(R.string.bot_service_rt)
        };
        ArrayAdapter<String> quickAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        quickAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerQuickService.setAdapter(quickAdapter);

        ArrayAdapter<String> defAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        defAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDefaultService.setAdapter(defAdapter);
    }

    private void loadProfilesAndPopulate(String preferId) {
        profiles = BotConfig.loadAll(this);
        String targetId = !TextUtils.isEmpty(preferId) ? preferId : BotConfig.getActiveId(this);
        int selectedIdx = 0;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < profiles.size(); i++) {
            BotConfig c = profiles.get(i);
            String label = !TextUtils.isEmpty(c.profileName) ? c.profileName : ("DJ Bot #" + (i + 1));
            if (!TextUtils.isEmpty(c.host)) {
                label += " (" + c.host + ")";
            }
            names.add(label);
            if (c.id.equals(targetId)) {
                selectedIdx = i;
            }
        }

        updatingProfileSpinner = true;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerProfile.setAdapter(adapter);
        spinnerProfile.setSelection(selectedIdx);
        updatingProfileSpinner = false;

        selectedProfile = profiles.get(selectedIdx).copy();
        BotConfig.setActiveId(this, selectedProfile.id);
        populateFieldsFromProfile(selectedProfile);
    }

    private void populateFieldsFromProfile(BotConfig cfg) {
        if (cfg == null) return;
        etProfileName.setText(cfg.profileName);
        etHost.setText(cfg.host);
        etTcpPort.setText(String.valueOf(cfg.tcpPort));
        etUdpPort.setText(String.valueOf(cfg.udpPort));
        switchEncrypted.setChecked(cfg.encrypted);
        etUsername.setText(cfg.username);
        etPassword.setText(cfg.password);
        etNickname.setText(cfg.nickname);
        etChannel.setText(cfg.channel);
        etChannelPass.setText(cfg.channelPassword);
        int svcIdx = serviceIndexOf(cfg.defaultService);
        spinnerDefaultService.setSelection(svcIdx);
        updatingServiceSpinner = true;
        spinnerQuickService.setSelection(svcIdx);
        updatingServiceSpinner = false;

        switchWelcomeEnabled.setChecked(cfg.welcomeEnabled);
        etWelcome.setText(cfg.welcomeText);

        switchGuardEnabled.setChecked(cfg.guardEnabled);
        switchGuardEmptyNick.setChecked(cfg.guardKickEmptyNick);
        switchGuardMat.setChecked(cfg.guardMatCheck);
        switchGuardBotnet.setChecked(cfg.guardBotnetNickCheck);
        switchGuardBan.setChecked(cfg.guardBanOnViolation);
        etTrustedUsers.setText(cfg.trustedUsers);

        switchVoiceAnnounce.setChecked(cfg.voiceAnnounceTrack);
        switchAutoStatus.setChecked(cfg.autoStatusTrack);
        switchChannelCmds.setChecked(cfg.allowChannelCommands);
        etAdmins.setText(cfg.adminUsers);

        seekVolume.setProgress(cfg.volume);
        tvVolumeLabel.setText(getString(R.string.bot_label_volume, cfg.volume));
    }

    private BotConfig readConfigFromFields() {
        BotConfig cfg = selectedProfile != null ? selectedProfile.copy() : BotConfig.createDefault();
        cfg.profileName = textOf(etProfileName);
        cfg.host = textOf(etHost);
        cfg.tcpPort = parsePort(textOf(etTcpPort), 10333);
        cfg.udpPort = parsePort(textOf(etUdpPort), cfg.tcpPort);
        cfg.encrypted = switchEncrypted.isChecked();
        cfg.username = textOf(etUsername);
        cfg.password = etPassword.getText() != null ? etPassword.getText().toString() : "";
        cfg.nickname = textOf(etNickname);
        cfg.channel = textOf(etChannel);
        cfg.channelPassword = etChannelPass.getText() != null ? etChannelPass.getText().toString() : "";
        int defIdx = Math.max(0, Math.min(SERVICE_KEYS.length - 1, spinnerDefaultService.getSelectedItemPosition()));
        cfg.defaultService = SERVICE_KEYS[defIdx];
        cfg.volume = seekVolume.getProgress();

        cfg.welcomeEnabled = switchWelcomeEnabled.isChecked();
        cfg.welcomeText = textOf(etWelcome);

        cfg.guardEnabled = switchGuardEnabled.isChecked();
        cfg.guardKickEmptyNick = switchGuardEmptyNick.isChecked();
        cfg.guardMatCheck = switchGuardMat.isChecked();
        cfg.guardBotnetNickCheck = switchGuardBotnet.isChecked();
        cfg.guardBanOnViolation = switchGuardBan.isChecked();
        cfg.trustedUsers = textOf(etTrustedUsers);

        cfg.voiceAnnounceTrack = switchVoiceAnnounce.isChecked();
        cfg.autoStatusTrack = switchAutoStatus.isChecked();
        cfg.allowChannelCommands = switchChannelCmds.isChecked();
        cfg.adminUsers = textOf(etAdmins);

        cfg.normalize();
        return cfg;
    }

    private void wireListeners() {
        btnToggleBot.setOnClickListener(v -> {
            TeamTalkService svc = getService();
            if (svc == null) {
                Toast.makeText(this, R.string.err_service_not_available, Toast.LENGTH_SHORT).show();
                return;
            }
            if (svc.isEmbeddedBotActive()) {
                svc.stopEmbeddedBot();
                Toast.makeText(this, R.string.bot_toast_stopped, Toast.LENGTH_SHORT).show();
            } else {
                BotConfig cfg = readConfigFromFields();
                if (TextUtils.isEmpty(cfg.host)) {
                    Toast.makeText(this, R.string.bot_err_no_host, Toast.LENGTH_SHORT).show();
                    etHost.requestFocus();
                    return;
                }
                BotConfig.upsert(this, cfg);
                selectedProfile = cfg;
                updateUpgradeNoticeVisibility();
                loadProfilesAndPopulate(cfg.id);
                svc.startEmbeddedBot(cfg);
                Toast.makeText(this, R.string.bot_toast_started, Toast.LENGTH_SHORT).show();
            }
        });

        btnFillCurrent.setOnClickListener(v -> fillFromCurrentServer());

        btnPause.setOnClickListener(v -> {
            if (boundBot != null) boundBot.togglePause();
        });

        btnNext.setOnClickListener(v -> {
            if (boundBot != null) boundBot.nextTrack();
        });

        btnStopTrack.setOnClickListener(v -> {
            if (boundBot != null) boundBot.stopPlayback();
        });

        btnQuickRadio.setOnClickListener(v -> showRadioPickerDialog());
        btnQuickVoln.setOnClickListener(v -> showVolnorezPickerDialog());
        btnQuickTv.setOnClickListener(v -> showTvPickerDialog());
        btnQuickPop.setOnClickListener(v -> {
            if (!ensureBotRunning()) return;
            boundBot.playRandomPopFromUi();
            Toast.makeText(this, R.string.djbot_toast_pop_loading, Toast.LENGTH_SHORT).show();
        });

        seekVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvVolumeLabel.setText(getString(R.string.bot_label_volume, progress));
                if (fromUser && boundBot != null) {
                    boundBot.setVolume(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        spinnerQuickService.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (updatingServiceSpinner) return;
                if (boundBot != null && position >= 0 && position < SERVICE_KEYS.length) {
                    boundBot.setService(SERVICE_KEYS[position]);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnSearchPlay.setOnClickListener(v -> triggerSearchPlay());
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                triggerSearchPlay();
                return true;
            }
            return false;
        });

        btnPickFile.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("audio/*");
            try {
                startActivityForResult(intent, REQUEST_PICK_AUDIO);
            } catch (Throwable t) {
                Toast.makeText(this, t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        spinnerProfile.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (updatingProfileSpinner) return;
                if (position >= 0 && position < profiles.size()) {
                    selectedProfile = profiles.get(position).copy();
                    BotConfig.setActiveId(BotManagerActivity.this, selectedProfile.id);
                    populateFieldsFromProfile(selectedProfile);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnProfileNew.setOnClickListener(v -> {
            BotConfig fresh = BotConfig.createDefault();
            fresh.profileName = "DJ Bot " + (profiles.size() + 1);
            BotConfig.upsert(this, fresh);
            updateUpgradeNoticeVisibility();
            loadProfilesAndPopulate(fresh.id);
            Toast.makeText(this, R.string.bot_toast_profile_created, Toast.LENGTH_SHORT).show();
        });

        btnProfileDelete.setOnClickListener(v -> {
            if (selectedProfile == null) return;
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.bot_action_delete_profile)
                    .setMessage(getString(R.string.bot_confirm_delete_profile, selectedProfile.profileName))
                    .setPositiveButton(android.R.string.yes, (d, w) -> {
                        BotConfig.deleteById(this, selectedProfile.id);
                        loadProfilesAndPopulate(null);
                        Toast.makeText(this, R.string.bot_toast_profile_deleted, Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(android.R.string.no, null)
                    .show();
        });

        btnTogglePass.setOnClickListener(v -> {
            passwordVisible = !passwordVisible;
            int sel = etPassword.getSelectionEnd();
            etPassword.setInputType(passwordVisible
                    ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                    : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
            if (sel >= 0) etPassword.setSelection(sel);
            btnTogglePass.setContentDescription(getString(passwordVisible ? R.string.hide_password : R.string.show_password));
        });

        btnSaveProfile.setOnClickListener(v -> {
            BotConfig cfg = readConfigFromFields();
            BotConfig.upsert(this, cfg);
            selectedProfile = cfg;
            updateUpgradeNoticeVisibility();
            loadProfilesAndPopulate(cfg.id);
            Toast.makeText(this, R.string.bot_toast_profile_saved, Toast.LENGTH_SHORT).show();
        });

        btnClearLog.setOnClickListener(v -> {
            if (boundBot != null) {
                boundBot.clearLogs();
            } else {
                tvLog.setText(R.string.bot_log_empty);
            }
        });
    }

    private boolean ensureBotRunning() {
        TeamTalkService svc = getService();
        if (svc == null || !svc.isEmbeddedBotActive() || boundBot == null) {
            Toast.makeText(this, R.string.bot_err_not_running, Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void showRadioPickerDialog() {
        if (!ensureBotRunning()) return;
        List<BotMusicSearch.BotTrack> stations = DjBotCatalogs.BUILT_IN_RADIO;
        CharSequence[] items = new CharSequence[stations.size()];
        for (int i = 0; i < stations.size(); i++) {
            items[i] = (i + 1) + ". " + stations.get(i).title;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.djbot_dialog_radio_title)
                .setItems(items, (d, which) -> {
                    if (which >= 0 && which < stations.size() && boundBot != null) {
                        boundBot.playTrackDirectFromUi(stations.get(which));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showVolnorezPickerDialog() {
        if (!ensureBotRunning()) return;
        Toast.makeText(this, R.string.djbot_toast_voln_loading, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<BotMusicSearch.BotTrack> stations = DjBotCatalogs.getVolnorezStations(false);
            CharSequence[] items = new CharSequence[stations.size()];
            for (int i = 0; i < stations.size(); i++) {
                items[i] = (i + 1) + ". " + stations.get(i).title;
            }
            runOnUiThread(() -> {
                if (isFinishing()) return;
                new MaterialAlertDialogBuilder(BotManagerActivity.this)
                        .setTitle(R.string.djbot_dialog_voln_title)
                        .setItems(items, (d, which) -> {
                            if (which >= 0 && which < stations.size() && boundBot != null) {
                                boundBot.playTrackDirectFromUi(stations.get(which));
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }, "Extro-VolnList").start();
    }

    private void showTvPickerDialog() {
        if (!ensureBotRunning()) return;
        List<BotMusicSearch.BotTrack> channels = DjBotCatalogs.BUILT_IN_TV;
        DjBotEpg epg = boundBot != null ? boundBot.getEpg() : null;
        if (epg != null) epg.ensureLoadedAsync();
        CharSequence[] items = new CharSequence[channels.size()];
        for (int i = 0; i < channels.size(); i++) {
            String title = channels.get(i).title;
            String label = (i + 1) + ". " + title;
            if (epg != null && epg.isLoaded()) {
                DjBotEpg.EpgInfo info = epg.getNow(title);
                if (info != null && !TextUtils.isEmpty(info.title)) {
                    label += " — " + info.title;
                }
            }
            items[i] = label;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.djbot_dialog_tv_title)
                .setItems(items, (d, which) -> {
                    if (which >= 0 && which < channels.size() && boundBot != null) {
                        boundBot.playTrackDirectFromUi(channels.get(which));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void triggerSearchPlay() {
        String query = textOf(etSearch);
        if (TextUtils.isEmpty(query)) {
            Toast.makeText(this, R.string.bot_hint_search, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ensureBotRunning()) return;
        int idx = Math.max(0, Math.min(SERVICE_KEYS.length - 1, spinnerQuickService.getSelectedItemPosition()));
        boundBot.playQueryFromUi(SERVICE_KEYS[idx], query);
    }

    private void fillFromCurrentServer() {
        TeamTalkService svc = getService();
        if (svc == null || svc.getServerEntry() == null || TextUtils.isEmpty(svc.getServerEntry().ipaddr)) {
            Toast.makeText(this, R.string.bot_err_no_active_server, Toast.LENGTH_SHORT).show();
            return;
        }
        ServerEntry srv = svc.getServerEntry();
        etHost.setText(srv.ipaddr);
        etTcpPort.setText(String.valueOf(srv.tcpport > 0 ? srv.tcpport : 10333));
        etUdpPort.setText(String.valueOf(srv.udpport > 0 ? srv.udpport : (srv.tcpport > 0 ? srv.tcpport : 10333)));
        switchEncrypted.setChecked(srv.encrypted);
        if (TextUtils.isEmpty(textOf(etProfileName)) || BotConfig.DEFAULT_PROFILE_NAME.equals(textOf(etProfileName))) {
            etProfileName.setText(!TextUtils.isEmpty(srv.servername) ? ("DJ Bot — " + srv.servername) : srv.ipaddr);
        }
        if (TextUtils.isEmpty(textOf(etUsername)) && !TextUtils.isEmpty(srv.username)) {
            etUsername.setText(srv.username);
        }
        if (TextUtils.isEmpty(etPassword.getText() != null ? etPassword.getText().toString() : "")
                && !TextUtils.isEmpty(srv.password)) {
            etPassword.setText(srv.password);
        }

        Channel curCh = svc.getMyChannel();
        TeamTalkBase client = svc.getTTInstance();
        String chPath = "/";
        if (curCh != null && curCh.nChannelID > 0) {
            String p = client != null ? client.getChannelPath(curCh.nChannelID) : "";
            chPath = !TextUtils.isEmpty(p) ? p : String.valueOf(curCh.nChannelID);
        } else if (!TextUtils.isEmpty(srv.channel)) {
            chPath = srv.channel;
        }
        etChannel.setText(chPath);
        if (!TextUtils.isEmpty(srv.chanpasswd)) {
            etChannelPass.setText(srv.chanpasswd);
        }
        Toast.makeText(this, R.string.bot_toast_filled_current, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_AUDIO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
            if (!ensureBotRunning()) return;
            boundBot.playLocalFileFromUi(uri, queryFileName(uri));
        }
    }

    private String queryFileName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = c.getString(idx);
                    if (!TextUtils.isEmpty(name)) return name;
                }
            }
        } catch (Throwable ignored) {
        }
        return uri.getLastPathSegment();
    }

    private TeamTalkService getService() {
        return mConnection != null ? mConnection.getService() : TeamTalkService.getInstance();
    }

    @Override
    public void onServiceConnected(TeamTalkService service) {
        if (service != null) {
            boundBot = service.getEmbeddedBot();
            if (boundBot != null) {
                boundBot.addStateListener(this);
            }
        }
    }

    @Override
    public void onServiceDisconnected(TeamTalkService service) {
        if (boundBot != null) {
            boundBot.removeStateListener(this);
            boundBot = null;
        }
    }

    @Override
    public void onBotStateChanged(EmbeddedMediaBot.Snapshot snap) {
        if (snap == null || isFinishing()) return;

        String badgeText;
        int badgeColor;
        switch (snap.state) {
            case CONNECTING:
                badgeText = getString(R.string.bot_state_connecting);
                badgeColor = Color.parseColor("#F57C00");
                break;
            case ONLINE:
                badgeText = getString(R.string.bot_state_online);
                badgeColor = Color.parseColor("#0288D1");
                break;
            case IN_CHANNEL:
                badgeText = getString(R.string.bot_state_in_channel,
                        !TextUtils.isEmpty(snap.channelName) ? snap.channelName : "/");
                badgeColor = Color.parseColor("#2E7D32");
                break;
            case RECONNECTING:
                badgeText = getString(R.string.bot_state_reconnecting);
                badgeColor = Color.parseColor("#E65100");
                break;
            case STOPPED:
            default:
                badgeText = getString(R.string.bot_state_stopped);
                badgeColor = Color.parseColor("#616161");
                break;
        }

        tvStatusBadge.setText(badgeText);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(16f);
        bg.setColor(badgeColor);
        tvStatusBadge.setBackground(bg);

        boolean active = snap.state != EmbeddedMediaBot.State.STOPPED;
        btnToggleBot.setText(active ? R.string.bot_action_stop : R.string.bot_action_start);

        if (!TextUtils.isEmpty(snap.epgSummary)) {
            tvNowPlaying.setText(snap.epgSummary);
        } else if (!TextUtils.isEmpty(snap.nowPlaying)) {
            StringBuilder line = new StringBuilder("🎵 ").append(snap.nowPlaying);
            if (!TextUtils.isEmpty(snap.liveStreamTitle)) {
                line.append("\n📻 ").append(snap.liveStreamTitle);
            }
            if (snap.listenersCount >= 0) {
                line.append(" • 👥 ").append(snap.listenersCount);
            }
            if (snap.paused) {
                line.append(" (").append(getString(R.string.karaoke_paused_label)).append(")");
            }
            if (snap.queueSize > 0) {
                line.append(" • ").append(getString(R.string.bot_queue_count, snap.queueSize));
            }
            tvNowPlaying.setText(line.toString());
        } else {
            tvNowPlaying.setText(R.string.bot_now_playing_idle);
        }

        btnPause.setText(snap.paused ? R.string.karaoke_resume : R.string.karaoke_pause);
        if (seekVolume.getProgress() != snap.volume) {
            seekVolume.setProgress(snap.volume);
        }
        tvVolumeLabel.setText(getString(R.string.bot_label_volume, snap.volume));

        int svcIdx = serviceIndexOf(snap.activeService);
        if (spinnerQuickService.getSelectedItemPosition() != svcIdx) {
            updatingServiceSpinner = true;
            spinnerQuickService.setSelection(svcIdx);
            updatingServiceSpinner = false;
        }
    }

    @Override
    public void onBotLogUpdated(List<String> logs) {
        if (isFinishing()) return;
        if (logs == null || logs.isEmpty()) {
            tvLog.setText(R.string.bot_log_empty);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = logs.size() - 1; i >= 0; i--) {
            sb.append(logs.get(i));
            if (i > 0) sb.append('\n');
        }
        tvLog.setText(sb.toString());
    }

    @Override
    protected void onDestroy() {
        if (boundBot != null) {
            boundBot.removeStateListener(this);
            boundBot = null;
        }
        if (mConnection != null && mConnection.isBound()) {
            try {
                unbindService(mConnection);
            } catch (Throwable ignored) {
            }
            mConnection.setBound(false);
        }
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private static int serviceIndexOf(String svc) {
        String norm = BotMusicSearch.normalizeService(svc);
        for (int i = 0; i < SERVICE_KEYS.length; i++) {
            if (SERVICE_KEYS[i].equals(norm)) return i;
        }
        return 0;
    }

    private static String textOf(EditText et) {
        return et != null && et.getText() != null ? et.getText().toString().trim() : "";
    }

    private static int parsePort(String s, int def) {
        try {
            int v = Integer.parseInt(s.trim());
            return (v > 0 && v <= 65535) ? v : def;
        } catch (Throwable ignored) {
            return def;
        }
    }
}
