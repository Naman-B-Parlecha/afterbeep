package dev.vm.route5;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/** Empty on purpose: being an enabled accessibility service is what grants in-call capture. */
public class A11yService extends AccessibilityService {
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}
}
