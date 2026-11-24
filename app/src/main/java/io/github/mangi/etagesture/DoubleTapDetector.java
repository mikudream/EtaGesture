package io.github.mangi.etagesture;

import android.view.MotionEvent;

/**
 * 手势条双击判定（纯数值状态，便于单元测试）。
 *
 * 每次点击必须是单指、落在手势条区域、位移不超过触摸阈值、时长不超过长按阈值；
 * 第二下按下距第一下抬手的间隔、两次落点距离分别受系统双击阈值约束。
 * 多指、滑动、取消都会作废当前按压，第一下超过双击窗口后自动失效。
 */
final class DoubleTapDetector {
    private static final long NO_TAP = Long.MIN_VALUE;

    private final float touchSlop;
    private final float doubleTapSlop;
    private final long doubleTapTimeout;
    private final long longPressTimeout;

    private boolean pressed;
    private long downTime;
    private float downX;
    private float downY;

    /** 本次按下时是否已落在上一次点击的双击窗口内。 */
    private boolean secondTap;

    private long lastTapTime = NO_TAP;
    private float lastTapX;
    private float lastTapY;

    DoubleTapDetector(float touchSlop, float doubleTapSlop,
                      long doubleTapTimeout, long longPressTimeout) {
        this.touchSlop = touchSlop;
        this.doubleTapSlop = doubleTapSlop;
        this.doubleTapTimeout = doubleTapTimeout;
        this.longPressTimeout = longPressTimeout;
    }

    /** 返回 true 表示识别到一次完整双击。 */
    boolean onEvent(int action, long eventTime, float x, float y,
                    int pointerCount, boolean inGestureBar) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                resetPress();
                if (inGestureBar && pointerCount == 1) {
                    pressed = true;
                    downTime = eventTime;
                    downX = x;
                    downY = y;
                    secondTap = isSecondTap(x, y, eventTime);
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                if (pressed && !withinSlop(x, y, pointerCount)) {
                    resetPress();
                }
                return false;
            case MotionEvent.ACTION_UP:
                return completeTap(eventTime, x, y, pointerCount, inGestureBar);
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                resetPress();
                return false;
            default:
                return false;
        }
    }

    void reset() {
        resetPress();
        lastTapTime = NO_TAP;
    }

    private boolean completeTap(long eventTime, float x, float y,
                                int pointerCount, boolean inGestureBar) {
        boolean isTap = pressed && inGestureBar && pointerCount == 1
                && eventTime - downTime <= longPressTimeout
                && withinSlop(x, y, pointerCount);
        boolean isDoubleTap = isTap && secondTap;
        resetPress();
        if (!isTap) {
            return false;
        }
        if (isDoubleTap) {
            // 先清空第一下，避免三连击继续命中同一窗口。
            lastTapTime = NO_TAP;
            return true;
        }
        lastTapTime = eventTime;
        lastTapX = downX;
        lastTapY = downY;
        return false;
    }

    private boolean isSecondTap(float x, float y, long eventTime) {
        if (lastTapTime == NO_TAP) {
            return false;
        }
        long gap = eventTime - lastTapTime;
        // 间隔必须为正：注入事件或极快操作可能给同一毫秒的 DOWN/UP 相同时间戳。
        if (gap <= 0 || gap > doubleTapTimeout) {
            return false;
        }
        double deltaX = x - lastTapX;
        double deltaY = y - lastTapY;
        return deltaX * deltaX + deltaY * deltaY <= (double) doubleTapSlop * doubleTapSlop;
    }

    private boolean withinSlop(float x, float y, int pointerCount) {
        return pointerCount == 1
                && Math.abs(x - downX) <= touchSlop
                && Math.abs(y - downY) <= touchSlop;
    }

    private void resetPress() {
        pressed = false;
        secondTap = false;
    }
}
