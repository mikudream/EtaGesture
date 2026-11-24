package io.github.mangi.etagesture;

import android.view.MotionEvent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DoubleTapDetectorTest {
    @Test
    public void twoQuickTapsTriggerOnce() {
        DoubleTapDetector detector = detector();
        Clock clock = new Clock();

        assertFalse(tap(detector, clock, 600f, 2540f, true, 120));
        assertTrue(tap(detector, clock, 600f, 2540f, true, 120));
        clock.now += 400;

        // 触发后必须重新积累两次点击，三连击不会继续命中同一窗口。
        assertFalse(tap(detector, clock, 600f, 2540f, true, 120));
        assertTrue(tap(detector, clock, 600f, 2540f, true, 120));
    }

    @Test
    public void secondTapOutsideDoubleTapWindowStartsNewPair() {
        DoubleTapDetector detector = detector();
        Clock clock = new Clock();

        assertFalse(tap(detector, clock, 600f, 2540f, true, 120));
        assertFalse(tap(detector, clock, 600f, 2540f, true, 400));
        assertTrue(tap(detector, clock, 600f, 2540f, true, 120));
    }

    @Test
    public void tapsOutsideGestureBarDoNotParticipate() {
        DoubleTapDetector detector = detector();
        Clock clock = new Clock();

        assertFalse(tap(detector, clock, 600f, 1800f, false, 120));
        assertFalse(tap(detector, clock, 600f, 1800f, false, 120));
        clock.now += 400;
        assertFalse(tap(detector, clock, 600f, 1800f, false, 120));
        // 区域外的点击既不能触发，也不能成为区域内的第一下。
        assertFalse(tap(detector, clock, 600f, 2540f, true, 120));
    }

    @Test
    public void longPressSwipeAndMultiTouchAreNotTaps() {
        DoubleTapDetector detector = detector();
        Clock clock = new Clock();

        clock.now += 10;
        assertFalse(detector.onEvent(MotionEvent.ACTION_DOWN, clock.now, 600f, 2540f, 1, true));
        clock.now += 600;
        assertFalse(detector.onEvent(MotionEvent.ACTION_UP, clock.now, 600f, 2540f, 1, true));
        clock.now += 400;
        assertFalse(tap(detector, clock, 600f, 2540f, true, 120));

        clock.now += 400;
        assertFalse(detector.onEvent(MotionEvent.ACTION_DOWN, clock.now, 600f, 2540f, 1, true));
        clock.now += 20;
        assertFalse(detector.onEvent(MotionEvent.ACTION_MOVE, clock.now, 300f, 2540f, 1, true));
        clock.now += 20;
        assertFalse(detector.onEvent(MotionEvent.ACTION_UP, clock.now, 300f, 2540f, 1, true));
        clock.now += 400;
        assertFalse(tap(detector, clock, 600f, 2540f, true, 120));

        clock.now += 400;
        assertFalse(detector.onEvent(MotionEvent.ACTION_DOWN, clock.now, 600f, 2540f, 1, true));
        clock.now += 10;
        assertFalse(detector.onEvent(
                MotionEvent.ACTION_POINTER_DOWN, clock.now, 600f, 2540f, 2, true));
        clock.now += 10;
        assertFalse(detector.onEvent(MotionEvent.ACTION_UP, clock.now, 600f, 2540f, 1, true));
        clock.now += 400;
        assertFalse(tap(detector, clock, 600f, 2540f, true, 120));
    }

    @Test
    public void farApartTapsDoNotTrigger() {
        DoubleTapDetector detector = detector();
        Clock clock = new Clock();

        assertFalse(tap(detector, clock, 100f, 2540f, true, 120));
        assertFalse(tap(detector, clock, 900f, 2540f, true, 120));

        clock.now += 400;
        assertFalse(tap(detector, clock, 600f, 2540f, true, 120));
    }

    @Test
    public void cancelInsertedBySystemDoesNotSwallowTheUp() {
        DoubleTapDetector detector = detector();
        // 桌面的手势识别会向部分事件流插入 CANCEL；CANCEL 本身即可视为释放，
        // 同一触摸后续到达的 UP 不会重复计数。
        assertFalse(detector.onEvent(MotionEvent.ACTION_DOWN, 1000L, 600f, 2540f, 1, true));
        assertFalse(detector.onEvent(MotionEvent.ACTION_CANCEL, 1000L, 600f, 2540f, 1, true));
        assertFalse(detector.onEvent(MotionEvent.ACTION_UP, 1080L, 600f, 2540f, 1, true));
        assertFalse(detector.onEvent(MotionEvent.ACTION_DOWN, 1200L, 600f, 2540f, 1, true));
        assertTrue(detector.onEvent(MotionEvent.ACTION_UP, 1280L, 600f, 2540f, 1, true));
    }

    @Test
    public void cancelWithoutUpStillCompletesTheTap() {
        DoubleTapDetector detector = detector();
        assertFalse(detector.onEvent(MotionEvent.ACTION_DOWN, 1000L, 600f, 2540f, 1, true));
        assertFalse(detector.onEvent(MotionEvent.ACTION_CANCEL, 1040L, 600f, 2540f, 1, true));
        assertFalse(detector.onEvent(MotionEvent.ACTION_DOWN, 1200L, 600f, 2540f, 1, true));
        assertTrue(detector.onEvent(MotionEvent.ACTION_CANCEL, 1240L, 600f, 2540f, 1, true));
    }

    @Test
    public void sameMillisecondDuplicateStreamDoesNotTrigger() {
        DoubleTapDetector detector = detector();
        // 注入事件或极快操作可能让 DOWN/UP 落在同一毫秒；另一路输入通道重复投递时，间隔为 0 不能当成双击。
        assertFalse(detector.onEvent(MotionEvent.ACTION_DOWN, 1000L, 600f, 2540f, 1, true));
        assertFalse(detector.onEvent(MotionEvent.ACTION_UP, 1000L, 600f, 2540f, 1, true));
        assertFalse(detector.onEvent(MotionEvent.ACTION_DOWN, 1000L, 600f, 2540f, 1, true));
        assertFalse(detector.onEvent(MotionEvent.ACTION_UP, 1000L, 600f, 2540f, 1, true));
    }

    private static DoubleTapDetector detector() {
        return new DoubleTapDetector(8f, 100f, 300L, 500L);
    }

    /** 一次完整点击：按下 40ms 后抬起，在此之前等待 gapBeforeTapMs。 */
    private static boolean tap(DoubleTapDetector detector, Clock clock,
                               float x, float y, boolean inGestureBar, long gapBeforeTapMs) {
        clock.now += gapBeforeTapMs;
        boolean down = detector.onEvent(MotionEvent.ACTION_DOWN, clock.now, x, y, 1, inGestureBar);
        clock.now += 40;
        boolean up = detector.onEvent(MotionEvent.ACTION_UP, clock.now, x, y, 1, inGestureBar);
        return down || up;
    }

    private static final class Clock {
        long now;
    }
}
