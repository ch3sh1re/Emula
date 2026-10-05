package dev.dunno.emula;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(27, 29, 33);
    private static final int SURFACE = Color.rgb(48, 52, 60);
    private static final int DIALOG_SURFACE = Color.rgb(53, 57, 65);
    private static final int TEXT_SECONDARY = Color.rgb(174, 181, 194);
    private static final int ACCENT = Color.rgb(113, 164, 255);
    private static final int ACTIVE_BORDER = Color.rgb(218, 240, 255);
    private static final int ERROR_TEXT = Color.rgb(255, 138, 128);

    private static final String STATE_EDITOR_OPEN = "editor.open";
    private static final String STATE_PERMISSION_PROMPT_SHOWN = "permission.prompt_shown";
    private static final String STATE_PERMISSION_DIALOG_OPEN = "permission.dialog_open";
    private static final String STATE_EDITOR_CARD_ID = "editor.card_id";
    private static final String STATE_EDITOR_NAME = "editor.name";
    private static final String STATE_EDITOR_UID = "editor.uid";
    private static final String STATE_EDITOR_MESSAGE = "editor.message";
    private static final String STATE_EDITOR_MESSAGE_ERROR = "editor.message_error";
    private static final String STATE_EDITOR_FOCUS = "editor.focus";
    private static final String STATE_EDITOR_READER = "editor.reader";
    private static final int EDITOR_FOCUS_NONE = -2;
    private static final int EDITOR_FOCUS_NAME = -1;
    private static final String LEGACY_PREFS = "legacy_nfc";
    private static final String LEGACY_PENDING_RESTORE = "pending_restore";
    private static final String LEGACY_PENDING_UNVERIFIED = "pending_unverified";
    private static final String LEGACY_ORIGINAL_CONFIGURATION = "original_configuration";
    private static final String APP_PREFS = "app_settings";
    private static final String PREF_FORCE_RAW_MODE = "force_raw_mode";
    private static final String PREF_EXPERIMENTAL_RAW_FINGERPRINT =
            "experimental_raw_fingerprint";
    private static final int MENU_RAW_MODE = 1;
    private static final int MENU_NFC_DIAGNOSTICS = 2;
    private static final int MENU_EDIT_CARD = 3;
    private static final int MENU_DELETE_CARD = 4;
    private static final int MENU_PRIVACY_POLICY = 6;
    private static final long NFC_PROGRESS_STATUS_DELAY_MILLIS = 300;
    private static final long PERMISSION_CHECK_INTERVAL_MILLIS = 1_000;
    private static final long SNACKBAR_DURATION_MILLIS = 2_600;
    private static final long ERROR_SNACKBAR_DURATION_MILLIS = 5_200;
    private static final float CARD_START_HUE = 268f;
    private static final float GOLDEN_ANGLE_DEGREES = 137.50776f;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean tagHandled = new AtomicBoolean(false);

    private NfcAdapter adapter;
    private LinearLayout cardsContainer;
    private TextView status;
    private TextView snackbar;
    private ImageButton addButton;
    private ImageButton appMenuButton;
    private Button permissionButton;
    private AlertDialog permissionDialog;
    private boolean permissionPromptShown;
    private boolean reopenPermissionDialog;

    private Dialog editorDialog;
    private SavedCard editorCard;
    private EditText editorName;
    private final EditText[] editorUid = new EditText[CardStore.UID_LENGTH];
    private TextView editorMessage;
    private Button editorReadButton;
    private boolean editorMessageIsError;
    private boolean editorReaderInterruptedForConfiguration;
    private boolean restoreEditorReaderModeOnResume;

    private volatile boolean readerMode;
    private boolean readerForEditor;
    private boolean busy;
    private boolean resumed;
    private final Runnable permissionCheck = this::refreshPermissionUi;
    private Runnable pendingNfcProgressStatus;
    private Runnable pendingSnackbarDismiss;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            permissionPromptShown = savedInstanceState.getBoolean(STATE_PERMISSION_PROMPT_SHOWN);
            reopenPermissionDialog = savedInstanceState.getBoolean(STATE_PERMISSION_DIALOG_OPEN);
        }
        // Edge-to-edge is enforced on every supported version (minSdk 35, targetSdk 36).
        // Draw the background behind the bars and keep their icons light for our dark UI.
        getWindow().setBackgroundDrawable(new ColorDrawable(BACKGROUND));
        adapter = NfcAdapter.getDefaultAdapter(this);
        setContentView(buildUi());
        getWindow().getInsetsController().setSystemBarsAppearance(0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        renderCards();

        boolean coldProcess = CardSession.beginProcess();
        if (coldProcess) {
            CardSession.clearActiveCard();
            restoreDefaultsAtSessionStart();
        } else {
            showIdleStatus();
        }

        if (savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_EDITOR_OPEN, false)) {
            restoreCardEditor(savedInstanceState);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refreshPermissionUi();
        if (restoreEditorReaderModeOnResume) {
            restoreEditorReaderModeOnResume = false;
            if (editorDialog != null && editorDialog.isShowing()) {
                Window editorWindow = editorDialog.getWindow();
                if (editorWindow != null) {
                    editorWindow.getDecorView().post(this::enableReaderModeForEditor);
                }
            }
        }
        showPermissionRequestIfNeeded();
    }

    @Override
    protected void onPause() {
        if (isChangingConfigurations() && readerMode && readerForEditor) {
            editorReaderInterruptedForConfiguration = true;
        }
        resumed = false;
        stopPermissionChecks();
        stopReaderMode();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_PERMISSION_PROMPT_SHOWN, permissionPromptShown);
        outState.putBoolean(STATE_PERMISSION_DIALOG_OPEN,
                permissionDialog != null && permissionDialog.isShowing());
        if (editorDialog != null && editorDialog.isShowing()) {
            outState.putBoolean(STATE_EDITOR_OPEN, true);
            if (editorCard != null) outState.putString(STATE_EDITOR_CARD_ID, editorCard.id);
            outState.putString(STATE_EDITOR_NAME, editorName.getText().toString());

            String[] uidParts = new String[editorUid.length];
            for (int i = 0; i < editorUid.length; i++) {
                uidParts[i] = editorUid[i].getText().toString();
            }
            outState.putStringArray(STATE_EDITOR_UID, uidParts);
            outState.putString(STATE_EDITOR_MESSAGE, editorMessage.getText().toString());
            outState.putBoolean(STATE_EDITOR_MESSAGE_ERROR, editorMessageIsError);
            outState.putBoolean(STATE_EDITOR_READER,
                    (readerMode && readerForEditor)
                            || editorReaderInterruptedForConfiguration);

            int focusedField = EDITOR_FOCUS_NONE;
            if (editorName.hasFocus()) {
                focusedField = EDITOR_FOCUS_NAME;
            } else {
                for (int i = 0; i < editorUid.length; i++) {
                    if (editorUid[i].hasFocus()) {
                        focusedField = i;
                        break;
                    }
                }
            }
            outState.putInt(STATE_EDITOR_FOCUS, focusedField);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        stopPermissionChecks();
        if (permissionDialog != null) permissionDialog.dismiss();
        cancelNfcProgressStatus();
        cancelSnackbar();
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(BACKGROUND);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        root.setPadding(dp(20), dp(24), dp(20), dp(20));
        screen.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        snackbar = new TextView(this);
        snackbar.setTextColor(Color.rgb(26, 39, 56));
        snackbar.setTextSize(14);
        snackbar.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        snackbar.setGravity(Gravity.CENTER_VERTICAL);
        snackbar.setPadding(dp(18), 0, dp(18), 0);
        snackbar.setBackground(rounded(Color.rgb(238, 244, 252), 14));
        snackbar.setElevation(dp(12));
        snackbar.setAlpha(0f);
        snackbar.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams snackbarParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(52),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        snackbarParams.leftMargin = dp(20);
        snackbarParams.rightMargin = dp(20);
        snackbarParams.bottomMargin = dp(20);
        screen.addView(snackbar, snackbarParams);

        screen.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets safeInsets = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int leftInset = safeInsets.left;
            int topInset = safeInsets.top;
            int rightInset = safeInsets.right;
            int bottomInset = safeInsets.bottom;
            root.setPadding(dp(20) + leftInset, dp(24) + topInset,
                    dp(20) + rightInset, dp(20) + bottomInset);
            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) snackbar.getLayoutParams();
            params.leftMargin = dp(20) + leftInset;
            params.rightMargin = dp(20) + rightInset;
            params.bottomMargin = dp(20) + bottomInset;
            snackbar.setLayoutParams(params);
            return windowInsets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView heading = new TextView(this);
        heading.setText(R.string.screen_title);
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(30);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(heading, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        addButton = new ImageButton(this);
        addButton.setImageResource(R.drawable.ic_add_24);
        addButton.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        addButton.setScaleType(ImageView.ScaleType.CENTER);
        addButton.setPadding(dp(12), dp(12), dp(12), dp(12));
        applyBorderlessRipple(addButton);
        addButton.setContentDescription(getString(R.string.add_card));
        addButton.setClickable(true);
        addButton.setFocusable(true);
        addButton.setOnClickListener(v -> {
            if (!busy && !readerMode) showCardEditor(null);
        });
        header.addView(addButton, new LinearLayout.LayoutParams(dp(48), dp(48)));

        appMenuButton = new ImageButton(this);
        appMenuButton.setImageResource(R.drawable.ic_more_vert_24);
        appMenuButton.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        appMenuButton.setScaleType(ImageView.ScaleType.CENTER);
        appMenuButton.setPadding(dp(12), dp(12), dp(12), dp(12));
        applyBorderlessRipple(appMenuButton);
        appMenuButton.setContentDescription(getString(R.string.app_menu));
        appMenuButton.setClickable(true);
        appMenuButton.setFocusable(true);
        appMenuButton.setOnClickListener(this::showAppMenu);
        LinearLayout.LayoutParams appMenuParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        appMenuParams.leftMargin = dp(4);
        header.addView(appMenuButton, appMenuParams);

        TextView caption = new TextView(this);
        caption.setText(R.string.screen_caption);
        caption.setTextColor(Color.rgb(143, 150, 164));
        caption.setTextSize(14);
        caption.setPadding(0, dp(6), 0, dp(18));
        root.addView(caption);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        cardsContainer = new LinearLayout(this);
        cardsContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(cardsContainer, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        status = new TextView(this);
        status.setTextColor(TEXT_SECONDARY);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setMinHeight(dp(48));
        status.setGravity(Gravity.CENTER);
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        permissionButton = new Button(this);
        permissionButton.setAllCaps(false);
        permissionButton.setText(R.string.copy_adb_command);
        permissionButton.setTextColor(Color.WHITE);
        permissionButton.setBackground(rounded(SURFACE, 14));
        permissionButton.setOnClickListener(v -> copyGrantCommand());
        root.addView(permissionButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));

        TextView note = new TextView(this);
        note.setText(R.string.local_storage_note);
        note.setTextColor(Color.rgb(105, 111, 123));
        note.setTextSize(12);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(12), 0, 0);
        root.addView(note);

        return screen;
    }

    private void renderCards() {
        if (cardsContainer == null) return;
        cardsContainer.removeAllViews();
        List<SavedCard> cards = CardStore.getCards(this);
        if (cards.isEmpty()) {
            cardsContainer.addView(createEmptyCard(), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(164)));
        } else {
            for (int i = 0; i < cards.size(); i++) {
                View cardView = createCardView(cards.get(i), i);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(132));
                params.bottomMargin = dp(14);
                cardsContainer.addView(cardView, params);
            }
        }

        boolean controlsEnabled = !busy && !readerMode;
        cardsContainer.setAlpha(1f);
        addButton.setEnabled(controlsEnabled);
        addButton.setAlpha(1f);
        appMenuButton.setEnabled(controlsEnabled);
        appMenuButton.setAlpha(1f);
        refreshPermissionUi();
    }

    private View createEmptyCard() {
        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(24), dp(20), dp(24), dp(20));
        empty.setBackground(rounded(Color.rgb(43, 47, 54), 22));
        empty.setClickable(true);
        empty.setFocusable(true);
        empty.setOnClickListener(v -> {
            if (!busy && !readerMode) showCardEditor(null);
        });

        TextView plus = new TextView(this);
        plus.setText("+");
        plus.setTextColor(Color.WHITE);
        plus.setTextSize(30);
        plus.setGravity(Gravity.CENTER);
        empty.addView(plus);

        TextView text = new TextView(this);
        text.setText(R.string.add_first_card);
        text.setTextColor(Color.WHITE);
        text.setTextSize(17);
        text.setGravity(Gravity.CENTER);
        empty.addView(text);
        return empty;
    }

    private View createCardView(SavedCard card, int index) {
        boolean active = CardSession.isActive(card.id);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(16), dp(14), dp(16));
        panel.setBackground(cardBackground(index, active));
        panel.setClickable(true);
        panel.setFocusable(true);
        panel.setElevation(dp(active ? 9 : 2));
        panel.setContentDescription(getString(R.string.card_content_description,
                card.name,
                Hex.bytes(card.uid),
                getString(active ? R.string.card_state_in_use : R.string.card_state_ready)));
        panel.setOnClickListener(v -> toggleCard(card));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText(card.name);
        title.setTextColor(Color.argb(active ? 255 : 226, 255, 255, 255));
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        if (active) {
            TextView badge = new TextView(this);
            badge.setText(R.string.card_state_in_use);
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(10);
            badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            badge.setAllCaps(true);
            badge.setGravity(Gravity.CENTER);
            badge.setPadding(dp(10), 0, dp(10), 0);
            badge.setBackground(rounded(Color.argb(58, 255, 255, 255), 11));
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(26));
            badgeParams.leftMargin = dp(10);
            badgeParams.rightMargin = dp(4);
            top.addView(badge, badgeParams);
        }

        TextView menu = new TextView(this);
        menu.setText("⋮");
        menu.setTextColor(Color.argb(active ? 255 : 210, 255, 255, 255));
        menu.setTextSize(28);
        menu.setGravity(Gravity.CENTER);
        menu.setContentDescription(getString(R.string.card_menu_content_description, card.name));
        menu.setClickable(true);
        menu.setFocusable(true);
        menu.setOnClickListener(v -> showCardMenu(v, card));
        top.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView uid = new TextView(this);
        uid.setText(getString(R.string.card_uid, Hex.bytes(card.uid)));
        uid.setTextColor(Color.argb(active ? 224 : 184, 255, 255, 255));
        uid.setTextSize(14);
        uid.setPadding(0, dp(7), 0, 0);
        panel.addView(uid);
        return panel;
    }

    private void toggleCard(SavedCard card) {
        if (busy || readerMode) return;
        if (CardSession.isActive(card.id)) {
            setActiveCard(null, null, getString(R.string.progress_restore_standard_nfc));
        } else {
            setActiveCard(card, null, getString(R.string.progress_activate_card, card.name));
        }
    }

    private void showCardMenu(View anchor, SavedCard card) {
        if (busy || readerMode) return;
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(Menu.NONE, MENU_EDIT_CARD, Menu.NONE, R.string.menu_edit);
        popup.getMenu().add(Menu.NONE, MENU_DELETE_CARD, Menu.NONE, R.string.menu_delete);
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_EDIT_CARD) {
                showCardEditor(card);
            } else if (item.getItemId() == MENU_DELETE_CARD) {
                confirmDelete(card);
            }
            return true;
        });
        popup.show();
    }

    private void showAppMenu(View anchor) {
        if (busy || readerMode) return;
        PopupMenu popup = new PopupMenu(this, anchor);
        boolean modernApi = OemNfcController.isUidEmulationSupported(this);
        boolean rawAvailable = LegacyNfcController.isExperimentalAvailable(this);
        if (rawAvailable) {
            boolean rawOnly = !modernApi;
            MenuItem rawMode = popup.getMenu().add(
                    Menu.NONE, MENU_RAW_MODE, Menu.NONE, R.string.menu_raw_mode);
            rawMode.setCheckable(true);
            rawMode.setChecked(rawOnly || isRawModeForced());
            rawMode.setEnabled(!rawOnly);
        }
        popup.getMenu().add(
                Menu.NONE, MENU_NFC_DIAGNOSTICS, Menu.NONE, R.string.menu_nfc_diagnostics);
        popup.getMenu().add(
                Menu.NONE, MENU_PRIVACY_POLICY, Menu.NONE, R.string.menu_privacy_policy);
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_RAW_MODE) {
                if (isRawModeForced()) {
                    setRawModeForced(false);
                } else {
                    confirmRawModeEnable();
                }
            } else if (item.getItemId() == MENU_NFC_DIAGNOSTICS) {
                showNfcDiagnostics();
            } else if (item.getItemId() == MENU_PRIVACY_POLICY) {
                openExternalPage(R.string.privacy_policy_url);
            }
            return true;
        });
        popup.show();
    }

    private void confirmRawModeEnable() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.raw_mode_dialog_title)
                .setMessage(R.string.raw_mode_dialog_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_enable,
                        (dialog, which) -> setRawModeForced(true))
                .show();
    }

    private void showNfcDiagnostics() {
        OemNfcController.Capabilities oemCapabilities =
                OemNfcController.inspectCapabilities(this);
        boolean rawApi = LegacyNfcDiagnostics.isRawNciApiAvailable(this);
        boolean permission = hasWriteSecureSettings();
        String initialSummary = diagnosticSummary(
                oemCapabilities, rawApi, permission, null);
        String[] detailedSummary = new String[] {
                diagnosticDetailedSummary(oemCapabilities, rawApi, permission, null)
        };

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(22), dp(24), dp(14));
        panel.setBackground(rounded(DIALOG_SURFACE, 22));

        TextView title = new TextView(this);
        title.setText(R.string.menu_nfc_diagnostics);
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(12));
        panel.addView(title);

        TextView diagnosticsText = new TextView(this);
        diagnosticsText.setText(initialSummary);
        diagnosticsText.setTextColor(Color.WHITE);
        diagnosticsText.setTextSize(14);
        diagnosticsText.setTextIsSelectable(true);
        diagnosticsText.setPadding(0, 0, dp(4), dp(8));

        ScrollView reportScroll = new ScrollView(this);
        reportScroll.setFillViewport(true);
        reportScroll.setClipToPadding(false);
        reportScroll.addView(diagnosticsText, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        panel.addView(reportScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        boolean canProbe = rawApi && permission && adapter != null && adapter.isEnabled();
        Button probeButton = null;
        if (canProbe) {
            probeButton = new Button(this);
            probeButton.setAllCaps(false);
            probeButton.setText(R.string.diagnostics_check_raw_nci);
            probeButton.setTextColor(Color.WHITE);
            probeButton.setTextSize(15);
            probeButton.setBackground(rounded(Color.rgb(59, 112, 211), 14));
            LinearLayout.LayoutParams probeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
            probeParams.topMargin = dp(8);
            panel.addView(probeButton, probeParams);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        actionsParams.topMargin = dp(4);
        panel.addView(actions, actionsParams);

        Button copyButton = dialogAction(R.string.diagnostics_copy);
        copyButton.setSingleLine(true);
        copyButton.setEllipsize(TextUtils.TruncateAt.END);
        copyButton.setOnClickListener(v -> copyDiagnostics(detailedSummary[0]));
        actions.addView(copyButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

        Button emailButton = dialogAction(R.string.diagnostics_email);
        emailButton.setSingleLine(true);
        emailButton.setEllipsize(TextUtils.TruncateAt.END);
        emailButton.setOnClickListener(v -> confirmEmailDiagnostics(detailedSummary[0]));
        actions.addView(emailButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

        Button closeButton = dialogAction(R.string.action_close);
        closeButton.setSingleLine(true);
        closeButton.setEllipsize(TextUtils.TruncateAt.END);
        closeButton.setOnClickListener(v -> dialog.dismiss());
        actions.addView(closeButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

        if (probeButton != null) {
            Button activeProbeButton = probeButton;
            activeProbeButton.setOnClickListener(v -> {
                activeProbeButton.setEnabled(false);
                copyButton.setEnabled(false);
                emailButton.setEnabled(false);
                diagnosticsText.setText(initialSummary + "\n\n"
                        + getString(R.string.diagnostics_reading));
                reportScroll.scrollTo(0, 0);
                executor.execute(() -> {
                    try {
                        LegacyNfcDiagnostics.ProbeResult result =
                                LegacyNfcDiagnostics.runReadOnlyProbe(this);
                        runOnUiThread(() -> {
                            if (!dialog.isShowing()) return;
                            diagnosticsText.setText(diagnosticSummary(
                                    oemCapabilities, rawApi, permission,
                                    result.userSummary));
                            detailedSummary[0] = diagnosticDetailedSummary(
                                    oemCapabilities, rawApi, permission,
                                    result.detailedSummary);
                            reportScroll.scrollTo(0, 0);
                            activeProbeButton.setEnabled(true);
                            copyButton.setEnabled(true);
                            emailButton.setEnabled(true);
                        });
                    } catch (Throwable t) {
                        runOnUiThread(() -> {
                            if (!dialog.isShowing()) return;
                            String failure = getString(
                                    R.string.diagnostics_failed, safeMessage(t));
                            diagnosticsText.setText(diagnosticSummary(
                                    oemCapabilities, rawApi, permission, failure));
                            detailedSummary[0] = diagnosticDetailedSummary(
                                    oemCapabilities, rawApi, permission, failure);
                            reportScroll.scrollTo(0, 0);
                            activeProbeButton.setEnabled(true);
                            copyButton.setEnabled(true);
                            emailButton.setEnabled(true);
                        });
                    }
                });
            });
        }

        dialog.setContentView(panel);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = getEditorDialogWidth();
            params.height = getDiagnosticsDialogHeight();
            params.dimAmount = 0.68f;
            window.setAttributes(params);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
    }

    private int getDiagnosticsDialogHeight() {
        int availableHeight = getResources().getDisplayMetrics().heightPixels;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics metrics = getWindowManager().getCurrentWindowMetrics();
            Insets safeInsets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            availableHeight = metrics.getBounds().height() - safeInsets.top - safeInsets.bottom;
        }
        return Math.max(1, Math.min(availableHeight - dp(36), dp(720)));
    }

    private String diagnosticSummary(OemNfcController.Capabilities oemCapabilities,
            boolean rawApi,
            boolean permission, String result) {
        StringBuilder summary = new StringBuilder("Emula ")
                .append(BuildConfig.VERSION_NAME).append(" (")
                .append(BuildConfig.VERSION_CODE).append(")\n\nDEVICE\n")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                .append("Android ").append(Build.VERSION.RELEASE)
                .append(" / API ").append(Build.VERSION.SDK_INT)
                .append("\n\nCAPABILITIES\n")
                .append(oemCapabilities.format(false))
                .append('\n')
                .append("Raw NCI API: ").append(rawApi ? "available" : "unavailable")
                .append('\n')
                .append("Permission: ").append(permission ? "granted" : "not granted")
                .append('\n')
                .append("Catalog profile: ")
                .append(RawNfcDeviceCatalog.profileDescription(
                        Build.MANUFACTURER, Build.MODEL))
                .append('\n')
                .append("Polling method: ")
                .append(rawApi ? LegacyNfcController.pollingMethod(this) : "UNAVAILABLE")
                .append("\n\n")
                .append(OemNfcController.lastOperationForDisplay())
                .append("\n\n")
                .append(LegacyNfcController.lastOperationForDisplay());
        if (result != null) summary.append("\n\n").append(result);
        if (rawApi) {
            summary.append("\n\n").append(getString(R.string.diagnostics_read_only_note));
        }
        return summary.toString();
    }

    private String diagnosticDetailedSummary(
            OemNfcController.Capabilities oemCapabilities, boolean rawApi,
            boolean permission, String result) {
        StringBuilder summary = new StringBuilder("APP\n")
                .append(BuildConfig.APPLICATION_ID).append('\n')
                .append("Version: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
                .append("NFC enabled: ").append(adapter != null && adapter.isEnabled())
                .append('\n').append("Force raw mode: ").append(isRawModeForced())
                .append("\n\nDEVICE\n")
                .append("Build.MANUFACTURER: ").append(Build.MANUFACTURER).append('\n')
                .append("Build.MODEL: ").append(Build.MODEL).append('\n')
                .append("Build.DEVICE: ").append(Build.DEVICE).append('\n')
                .append("Build.PRODUCT: ").append(Build.PRODUCT).append('\n')
                .append("Build.HARDWARE: ").append(Build.HARDWARE).append('\n')
                .append("Build.FINGERPRINT: ").append(Build.FINGERPRINT).append('\n')
                .append("Android: ").append(Build.VERSION.RELEASE)
                .append(" / API ").append(Build.VERSION.SDK_INT)
                .append("\n\nCAPABILITIES\n")
                .append(oemCapabilities.format(true))
                .append('\n')
                .append("Raw NCI API: ").append(rawApi ? "available" : "unavailable")
                .append('\n')
                .append("Permission: ").append(permission ? "granted" : "not granted")
                .append('\n')
                .append("Catalog profile: ")
                .append(RawNfcDeviceCatalog.profileDescription(
                        Build.MANUFACTURER, Build.MODEL))
                .append('\n')
                .append("Polling method: ")
                .append(rawApi ? LegacyNfcController.pollingMethod(this) : "UNAVAILABLE")
                .append("\n\n")
                .append(OemNfcController.lastOperationDetailed())
                .append("\n\n")
                .append(LegacyNfcController.lastOperationDetailed());
        if (result != null) summary.append("\n\n").append(result);
        return summary.toString();
    }

    private void copyDiagnostics(String diagnostics) {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(
                getString(R.string.diagnostics_clip_label), diagnostics));
        showSnackbar(R.string.diagnostics_copied);
    }

    private void confirmEmailDiagnostics(String diagnostics) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.diagnostics_email_title)
                .setMessage(getString(R.string.diagnostics_email_message,
                        getString(R.string.diagnostics_email_address)))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.diagnostics_open_email,
                        (dialog, which) -> openDiagnosticsEmail(diagnostics))
                .show();
    }

    private void openDiagnosticsEmail(String diagnostics) {
        String subject = getString(R.string.diagnostics_email_subject,
                Build.MANUFACTURER, Build.MODEL);
        String body = getString(R.string.diagnostics_email_body, diagnostics);
        Uri mailto = Uri.parse("mailto:" + getString(R.string.diagnostics_email_address)
                + "?subject=" + Uri.encode(subject) + "&body=" + Uri.encode(body));
        try {
            startActivity(new Intent(Intent.ACTION_SENDTO, mailto));
        } catch (ActivityNotFoundException e) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.diagnostics_email_unavailable_title)
                    .setMessage(getString(R.string.diagnostics_email_unavailable_message,
                            getString(R.string.diagnostics_email_address)))
                    .setNegativeButton(R.string.action_close, null)
                    .setPositiveButton(R.string.diagnostics_copy,
                            (dialog, which) -> copyDiagnostics(diagnostics))
                    .show();
        }
    }

    private void confirmDelete(SavedCard card) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_card_title)
                .setMessage(getString(R.string.delete_card_message, card.name))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.menu_delete, (dialog, which) -> deleteCard(card))
                .show();
    }

    private void deleteCard(SavedCard card) {
        Runnable remove = () -> {
            CardStore.delete(this, card.id);
            renderCards();
            showIdleStatus();
            showSnackbar(R.string.card_deleted);
        };
        if (CardSession.isActive(card.id)) {
            setActiveCard(null, remove, getString(R.string.progress_disable_before_delete));
        } else {
            remove.run();
        }
    }

    private void showCardEditor(SavedCard card) {
        if (busy || readerMode) return;
        editorCard = card;
        editorDialog = new Dialog(this);
        editorDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(22), dp(24), dp(18));
        form.setBackground(rounded(DIALOG_SURFACE, 22));

        TextView title = new TextView(this);
        title.setText(card == null ? R.string.editor_add_title : R.string.editor_edit_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(18));
        form.addView(title);

        TextView nameLabel = editorLabel(R.string.editor_name_label);
        form.addView(nameLabel);
        editorName = new EditText(this);
        editorName.setSingleLine(true);
        editorName.setHint(R.string.editor_name_hint);
        editorName.setHintTextColor(Color.rgb(133, 139, 151));
        editorName.setTextColor(Color.WHITE);
        editorName.setTextSize(17);
        editorName.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        if (card != null) editorName.setText(card.name);
        form.addView(editorName, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        TextView uidLabel = editorLabel(R.string.editor_uid_label);
        uidLabel.setPadding(0, dp(18), 0, dp(6));
        form.addView(uidLabel);

        LinearLayout uidRow = new LinearLayout(this);
        uidRow.setOrientation(LinearLayout.HORIZONTAL);
        form.addView(uidRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));
        for (int i = 0; i < editorUid.length; i++) {
            EditText field = createUidField(i);
            editorUid[i] = field;
            LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1);
            if (i > 0) fieldParams.leftMargin = dp(8);
            uidRow.addView(field, fieldParams);
            if (card != null) {
                field.setText(String.format(Locale.US, "%02X", card.uid[i] & 0xFF));
            }
        }

        editorReadButton = new Button(this);
        editorReadButton.setAllCaps(false);
        editorReadButton.setText(R.string.editor_read_card);
        editorReadButton.setTextColor(Color.WHITE);
        editorReadButton.setTextSize(15);
        editorReadButton.setBackground(rounded(Color.rgb(59, 112, 211), 14));
        editorReadButton.setOnClickListener(v -> startReadingIntoEditor());
        LinearLayout.LayoutParams readParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        readParams.topMargin = dp(16);
        form.addView(editorReadButton, readParams);

        editorMessage = new TextView(this);
        editorMessage.setText(R.string.editor_read_card_hint);
        editorMessage.setTextColor(TEXT_SECONDARY);
        editorMessage.setTextSize(13);
        editorMessage.setGravity(Gravity.CENTER);
        editorMessage.setMinHeight(dp(42));
        form.addView(editorMessage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        form.addView(actions);

        Button cancel = dialogAction(R.string.action_cancel);
        cancel.setOnClickListener(v -> editorDialog.dismiss());
        actions.addView(cancel);

        Button save = dialogAction(R.string.action_save);
        save.setOnClickListener(v -> saveEditor());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(48));
        saveParams.leftMargin = dp(8);
        actions.addView(save, saveParams);

        ScrollView formScroll = new ScrollView(this);
        formScroll.setFillViewport(false);
        formScroll.setClipToPadding(false);
        formScroll.addView(form, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        editorDialog.setContentView(formScroll);
        editorDialog.setOnDismissListener(dialog -> {
            stopReaderMode();
            clearEditorReferences();
        });
        editorDialog.show();
        Window window = editorDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = getEditorDialogWidth();
            params.height = getEditorDialogHeight();
            params.dimAmount = 0.68f;
            window.setAttributes(params);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
    }

    private int getEditorDialogWidth() {
        int availableWidth = getResources().getDisplayMetrics().widthPixels;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics metrics = getWindowManager().getCurrentWindowMetrics();
            Insets safeInsets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            availableWidth = metrics.getBounds().width() - safeInsets.left - safeInsets.right;
        }
        return Math.max(1, Math.min(availableWidth - dp(36), dp(520)));
    }

    private int getEditorDialogHeight() {
        int availableHeight = getResources().getDisplayMetrics().heightPixels;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics metrics = getWindowManager().getCurrentWindowMetrics();
            Insets safeInsets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            availableHeight = metrics.getBounds().height() - safeInsets.top - safeInsets.bottom;
        }
        // Portrait has enough room for natural wrap-content sizing. In a short landscape
        // window the dialog stays inside the safe area and its form becomes scrollable.
        if (availableHeight >= dp(560)) {
            return WindowManager.LayoutParams.WRAP_CONTENT;
        }
        return Math.max(1, availableHeight - dp(36));
    }

    private void restoreCardEditor(Bundle state) {
        String cardId = state.getString(STATE_EDITOR_CARD_ID);
        SavedCard card = null;
        if (cardId != null) {
            for (SavedCard candidate : CardStore.getCards(this)) {
                if (cardId.equals(candidate.id)) {
                    card = candidate;
                    break;
                }
            }
        }

        // A restored Activity is allowed to recreate its own window even while the brief
        // cold-start NFC reset is completing after process death.
        boolean wasBusy = busy;
        busy = false;
        showCardEditor(card);
        busy = wasBusy;
        if (editorDialog == null) return;

        String restoredName = state.getString(STATE_EDITOR_NAME);
        if (restoredName != null) editorName.setText(restoredName);
        String[] uidParts = state.getStringArray(STATE_EDITOR_UID);
        if (uidParts != null) {
            for (int i = 0; i < editorUid.length && i < uidParts.length; i++) {
                editorUid[i].setText(uidParts[i]);
            }
        }
        String restoredMessage = state.getString(STATE_EDITOR_MESSAGE);
        if (restoredMessage != null && !restoredMessage.isEmpty()) {
            setEditorMessage(restoredMessage,
                    state.getBoolean(STATE_EDITOR_MESSAGE_ERROR, false));
        }

        int focusedField = state.getInt(STATE_EDITOR_FOCUS, EDITOR_FOCUS_NONE);
        EditText focusTarget = null;
        if (focusedField == EDITOR_FOCUS_NAME) {
            focusTarget = editorName;
        } else if (focusedField >= 0 && focusedField < editorUid.length) {
            focusTarget = editorUid[focusedField];
        }
        if (focusTarget != null) {
            focusTarget.requestFocus();
            focusTarget.setSelection(focusTarget.length());
        }

        editorReaderInterruptedForConfiguration = false;
        restoreEditorReaderModeOnResume = state.getBoolean(STATE_EDITOR_READER, false);
    }

    private TextView editorLabel(int textResource) {
        TextView label = new TextView(this);
        label.setText(textResource);
        label.setTextColor(TEXT_SECONDARY);
        label.setTextSize(13);
        return label;
    }

    private EditText createUidField(int index) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setGravity(Gravity.CENTER);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(Color.rgb(130, 136, 148));
        field.setHint(R.string.uid_field_hint);
        field.setTextSize(20);
        field.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        if (Build.VERSION.SDK_INT >= 24) {
            field.setImeHintLocales(new LocaleList(Locale.US));
        }
        field.setFilters(new InputFilter[] {
                (source, start, end, destination, destinationStart, destinationEnd) -> {
                    StringBuilder filtered = new StringBuilder(end - start);
                    boolean changed = false;
                    for (int i = start; i < end; i++) {
                        char value = source.charAt(i);
                        boolean hex = (value >= '0' && value <= '9')
                                || (value >= 'a' && value <= 'f')
                                || (value >= 'A' && value <= 'F');
                        if (hex) {
                            filtered.append(value);
                        } else {
                            changed = true;
                        }
                    }
                    return changed ? filtered.toString() : null;
                },
                new InputFilter.AllCaps(),
                new InputFilter.LengthFilter(2)
        });
        field.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable editable) {
                if (editable.length() == 2 && field.hasFocus() && index + 1 < editorUid.length
                        && editorUid[index + 1] != null) {
                    editorUid[index + 1].requestFocus();
                }
            }
        });
        return field;
    }

    private Button dialogAction(int textResource) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(textResource);
        button.setTextColor(ACCENT);
        button.setTextSize(15);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(14), 0, dp(14), 0);
        return button;
    }

    private void saveEditor() {
        if (editorDialog == null) return;
        String name = editorName.getText().toString().trim();
        if (name.isEmpty()) {
            setEditorMessage(R.string.editor_name_required, true);
            editorName.requestFocus();
            return;
        }

        byte[] uid = readEditorUid();
        if (uid == null) {
            setEditorMessage(R.string.editor_uid_required, true);
            return;
        }

        Runnable save = () -> {
            SavedCard saved = CardStore.save(this, editorCard, name, uid);
            if (editorDialog != null) editorDialog.dismiss();
            renderCards();
            showIdleStatus();
            showSnackbar(getString(R.string.editor_card_saved, saved.name));
        };

        if (editorCard != null && CardSession.isActive(editorCard.id)
                && !editorCard.hasSameUid(uid)) {
            setEditorMessage(R.string.progress_disable_edited_card, false);
            setActiveCard(null, save, getString(R.string.progress_disable_edited_card));
        } else {
            save.run();
        }
    }

    private byte[] readEditorUid() {
        String[] parts = new String[editorUid.length];
        for (int i = 0; i < editorUid.length; i++) {
            parts[i] = editorUid[i].getText().toString();
        }
        return UidCodec.parse(parts);
    }

    private void startReadingIntoEditor() {
        if (editorDialog == null || !editorDialog.isShowing() || busy || readerMode) return;
        hideKeyboard();
        if (CardSession.getActiveCardId() != null) {
            Runnable start = () -> {
                setStatus(R.string.standard_nfc_restored);
                enableReaderModeForEditor();
            };
            setActiveCard(null, start, getString(R.string.progress_disable_before_reading));
        } else {
            enableReaderModeForEditor();
        }
    }

    private void enableReaderModeForEditor() {
        if (!resumed || adapter == null || editorDialog == null || !editorDialog.isShowing()) {
            return;
        }
        if (!adapter.isEnabled()) {
            setEditorMessage(R.string.enable_nfc_first, true);
            return;
        }
        try {
            tagHandled.set(false);
            readerMode = true;
            readerForEditor = true;
            editorReadButton.setText(R.string.editor_hold_card);
            editorReadButton.setEnabled(false);
            editorReadButton.setAlpha(0.72f);
            setEditorMessage(R.string.editor_hold_card_instruction, false);
            renderCards();
            adapter.enableReaderMode(this, this::onTagDiscovered,
                    NfcAdapter.FLAG_READER_NFC_A
                            | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
                            | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                    null);
        } catch (Throwable t) {
            readerMode = false;
            readerForEditor = false;
            editorReadButton.setText(R.string.editor_read_card);
            editorReadButton.setEnabled(true);
            editorReadButton.setAlpha(1f);
            setEditorMessage(getString(R.string.editor_reader_failed, safeMessage(t)), true);
            renderCards();
        }
    }

    private void onTagDiscovered(Tag tag) {
        if (!readerMode || !tagHandled.compareAndSet(false, true)) return;
        byte[] discovered = tag.getId();
        byte[] uid = discovered == null ? null : Arrays.copyOf(discovered, discovered.length);
        runOnUiThread(() -> {
            boolean fillEditor = readerForEditor;
            stopReaderMode();
            if (!fillEditor || editorDialog == null || !editorDialog.isShowing()) return;
            if (uid == null || uid.length != CardStore.UID_LENGTH) {
                int length = uid == null ? 0 : uid.length;
                setEditorMessage(getString(R.string.editor_uid_length_error, length), true);
                return;
            }
            for (int i = 0; i < uid.length; i++) {
                editorUid[i].setText(String.format(Locale.US, "%02X", uid[i] & 0xFF));
            }
            performConfirmHaptic(editorDialog.getWindow().getDecorView());
            setEditorMessage(R.string.editor_uid_read, false);
        });
    }

    private void stopReaderMode() {
        if (!readerMode) return;
        readerMode = false;
        readerForEditor = false;
        if (adapter != null) {
            try {
                adapter.disableReaderMode(this);
            } catch (Throwable ignored) {
                // Reader mode is also cleared automatically when the activity pauses.
            }
        }
        if (editorReadButton != null) {
            editorReadButton.setText(R.string.editor_read_card);
            editorReadButton.setEnabled(true);
            editorReadButton.setAlpha(1f);
        }
        renderCards();
    }

    private void setActiveCard(SavedCard card, Runnable onSuccess, String progressText) {
        if (adapter == null) {
            showNoCompatibleNfcMessage();
            return;
        }
        if (!adapter.isEnabled()) {
            showNfcPreflightFailure(
                    R.string.enable_nfc_first, R.string.nfc_turn_on_snackbar);
            return;
        }

        boolean modernApi = OemNfcController.isUidEmulationSupported(this);
        boolean cataloguedRaw = LegacyNfcController.isExperimentalAvailable(this);
        boolean rawApi = LegacyNfcDiagnostics.isRawNciApiAvailable(this);
        NfcActivationPolicy.Path path = NfcActivationPolicy.choose(
                card != null, modernApi, rawApi, cataloguedRaw,
                isRawModeForced(), isExperimentalRawAllowedForCurrentBuild());
        if (path == NfcActivationPolicy.Path.UNSUPPORTED) {
            showNoCompatibleNfcMessage();
            return;
        }
        if (!hasWriteSecureSettings()) {
            showNfcPreflightFailure(
                    R.string.permission_required_short, R.string.nfc_permission_snackbar);
            return;
        }

        if (path == NfcActivationPolicy.Path.CATALOG_RAW) {
            performLegacyCardChange(card, onSuccess, progressText,
                    card == null
                            ? RawOperationReport.Operation.DEACTIVATE
                            : RawOperationReport.Operation.ACTIVATE);
            return;
        }
        if (path == NfcActivationPolicy.Path.EXPERIMENTAL_RAW) {
            performExperimentalCardChange(card, onSuccess, progressText);
            return;
        }
        if (path == NfcActivationPolicy.Path.CONFIRM_EXPERIMENTAL_RAW) {
            confirmExperimentalRawMode(card, onSuccess, progressText);
            return;
        }

        busy = true;
        renderCards();
        scheduleNfcProgressStatus(progressText);
        executor.execute(() -> {
            try {
                if (card == null) {
                    OemNfcController.restoreDefaults(this);
                    CardSession.clearActiveCard();
                } else {
                    OemNfcController.setCardEnabled(this, card.uid, true);
                    CardSession.setActiveCardId(card.id);
                }
                saveLegacyRestoreState(false, false, null);
                runOnUiThread(() -> {
                    cancelNfcProgressStatus();
                    busy = false;
                    renderCards();
                    performConfirmHaptic(cardsContainer);
                    if (onSuccess != null) {
                        onSuccess.run();
                    } else {
                        showIdleStatus();
                        showSnackbar(card == null
                                ? getString(R.string.standard_nfc_restored)
                                : getString(R.string.card_active, card.name));
                    }
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    cancelNfcProgressStatus();
                    busy = false;
                    renderCards();
                    String message = getString(R.string.nfc_error, safeMessage(t));
                    setStatus(message);
                    if (editorMessage != null) setEditorMessage(message, true);
                    if (card == null) {
                        showErrorSnackbar(R.string.nfc_deactivation_failed);
                    } else {
                        showNfcSupportDialog(R.string.nfc_activation_failed);
                    }
                });
            }
        });
    }

    private void confirmExperimentalRawMode(SavedCard card, Runnable onSuccess,
            String progressText) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.experimental_raw_dialog_title)
                .setMessage(getString(R.string.experimental_raw_dialog_message,
                        Build.MANUFACTURER, Build.MODEL,
                        Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.experimental_raw_try,
                        (dialog, which) -> performExperimentalCardChange(
                                card, onSuccess, progressText))
                .show();
    }

    private void performExperimentalCardChange(SavedCard card, Runnable onSuccess,
            String progressText) {
        performLegacyCardChange(card, onSuccess, progressText,
                card == null
                        ? RawOperationReport.Operation.DEACTIVATE
                        : RawOperationReport.Operation.ACTIVATE,
                true, card != null, true);
    }

    private void performLegacyCardChange(SavedCard card, Runnable onSuccess,
            String progressText, RawOperationReport.Operation operation) {
        performLegacyCardChange(card, onSuccess, progressText, operation,
                false, false, true);
    }

    private void performLegacyCardChange(SavedCard card, Runnable onSuccess,
            String progressText, RawOperationReport.Operation operation,
            boolean allowUnverified, boolean probeBeforeWrite,
            boolean notifyFailure) {
        if (!hasWriteSecureSettings()) {
            setStatus(R.string.permission_required_short);
            renderCards();
            if (notifyFailure) showErrorSnackbar(R.string.nfc_permission_snackbar);
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            setStatus(R.string.enable_nfc_first);
            if (notifyFailure) showErrorSnackbar(R.string.nfc_turn_on_snackbar);
            return;
        }
        busy = true;
        renderCards();
        scheduleNfcProgressStatus(progressText);
        String previousActiveCardId = CardSession.getActiveCardId();
        SavedCard previousActiveCard = findSavedCard(previousActiveCardId);
        executor.execute(() -> {
            Map<Integer, byte[]> originalConfiguration = null;
            Map<Integer, byte[]> sessionOriginalConfiguration = null;
            boolean writeAttempted = false;
            try {
                if (allowUnverified) {
                    if (card != null && probeBeforeWrite) {
                        LegacyNfcDiagnostics.ProbeResult probe =
                                LegacyNfcDiagnostics.runReadOnlyProbe(this);
                        originalConfiguration = probe.configuration;
                        if (!LegacyNfcController.isCompleteExperimentalSnapshot(
                                originalConfiguration)) {
                            throw new IllegalStateException(
                                    "Could not snapshot every NFC parameter before writing");
                        }
                        sessionOriginalConfiguration = previousActiveCard == null
                                ? originalConfiguration : loadOriginalNfcConfiguration();
                        saveLegacyRestoreState(
                                true, true, sessionOriginalConfiguration);
                    } else {
                        originalConfiguration = loadOriginalNfcConfiguration();
                        sessionOriginalConfiguration = originalConfiguration;
                    }
                }
                byte[] uid = card == null ? new byte[CardStore.UID_LENGTH] : card.uid;
                writeAttempted = true;
                LegacyNfcController.ActivationResult result =
                        LegacyNfcController.setCardEnabled(
                                this, uid, card != null, operation,
                                allowUnverified, originalConfiguration);
                saveLegacyRestoreState(card != null, allowUnverified,
                        card != null ? sessionOriginalConfiguration : null);
                if (card == null) {
                    CardSession.clearActiveCard();
                } else {
                    CardSession.setActiveCardId(card.id);
                    if (allowUnverified) rememberExperimentalRawForCurrentBuild();
                }
                runOnUiThread(() -> {
                    cancelNfcProgressStatus();
                    busy = false;
                    renderCards();
                    performConfirmHaptic(cardsContainer);
                    if (onSuccess != null) {
                        onSuccess.run();
                    } else {
                        showIdleStatus();
                        showSnackbar(card == null
                                ? getString(R.string.standard_nfc_restored)
                                : getString(R.string.card_active, card.name));
                    }
                });
            } catch (Throwable t) {
                RawOperationReport primaryReport = writeAttempted
                        ? LegacyNfcController.getLastOperationReport() : null;
                RawOperationReport rollbackReport = null;
                Throwable rollbackFailure = null;
                if (card != null && writeAttempted) {
                    try {
                        boolean restorePreviousCard = !allowUnverified
                                && previousActiveCard != null;
                        LegacyNfcController.setCardEnabled(this,
                                restorePreviousCard
                                        ? previousActiveCard.uid
                                        : new byte[CardStore.UID_LENGTH],
                                restorePreviousCard,
                                RawOperationReport.Operation
                                        .ROLLBACK_AFTER_ACTIVATION_FAILURE,
                                allowUnverified, originalConfiguration);
                        rollbackReport = LegacyNfcController.getLastOperationReport();
                        if (previousActiveCardId == null) {
                            saveLegacyRestoreState(false, false, null);
                        } else {
                            saveLegacyRestoreState(true, allowUnverified,
                                    sessionOriginalConfiguration);
                        }
                        restoreActiveCardState(previousActiveCardId);
                    } catch (Throwable restoreFailure) {
                        rollbackFailure = restoreFailure;
                        rollbackReport = LegacyNfcController.getLastOperationReport();
                        AppLog.error("Raw NFC activation rollback failed", restoreFailure);
                        saveLegacyRestoreState(true, allowUnverified,
                                sessionOriginalConfiguration);
                    }
                }
                LegacyNfcController.attachRollback(
                        primaryReport, rollbackReport, rollbackFailure);
                String failureSummary = writeAttempted
                        ? LegacyNfcController.lastFailureSummary(t) : safeMessage(t);
                runOnUiThread(() -> {
                    cancelNfcProgressStatus();
                    busy = false;
                    renderCards();
                    String message = getString(R.string.raw_nfc_error, failureSummary);
                    setStatus(message);
                    if (editorMessage != null) setEditorMessage(message, true);
                    if (notifyFailure) {
                        if (card == null) {
                            showErrorSnackbar(R.string.nfc_deactivation_failed);
                        } else {
                            showNfcSupportDialog(R.string.nfc_activation_failed);
                        }
                    }
                });
            }
        });
    }

    private SavedCard findSavedCard(String cardId) {
        if (cardId == null) return null;
        for (SavedCard saved : CardStore.getCards(this)) {
            if (cardId.equals(saved.id)) return saved;
        }
        return null;
    }

    private void restoreActiveCardState(String cardId) {
        if (cardId == null) {
            CardSession.clearActiveCard();
        } else {
            CardSession.setActiveCardId(cardId);
        }
    }

    private void showNoCompatibleNfcMessage() {
        setStatus(R.string.no_compatible_nfc_interface);
        if (editorMessage != null) {
            setEditorMessage(R.string.no_compatible_nfc_interface, true);
        }
        renderCards();
        showNfcSupportDialog(R.string.no_compatible_nfc_interface);
    }

    private void showNfcSupportDialog(int titleResource) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(titleResource)
                .setMessage(getString(R.string.nfc_failure_support_message,
                        getString(R.string.diagnostics_email_address)))
                .setNegativeButton(R.string.action_close, null)
                .setPositiveButton(R.string.menu_nfc_diagnostics,
                        (dialog, which) -> showNfcDiagnostics())
                .show();
    }

    private void showNfcPreflightFailure(int statusResource, int snackbarResource) {
        setStatus(statusResource);
        if (editorMessage != null) setEditorMessage(statusResource, true);
        renderCards();
        showErrorSnackbar(snackbarResource);
    }

    private void restoreDefaultsAtSessionStart() {
        CardSession.clearActiveCard();
        renderCards();
        SharedPreferences legacyPreferences =
                getSharedPreferences(LEGACY_PREFS, MODE_PRIVATE);
        boolean pendingLegacyRestore = legacyPreferences.getBoolean(
                LEGACY_PENDING_RESTORE, false);
        if (pendingLegacyRestore) {
            if (adapter == null || !adapter.isEnabled()) {
                setStatus(R.string.nfc_disabled_pending_restore);
                return;
            }
            if (!hasWriteSecureSettings()) {
                setStatus(R.string.permission_required);
                return;
            }
            boolean allowUnverified = legacyPreferences.getBoolean(
                    LEGACY_PENDING_UNVERIFIED, false);
            if (!LegacyNfcDiagnostics.isRawNciApiAvailable(this)
                    || (!allowUnverified
                    && !RawNfcDeviceCatalog.isSupported(
                            Build.MANUFACTURER, Build.MODEL))) {
                setStatus(R.string.pending_restore_interface_unavailable);
                return;
            }
            performLegacyCardChange(null, null,
                    getString(R.string.progress_restore_standard_nfc),
                    RawOperationReport.Operation.STARTUP_PENDING_RESTORE,
                    allowUnverified, false, false);
            return;
        }

        // Keep the established OEM cleanup for existing users, but do not expose support or
        // permission errors during an ordinary startup. Compatibility is resolved on card tap.
        if (adapter == null || !adapter.isEnabled() || !hasWriteSecureSettings()
                || !OemNfcController.isUidEmulationSupported(this)) {
            showIdleStatus();
            return;
        }

        busy = true;
        renderCards();
        setStatus(R.string.progress_restore_standard_nfc);
        executor.execute(() -> {
            try {
                OemNfcController.restoreDefaults(
                        this, OemOperationReport.Operation.STARTUP_RESTORE);
                saveLegacyRestoreState(false, false, null);
                runOnUiThread(() -> {
                    busy = false;
                    renderCards();
                    showIdleStatus();
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    busy = false;
                    renderCards();
                    setStatus(getString(R.string.nfc_reset_failed, safeMessage(t)));
                });
            }
        });
    }

    private void showIdleStatus() {
        List<SavedCard> cards = CardStore.getCards(this);
        if (cards.isEmpty()) {
            setStatus(R.string.empty_cards_status);
        } else {
            setStatus("");
        }
    }

    private void setEditorMessage(String value, boolean error) {
        if (editorMessage == null) return;
        editorMessageIsError = error;
        editorMessage.setText(value);
        editorMessage.setTextColor(error ? ERROR_TEXT : TEXT_SECONDARY);
    }

    private void setEditorMessage(int textResource, boolean error) {
        setEditorMessage(getString(textResource), error);
    }

    private void clearEditorReferences() {
        editorDialog = null;
        editorCard = null;
        editorName = null;
        editorMessage = null;
        editorReadButton = null;
        editorMessageIsError = false;
        editorReaderInterruptedForConfiguration = false;
        restoreEditorReaderModeOnResume = false;
        Arrays.fill(editorUid, null);
    }

    private void showPermissionRequestIfNeeded() {
        if ((permissionPromptShown && !reopenPermissionDialog) || hasWriteSecureSettings()
                || (editorDialog != null && editorDialog.isShowing())
                || isFinishing() || isDestroyed()) {
            return;
        }
        permissionPromptShown = true;
        reopenPermissionDialog = false;
        permissionDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.permission_dialog_title)
                .setMessage(getString(R.string.permission_dialog_message, grantCommand()))
                .setNegativeButton(R.string.action_close, null)
                .setPositiveButton(R.string.copy_adb_command,
                        (dialog, which) -> copyGrantCommand())
                .create();
        permissionDialog.setOnDismissListener(dialog -> permissionDialog = null);
        permissionDialog.show();
    }

    private void refreshPermissionUi() {
        if (permissionButton == null) return;
        stopPermissionChecks();
        boolean granted = hasWriteSecureSettings();
        boolean newlyGranted = granted && permissionButton.getVisibility() == View.VISIBLE;
        permissionButton.setVisibility(granted ? View.GONE : View.VISIBLE);
        if (granted && permissionDialog != null) permissionDialog.dismiss();
        if (newlyGranted && resumed) {
            if (TextUtils.equals(status.getText(), getString(R.string.permission_required_short))
                    || TextUtils.equals(status.getText(), getString(R.string.permission_required))) {
                showIdleStatus();
            }
            showSnackbar(R.string.adb_permission_granted);
        }
        // ADB grants do not trigger an activity lifecycle callback while the app stays open.
        // Check only while resumed and waiting for the one-time permission.
        if (resumed && !granted) {
            permissionButton.postDelayed(permissionCheck, PERMISSION_CHECK_INTERVAL_MILLIS);
        }
    }

    private void stopPermissionChecks() {
        if (permissionButton != null) permissionButton.removeCallbacks(permissionCheck);
    }

    private boolean hasWriteSecureSettings() {
        return checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isRawModeForced() {
        return getSharedPreferences(APP_PREFS, MODE_PRIVATE)
                .getBoolean(PREF_FORCE_RAW_MODE, false);
    }

    private boolean isExperimentalRawAllowedForCurrentBuild() {
        String acceptedFingerprint = getSharedPreferences(APP_PREFS, MODE_PRIVATE)
                .getString(PREF_EXPERIMENTAL_RAW_FINGERPRINT, null);
        return Build.FINGERPRINT.equals(acceptedFingerprint);
    }

    private void rememberExperimentalRawForCurrentBuild() {
        getSharedPreferences(APP_PREFS, MODE_PRIVATE).edit()
                .putString(PREF_EXPERIMENTAL_RAW_FINGERPRINT, Build.FINGERPRINT)
                .apply();
    }

    private void saveLegacyRestoreState(boolean pending, boolean unverified,
            Map<Integer, byte[]> originalConfiguration) {
        SharedPreferences.Editor editor = getSharedPreferences(
                LEGACY_PREFS, MODE_PRIVATE).edit()
                .putBoolean(LEGACY_PENDING_RESTORE, pending)
                .putBoolean(LEGACY_PENDING_UNVERIFIED, pending && unverified);
        if (pending && unverified && originalConfiguration != null) {
            editor.putString(LEGACY_ORIGINAL_CONFIGURATION,
                    RawNciCodec.encodeConfiguration(originalConfiguration));
        } else {
            editor.remove(LEGACY_ORIGINAL_CONFIGURATION);
        }
        editor.apply();
    }

    private Map<Integer, byte[]> loadOriginalNfcConfiguration() {
        String encoded = getSharedPreferences(LEGACY_PREFS, MODE_PRIVATE)
                .getString(LEGACY_ORIGINAL_CONFIGURATION, null);
        Map<Integer, byte[]> configuration = RawNciCodec.decodeConfiguration(encoded);
        if (!LegacyNfcController.isCompleteExperimentalSnapshot(configuration)) {
            throw new IllegalStateException("Original NFC configuration snapshot is incomplete");
        }
        return configuration;
    }

    private void setRawModeForced(boolean enabled) {
        getSharedPreferences(APP_PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_FORCE_RAW_MODE, enabled)
                .apply();
        showSnackbar(enabled
                ? R.string.raw_mode_selected
                : R.string.standard_mode_selected);
    }

    private String grantCommand() {
        return "adb shell pm grant " + getPackageName()
                + " android.permission.WRITE_SECURE_SETTINGS";
    }

    private void copyGrantCommand() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(
                getString(R.string.adb_clip_label), grantCommand()));
        showSnackbar(R.string.adb_command_copied);
    }

    private void openExternalPage(int urlResource) {
        Intent browser = new Intent(Intent.ACTION_VIEW,
                Uri.parse(getString(urlResource)));
        try {
            startActivity(browser);
        } catch (Throwable ignored) {
            showSnackbar(R.string.browser_unavailable);
        }
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused == null) return;
        InputMethodManager keyboard =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        focused.clearFocus();
    }

    private void setStatus(String value) {
        if (status != null) status.setText(value);
    }

    private void setStatus(int textResource) {
        setStatus(getString(textResource));
    }

    private void showSnackbar(int textResource) {
        showSnackbar(getString(textResource));
    }

    private void showErrorSnackbar(int textResource) {
        showSnackbar(getString(textResource), ERROR_SNACKBAR_DURATION_MILLIS);
    }

    private void showSnackbar(String value) {
        showSnackbar(value, SNACKBAR_DURATION_MILLIS);
    }

    private void showSnackbar(String value, long durationMillis) {
        if (snackbar == null) return;
        cancelSnackbar();
        snackbar.setText(value);
        snackbar.setVisibility(View.VISIBLE);
        snackbar.setAlpha(0f);
        snackbar.animate().alpha(1f).setDuration(150).start();
        snackbar.announceForAccessibility(value);
        pendingSnackbarDismiss = () -> {
            pendingSnackbarDismiss = null;
            snackbar.animate()
                    .alpha(0f)
                    .setDuration(180)
                    .withEndAction(() -> snackbar.setVisibility(View.INVISIBLE))
                    .start();
        };
        snackbar.postDelayed(pendingSnackbarDismiss, durationMillis);
    }

    private void cancelSnackbar() {
        if (snackbar == null) return;
        if (pendingSnackbarDismiss != null) {
            snackbar.removeCallbacks(pendingSnackbarDismiss);
            pendingSnackbarDismiss = null;
        }
        snackbar.animate().cancel();
    }

    private void scheduleNfcProgressStatus(String value) {
        cancelNfcProgressStatus();
        if (status == null) return;
        pendingNfcProgressStatus = () -> {
            pendingNfcProgressStatus = null;
            if (busy) setStatus(value);
        };
        status.postDelayed(pendingNfcProgressStatus, NFC_PROGRESS_STATUS_DELAY_MILLIS);
    }

    private void cancelNfcProgressStatus() {
        if (status != null && pendingNfcProgressStatus != null) {
            status.removeCallbacks(pendingNfcProgressStatus);
        }
        pendingNfcProgressStatus = null;
    }

    private GradientDrawable cardBackground(int index, boolean active) {
        float hue = (CARD_START_HUE + index * GOLDEN_ANGLE_DEGREES) % 360f;
        int leading = cardColorForWhiteText(hue, 0.72f, 0.76f);
        int trailing = cardColorForWhiteText((hue + 18f) % 360f, 0.82f, 0.54f);
        if (!active) {
            leading = blendColors(leading, SURFACE, 0.24f);
            trailing = blendColors(trailing, SURFACE, 0.28f);
        }
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[] {leading, trailing});
        drawable.setCornerRadius(dp(22));
        if (active) drawable.setStroke(dp(3), ACTIVE_BORDER);
        return drawable;
    }

    private int cardColorForWhiteText(float hue, float saturation, float value) {
        float adjustedValue = value;
        int color;
        do {
            color = Color.HSVToColor(new float[] {hue, saturation, adjustedValue});
            adjustedValue -= 0.025f;
        } while (relativeLuminance(color) > 0.18 && adjustedValue >= 0.34f);
        return color;
    }

    private static int blendColors(int foreground, int background,
            float backgroundAmount) {
        float foregroundAmount = 1f - backgroundAmount;
        return Color.rgb(
                Math.round(Color.red(foreground) * foregroundAmount
                        + Color.red(background) * backgroundAmount),
                Math.round(Color.green(foreground) * foregroundAmount
                        + Color.green(background) * backgroundAmount),
                Math.round(Color.blue(foreground) * foregroundAmount
                        + Color.blue(background) * backgroundAmount));
    }

    private static double relativeLuminance(int color) {
        return 0.2126 * linearColorChannel(Color.red(color) / 255.0)
                + 0.7152 * linearColorChannel(Color.green(color) / 255.0)
                + 0.0722 * linearColorChannel(Color.blue(color) / 255.0);
    }

    private static double linearColorChannel(double channel) {
        return channel <= 0.04045
                ? channel / 12.92
                : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private void applyBorderlessRipple(View view) {
        TypedValue selectableBackground = new TypedValue();
        if (getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                selectableBackground,
                true)) {
            view.setBackgroundResource(selectableBackground.resourceId);
        } else {
            view.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    private static String safeMessage(Throwable throwable) {
        Throwable root = OemNfcController.unwrap(throwable);
        String message = root.getMessage();
        return message == null || message.isBlank()
                ? root.getClass().getSimpleName()
                : message;
    }

    private void performConfirmHaptic(View view) {
        int feedback = Build.VERSION.SDK_INT >= 30
                ? HapticFeedbackConstants.CONFIRM
                : HapticFeedbackConstants.VIRTUAL_KEY;
        view.performHapticFeedback(feedback);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
