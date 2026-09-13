package com.example.soyomesaj;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class SoyoAutomationService extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String SOYO_PACKAGE = "com.haflla.soulu";
    private static final String SOYO_LITE_PACKAGE = "com.haflla.soulu.lite";

    private static final long TICK_WATCHDOG_MS = 700L;
    private static final long MIN_TICK_GAP_MS = 70L;
    private static final long OPEN_CHAT_TIMEOUT_MS = 2800L;
    private static final long HEADER_WAIT_MS = 2100L;
    private static final long SEND_VERIFY_TIMEOUT_MS = 2500L;
    private static final long RETURN_RETRY_MS = 1300L;
    private static final long TEMP_SKIP_MS = 8000L;
    private static final long SCROLL_GAP_MS = 560L;

    private enum Phase {
        IDLE,
        LIST,
        OPENING_CHAT,
        CHAT_READY,
        VERIFYING_SEND,
        RETURNING
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Long> temporarySkips = new HashMap<>();

    private AppPrefs settings;
    private SharedPreferences prefs;
    private HandledStore handledStore;
    private WindowManager windowManager;

    private Phase phase = Phase.IDLE;
    private long phaseSince = 0L;
    private String selectedName;
    private boolean selectedVip;
    private String expectedMessage;

    private boolean sendClicked;
    private long sendClickedAt;
    private int sendClickCount;
    private int backAttempts;
    private int chatNudgeCount;
    private long lastChatNudgeAt;
    private long listSettleUntil;

    private String lastListSnapshot = "";
    private int stagnantListScans;
    private long lastScrollAt;
    private int swipeLane;

    private boolean manualApproveRequested;
    private boolean manualCancelRequested;

    private boolean tickScheduled;
    private long tickScheduledFor;
    private long lastTickAt;

    private View approvalView;
    private TextView approvalTitle;
    private TextView approvalMessage;
    private Button approvalSend;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            tickScheduled = false;
            tickScheduledFor = 0L;
            lastTickAt = SystemClock.elapsedRealtime();
            try {
                runTick();
            } catch (RuntimeException e) {
                setStatus("Ekran yeniden okunuyor…");
                scheduleTick(300L);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        settings = new AppPrefs(this);
        prefs = settings.raw();
        handledStore = new HandledStore(this);
        handledStore.cleanupExpired();
        prefs.registerOnSharedPreferenceChangeListener(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        AccessibilityServiceInfo info = getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);

        handler.removeCallbacksAndMessages(null);
        tickScheduled = false;
        resetTransient(false);
        setStatus(settings.fullAuto()
                ? "Tam otomatik hazır · SOYO Önerilenler ekranını bekliyor."
                : "Hazır · SOYO ekranını bekliyor.");
        scheduleTick(180L);
        Toast.makeText(this, "SOYO Mesaj Yardımcısı hazır.", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence packageName = event.getPackageName();
        if (packageName == null) return;
        String pkg = packageName.toString();
        if (!isSoyoPackage(pkg)) {
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                hideApproval();
            }
            return;
        }

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_FOCUSED
                || type == AccessibilityEvent.TYPE_VIEW_CLICKED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            scheduleTick(60L);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (AppPrefs.KEY_FULL_AUTO.equals(key) || AppPrefs.KEY_AUTO_OPEN.equals(key)) {
            resetTransient(false);
            hideApproval();
            setStatus(settings.fullAuto() ? "Tam otomatik mod açıldı." : "Otomasyon modu güncellendi.");
            scheduleTick(80L);
            return;
        }
        if (AppPrefs.KEY_VIP_ONLY.equals(key)) {
            resetTransient(false);
            hideApproval();
            setStatus(settings.vipOnly() ? "VIP filtresi açık." : "VIP filtresi kapalı.");
            scheduleTick(100L);
            return;
        }
        if (AppPrefs.KEY_TEMPLATE.equals(key) || AppPrefs.KEY_TITLE.equals(key)) {
            if (phase == Phase.IDLE || phase == Phase.LIST) scheduleTick(100L);
            return;
        }
        if (AppPrefs.KEY_HISTORY_RESET.equals(key)) {
            temporarySkips.clear();
            setStatus("İşlenen geçmişi temizlendi.");
            scheduleTick(100L);
        }
    }

    private void runTick() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            scheduleWatchdogIfNeeded();
            return;
        }

        if (!isSoyoRoot(root)) {
            AccessibilityTools.recycle(root);
            scheduleWatchdogIfNeeded();
            return;
        }

        try {
            try {
                root.refresh();
            } catch (RuntimeException ignored) {
            }

            Screen screen = inspect(root);
            try {
                if (screen.recommendations) {
                    processRecommendations(screen);
                } else if (screen.editor != null) {
                    processChat(screen);
                } else {
                    processUnknownScreen(screen);
                }
            } finally {
                screen.close();
            }
        } finally {
            AccessibilityTools.recycle(root);
        }

        scheduleWatchdogIfNeeded();
    }

    private void processRecommendations(Screen screen) {
        hideApproval();
        long now = SystemClock.elapsedRealtime();

        if (phase == Phase.VERIFYING_SEND && sendClicked && !TextUtils.isEmpty(selectedName)) {
            handledStore.mark(selectedName);
            setStatus(selectedName + " işlendi · listeye dönüldü.");
            clearChatState();
            transition(Phase.LIST);
            listSettleUntil = now + 350L;
            scheduleTick(380L);
            return;
        }

        // Sohbet satırına tıklama kabul edildikten hemen sonra SOYO kısa süre daha eski listeyi
        // gösterebilir. Bu sırada state'i LIST'e döndürüp aynı satıra ikinci kez tıklamak duplicate
        // açılışın ana sebeplerinden biridir. OPENING_CHAT durumunda liste görünse bile bekliyoruz.
        if (phase == Phase.VERIFYING_SEND && sendClicked) {
            long waited = now - sendClickedAt;
            if (waited < SEND_VERIFY_TIMEOUT_MS) {
                setStatus(selectedName + " gönderimi doğrulanıyor…");
                scheduleTick(250L);
                return;
            }
            if (!TextUtils.isEmpty(selectedName)) handledStore.mark(selectedName);
            setStatus(selectedName + " gönderim ekranı gecikti · tekrar göndermemek için tamamlandı sayıldı.");
            transition(Phase.RETURNING);
            backAttempts = 0;
            if (clickRecommendationsTab(screen.nodes)) {
                scheduleTick(500L);
            } else {
                scheduleTick(450L);
            }
            return;
        }

        if (phase == Phase.OPENING_CHAT) {
            long waited = now - phaseSince;
            if (waited < OPEN_CHAT_TIMEOUT_MS) {
                setStatus(selectedName + " sohbetinin açılması bekleniyor…");
                scheduleTick(220L);
                return;
            }
            addTemporarySkip(selectedName, TEMP_SKIP_MS);
            setStatus(selectedName + " sohbeti açılmadı · tekrar tıklanmadan atlandı.");
            clearChatState();
            transition(Phase.LIST);
            listSettleUntil = now + 420L;
        } else if (phase == Phase.RETURNING || phase == Phase.CHAT_READY) {
            clearChatState();
            transition(Phase.LIST);
            listSettleUntil = now + 300L;
        } else if (phase == Phase.IDLE) {
            transition(Phase.LIST);
        }

        if (!settings.fullAuto() && !settings.autoOpen()) {
            setStatus("Önerilenler bulundu · otomatik sohbet açma kapalı.");
            return;
        }

        if (now < listSettleUntil) {
            scheduleTick(listSettleUntil - now + 30L);
            return;
        }

        Target target = findFirstTarget(screen.nodes);
        if (target != null) {
            try {
                selectedName = target.name;
                selectedVip = target.vip;
                expectedMessage = null;
                sendClicked = false;
                sendClickedAt = 0L;
                sendClickCount = 0;
                backAttempts = 0;
                chatNudgeCount = 0;
                lastChatNudgeAt = 0L;

                setStatus(target.name + " sohbeti açılıyor…");
                if (AccessibilityTools.click(target.button)) {
                    transition(Phase.OPENING_CHAT);
                    scheduleTick(320L);
                } else {
                    addTemporarySkip(target.name, 4500L);
                    setStatus(target.name + " satırına dokunulamadı · sıradaki deneniyor.");
                    clearChatState();
                    transition(Phase.LIST);
                    scheduleTick(250L);
                }
            } finally {
                target.close();
            }
            return;
        }

        if (settings.fullAuto()) {
            scrollRecommendations(screen.nodes);
        } else {
            setStatus("Görünür uygun sohbet kalmadı.");
        }
    }

    private void processChat(Screen screen) {
        long now = SystemClock.elapsedRealtime();

        if (phase == Phase.RETURNING) {
            processReturnWhileStillInChat(screen);
            return;
        }

        // Tam otomatik mod yalnızca kendisinin açtığı sohbeti işler. Servis yeniden başlatılırsa
        // veya mod aç/kapa sonrası hâlihazırda bir sohbet ekranındaysak yanlış kişiye mesaj atmak
        // yerine bu doğrulanmamış sohbetten bir kez çıkıp Önerilenler listesinden temiz başlarız.
        if (TextUtils.isEmpty(selectedName)) {
            if (settings.fullAuto()) {
                setStatus("Tam otomatik temiz başlangıç · Önerilenler listesine dönülüyor…");
                transition(Phase.RETURNING);
                backAttempts = 0;
                issueBack(screen);
                return;
            }
            String manualName = screen.headerName;
            if (TextUtils.isEmpty(manualName)) {
                setStatus("Sohbet adı okunamadı.");
                maybeWakeChatUi(now);
                return;
            }
            selectedName = manualName;
            selectedVip = screen.headerVip;
            expectedMessage = null;
            transition(Phase.CHAT_READY);
        }

        String header = screen.headerName;
        boolean headerMatches = !TextUtils.isEmpty(header) && NameCleaner.same(header, selectedName);
        if (!headerMatches) {
            long waited = now - phaseSince;
            setStatus("Sohbet doğrulanıyor: " + selectedName + "…");
            if (waited >= 600L) maybeWakeChatUi(now);
            if (waited < HEADER_WAIT_MS) {
                scheduleTick(240L);
                return;
            }

            // Eski sohbet başlığı Flutter ağacında takılı kaldıysa kesinlikle mesaj gönderme.
            String badName = selectedName;
            addTemporarySkip(badName, TEMP_SKIP_MS);
            setStatus(badName + " doğrulanamadı · yanlış kişiye göndermemek için atlandı.");
            beginReturn(screen, false);
            return;
        }

        if (settings.vipOnly() && !selectedVip && !screen.headerVip) {
            handledStore.mark(selectedName);
            setStatus(selectedName + " VIP değil · atlandı.");
            beginReturn(screen, false);
            return;
        }

        if (phase == Phase.OPENING_CHAT || phase == Phase.IDLE || phase == Phase.LIST) {
            transition(Phase.CHAT_READY);
        }

        if (phase == Phase.VERIFYING_SEND) {
            verifySend(screen);
            return;
        }

        if (manualCancelRequested) {
            manualCancelRequested = false;
            String current = AccessibilityTools.text(screen.editor).trim();
            if (!TextUtils.isEmpty(expectedMessage) && expectedMessage.equals(current)) {
                AccessibilityTools.setText(screen.editor, "");
            }
            handledStore.mark(selectedName);
            setStatus(selectedName + " atlandı.");
            hideApproval();
            clearChatState();
            transition(Phase.IDLE);
            return;
        }

        if (TextUtils.isEmpty(expectedMessage)) {
            expectedMessage = settings.buildMessage(selectedName);
        }

        String currentText = AccessibilityTools.text(screen.editor).trim();
        if (currentText.isEmpty()) {
            hideApproval();
            setStatus(selectedName + " için mesaj hazırlanıyor…");
            if (AccessibilityTools.setText(screen.editor, expectedMessage)) {
                scheduleTick(180L);
            } else {
                maybeWakeChatUi(now);
                scheduleTick(300L);
            }
            return;
        }

        if (!expectedMessage.equals(currentText)) {
            if (settings.fullAuto()) {
                handledStore.mark(selectedName);
                setStatus(selectedName + " sohbetinde mevcut taslak var · dokunulmadan atlandı.");
                beginReturn(screen, false);
            } else {
                setStatus("Mesaj kutusunda mevcut bir taslak var · üzerine yazılmadı.");
                hideApproval();
            }
            return;
        }

        AccessibilityNodeInfo sendButton = findSendButton(screen.nodes, screen.editor);
        if (settings.fullAuto()) {
            if (sendButton == null) {
                setStatus("Gönder düğmesi bekleniyor…");
                maybeWakeChatUi(now);
                if (now - phaseSince > 4200L) {
                    handledStore.mark(selectedName);
                    setStatus(selectedName + " gönder düğmesi bulunamadı · tekrar açılmaması için atlandı.");
                    beginReturn(screen, false);
                } else {
                    scheduleTick(260L);
                }
                return;
            }

            boolean clicked;
            try {
                clicked = AccessibilityTools.click(sendButton);
            } finally {
                AccessibilityTools.recycle(sendButton);
            }
            if (!clicked) {
                clicked = tapSendFallback(screen.editor);
            }
            if (clicked) {
                sendClicked = true;
                sendClickedAt = now;
                sendClickCount = 1;
                transition(Phase.VERIFYING_SEND);
                setStatus(selectedName + " mesajı gönderiliyor…");
                scheduleTick(300L);
            } else {
                setStatus("Gönder düğmesine erişilemedi · yeniden okunuyor.");
                scheduleTick(320L);
            }
            return;
        }

        // Tek-onay modu.
        showApproval(selectedName, expectedMessage, sendButton != null);
        AccessibilityTools.recycle(sendButton);
        if (manualApproveRequested) {
            manualApproveRequested = false;
            AccessibilityNodeInfo freshButton = findSendButton(screen.nodes, screen.editor);
            boolean clicked = false;
            if (freshButton != null) {
                try {
                    clicked = AccessibilityTools.click(freshButton);
                } finally {
                    AccessibilityTools.recycle(freshButton);
                }
            }
            if (!clicked) clicked = tapSendFallback(screen.editor);
            if (clicked) {
                handledStore.mark(selectedName);
                setStatus(selectedName + " için gönderme dokunuşu yapıldı.");
                hideApproval();
                clearChatState();
                transition(Phase.IDLE);
            } else {
                setStatus("Gönder düğmesine ulaşılamadı · mesaj kutuda hazır.");
                showApproval(selectedName, expectedMessage, false);
            }
        }
    }

    private void verifySend(Screen screen) {
        long now = SystemClock.elapsedRealtime();
        String current = AccessibilityTools.text(screen.editor).trim();
        boolean bubbleVisible = containsExactNonEditableText(screen.nodes, expectedMessage);

        if (bubbleVisible || !expectedMessage.equals(current)) {
            String done = selectedName;
            handledStore.mark(done);
            setStatus(done + " mesajı gönderildi.");
            beginReturn(screen, true);
            return;
        }

        if (now - sendClickedAt < SEND_VERIFY_TIMEOUT_MS) {
            setStatus(selectedName + " gönderimi doğrulanıyor…");
            scheduleTick(250L);
            return;
        }

        // En önemli duplicate koruması: bir kez gönder tıklaması kabul edildikten sonra aynı
        // sohbette ikinci kez gönder düğmesine basmıyoruz. Sonuç görünmüyorsa kişiyi işlenmiş
        // sayıp listeden çıkıyoruz; böylece aynı mesaj iki kez gitmez.
        String uncertain = selectedName;
        handledStore.mark(uncertain);
        setStatus(uncertain + " gönderim sonucu gecikti · tekrar göndermemek için tamamlandı sayıldı.");
        beginReturn(screen, true);
    }

    private void processReturnWhileStillInChat(Screen screen) {
        long now = SystemClock.elapsedRealtime();
        if (backAttempts == 0) {
            issueBack(screen);
            return;
        }
        if (backAttempts < 2 && now - phaseSince >= RETURN_RETRY_MS) {
            // Yalnızca hâlâ sohbet editörü görünüyorsa ikinci ve son geri denemesi yapılır.
            issueBack(screen);
            return;
        }
        if (now - phaseSince > 3600L) {
            setStatus("Listeye dönüş gecikti · ekran yeniden kontrol ediliyor.");
            scheduleTick(500L);
        }
    }

    private void processUnknownScreen(Screen screen) {
        long now = SystemClock.elapsedRealtime();
        hideApproval();

        if (phase == Phase.OPENING_CHAT) {
            long waited = now - phaseSince;
            setStatus(selectedName + " sohbet ekranı yükleniyor…");
            if (waited >= 650L) maybeWakeChatUi(now);
            if (waited > OPEN_CHAT_TIMEOUT_MS) {
                addTemporarySkip(selectedName, TEMP_SKIP_MS);
                setStatus(selectedName + " sohbeti yüklenmedi · satır geçici olarak atlandı.");
                performGlobalAction(GLOBAL_ACTION_BACK);
                clearChatState();
                transition(Phase.LIST);
                listSettleUntil = now + 450L;
                scheduleTick(500L);
            } else {
                scheduleTick(250L);
            }
            return;
        }

        if (phase == Phase.RETURNING) {
            if (now - phaseSince > 2800L) {
                clickRecommendationsTab(screen.nodes);
                scheduleTick(450L);
            } else {
                scheduleTick(300L);
            }
            return;
        }

        if (settings.fullAuto()) {
            if (clickRecommendationsTab(screen.nodes)) {
                setStatus("Önerilenler sekmesine dönülüyor…");
                transition(Phase.LIST);
                listSettleUntil = now + 500L;
                scheduleTick(520L);
            } else {
                setStatus("SOYO ekranı bekleniyor…");
            }
        } else {
            setStatus("SOYO ekranı açık · uygun ekran bekleniyor.");
        }
    }

    private void beginReturn(Screen screen, boolean alreadyHandled) {
        if (!alreadyHandled && settings.fullAuto() && !TextUtils.isEmpty(selectedName)) {
            // Atlanan kişiler tam otomatik akışta tekrar tekrar açılmasın.
            handledStore.mark(selectedName);
        }
        hideApproval();
        transition(Phase.RETURNING);
        backAttempts = 0;
        issueBack(screen);
    }

    private void issueBack(Screen screen) {
        AccessibilityNodeInfo back = findBackButton(screen.nodes);
        boolean clicked = false;
        if (back != null) {
            try {
                clicked = AccessibilityTools.click(back);
            } finally {
                AccessibilityTools.recycle(back);
            }
        }
        if (!clicked) clicked = performGlobalAction(GLOBAL_ACTION_BACK);
        backAttempts++;
        setStatus("Önerilenler listesine dönülüyor…");
        scheduleTick(clicked ? 380L : 520L);
    }

    private Screen inspect(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = AccessibilityTools.collect(root);
        AccessibilityNodeInfo editor = findBestEditor(nodes);
        boolean recommendations = looksLikeRecommendations(nodes, editor != null);
        String headerName = editor == null ? null : findHeaderName(nodes, editor, selectedName);
        boolean headerVip = editor != null && findHeaderVip(nodes, editor);
        return new Screen(nodes, editor, recommendations, headerName, headerVip);
    }

    private AccessibilityNodeInfo findBestEditor(List<AccessibilityNodeInfo> nodes) {
        int height = getResources().getDisplayMetrics().heightPixels;
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isVisibleToUser() || !node.isEditable()) continue;
            Rect r = AccessibilityTools.rect(node);
            if (r.isEmpty()) continue;
            if (r.centerY() < height * 0.48f) continue;
            int score = r.centerY() + (node.isFocusable() ? 120 : 0) + Math.min(300, r.width() / 3);
            if (score > bestScore) {
                AccessibilityTools.recycle(best);
                best = AccessibilityNodeInfo.obtain(node);
                bestScore = score;
            }
        }
        return best;
    }

    private boolean looksLikeRecommendations(List<AccessibilityNodeInfo> nodes, boolean editorVisible) {
        if (editorVisible) return false;
        int height = getResources().getDisplayMetrics().heightPixels;
        boolean chatAction = false;
        boolean topRecommendations = false;
        int chatCount = 0;

        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isVisibleToUser()) continue;
            String label = NameCleaner.lower(AccessibilityTools.label(node));
            Rect r = AccessibilityTools.rect(node);
            if (isChatLabel(label)) {
                chatAction = true;
                chatCount++;
            }
            if (("önerilen".equals(label) || "önerilenler".equals(label))
                    && !r.isEmpty() && r.centerY() < height * 0.58f) {
                topRecommendations = true;
            }
        }
        return chatAction && (topRecommendations || chatCount >= 2);
    }

    private Target findFirstTarget(List<AccessibilityNodeInfo> nodes) {
        Target best = null;
        Set<String> seenRects = new HashSet<>();
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        long now = SystemClock.elapsedRealtime();
        cleanupTemporarySkips(now);

        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isVisibleToUser()) continue;
            String label = NameCleaner.lower(AccessibilityTools.label(node));
            if (!isChatLabel(label)) continue;

            AccessibilityNodeInfo button = AccessibilityTools.clickableCopy(node);
            if (button == null) continue;
            Rect chatRect = AccessibilityTools.rect(button);
            if (chatRect.isEmpty()) chatRect = AccessibilityTools.rect(node);
            if (chatRect.isEmpty() || chatRect.centerY() < screenHeight * 0.16f
                    || chatRect.centerY() > screenHeight * 0.90f) {
                AccessibilityTools.recycle(button);
                continue;
            }

            String rectKey = chatRect.flattenToString();
            if (!seenRects.add(rectKey)) {
                AccessibilityTools.recycle(button);
                continue;
            }

            String name = findNameLeftOfChat(nodes, chatRect);
            if (TextUtils.isEmpty(name) || handledStore.isHandled(name) || isTemporarilySkipped(name, now)) {
                AccessibilityTools.recycle(button);
                continue;
            }

            boolean vip = rowHasVip(nodes, chatRect);
            if (settings.vipOnly() && !vip) {
                handledStore.mark(name);
                AccessibilityTools.recycle(button);
                continue;
            }

            Target target = new Target(name, vip, button, chatRect);
            if (best == null || target.rect.top < best.rect.top) {
                if (best != null) best.close();
                best = target;
            } else {
                target.close();
            }
        }
        return best;
    }

    private String findNameLeftOfChat(List<AccessibilityNodeInfo> nodes, Rect chatRect) {
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        int tolerance = Math.max(dp(36), Math.min(dp(48), chatRect.height() + dp(12)));

        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isVisibleToUser() || node.isEditable()) continue;
            Rect r = AccessibilityTools.rect(node);
            if (r.isEmpty()) continue;
            if (r.left >= chatRect.left) continue;
            if (r.right > chatRect.left + dp(36)) continue;

            int vertical = Math.abs(r.centerY() - chatRect.centerY());
            if (vertical > tolerance) continue;

            // Kişi adı için yalnızca ekranda gerçekten yazan text kullanılır. Bazı Flutter
            // contentDescription alanları TAG/VIP/rozet bilgisi taşıdığı için isim kaynağı değildir.
            String raw = AccessibilityTools.text(node).trim();
            if (raw.isEmpty()) continue;
            String name = NameCleaner.clean(raw);
            if (name == null) continue;

            int horizontalGap = Math.max(0, chatRect.left - r.right);
            int score = 120;
            score -= vertical / Math.max(1, dp(3));
            score -= Math.min(24, horizontalGap / Math.max(1, dp(16)));
            if (r.centerY() <= chatRect.centerY() + dp(16)) score += 8;
            if (raw.equals(name)) score += 5;
            if (raw.length() >= 3) score += 3;
            if (score > bestScore) {
                bestScore = score;
                best = name;
            }
        }
        return best;
    }

    private boolean rowHasVip(List<AccessibilityNodeInfo> nodes, Rect chatRect) {
        int tolerance = Math.max(dp(60), chatRect.height() + dp(35));
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isVisibleToUser()) continue;
            Rect r = AccessibilityTools.rect(node);
            if (r.isEmpty() || Math.abs(r.centerY() - chatRect.centerY()) > tolerance) continue;
            if (r.left > chatRect.right + dp(18)) continue;
            String compact = NameCleaner.lower(AccessibilityTools.label(node)).replace(" ", "");
            if (compact.matches(".*vip\\d*.*")) return true;
        }
        return false;
    }

    private String findHeaderName(List<AccessibilityNodeInfo> nodes, AccessibilityNodeInfo editor, String expected) {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        Rect editorRect = AccessibilityTools.rect(editor);
        String best = null;
        int bestScore = Integer.MIN_VALUE;

        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isVisibleToUser() || node.isEditable()) continue;
            Rect r = AccessibilityTools.rect(node);
            if (r.isEmpty() || r.centerY() >= Math.min(editorRect.top, (int) (height * 0.42f))) continue;
            if (r.centerY() < dp(28)) continue;

            String raw = AccessibilityTools.text(node).trim();
            if (raw.isEmpty()) continue;
            String name = NameCleaner.clean(raw);
            if (name == null) continue;

            if (!TextUtils.isEmpty(expected) && NameCleaner.same(name, expected)) return name;

            int centerDistance = Math.abs(r.centerX() - width / 2);
            int y = r.centerY();
            int score = 420 - centerDistance - Math.abs(y - dp(95)) * 2;
            if (r.centerX() > width * 0.22f && r.centerX() < width * 0.78f) score += 90;
            if (!AccessibilityTools.text(node).trim().isEmpty()) score += 30;
            if (score > bestScore) {
                bestScore = score;
                best = name;
            }
        }
        return best;
    }

    private boolean findHeaderVip(List<AccessibilityNodeInfo> nodes, AccessibilityNodeInfo editor) {
        int height = getResources().getDisplayMetrics().heightPixels;
        Rect editorRect = AccessibilityTools.rect(editor);
        int cutoff = Math.min(editorRect.top, (int) (height * 0.42f));
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isVisibleToUser()) continue;
            Rect r = AccessibilityTools.rect(node);
            if (r.isEmpty() || r.centerY() > cutoff) continue;
            String compact = NameCleaner.lower(AccessibilityTools.label(node)).replace(" ", "");
            if (compact.matches(".*vip\\d*.*")) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findSendButton(List<AccessibilityNodeInfo> nodes, AccessibilityNodeInfo editor) {
        Rect editorRect = AccessibilityTools.rect(editor);
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;

        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isVisibleToUser() || node == editor) continue;
            Rect r = AccessibilityTools.rect(node);
            if (r.isEmpty()) continue;
            String label = NameCleaner.lower(AccessibilityTools.label(node));
            boolean semantic = label.contains("gönder") || label.contains("gonder")
                    || label.equals("send") || label.contains("send message");
            boolean forbidden = label.contains("emoji") || label.contains("sticker")
                    || label.contains("çıkart") || label.contains("cikart") || label.contains("galeri")
                    || label.contains("foto") || label.contains("kamera") || label.contains("ses");

            int vertical = Math.abs(r.centerY() - editorRect.centerY());
            boolean sameRow = vertical <= Math.max(dp(52), editorRect.height());
            boolean rightSide = r.centerX() >= editorRect.centerX();
            boolean plausibleSize = r.width() >= dp(24) && r.width() <= dp(110)
                    && r.height() >= dp(24) && r.height() <= dp(110);
            boolean geometry = sameRow && rightSide && plausibleSize && r.left >= editorRect.right - dp(28);
            if (forbidden && !semantic) continue;
            if (!semantic && !geometry) continue;

            AccessibilityNodeInfo clickable = AccessibilityTools.clickableCopy(node);
            if (clickable == null) continue;
            Rect clickableRect = AccessibilityTools.rect(clickable);
            int score = semantic ? 700 : 250;
            score += Math.max(0, clickableRect.centerX() - editorRect.right) / Math.max(1, dp(4));
            score -= vertical * 2;
            if (clickable.isClickable()) score += 40;
            if (score > bestScore) {
                AccessibilityTools.recycle(best);
                best = clickable;
                bestScore = score;
            } else {
                AccessibilityTools.recycle(clickable);
            }
        }
        return best;
    }

    private AccessibilityNodeInfo findBackButton(List<AccessibilityNodeInfo> nodes) {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MIN_VALUE;

        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isVisibleToUser()) continue;
            Rect r = AccessibilityTools.rect(node);
            if (r.isEmpty() || r.centerX() > width * 0.35f || r.centerY() > height * 0.22f) continue;
            String label = NameCleaner.lower(AccessibilityTools.label(node));
            boolean semantic = label.contains("geri") || label.equals("back")
                    || label.contains("navigate up") || label.contains("up button");
            boolean plausible = r.centerX() < dp(100) && r.centerY() < dp(155)
                    && r.width() >= dp(22) && r.width() <= dp(100)
                    && r.height() >= dp(22) && r.height() <= dp(100);
            if (!semantic && !plausible) continue;
            if (label.contains("profil") || label.contains("avatar") || label.contains("foto")) {
                if (!semantic) continue;
            }

            AccessibilityNodeInfo clickable = AccessibilityTools.clickableCopy(node);
            if (clickable == null) continue;
            int score = semantic ? 500 : 120;
            score -= r.centerX() + r.centerY() / 2;
            if (score > bestScore) {
                AccessibilityTools.recycle(best);
                best = clickable;
                bestScore = score;
            } else {
                AccessibilityTools.recycle(clickable);
            }
        }
        return best;
    }

    private boolean clickRecommendationsTab(List<AccessibilityNodeInfo> nodes) {
        int height = getResources().getDisplayMetrics().heightPixels;
        AccessibilityNodeInfo best = null;
        int bestY = -1;
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isVisibleToUser()) continue;
            String label = NameCleaner.lower(AccessibilityTools.label(node));
            if (!"önerilen".equals(label) && !"önerilenler".equals(label)) continue;
            Rect r = AccessibilityTools.rect(node);
            if (r.isEmpty() || r.centerY() < height * 0.62f) continue;
            AccessibilityNodeInfo clickable = AccessibilityTools.clickableCopy(node);
            if (clickable == null) continue;
            if (r.centerY() > bestY) {
                AccessibilityTools.recycle(best);
                best = clickable;
                bestY = r.centerY();
            } else {
                AccessibilityTools.recycle(clickable);
            }
        }
        if (best == null) return false;
        try {
            return AccessibilityTools.click(best);
        } finally {
            AccessibilityTools.recycle(best);
        }
    }

    private void scrollRecommendations(List<AccessibilityNodeInfo> nodes) {
        long now = SystemClock.elapsedRealtime();
        String snapshot = buildListSnapshot(nodes);
        if (!snapshot.isEmpty() && snapshot.equals(lastListSnapshot)) {
            stagnantListScans++;
        } else {
            stagnantListScans = 0;
            lastListSnapshot = snapshot;
        }

        if (now - lastScrollAt < SCROLL_GAP_MS) {
            scheduleTick(SCROLL_GAP_MS - (now - lastScrollAt) + 30L);
            return;
        }
        lastScrollAt = now;

        boolean movedRequest = false;
        if (stagnantListScans == 0) {
            AccessibilityNodeInfo scrollable = findLargestScrollable(nodes);
            if (scrollable != null) {
                try {
                    movedRequest = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                } finally {
                    AccessibilityTools.recycle(scrollable);
                }
            }
        }

        if (!movedRequest || stagnantListScans >= 1) {
            movedRequest = performListSwipe(stagnantListScans >= 3);
        }

        if (stagnantListScans >= 5) {
            clickRecommendationsTab(nodes);
            setStatus("Liste yenileniyor · yeni öneriler bekleniyor…");
            scheduleTick(1050L);
        } else {
            setStatus("Uygun kişi kalmadı · liste kaydırılıyor…");
            scheduleTick(movedRequest ? 620L : 850L);
        }
    }

    private AccessibilityNodeInfo findLargestScrollable(List<AccessibilityNodeInfo> nodes) {
        AccessibilityNodeInfo best = null;
        int bestArea = -1;
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isVisibleToUser()) continue;
            boolean scrollable = node.isScrollable()
                    || node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            if (!scrollable) continue;
            Rect r = AccessibilityTools.rect(node);
            int area = Math.max(0, r.width()) * Math.max(0, r.height());
            if (area > bestArea) {
                AccessibilityTools.recycle(best);
                best = AccessibilityNodeInfo.obtain(node);
                bestArea = area;
            }
        }
        return best;
    }

    private String buildListSnapshot(List<AccessibilityNodeInfo> nodes) {
        StringBuilder out = new StringBuilder();
        int added = 0;
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isVisibleToUser()) continue;
            Rect r = AccessibilityTools.rect(node);
            if (r.isEmpty()) continue;
            String raw = AccessibilityTools.text(node);
            String name = NameCleaner.clean(raw);
            String label = NameCleaner.lower(AccessibilityTools.label(node));
            if (name != null || isChatLabel(label)) {
                out.append(name != null ? NameCleaner.key(name) : "chat")
                        .append('@').append(r.centerY() / Math.max(1, dp(20))).append(';');
                if (++added >= 18) break;
            }
        }
        return out.toString();
    }

    private boolean performListSwipe(boolean strong) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        float x = width * (swipeLane++ % 2 == 0 ? 0.46f : 0.56f);
        float startY = height * (strong ? 0.80f : 0.74f);
        float endY = height * (strong ? 0.31f : 0.43f);
        Path path = new Path();
        path.moveTo(x, startY);
        path.lineTo(x, endY);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0L, strong ? 300L : 230L);
        return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    private void maybeWakeChatUi(long now) {
        if (chatNudgeCount >= 2 || now - lastChatNudgeAt < 650L) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        lastChatNudgeAt = now;
        chatNudgeCount++;

        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        float x = width * (chatNudgeCount % 2 == 0 ? 0.58f : 0.48f);
        float startY = height * 0.59f;
        float endY = height * 0.52f;
        Path path = new Path();
        path.moveTo(x, startY);
        path.lineTo(x, endY);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0L, 150L);
        dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    private boolean tapSendFallback(AccessibilityNodeInfo editor) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || editor == null) return false;
        Rect r = AccessibilityTools.rect(editor);
        if (r.isEmpty()) return false;
        int width = getResources().getDisplayMetrics().widthPixels;
        float x = Math.min(width - dp(22), Math.max(r.right + dp(28), width - dp(44)));
        float y = r.centerY();
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription tap = new GestureDescription.StrokeDescription(path, 0L, 70L);
        return dispatchGesture(new GestureDescription.Builder().addStroke(tap).build(), null, null);
    }

    private boolean containsExactNonEditableText(List<AccessibilityNodeInfo> nodes, String expected) {
        if (TextUtils.isEmpty(expected)) return false;
        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || !node.isVisibleToUser() || node.isEditable()) continue;
            if (expected.equals(AccessibilityTools.text(node).trim())) return true;
        }
        return false;
    }

    private void showApproval(String name, String message, boolean canSend) {
        if (windowManager == null) return;
        if (approvalView == null) createApprovalView();
        approvalTitle.setText(name + " için mesaj hazır");
        approvalMessage.setText(message);
        approvalSend.setEnabled(canSend);
        approvalSend.setAlpha(canSend ? 1f : 0.45f);
        approvalSend.setText(canSend ? "Gönder" : "Gönder bekleniyor");
        if (approvalView.getWindowToken() == null) {
            try {
                windowManager.addView(approvalView, overlayParams());
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void createApprovalView() {
        int pad = dp(16);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, pad, pad, pad);
        card.setElevation(dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFAFFFFFF);
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), 0xFFE2E8F0);
        card.setBackground(bg);

        approvalTitle = new TextView(this);
        approvalTitle.setTextColor(0xFF152238);
        approvalTitle.setTextSize(16f);
        approvalTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(approvalTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        approvalMessage = new TextView(this);
        approvalMessage.setTextColor(0xFF475467);
        approvalMessage.setTextSize(15f);
        LinearLayout.LayoutParams messageLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        messageLp.topMargin = dp(8);
        card.addView(approvalMessage, messageLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(12);
        card.addView(actions, actionsLp);

        Button skip = new Button(this);
        skip.setText("Atla");
        skip.setTextAllCaps(false);
        skip.setTextColor(0xFFC43D4B);
        skip.setOnClickListener(v -> {
            manualCancelRequested = true;
            manualApproveRequested = false;
            scheduleTick(0L);
        });
        actions.addView(skip, new LinearLayout.LayoutParams(0, dp(52), 1f));

        approvalSend = new Button(this);
        approvalSend.setText("Gönder");
        approvalSend.setTextAllCaps(false);
        approvalSend.setTextColor(Color.WHITE);
        GradientDrawable sendBg = new GradientDrawable();
        sendBg.setColor(0xFF635BFF);
        sendBg.setCornerRadius(dp(14));
        approvalSend.setBackground(sendBg);
        approvalSend.setOnClickListener(v -> {
            manualApproveRequested = true;
            manualCancelRequested = false;
            scheduleTick(0L);
        });
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(0, dp(52), 1.35f);
        sendLp.leftMargin = dp(8);
        actions.addView(approvalSend, sendLp);

        approvalView = card;
    }

    private WindowManager.LayoutParams overlayParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        lp.x = 0;
        lp.y = dp(18);
        return lp;
    }

    private void hideApproval() {
        manualApproveRequested = false;
        manualCancelRequested = false;
        if (approvalView == null || windowManager == null || approvalView.getWindowToken() == null) return;
        try {
            windowManager.removeView(approvalView);
        } catch (RuntimeException ignored) {
        }
    }

    private void addTemporarySkip(String name, long durationMs) {
        String key = NameCleaner.key(name);
        if (key.isEmpty()) return;
        temporarySkips.put(key, SystemClock.elapsedRealtime() + durationMs);
    }

    private boolean isTemporarilySkipped(String name, long now) {
        String key = NameCleaner.key(name);
        if (key.isEmpty()) return true;
        Long until = temporarySkips.get(key);
        return until != null && until > now;
    }

    private void cleanupTemporarySkips(long now) {
        List<String> remove = new ArrayList<>();
        for (Map.Entry<String, Long> entry : temporarySkips.entrySet()) {
            if (entry.getValue() <= now) remove.add(entry.getKey());
        }
        for (String key : remove) temporarySkips.remove(key);
    }

    private void transition(Phase next) {
        phase = next;
        phaseSince = SystemClock.elapsedRealtime();
    }

    private void clearChatState() {
        selectedName = null;
        selectedVip = false;
        expectedMessage = null;
        sendClicked = false;
        sendClickedAt = 0L;
        sendClickCount = 0;
        backAttempts = 0;
        chatNudgeCount = 0;
        lastChatNudgeAt = 0L;
        manualApproveRequested = false;
        manualCancelRequested = false;
    }

    private void resetTransient(boolean clearTemporarySkips) {
        clearChatState();
        phase = Phase.IDLE;
        phaseSince = SystemClock.elapsedRealtime();
        lastListSnapshot = "";
        stagnantListScans = 0;
        lastScrollAt = 0L;
        listSettleUntil = 0L;
        if (clearTemporarySkips) temporarySkips.clear();
        handler.removeCallbacks(tickRunnable);
        tickScheduled = false;
        tickScheduledFor = 0L;
    }

    private void scheduleTick(long delayMs) {
        long now = SystemClock.elapsedRealtime();
        long delay = Math.max(0L, delayMs);
        long since = now - lastTickAt;
        if (since < MIN_TICK_GAP_MS) delay = Math.max(delay, MIN_TICK_GAP_MS - since);
        long wantedAt = now + delay;

        if (tickScheduled) {
            if (tickScheduledFor <= wantedAt) return;
            handler.removeCallbacks(tickRunnable);
        }
        tickScheduled = true;
        tickScheduledFor = wantedAt;
        handler.postDelayed(tickRunnable, delay);
    }

    private void scheduleWatchdogIfNeeded() {
        if (settings != null && settings.fullAuto()) scheduleTick(TICK_WATCHDOG_MS);
    }

    private void setStatus(String value) {
        if (settings != null) settings.setRuntimeStatus(value);
    }

    private boolean isSoyoRoot(AccessibilityNodeInfo root) {
        if (root == null || root.getPackageName() == null) return false;
        return isSoyoPackage(root.getPackageName().toString());
    }

    private boolean isSoyoPackage(String pkg) {
        return SOYO_PACKAGE.equals(pkg) || SOYO_LITE_PACKAGE.equals(pkg);
    }

    private boolean isChatLabel(String label) {
        return "sohbet".equals(label) || "sohbete başla".equals(label) || "sohbete basla".equals(label);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onInterrupt() {
        hideApproval();
        handler.removeCallbacksAndMessages(null);
        tickScheduled = false;
        if (settings != null) setStatus("Erişilebilirlik servisi duraklatıldı.");
    }

    @Override
    public void onDestroy() {
        hideApproval();
        handler.removeCallbacksAndMessages(null);
        tickScheduled = false;
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        if (settings != null) setStatus("Erişilebilirlik servisi kapalı.");
        super.onDestroy();
    }

    private static final class Screen {
        final List<AccessibilityNodeInfo> nodes;
        final AccessibilityNodeInfo editor;
        final boolean recommendations;
        final String headerName;
        final boolean headerVip;

        Screen(List<AccessibilityNodeInfo> nodes,
               AccessibilityNodeInfo editor,
               boolean recommendations,
               String headerName,
               boolean headerVip) {
            this.nodes = nodes;
            this.editor = editor;
            this.recommendations = recommendations;
            this.headerName = headerName;
            this.headerVip = headerVip;
        }

        void close() {
            AccessibilityTools.recycle(editor);
            AccessibilityTools.recycleAll(nodes);
        }
    }

    private static final class Target {
        final String name;
        final boolean vip;
        final AccessibilityNodeInfo button;
        final Rect rect;

        Target(String name, boolean vip, AccessibilityNodeInfo button, Rect rect) {
            this.name = name;
            this.vip = vip;
            this.button = button;
            this.rect = new Rect(rect);
        }

        void close() {
            AccessibilityTools.recycle(button);
        }
    }
}
