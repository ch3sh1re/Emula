package dev.dunno.emula;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;

import androidx.core.splashscreen.SplashScreen;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Manual cards: tap to toggle emulation, hold to edit, swipe left to delete. */
public final class WatchActivity extends Activity {
    private enum Screen { CARDS, EDITOR, DELETE, SETTINGS, SETUP, DIAGNOSTICS, RECEIVE, SEND_REPORT }
    private enum Operation { START, STOP, RESTORE, PREPARE_REPORT, OTHER }
    private static final int RECEIVE_PERMISSION = 0x5744;
    private static final int REPORT_PERMISSION = 0x5745;
    private LocalWatchLink receiveLink;
    private String receiveMessage = "";
    private WatchReportLink reportLink;
    private String transferMessage = "";
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final int ACCENT = Color.rgb(141, 185, 255);
    private static final int ERROR = Color.rgb(255, 180, 171);
    private final StringBuilder evidence = new StringBuilder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OnBackInvokedCallback backCallback = this::navigateBack;
    private NfcAdapter adapter;
    private WatchSessionStore store;
    private WatchNfcSession session;
    private Screen screen = Screen.CARDS;
    private String editingId;
    private String deletingId;
    private String nameDraft = "";
    private String uidDraft = "";
    private String message = "";
    private String lastResult = "";
    private String reportText = "";
    private String diagnosticEvidence = "";
    private boolean reportPrepared;
    private String adapterEvidence = "";
    private boolean busy;
    private boolean resumed;
    private boolean coldStart;
    private LinearLayout content;
    private ScrollView scroll;
    private EditText nameInput;
    private EditText uidInput;
    private WatchNfcSession.Pending pending;
    private boolean recovery;
    private String busyCardId;
    private Operation operation = Operation.OTHER;
    private Screen renderedScreen;
    private int cardsScrollY;
    private WatchUiError uiError;
    private Operation errorOperation = Operation.OTHER;
    private Dialog errorDialog;
    private Dialog setupDialog;
    private Dialog rawConsentDialog;
    private boolean permissionPromptShown;
    private boolean reopenSetupPrompt;
    private WatchUiError inlineError;
    private String inlineCardId;
    private View inlineStatus;

    private final Runnable permissionRefresh = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            if (hasPermission()) {
                dismissSetupPrompt();
                if (inlineError == WatchUiError.PERMISSION) inlineError = null;
                if (!busy && (screen == Screen.CARDS || screen == Screen.SETUP)) render();
            } else {
                handler.postDelayed(this, 1000);
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(state);
        coldStart = !STARTED.getAndSet(true);
        store = new WatchSessionStore(getSharedPreferences("watch_restore", MODE_PRIVATE));
        session = new WatchNfcSession(store, new WatchHardwareBackend(this, evidence), Build.FINGERPRINT);
        try { adapter = NfcAdapter.getDefaultAdapter(this); }
        catch (Throwable failure) { adapterEvidence = android.util.Log.getStackTraceString(failure); }
        if (state != null) {
            try { screen = Screen.valueOf(state.getString("screen", Screen.CARDS.name())); }
            catch (IllegalArgumentException ignored) { screen = Screen.CARDS; }
            editingId = state.getString("editingId");
            deletingId = state.getString("deletingId");
            nameDraft = state.getString("nameDraft", "");
            uidDraft = state.getString("uidDraft", "");
            message = state.getString("message", "");
            lastResult = state.getString("lastResult", message);
            reportText = state.getString("reportText", "");
            evidence.append(state.getString("operationEvidence", ""));
            diagnosticEvidence = state.getString("diagnosticEvidence", "");
            cardsScrollY = state.getInt("cardsScrollY", 0);
            permissionPromptShown = state.getBoolean("permissionPromptShown");
            reopenSetupPrompt = state.getBoolean("setupPromptOpen");
            inlineCardId = state.getString("inlineCardId");
            try {
                String savedInline = state.getString("inlineError");
                if (savedInline != null) inlineError = WatchUiError.valueOf(savedInline);
                String savedError = state.getString("uiError");
                if (savedError != null) uiError = WatchUiError.valueOf(savedError);
                errorOperation = Operation.valueOf(state.getString("errorOperation", Operation.OTHER.name()));
            } catch (IllegalArgumentException ignored) { uiError = null; }
        }
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        clearResolvedPrerequisite();
        if (coldStart) {
            coldStart = false;
            try {
                if (store.load() != null) runTask(() -> {
                    requireNfc();
                    session.restore();
                    return getString(R.string.restored);
                }, null, Operation.RESTORE);
            } catch (Exception failure) {
                message = getString(R.string.error, describe(failure));
                showFailure(failure, true, Operation.RESTORE, null);
                render();
            }
        } else if (!busy && screen != Screen.EDITOR) {
            render();
        }
        // Wear OS may resume this Activity before its fullscreen IME commits text.
        // Keep the editor and its InputConnection alive across that transition.
        handler.removeCallbacks(permissionRefresh);
        if (!hasPermission()) handler.postDelayed(permissionRefresh, 1000);
        if (!busy && !hasPermission() && screen == Screen.CARDS && uiError == null
                && (!permissionPromptShown || reopenSetupPrompt)) showSetupPrompt();
    }

