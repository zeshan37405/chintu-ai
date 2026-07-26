package com.zeshan.chintuai;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * User-enabled screen control for Wazir. Window content is queried in memory only when an explicit
 * command is executed; it is never stored or uploaded. Node actions are preferred, with coordinate
 * gestures and clipboard input as fallbacks for Facebook and other custom-rendered applications.
 */
public final class ChintuAccessibilityService extends AccessibilityService {
    private static WeakReference<ChintuAccessibilityService> active = new WeakReference<>(null);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile String activePackage = "";
    private static volatile long lastWindowEventAt;
    private int gestureSequence;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            info.notificationTimeout = 80L;
            setServiceInfo(info);
        }
        active = new WeakReference<>(this);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        active.clear();
        activePackage = "";
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence packageName = event.getPackageName();
        if (packageName != null) activePackage = packageName.toString();
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            lastWindowEventAt = SystemClock.uptimeMillis();
        }
    }

    @Override
    public void onInterrupt() {
        // No continuous feedback is generated.
    }

    public static boolean isConnected() {
        return active.get() != null;
    }

    public static String getActivePackageName() {
        return activePackage;
    }

    public static long getLastWindowEventAt() {
        return lastWindowEventAt;
    }

    public static boolean perform(int action) {
        return withService(service -> service.performGlobalAction(action));
    }

    public static boolean scrollDown() {
        return withService(service -> service.scroll(true));
    }

    public static boolean scrollUp() {
        return withService(service -> service.scroll(false));
    }

    public static boolean swipeLeft() {
        return withService(service -> service.swipe(true));
    }

    public static boolean swipeRight() {
        return withService(service -> service.swipe(false));
    }

    public static boolean typeIntoFocusedField(String text) {
        return withService(service -> service.setFocusedText(text, false));
    }

    public static boolean clearFocusedText() {
        return withService(service -> service.setFocusedText("", true));
    }

    public static boolean pasteIntoFocusedField() {
        return withService(ChintuAccessibilityService::pasteFocused);
    }

    public static boolean pressEnter() {
        return withService(ChintuAccessibilityService::performEnter);
    }

    public static boolean clickByVisibleText(String text) {
        return withService(service -> service.clickText(text));
    }

    public static boolean focusSocialComposer() {
        return withService(ChintuAccessibilityService::focusComposerInternal);
    }

    /** Opens a visible post composer and writes text after the composer animation completes. */
    public static boolean prepareSocialPost(String text) {
        return withService(service -> {
            boolean focused = service.focusComposerInternal();
            if (!focused) return false;
            MAIN.postDelayed(() -> {
                ChintuAccessibilityService current = active.get();
                if (current != null) current.setFocusedText(text, false);
            }, 850L);
            return true;
        });
    }

    public static boolean clickCommonSubmitButton() {
        String[] labels = {
                "پوسٹ", "شائع کریں", "شائع", "بھیجیں", "بھیجو", "سینڈ",
                "Post", "Publish", "Send", "Submit", "Share",
                "पोस्ट", "पब्लिश", "भेजें", "सेंड"
        };
        for (String label : labels) {
            if (clickByVisibleText(label)) return true;
        }
        return false;
    }

    private static boolean withService(ServiceAction action) {
        ChintuAccessibilityService service = active.get();
        if (service == null) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                return action.run(service);
            } catch (RuntimeException ignored) {
                return false;
            }
        }

        AtomicBoolean result = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        MAIN.post(() -> {
            ChintuAccessibilityService current = active.get();
            try {
                result.set(current != null && action.run(current));
            } catch (RuntimeException ignored) {
                result.set(false);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    private boolean scroll(boolean forward) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            List<NodeArea> candidates = findScrollableCandidates(root);
            int action = forward
                    ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            for (NodeArea candidate : candidates) {
                try {
                    if (candidate.node.performAction(action)) return true;
                } catch (RuntimeException ignored) {
                    // Try another candidate or a raw gesture.
                }
            }
            try {
                if (root.performAction(action)) return true;
            } catch (RuntimeException ignored) {
            }
        }
        return dispatchVerticalSwipe(forward);
    }

    private boolean swipe(boolean left) {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        float y = height * 0.55f;
        float startX = left ? width * 0.86f : width * 0.14f;
        float endX = left ? width * 0.14f : width * 0.86f;
        return dispatchStroke(startX, y, endX, y, 470L);
    }

    private boolean dispatchVerticalSwipe(boolean forward) {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        float[] xChoices = {0.52f, 0.72f, 0.32f};
        float x = width * xChoices[Math.abs(gestureSequence++) % xChoices.length];
        float startY = forward ? height * 0.82f : height * 0.25f;
        float endY = forward ? height * 0.24f : height * 0.83f;
        return dispatchStroke(x, startY, x, endY, 560L);
    }

    private boolean dispatchTap(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, 90L))
                .build();
        return dispatchGesture(gesture, null, MAIN);
    }

    private boolean dispatchStroke(float startX, float startY,
                                   float endX, float endY, long durationMs) {
        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build();
        return dispatchGesture(gesture, null, MAIN);
    }

    private boolean focusComposerInternal() {
        String[] labels = {
                "What's on your mind", "What’s on your mind", "Write something",
                "Create post", "New post", "Add a caption", "Caption",
                "آپ کے ذہن میں کیا ہے", "آپ کیا سوچ رہے ہیں", "کچھ لکھیں",
                "پوسٹ بنائیں", "نئی پوسٹ", "کیپشن لکھیں",
                "आपके मन में क्या है", "कुछ लिखें", "पोस्ट बनाएं", "कैप्शन लिखें"
        };
        for (String label : labels) {
            if (clickText(label)) return true;
        }

        AccessibilityNodeInfo editable = findEditableNode();
        if (editable != null) {
            editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            if (editable.performAction(AccessibilityNodeInfo.ACTION_CLICK) || editable.isFocused()) {
                return true;
            }
            Rect bounds = new Rect();
            editable.getBoundsInScreen(bounds);
            return !bounds.isEmpty() && dispatchTap(bounds.exactCenterX(), bounds.exactCenterY());
        }
        return false;
    }

    private boolean setFocusedText(String text, boolean clearOnly) {
        AccessibilityNodeInfo node = findEditableNode();
        if (node == null) return false;
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Bundle arguments = new Bundle();
        arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                clearOnly ? "" : text);
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return true;
        if (clearOnly) return false;

        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return false;
        clipboard.setPrimaryClip(ClipData.newPlainText("Wazir text", text));
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
    }

    private boolean pasteFocused() {
        AccessibilityNodeInfo node = findEditableNode();
        if (node == null) return false;
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
    }

    private boolean performEnter() {
        AccessibilityNodeInfo node = findEditableNode();
        if (node != null && Build.VERSION.SDK_INT >= 30) {
            if (node.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())) {
                return true;
            }
        }
        String[] labels = {
                "Enter", "Done", "Go", "Search", "Next", "Send",
                "انٹر", "تلاش", "بھیجیں", "भेजें", "खोजें"
        };
        for (String label : labels) {
            if (clickText(label)) return true;
        }
        return false;
    }

    private boolean clickText(String requested) {
        String target = CommandEngine.normalize(
                AccentCommandNormalizer.canonicalize(requested));
        if (target.isEmpty()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        List<AccessibilityNodeInfo> direct = root.findAccessibilityNodeInfosByText(requested);
        if (direct != null) {
            for (AccessibilityNodeInfo node : direct) {
                if (clickNodeOrParent(node)) return true;
            }
        }

        AccessibilityNodeInfo best = null;
        int bestScore = 0;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            CharSequence text = node.getText();
            CharSequence description = node.getContentDescription();
            String normalizedText = CommandEngine.normalize(text == null ? "" : text.toString());
            String normalizedDescription = CommandEngine.normalize(
                    description == null ? "" : description.toString());
            int score = Math.max(matchScore(target, normalizedText),
                    matchScore(target, normalizedDescription));
            if (score > bestScore) {
                best = node;
                bestScore = score;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return best != null && bestScore >= 70 && clickNodeOrParent(best);
    }

    private int matchScore(String target, String candidate) {
        if (candidate.isEmpty()) return 0;
        if (candidate.equals(target)) return 100;
        if (candidate.contains(target) || target.contains(candidate)) return 90;
        return ContactMatcher.similarity(target, candidate);
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 10; depth++) {
            if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            current = current.getParent();
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return !bounds.isEmpty() && dispatchTap(bounds.exactCenterX(), bounds.exactCenterY());
    }

    private AccessibilityNodeInfo findEditableNode() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused != null && focused.isEditable()) return focused;

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        AccessibilityNodeInfo firstEditable = null;
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node.isEditable()) {
                if (node.isFocused()) return node;
                if (firstEditable == null) firstEditable = node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return firstEditable;
    }

    private List<NodeArea> findScrollableCandidates(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        List<NodeArea> values = new ArrayList<>();
        Rect bounds = new Rect();
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            boolean hasScrollAction = node.getActionList().contains(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                    || node.getActionList().contains(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            if (node.isScrollable() || hasScrollAction) {
                node.getBoundsInScreen(bounds);
                int area = Math.max(0, bounds.width()) * Math.max(0, bounds.height());
                values.add(new NodeArea(node, area));
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        values.sort(Comparator.comparingInt((NodeArea value) -> value.area).reversed());
        return values;
    }

    private static final class NodeArea {
        final AccessibilityNodeInfo node;
        final int area;

        NodeArea(AccessibilityNodeInfo node, int area) {
            this.node = node;
            this.area = area;
        }
    }

    private interface ServiceAction {
        boolean run(ChintuAccessibilityService service);
    }
    }
