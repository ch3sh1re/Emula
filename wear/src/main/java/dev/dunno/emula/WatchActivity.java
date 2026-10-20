package dev.dunno.emula;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private enum Screen { CARDS, EDITOR, DELETE, SETTINGS, SETUP, DIAGNOSTICS, RECEIVE }
    private static final int RECEIVE_PERMISSION = 0x5744;
    private LocalWatchLink receiveLink;
    private String receiveMessage = "";
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final int ACCENT = Color.rgb(141, 185, 255);
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
    private String reportText = "";
    private String adapterEvidence = "";
    private boolean busy;
    private boolean coldStart;
    private LinearLayout content;
    private ScrollView scroll;
    private EditText nameInput;
    private EditText uidInput;
    private WatchNfcSession.Pending pending;
    private boolean recovery;

    private final Runnable permissionRefresh = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            if (hasPermission()) {
                if (!busy && (screen == Screen.CARDS || screen == Screen.SETUP)) render();
            } else {
                handler.postDelayed(this, 1000);
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
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
            reportText = state.getString("reportText", "");
        }
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (coldStart) {
            coldStart = false;
            try {
                if (store.load() != null) runTask(() -> {
                    requireNfc();
                    session.restore();
                    return getString(R.string.restored);
                }, null);
            } catch (Exception failure) {
                message = getString(R.string.error, describe(failure));
                render();
            }
        } else if (!busy && screen != Screen.EDITOR) {
            render();
        }
        // Wear OS may resume this Activity before its fullscreen IME commits text.
        // Keep the editor and its InputConnection alive across that transition.
        handler.removeCallbacks(permissionRefresh);
        if (!hasPermission()) handler.postDelayed(permissionRefresh, 1000);
    }

    @Override protected void onPause() {
        cancelReceive();
        handler.removeCallbacks(permissionRefresh);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(permissionRefresh);
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
        state.putString("reportText", reportText);
        super.onSaveInstanceState(state);
    }

    private void render() {
        pending = null;
        recovery = false;
        try {
            pending = store.load();
            recovery = pending != null && (!pending.active || !Build.FINGERPRINT.equals(pending.firmware));
        } catch (Exception failure) {
            recovery = true;
            message = getString(R.string.error, describe(failure));
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
        switch (screen) {
            case EDITOR: renderEditor(); break;
            case DELETE: renderDelete(); break;
            case SETTINGS: renderSettings(); break;
            case SETUP: renderSetup(); break;
            case DIAGNOSTICS: renderDiagnostics(); break;
            case RECEIVE: renderReceive(); break;
            default: renderCards();
        }
        if (busy) addText(getString(R.string.busy), 13, ACCENT);
        else if (!message.isEmpty() && screen != Screen.DIAGNOSTICS) {
            TextView status = addText(message, 13, ACCENT);
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        }
        if (screen != Screen.EDITOR) scroll.requestFocus();
    }

    private void renderCards() {
        List<SavedCard> cards = CardStore.getCards(this);
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
        }
        button(getString(R.string.add_card), () -> openEditor(null));
        button(getString(R.string.receive_cards), this::beginReceive);
        if (!hasPermission()) button(getString(R.string.setup), () -> navigate(Screen.SETUP));
        button(getString(R.string.settings), () -> navigate(Screen.SETTINGS));
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
        runTask(() -> {
            WatchNfcSession.Pending current = store.load();
            if (current != null && current.cardId.equals(card.id)) {
                requireNfc();
                session.restore();
                return getString(R.string.restored);
            }
            if (!UidCodec.isSupportedLength(card.uid.length)) throw new IllegalArgumentException(getString(R.string.uid_error));
            requireNfc();
            if (!WatchReleasePolicy.supportsDevice(Build.MANUFACTURER, Build.MODEL)) {
                throw new UnsupportedOperationException(getString(R.string.unsupported_device));
            }
            if (!OemNfcController.inspectCapabilities(this).androidOemExtension) {
                throw new UnsupportedOperationException(getString(R.string.emulation_unavailable));
            }
            if (current != null) session.restore();
            session.activate(card.id, card.uid, WatchNfcSession.Path.OEM, false);
            return getString(R.string.active);
        }, null);
    }

    private void stopIfActive(String id) throws Exception {
        WatchNfcSession.Pending current = store.load();
        if (current != null && current.cardId.equals(id)) {
            requireNfc();
            session.restore();
        }
    }

    private void stopEmulation() {
        runTask(() -> { requireNfc(); session.restore(); return getString(R.string.restored); }, null);
    }

    private void renderSettings() {
        addText(getString(R.string.settings), 18, Color.WHITE);
        button(getString(R.string.setup), () -> navigate(Screen.SETUP));
        button(getString(R.string.diagnostics), () -> {
            screen = Screen.DIAGNOSTICS;
            runTask(() -> null, null);
        });
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
            TextView command = addText(getString(R.string.grant_command, BuildConfig.APPLICATION_ID), 11, Color.WHITE);
            command.setTypeface(Typeface.MONOSPACE);
            command.setTextIsSelectable(true);
        }
        addText(getString(R.string.setup_note), 12, Color.LTGRAY);
        button(getString(R.string.back), () -> navigate(Screen.CARDS));
    }

    private void renderDiagnostics() {
        addText(getString(R.string.diagnostics), 18, Color.WHITE);
        button(getString(R.string.get_config), () -> runTask(() -> {
            requireNfc();
            LegacyNfcDiagnostics.ProbeResult probe = LegacyNfcDiagnostics.runReadOnlyProbe(this);
            evidence.append("\nDIAGNOSTIC GET_CONFIG\n").append(probe.detailedSummary);
            return probe.logSummary;
        }, null));
        button(getString(R.string.back), () -> navigate(Screen.SETTINGS));
        TextView report = addText(reportText, 11, Color.LTGRAY);
        report.setGravity(Gravity.START);
        report.setTypeface(Typeface.MONOSPACE);
        report.setTextIsSelectable(true);
    }

    private void requireNfc() {
        if (adapter == null) throw new IllegalStateException(getString(R.string.no_adapter));
        if (!adapter.isEnabled()) throw new IllegalStateException(getString(R.string.nfc_off));
        if (!hasPermission()) throw new SecurityException(getString(R.string.grant_required));
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

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == RECEIVE_PERMISSION && screen == Screen.RECEIVE) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startReceive();
            else { receiveMessage = getString(R.string.receive_permission); render(); }
        }
    }

    private interface Job { String run() throws Exception; }

    private void runTask(Job job, Runnable success) {
        if (busy) return;
        busy = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        render();
        WORK.execute(() -> {
            String result = null;
            boolean completed = false;
            try { result = job.run(); completed = true; }
            catch (Throwable failure) {
                evidence.append("\nOPERATION FAILURE\n").append(android.util.Log.getStackTraceString(failure));
                result = getString(R.string.error, describe(failure));
                if (failure.getSuppressed().length > 0) result += "\n" + getString(R.string.recovery);
            }
            String report;
            try {
                report = buildReport(result == null ? message : result);
                Files.write(new File(getFilesDir(), "nfc-diagnostics.txt").toPath(), report.getBytes(StandardCharsets.UTF_8));
            } catch (Throwable failure) {
                report = "REPORT FAILURE\n" + android.util.Log.getStackTraceString(failure) + "\n" + evidence;
            }
            String finalResult = result;
            String finalReport = report;
            boolean succeeded = completed;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                busy = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (finalResult != null) message = finalResult;
                reportText = finalReport;
                if (succeeded && success != null) success.run();
                if (succeeded && screen == Screen.CARDS && finalResult != null) {
                    getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                }
                render();
            });
        });
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
                .append("\nSupported model: ").append(WatchReleasePolicy.supportsDevice(Build.MANUFACTURER, Build.MODEL))
                .append("\nWRITE_SECURE_SETTINGS: ").append(hasPermission())
                .append("\nNFC adapter: ").append(adapter != null).append('\n').append(adapterEvidence)
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
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setGravity(Gravity.CENTER);
        text.setPadding(0, dp(6), 0, dp(6));
        content.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return text;
    }

    private Button button(String label, Runnable action) {
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
        content.addView(button, layout);
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