    @Override protected void onPause() {
        resumed = false;
        cancelReceive();
        cancelReport();
        handler.removeCallbacks(permissionRefresh);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(permissionRefresh);
        if (errorDialog != null) errorDialog.dismiss();
        dismissSetupPrompt();
        if (rawConsentDialog != null) rawConsentDialog.dismiss();
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("screen", screen.name());
        state.putString("editingId", editingId);
        state.putString("deletingId", deletingId);
        state.putString("nameDraft", nameDraft);
        state.putString("uidDraft", uidDraft);
        state.putString("message", message);
        state.putString("lastResult", lastResult);
        state.putString("reportText", reportText);
        state.putString("operationEvidence", evidence.toString());
        state.putString("diagnosticEvidence", diagnosticEvidence);
        state.putInt("cardsScrollY", renderedScreen == Screen.CARDS && scroll != null
                ? scroll.getScrollY() : cardsScrollY);
        state.putBoolean("permissionPromptShown", permissionPromptShown);
        state.putBoolean("setupPromptOpen", setupDialog != null && setupDialog.isShowing());
        state.putString("inlineCardId", inlineCardId);
        if (inlineError != null) state.putString("inlineError", inlineError.name());
        if (uiError != null) state.putString("uiError", uiError.name());
        state.putString("errorOperation", errorOperation.name());
        super.onSaveInstanceState(state);
    }

