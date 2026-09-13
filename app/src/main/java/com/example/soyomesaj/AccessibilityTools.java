package com.example.soyomesaj;

import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

public final class AccessibilityTools {
    private AccessibilityTools() {}

    public static String text(AccessibilityNodeInfo node) {
        if (node == null || node.getText() == null) return "";
        return node.getText().toString();
    }

    public static String desc(AccessibilityNodeInfo node) {
        if (node == null || node.getContentDescription() == null) return "";
        return node.getContentDescription().toString();
    }

    public static String label(AccessibilityNodeInfo node) {
        String a = text(node);
        String b = desc(node);
        if (a.isEmpty()) return b;
        if (b.isEmpty() || a.equals(b)) return a;
        return a + " " + b;
    }

    public static Rect rect(AccessibilityNodeInfo node) {
        Rect r = new Rect();
        if (node != null) node.getBoundsInScreen(r);
        return r;
    }

    public static List<AccessibilityNodeInfo> collect(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        collect(root, out, 0);
        return out;
    }

    private static void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 26 || out.size() >= 550) return;
        out.add(AccessibilityNodeInfo.obtain(node));
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            collect(child, out, depth + 1);
            recycle(child);
        }
    }

    public static boolean setText(AccessibilityNodeInfo node, String value) {
        if (node == null) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value == null ? "" : value);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    public static boolean click(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        AccessibilityNodeInfo parent = node.getParent();
        int hops = 0;
        while (parent != null && hops++ < 6) {
            if (parent.isClickable() && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                recycle(parent);
                return true;
            }
            AccessibilityNodeInfo next = parent.getParent();
            recycle(parent);
            parent = next;
        }
        recycle(parent);
        return false;
    }

    public static AccessibilityNodeInfo clickableCopy(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isClickable()) return AccessibilityNodeInfo.obtain(node);
        AccessibilityNodeInfo parent = node.getParent();
        int hops = 0;
        while (parent != null && hops++ < 6) {
            if (parent.isClickable()) {
                AccessibilityNodeInfo result = AccessibilityNodeInfo.obtain(parent);
                recycle(parent);
                return result;
            }
            AccessibilityNodeInfo next = parent.getParent();
            recycle(parent);
            parent = next;
        }
        recycle(parent);
        return null;
    }

    public static void recycleAll(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null) return;
        for (AccessibilityNodeInfo node : nodes) recycle(node);
        nodes.clear();
    }

    public static void recycle(AccessibilityNodeInfo node) {
        if (node == null) return;
        try {
            node.recycle();
        } catch (RuntimeException ignored) {
        }
    }
}
