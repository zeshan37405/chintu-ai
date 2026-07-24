package com.zeshan.chintuai;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional user-enabled service for explicit Jarvis-style screen actions. Screen content is used
 * only in memory to locate the requested control and is never stored or transmitted.
 */
public final class ChintuAccessibilityService extends AccessibilityService {
    private static WeakReference<ChintuAccessibilityService> active = new WeakReference<>(null);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            setServiceInfo(info);
        }
        active = new WeakReference<>(this);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        active.clear();
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No event stream is stored. Commands query the current window only when explicitly asked.
    }

    @Override
    public void onInterrupt() {
        // No continuous accessibility feedback.
    }

    public static boolean isConnected() {
        return active.get() != null;
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
            }, 700L);
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
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    private boolean scroll(boolean forward) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            AccessibilityNodeInfo scrollable = findBestScrollable(root);
            if (scrollable != null) {
                int action = forward
                        ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                if (scrollable.performAction(action)) return true;
            }
        }
        return dispatchVerticalSwipe(forward);
    }

    private boolean swipe(boolean left) {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        float y = height * 0.55f;
        float startX = left ? width * 0.84f : width * 0.16f;
        float endX = left ? width * 0.16f : width * 0.84f;
        return dispatchStroke(startX, y, endX, y, 430L);
    }

    private boolean dispatchVerticalSwipe(boolean forward) {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        float x = width * 0.52f;
        float startY = forward ? height * 0.80f : height * 0.28f;
        float endY = forward ? height * 0.27f : height * 0.81f;
        return dispatchStroke(x, startY, x, endY, 500L);
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
            return editable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    || editable.isFocused();
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
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
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
        return best != null && bestScore >= 72 && clickNodeOrParent(best);
    }

    private int matchScore(String target, String candidate) {
        if (candidate.isEmpty()) return 0;
        if (candidate.equals(target)) return 100;
        if (candidate.contains(target) || target.contains(candidate)) return 90;
        return ContactMatcher.similarity(target, candidate);
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            current = current.getParent();
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
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

    private AccessibilityNodeInfo findBestScrollable(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        AccessibilityNodeInfo best = null;
        int bestArea = -1;
        android.graphics.Rect bounds = new android.graphics.Rect();
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node.isScrollable()) {
                node.getBoundsInScreen(bounds);
                int area = Math.max(0, bounds.width()) * Math.max(0, bounds.height());
                if (area > bestArea) {
                    best = node;
                    bestArea = area;
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return best;
    }

    private interface ServiceAction {
        boolean run(ChintuAccessibilityService service);
    }
}
