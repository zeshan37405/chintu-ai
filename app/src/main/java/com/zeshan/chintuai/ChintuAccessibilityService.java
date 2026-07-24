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

/**
 * Optional user-enabled service for explicit Jarvis-style screen actions. Screen content is used
 * only in memory to find the requested control and is never stored or transmitted.
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
        ChintuAccessibilityService service = active.get();
        return service != null && service.performGlobalAction(action);
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

    public static boolean clickCommonSubmitButton() {
        String[] labels = {
                "پوسٹ", "شائع کریں", "شائع", "بھیجیں", "بھیجو", "سینڈ",
                "Post", "Publish", "Send", "Submit", "Share"
        };
        for (String label : labels) {
            if (clickByVisibleText(label)) return true;
        }
        return false;
    }

    private static boolean withService(ServiceAction action) {
        ChintuAccessibilityService service = active.get();
        if (service == null) return false;
        try {
            return action.run(service);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean scroll(boolean forward) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            AccessibilityNodeInfo scrollable = findScrollable(root);
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
        float startX = left ? width * 0.82f : width * 0.18f;
        float endX = left ? width * 0.18f : width * 0.82f;
        return dispatchStroke(startX, y, endX, y, 420L);
    }

    private boolean dispatchVerticalSwipe(boolean forward) {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        float x = width * 0.5f;
        float startY = forward ? height * 0.78f : height * 0.30f;
        float endY = forward ? height * 0.30f : height * 0.78f;
        return dispatchStroke(x, startY, x, endY, 460L);
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

    private boolean setFocusedText(String text, boolean clearOnly) {
        AccessibilityNodeInfo node = findEditableNode();
        if (node == null) return false;
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
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
        String[] labels = {"Enter", "Done", "Go", "Search", "Next", "Send", "انٹر", "تلاش"};
        for (String label : labels) {
            if (clickText(label)) return true;
        }
        return false;
    }

    private boolean clickText(String requested) {
        String target = CommandEngine.normalize(requested);
        if (target.isEmpty()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        List<AccessibilityNodeInfo> direct = root.findAccessibilityNodeInfosByText(requested);
        if (direct != null) {
            for (AccessibilityNodeInfo node : direct) {
                if (clickNodeOrParent(node)) return true;
            }
        }

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            CharSequence text = node.getText();
            CharSequence description = node.getContentDescription();
            String normalizedText = CommandEngine.normalize(text == null ? "" : text.toString());
            String normalizedDescription = CommandEngine.normalize(
                    description == null ? "" : description.toString());
            if (matchesTarget(target, normalizedText) || matchesTarget(target, normalizedDescription)) {
                if (clickNodeOrParent(node)) return true;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return false;
    }

    private boolean matchesTarget(String target, String candidate) {
        if (candidate.isEmpty()) return false;
        return candidate.equals(target)
                || candidate.contains(target)
                || target.contains(candidate);
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 6; depth++) {
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

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node.isScrollable()) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }
        return null;
    }

    private interface ServiceAction {
        boolean run(ChintuAccessibilityService service);
    }
}
