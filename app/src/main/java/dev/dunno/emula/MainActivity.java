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
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
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
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
    private static final String STATE_SEARCH_OPEN = "search.open";
    private static final String STATE_SEARCH_QUERY = "search.query";
    private static final String STATE_EDITOR_CARD_ID = "editor.card_id";
    private static final String STATE_EDITOR_NAME = "editor.name";
    private static final String STATE_EDITOR_UID = "editor.uid";
    private static final String STATE_EDITOR_UID_LENGTH = "editor.uid_length";
    private static final String STATE_EDITOR_MESSAGE = "editor.message";
    private static final String STATE_EDITOR_MESSAGE_ERROR = "editor.message_error";
    private static final String STATE_EDITOR_FOCUS = "editor.focus";
    private static final String STATE_EDITOR_READER = "editor.reader";
    private static final int EDITOR_FOCUS_NONE = -2;
    private static final int EDITOR_FOCUS_NAME = -1;
    private static final String APP_PREFS = "app_settings";
    private static final String PREF_FORCE_RAW_MODE = "force_raw_mode";
    private static final int MENU_RAW_MODE = 1;
    private static final int MENU_NFC_DIAGNOSTICS = 2;
    private static final int MENU_EDIT_CARD = 3;
    private static final int MENU_DELETE_CARD = 4;
    private static final int MENU_PRIVACY_POLICY = 6;
    private static final int MENU_IMPORT_CSV = 7;
    private static final int MENU_EXPORT_CSV = 8;
    private static final int MENU_PIN_CARD = 9;
    private static final int MENU_ADD_TILE = 10;
    private static final int MENU_TILE_CARD = 11;
    private static final long NFC_PROGRESS_STATUS_DELAY_MILLIS = 300;
    private static final long PERMISSION_CHECK_INTERVAL_MILLIS = 1_000;
    private static final long SNACKBAR_DURATION_MILLIS = 2_600;
    private static final long ERROR_SNACKBAR_DURATION_MILLIS = 5_200;
    private static final float CARD_START_HUE = 268f;
    private static final float GOLDEN_ANGLE_DEGREES = 137.50776f;

    private final AtomicBoolean tagHandled = new AtomicBoolean(false);

    private NfcAdapter adapter;
    private CardTransfer cardTransfer;
    private CardQuickActions quickActions;
    private PhoneNfcRuntime nfc;
    private AlertDialog nfcConsentDialog;
    private final Runnable nfcStateChanged = () -> {
        if (isFinishing() || isDestroyed()) return;
        busy = nfc.isBusy();
        renderCards();
        resumeEditorReaderIfReady();
        requestNotificationPermissionIfNeeded();
    };
    private LinearLayout cardsContainer;
    private ScrollView cardsScroll;
    private List<SavedCard> displayedCards = List.of();
    private final List<View> displayedCardViews = new ArrayList<>();
    private TextView noSearchResults;
    private LinearLayout normalHeader;
    private LinearLayout mainSearchBar;
    private EditText mainSearchInput;
    private TextView screenCaption;
    private ImageButton searchButton;
    private LinearLayout hiddenActiveCard;
    private TextView hiddenActiveLabel;
    private Button hiddenActiveStop;
    private boolean mainSearchOpen;
    private String mainSearchQuery = "";
    private final OnBackInvokedCallback searchBack = this::closeMainSearch;
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
    private UidByteEditText[] editorUid = new UidByteEditText[0];
    private int editorUidLength;
    private UidByteGrid editorUidGrid;
    private RadioGroup editorUidLengthSelector;
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
        nfc = PhoneNfcRuntime.get(this);
        if (savedInstanceState != null) {
            mainSearchOpen = savedInstanceState.getBoolean(STATE_SEARCH_OPEN);
            mainSearchQuery = savedInstanceState.getString(STATE_SEARCH_QUERY, "");
        }
        setContentView(buildUi());
        if (mainSearchOpen) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, searchBack);
            mainSearchInput.requestFocus();
        }
        cardTransfer = new CardTransfer(this, new CardTransfer.Host() {
            @Override public boolean canImport() {
                return !busy && !readerMode && editorDialog == null && permissionDialog == null;
            }
            @Override public void importApproved(List<CardCsv.Entry> cards, boolean replace) {
                importCsvCards(cards, replace);
            }
            @Override public void message(String text) { showSnackbar(text); }
            @Override public Dialog chooseExport(Consumer<Boolean> selected) {
                return showCsvExportChooser(selected);
            }
        }, savedInstanceState);
        quickActions = new CardQuickActions(this, new CardQuickActions.Host() {
            @Override public boolean canRun() {
                return !busy && !readerMode && editorDialog == null && permissionDialog == null
                        && !cardTransfer.hasPendingImport()
                        && getWindow().getDecorView().hasWindowFocus();
            }
            @Override public void toggle(SavedCard card) { toggleCard(card); }
            @Override public void message(int resource) { showSnackbar(resource); }
            @Override public Dialog chooseCard(List<SavedCard> cards, String selectedId,
                    String query, Consumer<String> queryChanged,
                    Consumer<SavedCard> selected, Runnable cancelled) {
                return showTileCardChooser(cards, selectedId, query, queryChanged, selected, cancelled);
            }
        }, savedInstanceState);
        getWindow().getInsetsController().setSystemBarsAppearance(0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        renderCards();

        nfc.addListener(nfcStateChanged);
        nfc.attachUi(this, () -> resumed && (readerMode || editorDialog != null
                || permissionDialog != null || nfcConsentDialog != null
                || cardTransfer.hasPendingImport()));
        busy = nfc.isBusy();
        restoreDefaultsAtSessionStart();

        if (savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_EDITOR_OPEN, false)) {
            restoreCardEditor(savedInstanceState);
        }
        if (savedInstanceState == null && !quickActions.handle(getIntent())) {
            cardTransfer.handleIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (quickActions.matches(intent) && cardTransfer.hasPendingImport()) {
            showSnackbar(R.string.quick_finish_import);
        } else if (!quickActions.handle(intent)) {
            if (Intent.ACTION_SEND.equals(intent.getAction()) || Intent.ACTION_VIEW.equals(intent.getAction())) {
                quickActions.clear();
            }
            cardTransfer.handleIntent(intent);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        cardTransfer.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        nfc.resumeUi(this);
        refreshPermissionUi();
        resumeEditorReaderIfReady();
        showPermissionRequestIfNeeded();
        cardTransfer.onResume();
        quickActions.resume();
        requestNotificationPermissionIfNeeded();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (nfc != null) nfc.focusUi(this, hasFocus);
        if (hasFocus && quickActions != null) {
            quickActions.onReady();
            requestNotificationPermissionIfNeeded();
        }
    }

    @Override
    protected void onPause() {
        nfc.pauseUi(this);
        quickActions.pause();
        cardTransfer.onPause();
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
        outState.putBoolean(STATE_SEARCH_OPEN, mainSearchOpen);
        outState.putString(STATE_SEARCH_QUERY, mainSearchQuery);
        cardTransfer.saveState(outState);
        quickActions.saveState(outState);
        outState.putBoolean(STATE_PERMISSION_PROMPT_SHOWN, permissionPromptShown);
        outState.putBoolean(STATE_PERMISSION_DIALOG_OPEN,
                permissionDialog != null && permissionDialog.isShowing());
        if (editorDialog != null && editorDialog.isShowing()) {
            outState.putBoolean(STATE_EDITOR_OPEN, true);
            if (editorCard != null) outState.putString(STATE_EDITOR_CARD_ID, editorCard.id);
            outState.putString(STATE_EDITOR_NAME, editorName.getText().toString());

            outState.putStringArray(STATE_EDITOR_UID, readEditorUidParts());
            outState.putInt(STATE_EDITOR_UID_LENGTH, editorUidLength);
            outState.putString(STATE_EDITOR_MESSAGE, editorMessage.getText().toString());
            outState.putBoolean(STATE_EDITOR_MESSAGE_ERROR, editorMessageIsError);
            outState.putBoolean(STATE_EDITOR_READER,
                    (readerMode && readerForEditor)
                            || editorReaderInterruptedForConfiguration);

            int focusedField = EDITOR_FOCUS_NONE;
            if (editorName.hasFocus()) {
                focusedField = EDITOR_FOCUS_NAME;
            } else if (editorUidGrid.hasFocus()) {
                focusedField = editorUidGrid.getActiveByte();
            }
            outState.putInt(STATE_EDITOR_FOCUS, focusedField);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (mainSearchOpen) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(searchBack);
        if (quickActions != null) quickActions.dispose();
        if (cardTransfer != null) cardTransfer.dispose();
        stopPermissionChecks();
        if (permissionDialog != null) permissionDialog.dismiss();
        cancelNfcProgressStatus();
        cancelSnackbar();
        nfc.removeListener(nfcStateChanged);
        nfc.detachUi(this);
        if (nfcConsentDialog != null) nfcConsentDialog.dismiss();
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
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
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
        normalHeader = header;
        header.setVisibility(mainSearchOpen ? View.GONE : View.VISIBLE);
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView heading = new TextView(this);
        heading.setText(R.string.screen_title);
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(30);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(heading, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        addButton = iconAction(R.drawable.ic_add_24, R.string.add_card);
        addButton.setOnClickListener(v -> {
            if (!busy && !readerMode) showCardEditor(null);
        });
        header.addView(addButton, new LinearLayout.LayoutParams(dp(48), dp(48)));

        searchButton = iconAction(R.drawable.ic_search_24, R.string.search_cards);
        searchButton.setOnClickListener(v -> openMainSearch());
        header.addView(searchButton, new LinearLayout.LayoutParams(dp(48), dp(48)));

        appMenuButton = iconAction(R.drawable.ic_more_vert_24, R.string.app_menu);
        appMenuButton.setOnClickListener(this::showAppMenu);
        header.addView(appMenuButton, new LinearLayout.LayoutParams(dp(48), dp(48)));

        mainSearchBar = new LinearLayout(this);
        mainSearchBar.setGravity(Gravity.CENTER_VERTICAL);
        mainSearchBar.setVisibility(mainSearchOpen ? View.VISIBLE : View.GONE);
        ImageButton searchBackButton = iconAction(R.drawable.ic_arrow_back_24, R.string.search_close);
        searchBackButton.setOnClickListener(v -> closeMainSearch());
        mainSearchBar.addView(searchBackButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        mainSearchInput = newSearchInput(mainSearchQuery);
        mainSearchBar.addView(searchField(mainSearchInput, query -> {
            mainSearchQuery = query;
            filterMainCards();
            if (cardsScroll != null) cardsScroll.scrollTo(0, 0);
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams searchBarParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchBarParams.bottomMargin = dp(16);
        root.addView(mainSearchBar, searchBarParams);

        hiddenActiveCard = new LinearLayout(this);
        hiddenActiveCard.setGravity(Gravity.CENTER_VERTICAL);
        hiddenActiveCard.setPadding(dp(12), dp(8), 0, dp(8));
        hiddenActiveCard.setBackground(rounded(SURFACE, 14));
        hiddenActiveCard.setVisibility(View.GONE);
        hiddenActiveLabel = new TextView(this);
        hiddenActiveLabel.setTextColor(Color.WHITE);
        hiddenActiveLabel.setTextSize(14);
        hiddenActiveCard.addView(hiddenActiveLabel,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        hiddenActiveStop = dialogAction(R.string.background_stop);
        hiddenActiveStop.setMinHeight(dp(48));
        hiddenActiveStop.setOnClickListener(v -> {
            if (!busy && !readerMode) setActiveCard(null, null, getString(R.string.progress_restore_standard_nfc));
        });
        hiddenActiveCard.addView(hiddenActiveStop);
        LinearLayout.LayoutParams activeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        activeParams.bottomMargin = dp(16);
        root.addView(hiddenActiveCard, activeParams);

        TextView caption = new TextView(this);
        screenCaption = caption;
        caption.setVisibility(mainSearchOpen ? View.GONE : View.VISIBLE);
        caption.setText(R.string.screen_caption);
        caption.setTextColor(Color.rgb(143, 150, 164));
        caption.setTextSize(14);
        caption.setPadding(0, dp(6), 0, dp(18));
        root.addView(caption);

        ScrollView scroll = new ScrollView(this);
        cardsScroll = scroll;
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
        displayedCards = cards;
        displayedCardViews.clear();
        QuickAccess.refresh(this, cards);
        if (cards.isEmpty()) {
            cardsContainer.addView(createEmptyCard(), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(164)));
        } else {
            for (int i = 0; i < cards.size(); i++) {
                View cardView = createCardView(cards.get(i), i);
                displayedCardViews.add(cardView);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(132));
                params.bottomMargin = dp(14);
                cardsContainer.addView(cardView, params);
            }
        }
        noSearchResults = searchEmptyMessage();
        cardsContainer.addView(noSearchResults);
        filterMainCards();

        boolean controlsEnabled = !busy && !readerMode;
        cardsContainer.setAlpha(1f);
        addButton.setEnabled(controlsEnabled);
        addButton.setAlpha(1f);
        appMenuButton.setEnabled(controlsEnabled);
        appMenuButton.setAlpha(1f);
        searchButton.setEnabled(controlsEnabled);
        refreshPermissionUi();
        cardsContainer.post(() -> {
            if (cardTransfer != null) cardTransfer.onHostReady();
            if (quickActions != null) quickActions.onReady();
        });
    }

    private void openMainSearch() {
        if (mainSearchOpen || busy || readerMode) return;
        mainSearchOpen = true;
        normalHeader.setVisibility(View.GONE);
        screenCaption.setVisibility(View.GONE);
        mainSearchBar.setVisibility(View.VISIBLE);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, searchBack);
        mainSearchInput.requestFocus();
        mainSearchInput.post(() -> getSystemService(InputMethodManager.class)
                .showSoftInput(mainSearchInput, InputMethodManager.SHOW_IMPLICIT));
        filterMainCards();
    }

    private void closeMainSearch() {
        if (!mainSearchOpen) return;
        mainSearchOpen = false;
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(searchBack);
        getSystemService(InputMethodManager.class)
                .hideSoftInputFromWindow(mainSearchInput.getWindowToken(), 0);
        mainSearchInput.setText("");
        mainSearchInput.clearFocus();
        mainSearchBar.setVisibility(View.GONE);
        normalHeader.setVisibility(View.VISIBLE);
        screenCaption.setVisibility(View.VISIBLE);
        filterMainCards();
    }

    private void filterMainCards() {
        if (noSearchResults == null) return;
        CardSearch query = new CardSearch(mainSearchOpen ? mainSearchQuery : "");
        int visible = 0;
        SavedCard hiddenActive = null;
        for (int i = 0; i < displayedCards.size(); i++) {
            SavedCard card = displayedCards.get(i);
            boolean matches = query.matches(card);
            displayedCardViews.get(i).setVisibility(matches ? View.VISIBLE : View.GONE);
            if (matches) visible++;
            else if (CardSession.isActive(card.id)) hiddenActive = card;
        }
        noSearchResults.setVisibility(!displayedCards.isEmpty() && visible == 0 ? View.VISIBLE : View.GONE);
        hiddenActiveCard.setVisibility(hiddenActive == null ? View.GONE : View.VISIBLE);
        if (hiddenActive != null) {
            hiddenActiveLabel.setText(getString(R.string.search_active_card,
                    hiddenActive.name, Hex.bytes(hiddenActive.uid)));
            hiddenActiveStop.setEnabled(!busy && !readerMode);
        }
    }

    private ImageButton iconAction(int icon, int label) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setContentDescription(getString(label));
        button.setClickable(true);
        button.setFocusable(true);
        applyBorderlessRipple(button);
        return button;
    }

    private EditText newSearchInput(String query) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(TEXT_SECONDARY);
        input.setTextSize(16);
        input.setHint(R.string.search_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(dp(12), dp(10), 0, dp(10));
        input.setMinHeight(dp(48));
        input.setText(query);
        input.setSelection(input.length());
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
            return true;
        });
        return input;
    }

    private LinearLayout searchField(EditText input, Consumer<String> changed) {
        LinearLayout field = new LinearLayout(this);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setBackground(rounded(SURFACE, 14));
        field.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton clear = iconAction(R.drawable.ic_close_24, R.string.search_clear);
        clear.setVisibility(input.length() == 0 ? View.INVISIBLE : View.VISIBLE);
        clear.setOnClickListener(v -> input.setText(""));
        field.addView(clear, new LinearLayout.LayoutParams(dp(48), dp(48)));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable text) {
                clear.setVisibility(text.length() == 0 ? View.INVISIBLE : View.VISIBLE);
                changed.accept(text.toString());
            }
        });
        return field;
    }

    private TextView searchEmptyMessage() {
        TextView empty = new TextView(this);
        empty.setText(R.string.search_no_results);
        empty.setTextColor(TEXT_SECONDARY);
        empty.setTextSize(16);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(16), dp(32), dp(16), dp(32));
        empty.setVisibility(View.GONE);
        return empty;
    }

    private Dialog showTileCardChooser(List<SavedCard> cards, String selectedId,
            String query, Consumer<String> queryChanged,
            Consumer<SavedCard> selected, Runnable cancelled) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = choiceDialogPanel(R.string.quick_choose_card);
        LinearLayout list = choiceDialogList(panel);
        EditText input = newSearchInput(query);
        List<View> rows = new ArrayList<>();
        TextView empty = searchEmptyMessage();
        Runnable filter = () -> {
            CardSearch search = new CardSearch(input.getText().toString());
            int visible = 0;
            for (int i = 0; i < cards.size(); i++) {
                boolean matches = search.matches(cards.get(i));
                rows.get(i).setVisibility(matches ? View.VISIBLE : View.GONE);
                if (matches) visible++;
            }
            empty.setVisibility(visible == 0 ? View.VISIBLE : View.GONE);
        };
        LinearLayout search = searchField(input, value -> {
            queryChanged.accept(value);
            filter.run();
            ((ScrollView) list.getParent()).scrollTo(0, 0);
        });
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchParams.bottomMargin = dp(12);
        panel.addView(search, panel.indexOfChild((View) list.getParent()), searchParams);
        for (int i = 0; i < cards.size(); i++) {
            SavedCard card = cards.get(i);
            boolean checked = card.id.equals(selectedId);
            String uidLabel = getString(R.string.card_uid, Hex.bytes(card.uid));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(14), dp(12), dp(14));
            row.setMinimumHeight(dp(82));
            row.setBackground(cardBackground(i, checked));
            row.setForeground(new RippleDrawable(ColorStateList.valueOf(Color.argb(40, 255, 255, 255)),
                    null, rounded(Color.WHITE, 22)));
            row.setClickable(true);
            row.setFocusable(true);
            row.setSelected(checked);
            row.setContentDescription(card.name + ", " + uidLabel);
            row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(RadioButton.class.getName());
                    info.setCheckable(true);
                    info.setChecked(checked);
                }
            });
            row.setOnClickListener(v -> { dialog.dismiss(); selected.accept(card); });

            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            TextView name = new TextView(this);
            name.setText(card.name);
            name.setTextColor(Color.WHITE);
            name.setTextSize(18);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(name);
            TextView uid = new TextView(this);
            uid.setText(uidLabel);
            uid.setTextColor(Color.argb(205, 255, 255, 255));
            uid.setTextSize(13);
            uid.setTypeface(Typeface.MONOSPACE);
            uid.setPadding(0, dp(6), 0, 0);
            text.addView(uid);
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            RadioButton mark = new RadioButton(this);
            mark.setChecked(checked);
            mark.setButtonTintList(ColorStateList.valueOf(Color.WHITE));
            mark.setClickable(false);
            mark.setFocusable(false);
            mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(dp(40), dp(48));
            markParams.setMarginStart(dp(8));
            row.addView(mark, markParams);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) rowParams.topMargin = dp(10);
            list.addView(row, rowParams);
            rows.add(row);
        }
        list.addView(empty);
        Button cancel = addChoiceCancel(panel, () -> { cancelled.run(); dialog.dismiss(); });
        dialog.setOnCancelListener(d -> cancelled.run());
        panel.setFocusableInTouchMode(true);
        panel.requestFocus();
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        showChoiceDialog(dialog, panel);
        fitSearchDialogToKeyboard(dialog, panel, search, cancel, rows);
        filter.run();
        return dialog;
    }

    private Dialog showCsvExportChooser(Consumer<Boolean> selected) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = choiceDialogPanel(R.string.csv_export_title);
        LinearLayout options = choiceDialogList(panel);
        int[] labels = {R.string.csv_save_file, R.string.csv_share_file};
        for (int i = 0; i < labels.length; i++) {
            boolean share = i == 1;
            Button action = dialogAction(labels[i]);
            action.setTextColor(Color.WHITE);
            action.setTextSize(16);
            action.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            action.setPadding(dp(18), dp(12), dp(18), dp(12));
            action.setMinHeight(dp(56));
            action.setMinimumHeight(dp(56));
            action.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(40, 255, 255, 255)),
                    rounded(SURFACE, 14), null));
            action.setOnClickListener(v -> { dialog.dismiss(); selected.accept(share); });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) params.topMargin = dp(10);
            options.addView(action, params);
        }
        addChoiceCancel(panel, dialog::dismiss);
        showChoiceDialog(dialog, panel);
        return dialog;
    }

    private LinearLayout choiceDialogPanel(int titleResource) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(22), dp(24), dp(12));
        panel.setBackground(rounded(DIALOG_SURFACE, 22));
        TextView title = new TextView(this);
        title.setText(titleResource);
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(18));
        panel.addView(title);
        return panel;
    }

    private LinearLayout choiceDialogList(LinearLayout panel) {
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return list;
    }

    private Button addChoiceCancel(LinearLayout panel, Runnable cancelled) {
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(8), 0, 0);
        Button cancel = dialogAction(R.string.action_cancel);
        cancel.setOnClickListener(v -> cancelled.run());
        actions.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        panel.addView(actions);
        return cancel;
    }

    private void fitSearchDialogToKeyboard(Dialog dialog, LinearLayout panel,
            LinearLayout search, Button cancel, List<View> rows) {
        Window window = dialog.getWindow();
        if (window == null) return;
        int originalHeight = window.getAttributes().height;
        View title = panel.getChildAt(0);
        LinearLayout footer = (LinearLayout) cancel.getParent();
        boolean[] wasCompact = {false};
        // A fixed-height floating window can extend behind the IME despite ADJUST_RESIZE.
        window.getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (!dialog.isShowing()) return;
            WindowMetrics metrics = getWindowManager().getCurrentWindowMetrics();
            WindowInsets insets = metrics.getWindowInsets();
            WindowInsets activityInsets = getWindow().getDecorView().getRootWindowInsets();
            boolean keyboard = insets.isVisible(WindowInsets.Type.ime())
                    || (activityInsets != null && activityInsets.isVisible(WindowInsets.Type.ime()));
            Insets safe = insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int keyboardBottom = Math.max(insets.getInsets(WindowInsets.Type.ime()).bottom,
                    activityInsets == null ? 0 : activityInsets.getInsets(WindowInsets.Type.ime()).bottom);
            int available = metrics.getBounds().height() - safe.top - Math.max(safe.bottom, keyboardBottom) - dp(8);
            int height = keyboard ? Math.min(originalHeight, Math.max(1, available)) : originalHeight;
            boolean compact = keyboard && available < dp(300);
            if (compact != wasCompact[0]) {
                wasCompact[0] = compact;
                title.setVisibility(compact ? View.GONE : View.VISIBLE);
                panel.setPadding(dp(compact ? 16 : 24), dp(compact ? 8 : 22),
                        dp(compact ? 16 : 24), dp(compact ? 8 : 12));
                ((ViewGroup) cancel.getParent()).removeView(cancel);
                (compact ? search : footer).addView(cancel, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
                footer.setVisibility(compact ? View.GONE : View.VISIBLE);
                LinearLayout.LayoutParams searchParams = (LinearLayout.LayoutParams) search.getLayoutParams();
                searchParams.bottomMargin = dp(compact ? 8 : 12);
                search.setLayoutParams(searchParams);
                for (View row : rows) row.setPadding(dp(16), dp(compact ? 8 : 14),
                        dp(12), dp(compact ? 8 : 14));
            }
            WindowManager.LayoutParams params = window.getAttributes();
            int gravity = keyboard ? Gravity.TOP | Gravity.CENTER_HORIZONTAL : Gravity.CENTER;
            int y = keyboard ? dp(4) : 0;
            if (params.height != height || params.gravity != gravity || params.y != y) {
                params.height = height;
                params.gravity = gravity;
                params.y = y;
                window.setAttributes(params);
            }
        });
    }

    private void showChoiceDialog(Dialog dialog, LinearLayout panel) {
        dialog.setContentView(panel);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = getEditorDialogWidth();
            panel.measure(View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(getDiagnosticsDialogHeight(), View.MeasureSpec.AT_MOST));
            params.height = panel.getMeasuredHeight();
            params.dimAmount = 0.68f;
            window.setAttributes(params);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
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
        QuickAccess.remember(this, card);
        if (QuickCardPolicy.deactivate(card, CardSession.getActiveCardId())) {
            setActiveCard(null, null, getString(R.string.progress_restore_standard_nfc));
        } else {
            setActiveCard(card, null, getString(R.string.progress_activate_card, card.name));
        }
    }

    private void showCardMenu(View anchor, SavedCard card) {
        if (busy || readerMode) return;
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(Menu.NONE, MENU_EDIT_CARD, Menu.NONE, R.string.menu_edit);
        MenuItem tile = popup.getMenu().add(Menu.NONE, MENU_TILE_CARD, Menu.NONE, R.string.quick_use_for_tile);
        tile.setCheckable(true);
        tile.setChecked(card.id.equals(QuickAccess.lastCard(this)));
        popup.getMenu().add(Menu.NONE, MENU_PIN_CARD, Menu.NONE, R.string.quick_pin_card);
        popup.getMenu().add(Menu.NONE, MENU_DELETE_CARD, Menu.NONE, R.string.menu_delete);
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_EDIT_CARD) {
                showCardEditor(card);
            } else if (item.getItemId() == MENU_DELETE_CARD) {
                confirmDelete(card);
            } else if (item.getItemId() == MENU_PIN_CARD) {
                QuickAccess.pin(this, card);
            } else if (item.getItemId() == MENU_TILE_CARD) {
                SavedCard current = findSavedCard(card.id);
                if (current == null) showSnackbar(R.string.quick_card_removed);
                else {
                    QuickAccess.remember(this, current);
                    QuickAccess.refresh(this);
                    showSnackbar(R.string.quick_tile_card_saved);
                }
            }
            return true;
        });
        popup.show();
    }

    private void showAppMenu(View anchor) {
        if (busy || readerMode) return;
        PopupMenu popup = new PopupMenu(this, anchor);
        boolean modernApi = OemNfcController.isUidEmulationSupported(this);
        boolean rawAvailable = LegacyNfcController.isExperimentalAvailable(this)
                || XiaomiNfcController.isTargetDevice() || XiaomiNfcController.hasPendingRestore(this);
        if (MotorolaStNfcController.isTargetDevice() || MotorolaStNfcController.isRawModeTarget()
                || MotorolaStNfcController.hasPendingRestore(this)) {
            rawAvailable = LegacyNfcDiagnostics.isRawNciApiAvailable(this);
        }
        if (rawAvailable) {
            boolean rawOnly = !modernApi;
            MenuItem rawMode = popup.getMenu().add(
                    Menu.NONE, MENU_RAW_MODE, Menu.NONE, R.string.menu_raw_mode);
            rawMode.setCheckable(true);
            rawMode.setChecked(rawOnly || isRawModeForced());
            rawMode.setEnabled(!rawOnly);
            if (MotorolaStNfcController.hasPendingRestore(this)
                    || ((MotorolaStNfcController.isTargetDevice() || MotorolaStNfcController.isRawModeTarget())
                        && CardSession.getActiveCardId() != null)
                    || XiaomiNfcController.hasPendingRestore(this)
                    || (XiaomiNfcController.isTargetDevice() && CardSession.getActiveCardId() != null)) {
                rawMode.setEnabled(false);
            }
        }
        popup.getMenu().add(
                Menu.NONE, MENU_NFC_DIAGNOSTICS, Menu.NONE, R.string.menu_nfc_diagnostics);
        popup.getMenu().add(
                Menu.NONE, MENU_IMPORT_CSV, Menu.NONE, R.string.menu_import_csv);
        popup.getMenu().add(
                Menu.NONE, MENU_EXPORT_CSV, Menu.NONE, R.string.menu_export_csv)
                .setEnabled(!CardStore.getCards(this).isEmpty());
        popup.getMenu().add(Menu.NONE, MENU_ADD_TILE, Menu.NONE, R.string.quick_add_tile);
        popup.getMenu().add(Menu.NONE, MENU_TILE_CARD, Menu.NONE, R.string.quick_tile_card)
                .setEnabled(!CardStore.getCards(this).isEmpty());
        popup.getMenu().add(
                Menu.NONE, MENU_PRIVACY_POLICY, Menu.NONE, R.string.menu_privacy_policy);
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_RAW_MODE) {
                if (isRawModeForced()) {
                    setRawModeForced(false);
                } else if (XiaomiNfcController.isTargetDevice()
                        || MotorolaStNfcController.isRawModeTarget()) {
                    setRawModeForced(true);
                } else {
                    confirmRawModeEnable();
                }
            } else if (item.getItemId() == MENU_NFC_DIAGNOSTICS) {
                showNfcDiagnostics();
            } else if (item.getItemId() == MENU_IMPORT_CSV) {
                cardTransfer.pickImport();
            } else if (item.getItemId() == MENU_EXPORT_CSV) {
                cardTransfer.chooseExport();
            } else if (item.getItemId() == MENU_PRIVACY_POLICY) {
                openExternalPage(R.string.privacy_policy_url);
            } else if (item.getItemId() == MENU_ADD_TILE) {
                QuickAccess.addTile(this);
            } else if (item.getItemId() == MENU_TILE_CARD) {
                quickActions.handle(QuickAccess.tileIntent(this, true));
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

        boolean canProbe = (rawApi || XiaomiNfcController.isTargetDevice()
                || XiaomiNfcController.hasPendingRestore(this))
                && permission && adapter != null && adapter.isEnabled();
        Button probeButton = null;
        if (canProbe) {
            diagnosticsText.append("\n\n" + getString(R.string.diagnostics_prepare_report_hint));
            probeButton = new Button(this);
            probeButton.setAllCaps(false);
            probeButton.setText(R.string.diagnostics_prepare_report);
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
        setDiagnosticsExportEnabled(copyButton, emailButton, !canProbe);

        if (probeButton != null) {
            Button activeProbeButton = probeButton;
            activeProbeButton.setOnClickListener(v -> {
                activeProbeButton.setEnabled(false);
                setDiagnosticsExportEnabled(copyButton, emailButton, false);
                diagnosticsText.setText(initialSummary + "\n\n"
                        + getString(R.string.diagnostics_reading));
                reportScroll.scrollTo(0, 0);
                Context app = getApplicationContext();
                nfc.submit(false, state -> {
                    String xiaomi = XiaomiNfcController.capabilities(app, true);
                    try {
                        if (!rawApi) return new String[] {xiaomi, xiaomi};
                        LegacyNfcDiagnostics.ProbeResult probe = LegacyNfcDiagnostics.runReadOnlyProbe(app);
                        return new String[] {probe.userSummary + "\n\n" + xiaomi,
                                probe.detailedSummary + "\n\n" + xiaomi};
                    } catch (Throwable error) {
                        String message = getString(R.string.diagnostics_failed, safeMessage(error));
                        return new String[] {message + "\n\n" + xiaomi, message + "\n\n" + xiaomi};
                    }
                }, (result, failure) -> {
                    if (isDestroyed() || !dialog.isShowing()) return;
                    if (failure == null) {
                        diagnosticsText.setText(diagnosticSummary(oemCapabilities, rawApi, permission, result[0]));
                        detailedSummary[0] = diagnosticDetailedSummary(oemCapabilities, rawApi, permission, result[1]);
                    } else {
                        String message = getString(R.string.diagnostics_failed, safeMessage(failure));
                        diagnosticsText.setText(diagnosticSummary(oemCapabilities, rawApi, permission, message));
                        detailedSummary[0] = diagnosticDetailedSummary(oemCapabilities, rawApi, permission, message);
                    }
                    reportScroll.scrollTo(0, 0);
                    activeProbeButton.setEnabled(true);
                    setDiagnosticsExportEnabled(copyButton, emailButton, true);
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

    private void setDiagnosticsExportEnabled(Button copy, Button email, boolean enabled) {
        for (Button action : new Button[] {copy, email}) {
            action.setEnabled(enabled);
            action.setAlpha(enabled ? 1f : 0.4f);
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
                .append('\n').append(XiaomiNfcController.capabilities(this, false))
                .append('\n')
                .append(MotorolaStNfcController.rawModeStatus(this, false))
                .append('\n')
                .append("Raw NCI API: ").append(rawApi ? "available" : "unavailable")
                .append('\n')
                .append("Permission: ").append(permission ? "granted" : "not granted")
                .append('\n')
                .append("Catalog profile: ")
                .append(RawNfcDeviceCatalog.profileDescription(
                        Build.MANUFACTURER, Build.MODEL))
                .append('\n')
                .append(nfc.pollingCapabilities(rawApi))
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
                .append('\n').append(XiaomiNfcController.capabilities(this, false))
                .append('\n')
                .append(MotorolaStNfcController.rawModeStatus(this, true))
                .append('\n')
                .append("Raw NCI API: ").append(rawApi ? "available" : "unavailable")
                .append('\n')
                .append("Permission: ").append(permission ? "granted" : "not granted")
                .append('\n')
                .append("Catalog profile: ")
                .append(RawNfcDeviceCatalog.profileDescription(
                        Build.MANUFACTURER, Build.MODEL))
                .append('\n')
                .append(nfc.pollingCapabilities(rawApi))
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
        Context app = getApplicationContext();
        runNfcWork(false, getString(R.string.progress_disable_before_delete), state -> {
            state.beforeDelete(card.id);
            CardStore.delete(app, card.id);
            return null;
        }, (v, failure) -> {
            if (failure != null) showNfcFailure(failure, true);
            else {
                renderCards();
                showIdleStatus();
                showSnackbar(R.string.card_deleted);
            }
        });
    }

    private void importCsvCards(List<CardCsv.Entry> incoming, boolean replace) {
        Context app = getApplicationContext();
        runNfcWork(false, getString(R.string.csv_disable_before_replace), state -> {
            if (replace) state.beforeReplace();
            return CardImport.apply(app, incoming, replace);
        }, (result, failure) -> {
            if (failure != null) {
                new AlertDialog.Builder(this).setTitle(R.string.csv_error_title)
                        .setMessage(getString(R.string.csv_import_failed) + "\n\n" + PhoneNfcRuntime.message(this, failure))
                        .setPositiveButton(R.string.action_close, null).show();
            } else {
                renderCards();
                showIdleStatus();
                showSnackbar(getString(R.string.csv_imported, result.added, result.skipped));
            }
        });
    }

    private void showCardEditor(SavedCard card) {
        if (busy || readerMode) return;
        editorCard = card;
        editorDialog = new Dialog(this);
        editorDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setFocusableInTouchMode(true);
        form.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
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
        editorName.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        editorName.setBackgroundTintList(null);
        editorName.setBackground(editorFieldBackground(android.R.attr.state_focused));
        editorName.setPadding(dp(12), 0, dp(12), 0);
        if (card != null) editorName.setText(card.name);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        nameParams.topMargin = dp(8);
        form.addView(editorName, nameParams);

        TextView uidLabel = editorLabel(R.string.editor_uid_label);
        uidLabel.setPadding(0, dp(20), 0, dp(8));
        form.addView(uidLabel);

        editorUidLengthSelector = new RadioGroup(this);
        editorUidLengthSelector.setOrientation(LinearLayout.HORIZONTAL);
        for (int length : new int[] {4, 7, 10}) {
            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId());
            option.setTag(length);
            option.setText(getResources().getQuantityString(R.plurals.editor_uid_bytes,
                    length, length));
            option.setTextSize(14);
            option.setGravity(Gravity.CENTER);
            option.setButtonDrawable(null);
            option.setPadding(dp(4), 0, dp(4), 0);
            int[][] states = new int[][] {new int[] {android.R.attr.state_checked}, new int[0]};
            option.setTextColor(new ColorStateList(states,
                    new int[] {BACKGROUND, TEXT_SECONDARY}));
            option.setBackground(rounded(SURFACE, 10));
            option.setBackgroundTintList(new ColorStateList(states,
                    new int[] {ACCENT, SURFACE}));
            RadioGroup.LayoutParams optionParams = new RadioGroup.LayoutParams(0, dp(48), 1);
            if (editorUidLengthSelector.getChildCount() > 0) optionParams.leftMargin = dp(8);
            editorUidLengthSelector.addView(option, optionParams);
        }
        form.addView(editorUidLengthSelector);
        editorUidGrid = new UidByteGrid(this);
        editorUidGrid.setId(View.generateViewId());
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gridParams.topMargin = dp(8);
        form.addView(editorUidGrid, gridParams);
        editorUid = new UidByteEditText[UidCodec.MAX_UID_LENGTH];
        for (int i = 0; i < editorUid.length; i++) {
            UidByteEditText field = createUidField(i);
            field.setId(View.generateViewId());
            field.setContentDescription(getString(R.string.editor_uid_byte_hint, i + 1));
            editorUid[i] = field;

            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            TextView number = new TextView(this);
            number.setText(getString(R.string.editor_uid_byte_number, i + 1));
            number.setTextSize(11);
            number.setTextColor(TEXT_SECONDARY);
            number.setGravity(Gravity.CENTER);
            number.setLabelFor(field.getId());
            number.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            cell.addView(number, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(20)));
            cell.addView(field, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
            editorUidGrid.addView(cell, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (card != null && i < card.uid.length) {
                field.setText(String.format(Locale.US, "%02X", card.uid[i] & 0xFF));
            }
        }
        editorUidGrid.setEditors(editorUid);
        setEditorUidLength(card == null ? UidCodec.DEFAULT_UID_LENGTH : card.uid.length);
        editorUidLengthSelector.setOnCheckedChangeListener((group, checkedId) -> {
            RadioButton selected = group.findViewById(checkedId);
            if (selected != null) setEditorUidLength((Integer) selected.getTag());
        });

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
            cardTransfer.onHostReady();
            quickActions.onReady();
        });
        Window window = editorDialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        editorDialog.show();
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

    private String[] readEditorUidParts() {
        String[] parts = new String[editorUid.length];
        for (int i = 0; i < editorUid.length; i++) {
            parts[i] = editorUid[i].getText().toString();
        }
        return parts;
    }

    private void setEditorUidLength(int length) {
        if (!UidCodec.isSupportedLength(length) || editorUidLength == length) return;
        // The grid remains the IME editor while its logical active byte is clamped.
        editorUidLength = length;
        editorUidGrid.setUidLength(length);
        for (int i = 0; i < editorUidLengthSelector.getChildCount(); i++) {
            RadioButton option = (RadioButton) editorUidLengthSelector.getChildAt(i);
            if ((Integer) option.getTag() == length) {
                editorUidLengthSelector.check(option.getId());
                break;
            }
        }
        // Preserve the current status and window bounds; only the byte layout changes.
    }

    private int getEditorDialogHeight() {
        int availableHeight = getResources().getDisplayMetrics().heightPixels;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics metrics = getWindowManager().getCurrentWindowMetrics();
            Insets safeInsets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            availableHeight = metrics.getBounds().height() - safeInsets.top - safeInsets.bottom;
        }
        // The UID area reserves two rows in every mode. Window height must also stay
        // independent of UID length, including in shorter portrait/landscape windows.
        int requiredHeight = dp(716);
        if (availableHeight >= requiredHeight) {
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
            int length = state.getInt(STATE_EDITOR_UID_LENGTH, UidCodec.DEFAULT_UID_LENGTH);
            setEditorUidLength(length);
            for (int i = 0; i < editorUid.length && i < uidParts.length; i++) {
                editorUid[i].setText(uidParts[i] == null ? "" : uidParts[i]);
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
        } else if (focusedField >= 0 && focusedField < editorUidLength) {
            editorUidGrid.selectByte(focusedField, true);
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

    private UidByteEditText createUidField(int index) {
        UidByteEditText field = new UidByteEditText(this);
        // Card bytes are plain text. Password variations can make keyboards offer
        // credential providers even when this view is excluded from autofill.
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setSingleLine(true);
        field.setOnClickListener(view -> editorUidGrid.showKeyboardForByte(index));
        field.setGravity(Gravity.CENTER);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(Color.rgb(130, 136, 148));
        field.setHint(R.string.uid_field_hint);
        field.setTextSize(20);
        field.setTypeface(Typeface.MONOSPACE);
        field.setPadding(0, 0, 0, 0);
        field.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_FORCE_ASCII
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        field.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_NEXT && actionId != EditorInfo.IME_ACTION_DONE) {
                return false;
            }
            if (index + 1 < editorUidLength) {
                editorUidGrid.selectByte(index + 1, true);
            } else {
                InputMethodManager keyboard = (InputMethodManager)
                        getSystemService(Context.INPUT_METHOD_SERVICE);
                keyboard.hideSoftInputFromWindow(field.getWindowToken(), 0);
            }
            return true;
        });
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
        field.setBackgroundTintList(null);
        field.setBackground(editorFieldBackground(android.R.attr.state_activated));
        field.addTextChangedListener(new TextWatcher() {
            private boolean deleting;

            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                deleting = before > count;
            }
            @Override public void afterTextChanged(Editable editable) {
                editorUidGrid.onByteTextChanged(index, deleting);
            }
        });
        return field;
    }

    private StateListDrawable editorFieldBackground(int activeState) {
        StateListDrawable background = new StateListDrawable();
        GradientDrawable focused = rounded(SURFACE, 10);
        focused.setStroke(dp(2), ACCENT);
        GradientDrawable idle = rounded(SURFACE, 10);
        idle.setStroke(dp(1), Color.rgb(91, 98, 112));
        background.addState(new int[] {activeState}, focused);
        background.addState(new int[0], idle);
        return background;
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
        if (nfc.isBusy()) return;
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

        String existingId = editorCard == null ? null : editorCard.id;
        Context app = getApplicationContext();
        runNfcWork(false, getString(R.string.progress_disable_edited_card), state -> {
            state.beforeSave(existingId, uid);
            SavedCard current = QuickCardPolicy.find(state.backend.cards(), existingId);
            return CardStore.save(app, current, name, uid);
        }, (saved, failure) -> {
            if (failure != null) showNfcFailure(failure, true);
            else {
                if (editorDialog != null) editorDialog.dismiss();
                renderCards();
                showIdleStatus();
                showSnackbar(getString(R.string.editor_card_saved, saved.name));
            }
        });
    }

    private byte[] readEditorUid() {
        return UidCodec.parse(Arrays.copyOf(readEditorUidParts(), editorUidLength));
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

    private void resumeEditorReaderIfReady() {
        if (!restoreEditorReaderModeOnResume || !resumed || nfc.isBusy()
                || editorDialog == null || !editorDialog.isShowing()) return;
        Window window = editorDialog.getWindow();
        if (window == null) return;
        window.getDecorView().post(() -> {
            if (!resumed || nfc.isBusy() || !restoreEditorReaderModeOnResume) return;
            restoreEditorReaderModeOnResume = false;
            startReadingIntoEditor();
        });
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
            nfc.setReaderBusy(true);
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
            nfc.setReaderBusy(false);
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
            if (uid == null || !UidCodec.isSupportedLength(uid.length)) {
                int length = uid == null ? 0 : uid.length;
                setEditorMessage(getString(R.string.editor_uid_length_error, length), true);
                return;
            }
            setEditorUidLength(uid.length);
            for (int i = 0; i < editorUid.length; i++) {
                editorUid[i].setText(i < uid.length
                        ? String.format(Locale.US, "%02X", uid[i] & 0xFF) : "");
            }
            performConfirmHaptic(editorDialog.getWindow().getDecorView());
            setEditorMessage(R.string.editor_uid_read, false);
        });
    }

    private void stopReaderMode() {
        nfc.setReaderBusy(false);
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

    private <T> void runNfcWork(boolean hardware, String progress,
            PhoneNfcRuntime.Work<T> work, PhoneNfcRuntime.Completion<T> completion) {
        scheduleNfcProgressStatus(progress);
        nfc.submit(hardware, work, (value, failure) -> {
            if (isFinishing() || isDestroyed()) return;
            cancelNfcProgressStatus();
            completion.complete(value, failure);
        });
    }

    private void setActiveCard(SavedCard card, Runnable onSuccess, String progressText) {
        setActiveCard(card, onSuccess, progressText, false);
    }

    private void setActiveCard(SavedCard card, Runnable onSuccess, String progressText, boolean confirmed) {
        PhoneNfcSession.Reason problem = nfc.session.backend.preflight(card);
        if (problem == PhoneNfcSession.Reason.CONSENT && !confirmed) {
            confirmExperimentalRawMode(card, onSuccess, progressText);
            return;
        }
        if (problem != null && !(confirmed && problem == PhoneNfcSession.Reason.CONSENT)) {
            showNfcFailure(new PhoneNfcSession.Failure(problem), card == null);
            return;
        }
        String id = card == null ? null : card.id;
        runNfcWork(true, progressText, state -> { state.change(id, confirmed); return null; }, (v, failure) -> {
            if (failure instanceof PhoneNfcSession.Failure
                    && ((PhoneNfcSession.Failure) failure).reason == PhoneNfcSession.Reason.CONSENT) {
                confirmExperimentalRawMode(card, onSuccess, progressText);
            } else if (failure != null) {
                showNfcFailure(failure, card == null);
            } else {
                renderCards();
                performConfirmHaptic(cardsContainer);
                if (onSuccess != null) onSuccess.run();
                else {
                    showIdleStatus();
                    SavedCard active = findSavedCard(CardSession.getActiveCardId());
                    showSnackbar(active == null ? getString(R.string.standard_nfc_restored)
                            : getString(R.string.card_active, active.name));
                }
                requestNotificationPermissionIfNeeded();
            }
        });
    }

    private void showNfcFailure(Throwable failure, boolean deactivating) {
        if (failure instanceof PhoneNfcSession.Failure) {
            PhoneNfcSession.Reason reason = ((PhoneNfcSession.Failure) failure).reason;
            if (reason == PhoneNfcSession.Reason.NO_NFC || reason == PhoneNfcSession.Reason.UNSUPPORTED) {
                showNoCompatibleNfcMessage();
                return;
            }
        }
        String message = PhoneNfcRuntime.message(this, failure);
        setStatus(message);
        if (editorMessage != null) setEditorMessage(message, true);
        if (failure instanceof PhoneNfcSession.Failure
                && ((PhoneNfcSession.Failure) failure).reason == PhoneNfcSession.Reason.FAILED) {
            if (deactivating) showErrorSnackbar(R.string.nfc_deactivation_failed);
            else showNfcSupportDialog(R.string.nfc_activation_failed);
        } else showSnackbar(message, ERROR_SNACKBAR_DURATION_MILLIS);
    }

    private void confirmExperimentalRawMode(SavedCard card, Runnable onSuccess, String progressText) {
        if (nfcConsentDialog != null) return;
        nfcConsentDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.experimental_raw_dialog_title)
                .setMessage(getString(R.string.experimental_raw_dialog_message,
                        Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.experimental_raw_try,
                        (dialog, which) -> setActiveCard(card, onSuccess, progressText, true))
                .create();
        nfcConsentDialog.setOnDismissListener(dialog -> nfcConsentDialog = null);
        nfcConsentDialog.show();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (!resumed || editorDialog != null || permissionDialog != null || nfcConsentDialog != null
                || cardTransfer.hasPendingImport() || nfc.isBusy()
                || !getWindow().getDecorView().hasWindowFocus() || !nfc.activeOrRecovery()
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences prefs = getSharedPreferences(APP_PREFS, MODE_PRIVATE);
        if (prefs.getBoolean("notifications_asked", false)) return;
        prefs.edit().putBoolean("notifications_asked", true).apply();
        requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 703);
    }

    private SavedCard findSavedCard(String cardId) {
        if (cardId == null) return null;
        for (SavedCard saved : CardStore.getCards(this)) {
            if (cardId.equals(saved.id)) return saved;
        }
        return null;
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
        runNfcWork(true, getString(R.string.progress_restore_standard_nfc), state -> {
            state.initialize();
            return null;
        }, (v, failure) -> {
            if (failure == null) showIdleStatus();
            else setStatus(PhoneNfcRuntime.message(this, failure));
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
        editorUid = new UidByteEditText[0];
        editorUidLength = 0;
        editorUidGrid = null;
        editorUidLengthSelector = null;
    }

    private void showPermissionRequestIfNeeded() {
        if ((permissionPromptShown && !reopenPermissionDialog) || hasWriteSecureSettings()
                || (cardTransfer != null && cardTransfer.hasPendingImport())
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
        permissionDialog.setOnDismissListener(dialog -> {
            permissionDialog = null;
            if (cardTransfer != null) cardTransfer.onHostReady();
            if (quickActions != null) quickActions.onReady();
        });
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