    private void render() {
        if (renderedScreen == Screen.CARDS && scroll != null) cardsScrollY = scroll.getScrollY();
        int scrollY = scroll != null && renderedScreen == screen ? scroll.getScrollY()
                : screen == Screen.CARDS ? cardsScrollY : 0;
        pending = null;
        recovery = false;
        try {
            pending = store.load();
            recovery = pending != null && (!pending.active || !Build.FINGERPRINT.equals(pending.firmware));
        } catch (Exception failure) {
            recovery = true;
            message = getString(R.string.error, describe(failure));
            if (screen != Screen.DIAGNOSTICS && screen != Screen.SEND_REPORT) {
                uiError = WatchUiError.RECOVERY;
                errorOperation = Operation.RESTORE;
            }
        }
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(true);
        boolean round = getResources().getConfiguration().isScreenRound();
        scroll.setPadding(dp(round ? 24 : 16), dp(round ? 24 : 16),
                dp(round ? 24 : 16), dp(round ? 32 : 24));
        scroll.setClipToPadding(false);
        scroll.setFocusable(true);
        scroll.setOnGenericMotionListener((view, event) -> {
            if (event.getAction() != MotionEvent.ACTION_SCROLL) return false;
            float delta = event.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (delta == 0) delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (delta == 0) return false;
            scroll.scrollBy(0, Math.round(-delta * dp(36)));
            return true;
        });
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
        WindowInsetsController insets = getWindow().getInsetsController();
        if (insets != null) {
            insets.hide(WindowInsets.Type.systemBars());
            insets.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        nameInput = null;
        uidInput = null;
        inlineStatus = null;
        switch (screen) {
            case EDITOR: renderEditor(); break;
            case DELETE: renderDelete(); break;
            case SETTINGS: renderSettings(); break;
            case SETUP: renderSetup(); break;
            case DIAGNOSTICS: renderDiagnostics(); break;
            case RECEIVE: renderReceive(); break;
            case SEND_REPORT: renderReportTransfer(); break;
            default: renderCards();
        }
        if (!busy && screen != Screen.CARDS && inlineError != null) renderInlineStatus();
        if (busy && busyCardId == null) addText(getString(R.string.busy), 13, ACCENT);
        else if (!busy && uiError == null && inlineError == null && !message.isEmpty() && screen != Screen.DIAGNOSTICS) {
            TextView status = addText(message, 13, ACCENT);
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        }
        renderedScreen = screen;
        ScrollView renderedScroll = scroll;
        View statusToReveal = inlineStatus;
        renderedScroll.post(() -> {
            if (scroll != renderedScroll) return;
            renderedScroll.scrollTo(0, scrollY);
            if (statusToReveal != null) statusToReveal.requestRectangleOnScreen(
                    new Rect(0, 0, statusToReveal.getWidth(), statusToReveal.getHeight()), false);
        });
        if (screen != Screen.EDITOR) scroll.requestFocus();
        if (uiError != null && errorDialog == null && !isFinishing() && !isDestroyed()) showErrorDialog();
    }

    private void renderCards() {
        List<SavedCard> cards = CardStore.getCards(this);
        if (inlineError != null && inlineCardId == null) renderInlineStatus();
        if (cards.isEmpty()) addText(getString(R.string.empty), 16, Color.LTGRAY);
        if (recovery) {
            addText(getString(R.string.recovery), 14, ACCENT);
            button(getString(R.string.restore), this::stopEmulation);
        }
        for (SavedCard card : cards) {
            boolean active = pending != null && pending.active && pending.cardId.equals(card.id);
            Button item = button(getString(active ? R.string.card_selected : R.string.card_label,
                    card.name, Hex.bytes(card.uid)), () -> toggleCard(card));
            item.setMinHeight(dp(64));
            item.setMaxLines(3);
            item.setTextSize(15);
            if (active) item.setBackground(buttonBackground(true));
            item.setContentDescription(getString(active ? R.string.card_active_accessibility
                    : R.string.card_accessibility, card.name, Hex.bytes(card.uid)));
            item.setOnLongClickListener(view -> {
                if (busy) return true;
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                openEditor(card);
                return true;
            });
            attachDeleteGesture(item, card);
            item.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                            R.id.action_edit_card, getString(R.string.edit_card)));
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                            R.id.action_delete_card, getString(R.string.delete_card)));
                }
                @Override public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
                    if (!busy && action == R.id.action_edit_card) { openEditor(card); return true; }
                    if (!busy && action == R.id.action_delete_card) { confirmDelete(card); return true; }
                    return super.performAccessibilityAction(host, action, arguments);
                }
            });
            if (busy && card.id.equals(busyCardId)) {
                TextView status = addText(getString(operation == Operation.STOP
                        ? R.string.stopping : R.string.starting), 13, ACCENT);
                status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            } else if (inlineError != null && card.id.equals(inlineCardId)) renderInlineStatus();
        }
        button(getString(R.string.add_card), () -> openEditor(null));
        button(getString(R.string.receive_cards), this::beginReceive);
        if (!hasPermission()) button(getString(R.string.setup), () -> navigate(Screen.SETUP));
        button(getString(R.string.settings), () -> navigate(Screen.SETTINGS));
    }

    private void renderInlineStatus() {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        content.addView(group, new LinearLayout.LayoutParams(-1, -2));
        TextView reason = addText(group, getString(inlineError == WatchUiError.PERMISSION
                ? R.string.error_permission : R.string.error_nfc_off), 13, ERROR);
        reason.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        if (inlineError == WatchUiError.PERMISSION) {
            button(group, getString(R.string.setup), () -> navigate(Screen.SETUP));
        } else button(group, getString(R.string.open_nfc_settings), this::openNfcSettings);
        inlineStatus = group;
    }

    private void clearResolvedPrerequisite() {
        if (inlineError == WatchUiError.PERMISSION && hasPermission()) inlineError = null;
        if (inlineError == WatchUiError.NFC_OFF && adapter != null && adapter.isEnabled()) inlineError = null;
        if (hasPermission()) dismissSetupPrompt();
    }

    private void attachDeleteGesture(Button item, SavedCard card) {
        item.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY;
            boolean horizontal;
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getX();
                        startY = event.getY();
                        horizontal = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getX() - startX;
                        float dy = event.getY() - startY;
                        if (!horizontal && dx < -dp(24) && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                            horizontal = true;
                            view.getParent().requestDisallowInterceptTouchEvent(true);
                            view.cancelLongPress();
                            view.setPressed(false);
                        }
                        return horizontal;
                    case MotionEvent.ACTION_UP:
                        view.getParent().requestDisallowInterceptTouchEvent(false);
                        if (!horizontal) return false;
                        view.setPressed(false);
                        if (!busy && event.getX() - startX < -dp(48)
                                && Math.abs(event.getX() - startX) > Math.abs(event.getY() - startY) * 1.5f) {
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                            confirmDelete(card);
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        view.getParent().requestDisallowInterceptTouchEvent(false);
                        view.setPressed(false);
                        return horizontal;
                    default: return false;
                }
            }
        });
    }

    private void openEditor(SavedCard card) {
        editingId = card == null ? null : card.id;
        nameDraft = card == null ? getString(R.string.new_card_name,
                CardStore.getCards(this).size() + 1) : card.name;
        uidDraft = card == null ? "" : Hex.bytes(card.uid).replace(" ", "");
        navigate(Screen.EDITOR);
    }

    private void renderEditor() {
        nameInput = input(getString(R.string.name), nameDraft, false);
        nameInput.addTextChangedListener(watcher(value -> nameDraft = value));
        uidInput = input(getString(R.string.uid), uidDraft, true);
        uidInput.addTextChangedListener(watcher(value -> uidDraft = value));
        button(getString(R.string.save), this::saveEditor);
        button(getString(R.string.cancel), () -> { hideKeyboard(); navigate(Screen.CARDS); });
    }

    private void saveEditor() {
        if (busy) return;
        byte[] uid = WatchReleasePolicy.parseUid(uidDraft);
        if (uid == null) { uidInput.setError(getString(R.string.uid_error)); return; }
        String id = editingId;
        String name = nameDraft.trim();
        hideKeyboard();
        runTask(() -> {
            SavedCard existing = id == null ? null : requireCard(id);
            stopIfActive(id);
            CardStore.save(this, existing, name, uid);
            return getString(R.string.saved);
        }, () -> { editingId = null; screen = Screen.CARDS; });
    }

    private void confirmDelete(SavedCard card) {
        deletingId = card.id;
        navigate(Screen.DELETE);
    }

    private void renderDelete() {
        SavedCard card = findCard(deletingId);
        if (card == null) { screen = Screen.CARDS; renderCards(); return; }
        addText(getString(R.string.delete_title, card.name), 18, Color.WHITE);
        addText(getString(R.string.delete_note), 13, Color.LTGRAY);
        Button remove = button(getString(R.string.delete_card), () -> {
            String id = card.id;
            runTask(() -> {
                stopIfActive(id);
                CardStore.delete(this, id);
                return getString(R.string.deleted);
            }, () -> { deletingId = null; editingId = null; screen = Screen.CARDS; });
        });
        remove.setTextColor(Color.rgb(255, 170, 160));
        button(getString(R.string.cancel), () -> navigate(Screen.CARDS));
    }

    private void toggleCard(SavedCard card) {
        if (busy) return;
        boolean[] consentRequired = {false};
        busyCardId = card.id;
        Operation requested = pending != null && pending.cardId.equals(card.id)
                ? Operation.STOP : Operation.START;
        runTask(() -> {
            WatchNfcSession.Pending current = store.load();
            if (current != null && current.cardId.equals(card.id)) {
                requireNfc();
                session.restore();
                return getString(R.string.restored);
            }
            if (!UidCodec.isSupportedLength(card.uid.length)) throw new IllegalArgumentException(getString(R.string.uid_error));
            requireNfc();
            WatchNfcSession.Path path = activationPath();
            if (path == null) {
                throw new WatchUiError.Condition(WatchUiError.OEM_UNAVAILABLE,
                        getString(R.string.emulation_unavailable));
            }
            boolean rawConsent = hasRawConsent();
            if (path == WatchNfcSession.Path.RAW && !rawConsent) {
                consentRequired[0] = true;
                return null;
            }
            if (current != null) session.restore();
            session.activate(card.id, card.uid, path, rawConsent);
            return getString(R.string.active);
        }, () -> { if (consentRequired[0]) confirmExperimentalRaw(card); }, requested);
    }

    private WatchNfcSession.Path activationPath() {
        return WatchActivationPolicy.select(Build.VERSION.SDK_INT,
                getPackageManager().hasSystemFeature("android.hardware.type.watch"),
                OemNfcController.inspectCapabilities(this).androidOemExtension,
                LegacyNfcDiagnostics.isRawNciApiAvailable(this),
                !"UNAVAILABLE".equals(LegacyNfcController.pollingMethod(this)));
    }

    private boolean hasRawConsent() {
        return WatchActivationPolicy.hasConsent(Build.FINGERPRINT,
                getSharedPreferences("watch_experiments", MODE_PRIVATE).getString("rawFirmware", ""));
    }

    private void confirmExperimentalRaw(SavedCard card) {
        if (rawConsentDialog != null || !resumed || isFinishing() || isDestroyed()) return;
        Dialog dialog = watchDialog(R.string.experimental_nfc, body -> {
            addText(body, getString(R.string.experimental_nfc_note), 14, Color.WHITE);
            button(body, getString(R.string.try_experimental), () -> {
                rawConsentDialog.dismiss();
                runTask(() -> {
                    if (!WatchActivationPolicy.hasConsent(Build.FINGERPRINT, Build.FINGERPRINT)
                            || !getSharedPreferences("watch_experiments", MODE_PRIVATE).edit()
                            .putString("rawFirmware", Build.FINGERPRINT).commit()) {
                        throw new IllegalStateException("Could not save experimental NFC consent");
                    }
                    return null;
                }, () -> toggleCard(card));
            });
            button(body, getString(R.string.cancel), () -> rawConsentDialog.dismiss());
        });
        rawConsentDialog = dialog;
        dialog.setOnDismissListener(ignored -> { if (rawConsentDialog == dialog) rawConsentDialog = null; });
    }

    private void stopIfActive(String id) throws Exception {
        WatchNfcSession.Pending current = store.load();
        if (current != null && current.cardId.equals(id)) {
            requireNfc();
            session.restore();
        }
    }

    private void stopEmulation() {
        runTask(() -> { requireNfc(); session.restore(); return getString(R.string.restored); },
                null, Operation.RESTORE);
    }

    private void renderSettings() {
        addText(getString(R.string.settings), 18, Color.WHITE);
        button(getString(R.string.setup), () -> navigate(Screen.SETUP));
        button(getString(R.string.diagnostics), this::openDiagnostics);
        if (hasRawConsent()) button(getString(R.string.disable_experimental), () -> runTask(() -> {
            WatchNfcSession.Pending current = store.load();
            if (current != null && current.path == WatchNfcSession.Path.RAW) {
                requireNfc();
                session.restore();
            }
            if (!getSharedPreferences("watch_experiments", MODE_PRIVATE).edit()
                    .remove("rawFirmware").commit()) throw new IllegalStateException("Could not save preference");
            return getString(R.string.experimental_disabled);
        }, null));
        addText(getString(R.string.controls), 13, Color.LTGRAY);
        addText(getString(R.string.support_scope), 12, Color.LTGRAY);
        addText(BuildConfig.VERSION_NAME, 11, Color.GRAY);
        button(getString(R.string.back), () -> navigate(Screen.CARDS));
    }

    private void renderSetup() {
        addText(getString(R.string.setup), 18, Color.WHITE);
        addText(getString(hasPermission() ? R.string.permission_ready : R.string.grant_required), 14,
                hasPermission() ? ACCENT : Color.LTGRAY);
        if (!hasPermission()) {
            addText(getString(R.string.setup_note), 13, Color.LTGRAY);
            Intent debugging = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            if (debugging.resolveActivity(getPackageManager()) != null) {
                button(getString(R.string.open_debugging), () -> {
                    try { startActivity(debugging); }
                    catch (android.content.ActivityNotFoundException | SecurityException ignored) {
                        message = getString(R.string.debugging_unavailable); render();
                    }
                });
            }
            TextView command = addText(getString(R.string.grant_command, BuildConfig.APPLICATION_ID), 11, Color.WHITE);
            command.setTypeface(Typeface.MONOSPACE);
            command.setTextIsSelectable(true);
        }
        button(getString(R.string.back), () -> navigate(Screen.CARDS));
    }

    private void showSetupPrompt() {
        if (setupDialog != null || isFinishing() || isDestroyed()) return;
        permissionPromptShown = true;
        reopenSetupPrompt = false;
        Dialog dialog = watchDialog(R.string.setup_once, body -> {
            addText(body, getString(R.string.setup_short), 14, Color.WHITE);
            LinearLayout actions = new LinearLayout(this);
            body.addView(actions, new LinearLayout.LayoutParams(-1, -2));
            Button later = button(actions, getString(R.string.later), this::dismissSetupPrompt);
            Button setup = button(actions, getString(R.string.setup), () -> {
                dismissSetupPrompt(); navigate(Screen.SETUP);
            });
            for (Button action : new Button[]{later, setup}) {
                action.setMinWidth(0);
                action.setMinimumWidth(0);
                LinearLayout.LayoutParams layout = (LinearLayout.LayoutParams) action.getLayoutParams();
                layout.width = 0;
                layout.weight = 1;
                layout.leftMargin = dp(2);
                layout.rightMargin = dp(2);
            }
        });
        setupDialog = dialog;
        dialog.setOnDismissListener(ignored -> { if (setupDialog == dialog) setupDialog = null; });
    }

    private void dismissSetupPrompt() {
        if (setupDialog != null) { setupDialog.dismiss(); setupDialog = null; }
        reopenSetupPrompt = false;
    }

    private void renderDiagnostics() {
        addText(getString(R.string.diagnostics), 18, Color.WHITE);
        button(getString(R.string.prepare_report), () -> prepareReport(null));
        button(getString(R.string.send_report), this::beginReportTransfer);
        button(getString(R.string.back), () -> navigate(Screen.SETTINGS));
        TextView report = addText(reportText, 11, Color.LTGRAY);
        report.setGravity(Gravity.START);
        report.setTypeface(Typeface.MONOSPACE);
        report.setTextIsSelectable(true);
    }

    private void prepareReport(Runnable success) {
        runTask(() -> {
            // A report must remain shareable when NFC/permission is missing or the GET fails.
            // Keep this read-only result separate from the last activation/restoration outcome.
            diagnosticEvidence = "Prepared: " + Instant.now() + "\n";
            try {
                requireNfc();
                LegacyNfcDiagnostics.ProbeResult probe = LegacyNfcDiagnostics.runReadOnlyProbe(this);
                diagnosticEvidence += probe.detailedSummary;
            } catch (Exception failure) {
                diagnosticEvidence += "READ-ONLY PROBE UNAVAILABLE\n"
                        + android.util.Log.getStackTraceString(failure);
            }
            return null;
        }, success, Operation.PREPARE_REPORT);
    }

    private void requireNfc() {
        if (adapter == null) throw new WatchUiError.Condition(WatchUiError.NFC_UNAVAILABLE,
                getString(R.string.no_adapter));
        if (!adapter.isEnabled()) throw new WatchUiError.Condition(WatchUiError.NFC_OFF,
                getString(R.string.nfc_off));
        if (!hasPermission()) throw new WatchUiError.Condition(WatchUiError.PERMISSION,
                getString(R.string.grant_required));
    }

    private void openDiagnostics() {
        dismissError();
        screen = Screen.DIAGNOSTICS;
        // Preserve the failed operation report; opening diagnostics never repeats an NFC call.
        if (reportText.isEmpty()) runTask(() -> null, null);
        else render();
    }

    private void showErrorDialog() {
        Dialog dialog = watchDialog(errorTitle(), body -> {
            int reason;
            switch (uiError) {
                case NFC_UNAVAILABLE: reason = R.string.error_no_nfc; break;
                case OEM_UNAVAILABLE: reason = R.string.error_no_oem; break;
                case RECOVERY: reason = R.string.error_recovery; break;
                default: reason = R.string.error_generic;
            }
            addText(body, getString(reason), 14, Color.WHITE);
            if (!reportText.isEmpty()) button(body, getString(R.string.send_report), this::beginReportTransfer);
            if (uiError == WatchUiError.RECOVERY) button(body, getString(R.string.restore), () -> {
                dismissError(); stopEmulation();
            });
            button(body, getString(R.string.diagnostics), this::openDiagnostics);
            button(body, getString(R.string.back), this::dismissError);
        });
        errorDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (errorDialog == dialog) { errorDialog = null; uiError = null; message = ""; }
        });
        getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.REJECT);
    }

    private Dialog watchDialog(int titleId, java.util.function.Consumer<LinearLayout> controls) {
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_NoActionBar);
        dialog.setTitle(getString(titleId));
        ScrollView errorScroll = new ScrollView(this);
        errorScroll.setFillViewport(true);
        boolean round = getResources().getConfiguration().isScreenRound();
        errorScroll.setPadding(dp(round ? 28 : 16), dp(round ? 28 : 16),
                dp(round ? 28 : 16), dp(round ? 32 : 20));
        errorScroll.setClipToPadding(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER);
        errorScroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        TextView title = addText(body, getString(titleId), 18,
                titleId == R.string.setup_once || titleId == R.string.experimental_nfc ? ACCENT : ERROR);
        title.setAccessibilityHeading(true);
        title.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        controls.accept(body);
        errorScroll.setOnGenericMotionListener((view, event) -> {
            if (event.getAction() != MotionEvent.ACTION_SCROLL) return false;
            float delta = event.getAxisValue(MotionEvent.AXIS_SCROLL);
            if (delta == 0) delta = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if (delta == 0) return false;
            errorScroll.scrollBy(0, Math.round(-delta * dp(36)));
            return true;
        });
        dialog.setContentView(errorScroll);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            dialog.getWindow().setLayout(-1, -1);
            WindowInsetsController insets = dialog.getWindow().getInsetsController();
            if (insets != null) insets.hide(WindowInsets.Type.systemBars());
        }
        errorScroll.requestFocus();
        return dialog;
    }

    private int errorTitle() {
        if (uiError == WatchUiError.RECOVERY || errorOperation == Operation.RESTORE) return R.string.error_restore;
        if (errorOperation == Operation.START) return R.string.error_start;
        if (errorOperation == Operation.STOP) return R.string.error_stop;
        return R.string.error_action;
    }

    private void dismissError() {
        uiError = null;
        message = "";
        if (errorDialog != null) errorDialog.dismiss();
    }

    private void openNfcSettings() {
        for (String action : new String[] {Settings.ACTION_NFC_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS,
                Settings.ACTION_SETTINGS}) {
            Intent intent = new Intent(action);
            if (intent.resolveActivity(getPackageManager()) == null) continue;
            try { startActivity(intent); dismissError(); return; }
            catch (android.content.ActivityNotFoundException | SecurityException ignored) { /* Try fallback. */ }
        }
        dismissError();
        navigate(Screen.SETUP);
    }

    private boolean hasPermission() {
        return checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    private SavedCard findCard(String id) {
        if (id == null) return null;
        for (SavedCard card : CardStore.getCards(this)) if (card.id.equals(id)) return card;
        return null;
    }

    private SavedCard requireCard(String id) {
        SavedCard card = findCard(id);
        if (card == null) throw new IllegalStateException(getString(R.string.card_missing));
        return card;
    }

    private void navigate(Screen target) {
        if (busy) return;
        if (screen == Screen.RECEIVE && target != Screen.RECEIVE) cancelReceive();
        if (screen == Screen.SEND_REPORT && target != Screen.SEND_REPORT) cancelReport();
        screen = target;
        message = "";
        render();
    }

    private void navigateBack() {
        if (busy) return;
        hideKeyboard();
        if (screen == Screen.CARDS) finish();
        else navigate(screen == Screen.DIAGNOSTICS ? Screen.SETTINGS : Screen.CARDS);
    }

    private void renderReceive() {
        addText(getString(R.string.receive_cards), 18, Color.WHITE);
        addText(receiveMessage.isEmpty() ? getString(R.string.receive_stopped) : receiveMessage,
                14, Color.LTGRAY);
        addText(getString(R.string.receive_note), 12, Color.LTGRAY);
        if (receiveLink == null) button(getString(R.string.receive_retry), this::beginReceive);
        button(getString(R.string.cancel), () -> navigate(Screen.CARDS));
    }

    private void beginReceive() {
        if (busy || receiveLink != null) return;
        screen = Screen.RECEIVE;
        message = "";
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            receiveMessage = getString(R.string.receive_permission);
            render();
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, RECEIVE_PERMISSION);
            return;
        }
        startReceive();
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void startReceive() {
        try {
            android.bluetooth.BluetoothManager manager = getSystemService(android.bluetooth.BluetoothManager.class);
            android.bluetooth.BluetoothAdapter bluetooth = manager == null ? null : manager.getAdapter();
            if (bluetooth == null || !bluetooth.isEnabled()) {
                receiveMessage = getString(R.string.receive_bluetooth);
                render();
                return;
            }
            receiveMessage = getString(R.string.receive_wait);
            receiveLink = LocalWatchLink.receive(bluetooth,
                    cards -> CardStore.appendReceived(getApplicationContext(), cards), new LocalWatchLink.Callback() {
                @Override public void complete(WatchCardPacket.Result result) {
                    cancelReceive();
                    if (isDestroyed() || isFinishing()) return;
                    screen = Screen.CARDS;
                    message = getString(R.string.receive_done, result.added, result.skipped);
                    render();
                }
                @Override public void failed(boolean timeout) {
                    cancelReceive();
                    if (isDestroyed() || isFinishing()) return;
                    receiveMessage = getString(timeout ? R.string.receive_timeout : R.string.receive_failed);
                    render();
                }
            });
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            render();
        } catch (SecurityException failure) {
            receiveMessage = getString(R.string.receive_permission);
            render();
        }
    }

    private void cancelReceive() {
        if (receiveLink != null) {
            receiveLink.close();
            receiveLink = null;
            receiveMessage = getString(R.string.receive_stopped);
            if (!busy) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void renderReportTransfer() {
        addText(getString(R.string.send_report), 18, Color.WHITE);
        addText(transferMessage.isEmpty() ? getString(R.string.report_stopped) : transferMessage,
                14, Color.LTGRAY);
        addText(getString(R.string.report_privacy), 12, Color.LTGRAY);
        if (reportLink == null) button(getString(R.string.receive_retry), this::beginReportTransfer);
        button(getString(R.string.back), () -> navigate(Screen.DIAGNOSTICS));
    }

    private void beginReportTransfer() {
        if (busy || reportLink != null) return;
        dismissError();
        if (!reportPrepared) {
            prepareReport(this::offerPreparedReport);
            return;
        }
        offerPreparedReport();
    }

    private void offerPreparedReport() {
        // Preparation can finish after Home/ambient mode; never open a transfer in background.
        if (!resumed || isFinishing()) return;
        screen = Screen.SEND_REPORT;
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            transferMessage = getString(R.string.report_permission);
            render();
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REPORT_PERMISSION);
        } else startReportTransfer();
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void startReportTransfer() {
        try {
            android.bluetooth.BluetoothManager manager = getSystemService(android.bluetooth.BluetoothManager.class);
            android.bluetooth.BluetoothAdapter bluetooth = manager == null ? null : manager.getAdapter();
            if (bluetooth == null || !bluetooth.isEnabled()) {
                transferMessage = getString(R.string.receive_bluetooth);
                render(); return;
            }
            byte[] report = WatchReportPacket.encode(reportText);
            transferMessage = getString(R.string.report_wait);
            reportLink = WatchReportLink.offer(bluetooth, report, new WatchReportLink.Callback() {
                @Override public void complete(String ignored) {
                    cancelReport();
                    if (isDestroyed() || isFinishing()) return;
                    transferMessage = getString(R.string.report_done);
                    getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    render();
                }
                @Override public void failed(boolean timeout) {
                    cancelReport();
                    if (isDestroyed() || isFinishing()) return;
                    transferMessage = getString(timeout ? R.string.report_timeout : R.string.report_failed);
                    render();
                }
            });
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (SecurityException failure) {
            transferMessage = getString(R.string.report_permission);
        } catch (java.io.IOException failure) {
            transferMessage = getString(R.string.report_limit);
        }
        render();
    }

    private void cancelReport() {
        if (reportLink == null) return;
        reportLink.close();
        reportLink = null;
        transferMessage = getString(R.string.report_stopped);
        if (!busy) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == RECEIVE_PERMISSION && screen == Screen.RECEIVE) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startReceive();
            else { receiveMessage = getString(R.string.receive_permission); render(); }
        }
        if (requestCode == REPORT_PERMISSION && screen == Screen.SEND_REPORT) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startReportTransfer();
            else { transferMessage = getString(R.string.report_permission); render(); }
        }
    }

    private interface Job { String run() throws Exception; }

    private void runTask(Job job, Runnable success) {
        runTask(job, success, Operation.OTHER);
    }

    private void runTask(Job job, Runnable success, Operation requested) {
        if (busy) return;
        reportPrepared = false;
        if (requested != Operation.PREPARE_REPORT) diagnosticEvidence = "";
        operation = requested;
        String targetCardId = busyCardId;
        inlineError = null;
        inlineCardId = null;
        busy = true;
        message = "";
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        render();
        WORK.execute(() -> {
            String result = null;
            boolean completed = false;
            Throwable operationFailure = null;
            try { result = job.run(); completed = true; }
            catch (Throwable failure) {
                operationFailure = failure;
                evidence.append("\nOPERATION FAILURE\n").append(android.util.Log.getStackTraceString(failure));
                result = getString(R.string.error, describe(failure));
                if (failure.getSuppressed().length > 0) result += "\n" + getString(R.string.recovery);
            }
            String report;
            try {
                report = buildReport(result == null ? lastResult : result);
                Files.write(new File(getFilesDir(), "nfc-diagnostics.txt").toPath(), report.getBytes(StandardCharsets.UTF_8));
            } catch (Throwable failure) {
                report = "REPORT FAILURE\n" + android.util.Log.getStackTraceString(failure) + "\n" + evidence;
            }
            String finalResult = result;
            String finalReport = report;
            boolean succeeded = completed;
            Throwable finalFailure = operationFailure;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                busy = false;
                busyCardId = null;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (finalResult != null) {
                    lastResult = finalResult;
                    message = succeeded && requested != Operation.OTHER ? "" : finalResult;
                }
                reportText = finalReport;
                reportPrepared = succeeded && requested == Operation.PREPARE_REPORT;
                if (!succeeded) {
                    boolean needsRecovery = finalFailure.getSuppressed().length > 0;
                    try {
                        WatchNfcSession.Pending current = store.load();
                        needsRecovery |= current != null && (!current.active
                                || !Build.FINGERPRINT.equals(current.firmware)
                                || requested == Operation.STOP || requested == Operation.RESTORE);
                    } catch (Exception ignored) { needsRecovery = true; }
                    showFailure(finalFailure, needsRecovery, requested, targetCardId);
                }
                if (succeeded && success != null) success.run();
                if (succeeded && screen == Screen.CARDS && finalResult != null) {
                    getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                }
                render();
                if (!permissionPromptShown && !hasPermission() && screen == Screen.CARDS
                        && uiError == null) showSetupPrompt();
            });
        });
    }

    private void showFailure(Throwable failure, boolean needsRecovery, Operation requested, String cardId) {
        WatchUiError kind = WatchUiError.from(failure, needsRecovery, hasPermission());
        if (kind == WatchUiError.PERMISSION || kind == WatchUiError.NFC_OFF) {
            inlineError = kind;
            inlineCardId = cardId;
            uiError = null;
            message = "";
        } else {
            uiError = kind;
            errorOperation = requested;
        }
    }

    private String buildReport(String result) {
        StringBuilder output = new StringBuilder("EMULA WEAR\n").append(Instant.now()).append('\n')
                .append(BuildConfig.APPLICATION_ID).append(' ').append(BuildConfig.VERSION_NAME)
                .append("\nManufacturer: ").append(Build.MANUFACTURER)
                .append("\nModel: ").append(Build.MODEL)
                .append("\nDevice: ").append(Build.DEVICE)
                .append("\nAndroid: ").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT)
                .append("\nFingerprint: ").append(Build.FINGERPRINT)
                .append("\nSecurity patch: ").append(Build.VERSION.SECURITY_PATCH)
                .append("\nActivation scope: Android 15+ watches; no model restriction")
                .append("\nWRITE_SECURE_SETTINGS: ").append(hasPermission())
                .append("\nNFC adapter: ").append(adapter != null).append('\n').append(adapterEvidence)
                .append("\nNFC enabled: ").append(adapter != null && adapter.isEnabled())
                .append("\nExperimental raw consent for current firmware: ").append(hasRawConsent())
                .append("\nRaw NCI API: ").append(LegacyNfcDiagnostics.isRawNciApiAvailable(this))
                .append("\nPolling method (foreground): ").append(LegacyNfcController.pollingMethod(this))
                .append("\nAvailable activation path: ").append(activationPath())
                .append("\n\n").append(OemNfcController.inspectCapabilities(this).format(true));
        for (String feature : new String[] {"android.hardware.type.watch", "android.hardware.nfc",
                "android.hardware.nfc.hce", "android.hardware.nfc.hcef", "android.hardware.nfc.ese"}) {
            output.append('\n').append(feature).append(": ").append(getPackageManager().hasSystemFeature(feature));
        }
        try {
            WatchNfcSession.Pending current = store.load();
            output.append("\nPending restore: ").append(current != null);
            if (current != null) output.append("\nPath: ").append(current.path)
                    .append("\nConfigured: ").append(current.active)
                    .append("\nSaved firmware: ").append(current.firmware)
                    .append("\nOriginal snapshot: ").append(current.snapshot);
        } catch (Exception failure) { output.append("\nPending state failure: ").append(failure); }
        return output.append("\n\nRESULT\n").append(result)
                .append("\n\nOPERATIONS\n").append(evidence)
                .append("\n\nREPORT DIAGNOSTIC\n").append(diagnosticEvidence.isEmpty()
                        ? "Not prepared yet" : diagnosticEvidence)
                .append("\n\nADB permission:\n")
                .append(getString(R.string.grant_command, BuildConfig.APPLICATION_ID)).toString();
    }

    private EditText input(String hint, String value, boolean hex) {
        EditText field = new EditText(this);
        field.setId(hex ? R.id.card_uid_input : R.id.card_name_input);
        field.setHint(hint);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(Color.LTGRAY);
        field.setTextSize(16);
        field.setSingleLine(true);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | (hex ? InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS : InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
        InputFilter hexOnly = (source, start, end, destination, replaceStart, replaceEnd) -> {
            for (int index = start; index < end; index++) {
                char valueAt = source.charAt(index);
                if (!(valueAt >= '0' && valueAt <= '9') && !(valueAt >= 'A' && valueAt <= 'F')) return "";
            }
            return null;
        };
        field.setFilters(hex ? new InputFilter[] {new InputFilter.AllCaps(), hexOnly, new InputFilter.LengthFilter(20)}
                : new InputFilter[] {new InputFilter.LengthFilter(40)});
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                | (hex ? EditorInfo.IME_FLAG_FORCE_ASCII : 0));
        if (hex) field.setTypeface(Typeface.MONOSPACE);
        field.setText(value);
        field.setEnabled(!busy);
        content.addView(field, new LinearLayout.LayoutParams(-1, dp(52)));
        return field;
    }

    private interface OnText { void changed(String value); }
    private static TextWatcher watcher(OnText change) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { change.changed(s.toString()); }
            @Override public void afterTextChanged(Editable text) {}
        };
    }

    private TextView addText(String value, int size, int color) {
        return addText(content, value, size, color);
    }

    private TextView addText(LinearLayout parent, String value, int size, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setGravity(Gravity.CENTER);
        text.setPadding(0, dp(6), 0, dp(6));
        parent.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return text;
    }

    private Button button(String label, Runnable action) {
        return button(content, label, action);
    }

    private Button button(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(10), dp(8), dp(10), dp(8));
        button.setBackground(buttonBackground(false));
        button.setEnabled(!busy);
        button.setAlpha(busy ? 0.45f : 1f);
        button.setOnClickListener(view -> { if (!busy) action.run(); });
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(4);
        layout.bottomMargin = dp(4);
        parent.addView(button, layout);
        return button;
    }

    private RippleDrawable buttonBackground(boolean active) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(active ? Color.rgb(37, 69, 105) : Color.rgb(30, 41, 57));
        shape.setCornerRadius(dp(28));
        if (active) shape.setStroke(dp(2), ACCENT);
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null);
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String describe(Throwable failure) {
        return failure.getMessage() == null ? failure.toString() : failure.getMessage();
    }
}
