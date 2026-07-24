package com.zeshan.chintuai;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

import java.lang.ref.WeakReference;

/** Optional service for navigation actions Android does not expose to ordinary apps. */
public final class ChintuAccessibilityService extends AccessibilityService {
    private static WeakReference<ChintuAccessibilityService> active = new WeakReference<>(null);

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        active = new WeakReference<>(this);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        active.clear();
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Chintu only uses explicit global actions; it does not read screen content.
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
}
