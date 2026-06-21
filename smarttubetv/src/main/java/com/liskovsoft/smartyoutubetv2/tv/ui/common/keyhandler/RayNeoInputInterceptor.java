package com.liskovsoft.smartyoutubetv2.tv.ui.common.keyhandler;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import com.ffalcon.mercury.android.sdk.touch.CommonTouchCallback;
import com.ffalcon.mercury.android.sdk.touch.FlingArgs;
import com.ffalcon.mercury.android.sdk.touch.TouchDispatcher;
import com.ffalcon.mercury.android.sdk.util.MyTouchUtils;

import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Intercepts temple touchpad MotionEvents on RayNeo X3 Pro AR glasses and translates
 * detected gestures into standard Android TV DPAD KeyEvents that SmartTube expects.
 */
public class RayNeoInputInterceptor {
    private static final String TAG = "RayNeoInputInterceptor";
    private static final boolean DEBUG_INPUT = true;
    private static final float SWIPE_THRESHOLD = 45f;
    private static final ExecutorService sInputExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.setName("RayNeoInputThread");
        return t;
    });

    private final Activity mActivity;
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private TouchDispatcher mTouchDispatcher;
    private CommonTouchCallback mTouchCallback;
    private boolean mInitialized;

    // For continuous scrolling emulation
    private float mAccumulatedDeltaX = 0f;
    private float mAccumulatedDeltaY = 0f;
    private float mLastDeltaX = 0f;
    private float mLastDeltaY = 0f;
    private boolean mInjectedContinuousThisSwipe = false;

    // MOD: pointer (cursor) mode — used in the video player so the pad moves a
    // pointer instead of injecting DPAD (which seeks the timeline). Gain and
    // the binocular coordinate mapping may need on-device tuning.
    private boolean mCursorMode = false;
    private View mCursorView;
    private View mCursorHost;
    private float mCursorX = -1f;
    private float mCursorY = -1f;
    private float mCursorTargetX = -1f;
    private float mCursorTargetY = -1f;
    private boolean mCursorAnimating = false;
    private float mClickX = -1f;
    private float mClickY = -1f;
    private float mLastContX = 0f;
    private float mLastContY = 0f;
    private float mGestureMoveX = 0f;
    private float mGestureMoveY = 0f;
    private boolean mSuppressClickThisGesture;
    private long mSuppressClickUntilMs;
    private long mLastSwipeKeyTimeMs;
    private int mPendingSwipeKeyCode;
    private int mHalfWidth = 0; // per-eye width (X3 Pro mirrors the left 640px)
    // Lower gain = steadier, slower pointer (user preference). Smoothing eases
    // the displayed cursor toward its target each animation frame so it never
    // lags-then-races when the pad's deltas arrive in bursts.
    private static final float CURSOR_GAIN = 0.55f;
    private static final float CURSOR_SMOOTHING = 0.35f;
    private static final float CLICK_CANCEL_MOVE_PX = 18f;
    private static final long CURSOR_AUTO_HIDE_MS = 3_000L;
    private static final long SWIPE_CLICK_SUPPRESS_MS = 450L;
    private static final long SWIPE_NAV_COOLDOWN_MS = 320L;
    private final Runnable mHideCursorRunnable = new Runnable() {
        @Override
        public void run() {
            if (mCursorView != null) {
                mCursorView.setVisibility(View.GONE);
            }
        }
    };
    // Per-frame easing of the displayed cursor toward its input-driven target.
    private final Runnable mCursorAnimRunnable = new Runnable() {
        @Override
        public void run() {
            if (mCursorView == null) {
                mCursorAnimating = false;
                return;
            }
            float dx = mCursorTargetX - mCursorX;
            float dy = mCursorTargetY - mCursorY;
            if (Math.abs(dx) < 0.5f && Math.abs(dy) < 0.5f) {
                mCursorX = mCursorTargetX;
                mCursorY = mCursorTargetY;
                positionCursor();
                mCursorAnimating = false;
                return;
            }
            mCursorX += dx * CURSOR_SMOOTHING;
            mCursorY += dy * CURSOR_SMOOTHING;
            positionCursor();
            mCursorView.postOnAnimation(this);
        }
    };

    public RayNeoInputInterceptor(Activity activity) {
        mActivity = activity;
        android.util.Log.d(TAG, "Initializing for Activity: " + activity.getClass().getSimpleName());
        try {
            initDispatcher();
        } catch (Throwable e) {
            android.util.Log.e(TAG, "Failed to init RayNeo TouchDispatcher: " + e.getMessage(), e);
            mInitialized = false;
        }
    }

    public boolean isActive() {
        return mInitialized;
    }

    public boolean onTouchEvent(MotionEvent event) {
        if (!mInitialized || mTouchDispatcher == null || mTouchCallback == null) {
            log("ignore touch: initialized=" + mInitialized + " dispatcher=" + (mTouchDispatcher != null)
                    + " callback=" + (mTouchCallback != null));
            return false;
        }

        // Ignore the left temple touchpad (typically used for system volume/brightness)
        if (MyTouchUtils.isLeft(event)) {
            log("ignore touch: left pad action=" + motionActionToString(event.getActionMasked())
                    + " source=0x" + Integer.toHexString(event.getSource()));
            return false;
        }

        // Only process events from a touchscreen or touchpad to prevent swallowing mouse events
        if (!event.isFromSource(android.view.InputDevice.SOURCE_TOUCHSCREEN) && 
            !event.isFromSource(android.view.InputDevice.SOURCE_TOUCHPAD)) {
            log("ignore touch: unsupported source action=" + motionActionToString(event.getActionMasked())
                    + " source=0x" + Integer.toHexString(event.getSource()));
            return false;
        }

        try {
            log("touch action=" + motionActionToString(event.getActionMasked())
                    + " x=" + event.getX() + " y=" + event.getY()
                    + " source=0x" + Integer.toHexString(event.getSource())
                    + " cursorMode=" + mCursorMode
                    + " injectedThisSwipe=" + mInjectedContinuousThisSwipe
                    + " focus=" + describeFocus());
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                android.util.Log.d(TAG, "ACTION_DOWN received, resetting deltas.");
                mAccumulatedDeltaX = 0f;
                mAccumulatedDeltaY = 0f;
                mLastDeltaX = 0f;
                mLastDeltaY = 0f;
                mLastContX = 0f;
                mLastContY = 0f;
                mGestureMoveX = 0f;
                mGestureMoveY = 0f;
                mSuppressClickThisGesture = false;
                mPendingSwipeKeyCode = 0;
                // Snapshot where the pointer is aimed at the START of this
                // gesture, so a tap clicks there even if the tap jitters it.
                mClickX = mCursorX;
                mClickY = mCursorY;
                mInjectedContinuousThisSwipe = false;
            }

            mTouchDispatcher.onMotionEvent(event, mTouchCallback);

            if ((event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
                    && mPendingSwipeKeyCode != 0) {
                final int keyCode = mPendingSwipeKeyCode;
                mPendingSwipeKeyCode = 0;
                log("dispatch pending swipe after " + motionActionToString(event.getActionMasked())
                        + " key=" + keyToString(keyCode));
                mUiHandler.postDelayed(() -> {
                    if (moveFocusForNavigationKey(keyCode)) {
                        // Focus moved directly (e.g. a vertical settings list).
                        // Leave touch mode so the focus highlight actually DRAWS
                        // on the new item — otherwise the selection moves but the
                        // highlight only appears on the next swipe (which falls
                        // through to injectKeyEventAsync and clears touch mode).
                        exitTouchModeAsync();
                    } else {
                        injectKeyEventAsync(keyCode);
                    }
                }, 35L);
            }

            return true; // Consume event to prevent default pointer/drag behavior
        } catch (Throwable e) {
            android.util.Log.e(TAG, "Error dispatching touch event: " + e.getMessage(), e);
            return false;
        }
    }

    private void initDispatcher() {
        mTouchDispatcher = new TouchDispatcher(TouchDispatcher.Source.Activity.INSTANCE);

        mTouchCallback = new CommonTouchCallback() {
            @Override
            public boolean onTPClick() {
                log("callback onTPClick suppressUntil=" + mSuppressClickUntilMs
                        + " now=" + SystemClock.uptimeMillis()
                        + " cursorMode=" + mCursorMode
                        + " suppressGesture=" + mSuppressClickThisGesture
                        + " focus=" + describeFocus());
                if (SystemClock.uptimeMillis() < mSuppressClickUntilMs) {
                    android.util.Log.d(TAG, "onTPClick suppressed after swipe navigation");
                    return true;
                }

                if (mCursorMode) {
                    if (mSuppressClickThisGesture) {
                        android.util.Log.d(TAG, "onTPClick suppressed after cursor swipe");
                        return true;
                    }
                    android.util.Log.d(TAG, "onTPClick (cursor) - clicking at pointer");
                    clickAtCursor();
                    return true;
                }
                android.util.Log.d(TAG, "onTPClick detected - explicitly injecting click");
                injectClick();
                return true;
            }

            @Override
            public boolean onTPDoubleClick() {
                log("callback onTPDoubleClick -> BACK focus=" + describeFocus());
                injectKeyEventAsync(KeyEvent.KEYCODE_BACK);
                return true;
            }

            @Override
            public boolean onTPSlideForward(FlingArgs flingArgs) {
                log("callback onTPSlideForward injectedThisSwipe=" + mInjectedContinuousThisSwipe
                        + " focus=" + describeFocus());
                if (mCursorMode) return true; // swallow: no seek in pointer mode
                if (!mInjectedContinuousThisSwipe) {
                    mInjectedContinuousThisSwipe = injectSwipeKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT);
                }
                return true;
            }

            @Override
            public boolean onTPSlideBackward(FlingArgs flingArgs) {
                log("callback onTPSlideBackward injectedThisSwipe=" + mInjectedContinuousThisSwipe
                        + " focus=" + describeFocus());
                if (mCursorMode) return true;
                if (!mInjectedContinuousThisSwipe) {
                    mInjectedContinuousThisSwipe = injectSwipeKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT);
                }
                return true;
            }

            @Override
            public boolean onTPSlideUpwards(FlingArgs flingArgs) {
                log("callback onTPSlideUpwards injectedThisSwipe=" + mInjectedContinuousThisSwipe
                        + " focus=" + describeFocus());
                if (mCursorMode) return true;
                if (!mInjectedContinuousThisSwipe) {
                    mInjectedContinuousThisSwipe = injectSwipeKeyEvent(KeyEvent.KEYCODE_DPAD_UP);
                }
                return true;
            }

            @Override
            public boolean onTPSlideDownwards(FlingArgs flingArgs) {
                log("callback onTPSlideDownwards injectedThisSwipe=" + mInjectedContinuousThisSwipe
                        + " focus=" + describeFocus());
                if (mCursorMode) return true;
                if (!mInjectedContinuousThisSwipe) {
                    mInjectedContinuousThisSwipe = injectSwipeKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN);
                }
                return true;
            }

            @Override
            public boolean onTPSlideContinuous(float delta, boolean longClick, boolean vertical) {
                log("callback onTPSlideContinuous delta=" + delta
                        + " vertical=" + vertical
                        + " longClick=" + longClick
                        + " injectedThisSwipe=" + mInjectedContinuousThisSwipe
                        + " cursorMode=" + mCursorMode);
                if (mCursorMode) {
                    moveCursorContinuous(delta, vertical);
                    return true;
                }
                handleContinuous(delta, vertical);
                return true;
            }
        };

        mInitialized = true;
        android.util.Log.d(TAG, "RayNeo TouchDispatcher initialized successfully");
    }

    private void ensureFocus() {
        mActivity.runOnUiThread(() -> {
            try {
                if (mActivity.getWindow() != null && mActivity.getWindow().getDecorView() != null) {
                    View decorView = mActivity.getWindow().getDecorView();
                    if (decorView.isInTouchMode()) {
                        android.util.Log.d(TAG, "Exiting touch mode via requestFocus");
                        decorView.requestFocus();
                    }
                }
            } catch (Throwable ignored) {}
        });
    }

    private void handleContinuous(float delta, boolean vertical) {
        if (mInjectedContinuousThisSwipe) {
            log("continuous ignored: already injected delta=" + delta + " vertical=" + vertical);
            return;
        }

        if (vertical) {
            float step = delta - mLastDeltaY;
            mLastDeltaY = delta;
            mAccumulatedDeltaY += step;
            log("continuous vertical step=" + step + " accumY=" + mAccumulatedDeltaY
                    + " threshold=" + SWIPE_THRESHOLD);
            
            if (Math.abs(mAccumulatedDeltaY) > SWIPE_THRESHOLD) {
                if (mAccumulatedDeltaY > 0) {
                    mInjectedContinuousThisSwipe = injectSwipeKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN);
                } else {
                    mInjectedContinuousThisSwipe = injectSwipeKeyEvent(KeyEvent.KEYCODE_DPAD_UP);
                }
                mAccumulatedDeltaY = 0f;
            }
        } else {
            float step = delta - mLastDeltaX;
            mLastDeltaX = delta;
            mAccumulatedDeltaX += step;
            log("continuous horizontal step=" + step + " accumX=" + mAccumulatedDeltaX
                    + " threshold=" + SWIPE_THRESHOLD);

            if (Math.abs(mAccumulatedDeltaX) > SWIPE_THRESHOLD) {
                if (mAccumulatedDeltaX > 0) {
                    mInjectedContinuousThisSwipe = injectSwipeKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT);
                } else {
                    mInjectedContinuousThisSwipe = injectSwipeKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT);
                }
                mAccumulatedDeltaX = 0f;
            }
        }
    }

    private boolean injectSwipeKeyEvent(int keyCode) {
        long now = SystemClock.uptimeMillis();
        mSuppressClickThisGesture = true;
        mSuppressClickUntilMs = now + SWIPE_CLICK_SUPPRESS_MS;

        if (now - mLastSwipeKeyTimeMs < SWIPE_NAV_COOLDOWN_MS) {
            log("swipe key suppressed by cooldown key=" + keyToString(keyCode)
                    + " sinceLast=" + (now - mLastSwipeKeyTimeMs)
                    + " cooldown=" + SWIPE_NAV_COOLDOWN_MS);
            return true;
        }

        mLastSwipeKeyTimeMs = now;
        log("inject swipe key=" + keyToString(keyCode)
                + " focusBefore=" + describeFocus()
                + " activity=" + mActivity.getClass().getSimpleName());
        // One key per physical swipe. The RayNeo SDK may emit multiple gesture
        // sequences for one fast pad movement, so this method also rate-limits
        // globally instead of trusting ACTION_DOWN boundaries alone.
        mPendingSwipeKeyCode = keyCode;
        return true;
    }

    private void injectClick() {
        mActivity.runOnUiThread(() -> {
            try {
                ensureFocus();
                View focus = null;
                if (mActivity.getWindow() != null) {
                    focus = mActivity.getWindow().getCurrentFocus();
                }
                if (focus != null) {
                    android.util.Log.d(TAG, "Explicitly clicking focused view: " + focus.getClass().getSimpleName());
                    boolean clicked = focus.performClick();
                    if (!clicked) {
                        android.util.Log.d(TAG, "performClick returned false, falling back to KeyEvent");
                        injectKeyEventAsync(KeyEvent.KEYCODE_DPAD_CENTER);
                    }
                } else {
                    android.util.Log.d(TAG, "No focused view found, falling back to KeyEvent");
                    injectKeyEventAsync(KeyEvent.KEYCODE_DPAD_CENTER);
                }
            } catch (Throwable e) {
                android.util.Log.e(TAG, "Error in injectClick: " + e.getMessage());
                injectKeyEventAsync(KeyEvent.KEYCODE_DPAD_CENTER);
            }
        });
    }

    private void injectKeyEventAsync(int keyCode) {
        log("queue key=" + keyToString(keyCode) + " focus=" + describeFocus());
        sInputExecutor.execute(() -> {
            if (mActivity.isFinishing() || mActivity.isDestroyed()) {
                log("drop key: activity finishing/destroyed key=" + keyToString(keyCode));
                return;
            }

            try {
                // Ensure focus highlight is visible before injecting
                ensureFocus();
                
                android.app.Instrumentation inst = new android.app.Instrumentation();

                // Force the system out of touch mode so the D-pad event isn't
                // swallowed just "waking up" from touch. The change is processed
                // asynchronously on the UI thread, so WAIT (bounded) until it has
                // actually cleared before sending the key — otherwise the key is
                // either wasted exiting touch mode (menu "flashes", won't scroll)
                // or, if it clears late, an extra key slips through and skips an
                // item. Polling for the real state makes every press move once.
                inst.setInTouchMode(false);
                View decor = mActivity.getWindow() != null ? mActivity.getWindow().getDecorView() : null;
                boolean wasInTouchMode = decor != null && decor.isInTouchMode();
                for (int i = 0; i < 30 && decor != null && decor.isInTouchMode(); i++) {
                    try { Thread.sleep(8); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
                }
                log("send instrumentation key=" + keyToString(keyCode)
                        + " wasTouchMode=" + wasInTouchMode
                        + " nowTouchMode=" + (decor != null && decor.isInTouchMode())
                        + " focusBeforeSend=" + describeFocus());

                // setInTouchMode(false) is slow/unreliable on this device, so
                // touch mode often hasn't cleared by now. When it hasn't, the
                // FIRST D-pad key is consumed just LEAVING touch mode (focus
                // appears but doesn't move) — so a directional key has to be
                // sent twice: the first exits touch mode, the second navigates.
                // This is what made grid items highlight only every other swipe.
                boolean stillInTouchMode = decor != null && decor.isInTouchMode();

                long now = SystemClock.uptimeMillis();
                KeyEvent downEvent = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0,
                        android.view.InputDevice.SOURCE_KEYBOARD);
                KeyEvent upEvent = new KeyEvent(now, now + 10, KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, 0,
                        android.view.InputDevice.SOURCE_KEYBOARD);
                inst.sendKeySync(downEvent);
                inst.sendKeySync(upEvent);
                if (stillInTouchMode && isDpadNavigationKey(keyCode)) {
                    long now2 = SystemClock.uptimeMillis();
                    KeyEvent down2 = new KeyEvent(now2, now2, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0,
                            android.view.InputDevice.SOURCE_KEYBOARD);
                    KeyEvent up2 = new KeyEvent(now2, now2 + 10, KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, 0,
                            android.view.InputDevice.SOURCE_KEYBOARD);
                    inst.sendKeySync(down2);
                    inst.sendKeySync(up2);
                    log("sent SECOND nav key (was in touch mode) key=" + keyToString(keyCode));
                }
                log("sent instrumentation key=" + keyToString(keyCode)
                        + " focusAfterSend=" + describeFocus());
                
                // SmartTube dialogs/menus sometimes prefer ENTER over DPAD_CENTER
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    inst.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
                }
            } catch (Throwable e) {
                android.util.Log.e(TAG, "Instrumentation key failed key=" + keyToString(keyCode)
                        + " error=" + e.getMessage(), e);
                // Fallback to direct dispatch on UI thread if instrumentation fails
                mActivity.runOnUiThread(() -> {
                    if (mActivity.isFinishing() || mActivity.isDestroyed()) {
                        log("drop fallback key: activity finishing/destroyed key=" + keyToString(keyCode));
                        return;
                    }
                    long now = SystemClock.uptimeMillis();
                    KeyEvent downEvent = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0, android.view.InputDevice.SOURCE_DPAD);
                    KeyEvent upEvent = new KeyEvent(now, now + 10, KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, 0, android.view.InputDevice.SOURCE_DPAD);
                    log("dispatch fallback keyDown=" + keyToString(keyCode)
                            + " focusBefore=" + describeFocus());
                    boolean downHandled = mActivity.dispatchKeyEvent(downEvent);
                    boolean upHandled = mActivity.dispatchKeyEvent(upEvent);
                    log("dispatch fallback result key=" + keyToString(keyCode)
                            + " downHandled=" + downHandled
                            + " upHandled=" + upHandled
                            + " focusAfter=" + describeFocus());
                });
            }
        });
    }

    /** Leave touch mode (off the main thread) so a programmatic focus move via
     *  {@link #moveFocusForNavigationKey} actually shows its highlight. */
    private void exitTouchModeAsync() {
        sInputExecutor.execute(() -> {
            if (mActivity.isFinishing() || mActivity.isDestroyed()) {
                return;
            }
            try {
                new android.app.Instrumentation().setInTouchMode(false);
            } catch (Throwable e) {
                android.util.Log.e(TAG, "exitTouchMode failed: " + e.getMessage());
            }
        });
    }

    private void dispatchKeyEventOnUiThread(int keyCode) {
        mActivity.runOnUiThread(() -> {
            if (mActivity.isFinishing() || mActivity.isDestroyed()) {
                log("drop direct key: activity finishing/destroyed key=" + keyToString(keyCode));
                return;
            }

            try {
                long now = SystemClock.uptimeMillis();
                KeyEvent downEvent = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0,
                        android.view.InputDevice.SOURCE_DPAD);
                KeyEvent upEvent = new KeyEvent(now, now + 10, KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, 0,
                        android.view.InputDevice.SOURCE_DPAD);
                log("dispatch direct keyDown=" + keyToString(keyCode)
                        + " focusBefore=" + describeFocus());
                boolean downHandled = mActivity.dispatchKeyEvent(downEvent);
                boolean upHandled = mActivity.dispatchKeyEvent(upEvent);
                if (!downHandled && mActivity.getWindow() != null && mActivity.getWindow().getCurrentFocus() != null) {
                    View focus = mActivity.getWindow().getCurrentFocus();
                    log("dispatch direct fallback to focus key=" + keyToString(keyCode)
                            + " focus=" + describeFocus());
                    downHandled = focus.dispatchKeyEvent(downEvent);
                    upHandled = focus.dispatchKeyEvent(upEvent);
                }
                log("dispatch direct result key=" + keyToString(keyCode)
                        + " downHandled=" + downHandled
                        + " upHandled=" + upHandled
                        + " focusAfter=" + describeFocus());
            } catch (Throwable e) {
                android.util.Log.e(TAG, "Direct key dispatch failed key=" + keyToString(keyCode)
                        + " error=" + e.getMessage(), e);
            }
        });
    }

    private boolean moveFocusForNavigationKey(int keyCode) {
        if (!isDpadNavigationKey(keyCode)) {
            return false;
        }

        if (mActivity.isFinishing() || mActivity.isDestroyed() || mActivity.getWindow() == null) {
            return false;
        }

        int direction = focusDirectionForKey(keyCode);
        if (direction == 0) {
            return false;
        }

        View current = mActivity.getWindow().getCurrentFocus();
        View decor = mActivity.getWindow().getDecorView();
        if (current == null && decor != null) {
            current = decor.findFocus();
        }

        // For vertical moves inside a leanback list, defer to NATIVE DPAD
        // (injectKeyEventAsync). Native leanback moves selection, focus,
        // highlight and scroll together and handles touch mode; our manual
        // setSelectedPosition + requestFocus could move the selection but never
        // the highlight while in touch mode (items aren't focusable then), so
        // the highlight only landed every other swipe.
        if (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) {
            boolean inGrid = (current != null && findAncestorVerticalGridView(current) != null)
                    || findVisibleVerticalGridView(decor) != null;
            if (inGrid) {
                log("vertical nav deferred to native DPAD key=" + keyToString(keyCode));
                return false;
            }
        }

        if (current == null) {
            log("focus move failed: no current focus key=" + keyToString(keyCode));
            return false;
        }

        try {
            View target = findDeterministicFocusTarget(current, direction);
            if (target == null) {
                target = current.focusSearch(direction);
            }

            log("focus move key=" + keyToString(keyCode)
                    + " current=" + describeView(current)
                    + " target=" + describeView(target));

            if (target != null && target != current && target.isFocusable()) {
                boolean moved = target.requestFocus(direction);
                log("focus move result key=" + keyToString(keyCode)
                        + " moved=" + moved
                        + " focusAfter=" + describeFocus());
                return moved;
            }
        } catch (Throwable e) {
            android.util.Log.e(TAG, "Focus move failed key=" + keyToString(keyCode)
                    + " error=" + e.getMessage(), e);
        }

        return false;
    }

    private boolean moveVerticalGridSelection(View current, int direction) {
        if (direction != View.FOCUS_UP && direction != View.FOCUS_DOWN) {
            return false;
        }

        VerticalGridView grid = findAncestorVerticalGridView(current);
        if (grid == null || grid.getAdapter() == null || grid.getAdapter().getItemCount() == 0) {
            return false;
        }

        int position = grid.getSelectedPosition();
        if (position < 0) {
            position = findChildAdapterPosition(grid, current);
        }

        if (position < 0) {
            log("vertical grid selection failed: no adapter position current=" + describeView(current));
            return false;
        }

        int nextPosition = direction == View.FOCUS_DOWN ? position + 1 : position - 1;
        int itemCount = grid.getAdapter().getItemCount();
        if (nextPosition < 0 || nextPosition >= itemCount) {
            log("vertical grid selection at edge position=" + position
                    + " next=" + nextPosition
                    + " count=" + itemCount);
            return false;
        }

        log("vertical grid selection move position=" + position
                + " next=" + nextPosition
                + " direction=" + direction
                + " grid=" + describeView(grid));
        grid.setSelectedPosition(nextPosition);
        focusSelectedGridChild(grid, nextPosition, direction, 0);
        return true;
    }

    /** Move the grid's selection by one without needing a focused child first —
     *  used on the first swipe when touch mode means nothing is focused yet. */
    private boolean moveVerticalGridSelectionDirect(VerticalGridView grid, int direction) {
        if (grid == null || grid.getAdapter() == null || grid.getAdapter().getItemCount() == 0) {
            return false;
        }
        int position = grid.getSelectedPosition();
        if (position < 0) {
            return false;
        }
        int itemCount = grid.getAdapter().getItemCount();
        int nextPosition = direction == View.FOCUS_DOWN ? position + 1 : position - 1;
        if (nextPosition < 0 || nextPosition >= itemCount) {
            log("direct grid selection at edge position=" + position + " next=" + nextPosition
                    + " count=" + itemCount);
            return false;
        }
        log("direct grid selection move position=" + position + " next=" + nextPosition
                + " grid=" + describeView(grid));
        grid.setSelectedPosition(nextPosition);
        focusSelectedGridChild(grid, nextPosition, direction, 0);
        return true;
    }

    /** First visible VerticalGridView with a populated adapter (the active list). */
    private VerticalGridView findVisibleVerticalGridView(View root) {
        if (root == null || !root.isShown()) {
            return null;
        }
        if (root instanceof VerticalGridView) {
            VerticalGridView grid = (VerticalGridView) root;
            if (grid.getAdapter() != null && grid.getAdapter().getItemCount() > 0) {
                return grid;
            }
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                VerticalGridView found = findVisibleVerticalGridView(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void focusSelectedGridChild(VerticalGridView grid, int position, int direction, int attempt) {
        grid.postDelayed(() -> {
            if (mActivity.isFinishing() || mActivity.isDestroyed()) {
                return;
            }

            // Leanback grid items are focusable only OUT of touch mode, and the
            // itemView is usually a NON-focusable wrapper around a focusable
            // child. So: leave touch mode, and request focus by DESCENDING into
            // the item (requestFocus on the wrapper, not gated on its own
            // isFocusable). Without this the highlight never follows the moved
            // selection and only lands every other swipe (when touch mode
            // happens to be off). Retries cover the async touch-mode clear.
            exitTouchModeAsync();

            RecyclerView.ViewHolder viewHolder = grid.findViewHolderForAdapterPosition(position);
            View itemView = viewHolder != null ? viewHolder.itemView : null;
            if (itemView != null && itemView.isShown()) {
                boolean focused = itemView.requestFocus(direction);
                if (!focused) {
                    focused = itemView.requestFocus();
                }
                if (focused) {
                    log("vertical grid focus selected position=" + position
                            + " attempt=" + attempt
                            + " focused=true item=" + describeView(itemView)
                            + " focusAfter=" + describeFocus());
                    return;
                }
            }

            if (attempt < 4) {
                log("vertical grid focus retry position=" + position
                        + " attempt=" + attempt
                        + " holder=" + (viewHolder != null)
                        + " item=" + describeView(itemView));
                focusSelectedGridChild(grid, position, direction, attempt + 1);
            } else {
                log("vertical grid focus failed position=" + position
                        + " holder=" + (viewHolder != null)
                        + " item=" + describeView(itemView));
            }
        }, attempt == 0 ? 40L : 55L);
    }

    private VerticalGridView findAncestorVerticalGridView(View view) {
        View current = view;
        while (current != null) {
            if (current instanceof VerticalGridView) {
                return (VerticalGridView) current;
            }

            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }

        return null;
    }

    private int findChildAdapterPosition(VerticalGridView grid, View current) {
        View view = current;
        while (view != null && view.getParent() != grid) {
            ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }

        return view != null ? grid.getChildAdapterPosition(view) : -1;
    }

    private View findDeterministicFocusTarget(View current, int direction) {
        if (direction != View.FOCUS_UP && direction != View.FOCUS_DOWN) {
            return null;
        }

        View decor = mActivity.getWindow() != null ? mActivity.getWindow().getDecorView() : null;
        if (decor == null) {
            return null;
        }

        ArrayList<View> focusables = new ArrayList<>();
        decor.addFocusables(focusables, direction);
        if (focusables.isEmpty()) {
            return null;
        }

        Rect currentRect = new Rect();
        if (!current.getGlobalVisibleRect(currentRect)) {
            return null;
        }

        final int currentCenterX = currentRect.centerX();
        View best = null;
        long bestScore = Long.MAX_VALUE;
        Rect candidateRect = new Rect();

        for (View candidate : focusables) {
            if (candidate == current
                    || candidate == decor
                    || !candidate.isShown()
                    || !candidate.isFocusable()
                    || !candidate.isEnabled()
                    || !candidate.getGlobalVisibleRect(candidateRect)) {
                continue;
            }

            int verticalGap;
            if (direction == View.FOCUS_DOWN) {
                if (candidateRect.centerY() <= currentRect.centerY()) {
                    continue;
                }
                verticalGap = Math.max(1, candidateRect.top - currentRect.bottom);
            } else {
                if (candidateRect.centerY() >= currentRect.centerY()) {
                    continue;
                }
                verticalGap = Math.max(1, currentRect.top - candidateRect.bottom);
            }

            boolean columnOverlap = candidateRect.left < currentRect.right && candidateRect.right > currentRect.left;
            if (!columnOverlap) {
                continue;
            }

            int horizontalDistance = Math.abs(candidateRect.centerX() - currentCenterX);
            long score = (long) verticalGap * 10_000L + horizontalDistance;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        log("deterministic vertical target direction=" + direction
                + " currentRect=" + currentRect
                + " target=" + describeView(best)
                + " score=" + bestScore);
        return best;
    }

    private void log(String message) {
        if (DEBUG_INPUT) {
            android.util.Log.d(TAG, message);
        }
    }

    private String describeFocus() {
        try {
            View focus = mActivity.getWindow() != null ? mActivity.getWindow().getCurrentFocus() : null;
            if (focus == null) {
                return "null";
            }
            return focus.getClass().getSimpleName()
                    + "#" + focus.getId()
                    + " touchMode=" + focus.isInTouchMode()
                    + " selected=" + focus.isSelected()
                    + " focused=" + focus.isFocused();
        } catch (Throwable e) {
            return "error:" + e.getClass().getSimpleName();
        }
    }

    private String describeView(View view) {
        if (view == null) {
            return "null";
        }

        return view.getClass().getSimpleName()
                + "#" + view.getId()
                + " touchMode=" + view.isInTouchMode()
                + " selected=" + view.isSelected()
                + " focused=" + view.isFocused()
                + " focusable=" + view.isFocusable();
    }

    private static String keyToString(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return "DPAD_UP";
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return "DPAD_DOWN";
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return "DPAD_LEFT";
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return "DPAD_RIGHT";
            case KeyEvent.KEYCODE_DPAD_CENTER:
                return "DPAD_CENTER";
            case KeyEvent.KEYCODE_ENTER:
                return "ENTER";
            case KeyEvent.KEYCODE_BACK:
                return "BACK";
            default:
                return String.valueOf(keyCode);
        }
    }

    private static boolean isDpadNavigationKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private static int focusDirectionForKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return View.FOCUS_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return View.FOCUS_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return View.FOCUS_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return View.FOCUS_RIGHT;
            default:
                return 0;
        }
    }

    private static String motionActionToString(int action) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                return "DOWN";
            case MotionEvent.ACTION_UP:
                return "UP";
            case MotionEvent.ACTION_MOVE:
                return "MOVE";
            case MotionEvent.ACTION_CANCEL:
                return "CANCEL";
            case MotionEvent.ACTION_SCROLL:
                return "SCROLL";
            default:
                return String.valueOf(action);
        }
    }

    // ===== MOD: pointer (cursor) mode for the video player =====================

    public void setCursorMode(final boolean enabled) {
        mCursorMode = enabled;
        mActivity.runOnUiThread(() -> {
            try {
                if (enabled) {
                    ensureCursorView();
                    showCursorAndScheduleHide();
                } else if (mCursorView != null) {
                    mUiHandler.removeCallbacks(mHideCursorRunnable);
                    mCursorView.setVisibility(View.GONE);
                }
            } catch (Throwable e) {
                android.util.Log.e(TAG, "setCursorMode failed: " + e.getMessage());
            }
        });
    }

    private void ensureCursorView() {
        if (mCursorView != null) return;
        try {
            android.view.ViewGroup content = mActivity.findViewById(android.R.id.content);
            if (content == null || content.getChildCount() == 0) return;
            // The X3 Pro renders the LEFT 640px as the source content and the
            // MirroringView duplicates it to the right eye. Add the cursor INSIDE
            // that source so it shows in both eyes and shares the source's
            // coordinate space, and constrain it to the per-eye half-width so it
            // never roams onto the non-interactive mirror (the click-miss bug).
            View src = content.getChildAt(0);
            android.view.ViewGroup host = (src instanceof android.view.ViewGroup)
                    ? (android.view.ViewGroup) src : content;
            mHalfWidth = mActivity.getResources().getDisplayMetrics().widthPixels; // 640 per eye
            float density = mActivity.getResources().getDisplayMetrics().density;
            int size = Math.round(density * 22f);
            View v = new View(mActivity);
            v.setLayoutParams(new android.view.ViewGroup.LayoutParams(size, size));
            android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
            ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            ring.setColor(0x66FFFFFF);
            ring.setStroke(Math.round(density * 2f), 0xFFFFFFFF);
            v.setBackground(ring);
            v.setElevation(100000f);
            host.addView(v);
            mCursorView = v;
            mCursorHost = host;
            int hw = mHalfWidth > 0 ? mHalfWidth : host.getWidth();
            mCursorX = hw / 2f;
            mCursorY = host.getHeight() / 2f;
            mCursorTargetX = mCursorX;
            mCursorTargetY = mCursorY;
            positionCursor();
            android.util.Log.d(TAG, "cursor added host=" + host.getClass().getSimpleName()
                    + " halfW=" + mHalfWidth + " center=" + mCursorX + "," + mCursorY);
        } catch (Throwable e) {
            android.util.Log.e(TAG, "ensureCursorView failed: " + e.getMessage());
        }
    }

    private void positionCursor() {
        if (mCursorView == null) return;
        mCursorView.setX(mCursorX - mCursorView.getWidth() / 2f);
        mCursorView.setY(mCursorY - mCursorView.getHeight() / 2f);
    }

    private void moveCursorContinuous(final float delta, final boolean vertical) {
        mActivity.runOnUiThread(() -> {
            if (mCursorView == null) ensureCursorView();
            if (mCursorView == null) return;
            showCursorAndScheduleHide();
            float step;
            // Feed deltas into the TARGET; the animation runnable eases the
            // visible cursor toward it for steady, lag-free motion.
            if (vertical) {
                step = (delta - mLastContY) * CURSOR_GAIN;
                mLastContY = delta;
                mCursorTargetY += step;
                mGestureMoveY += step;
            } else {
                step = (delta - mLastContX) * CURSOR_GAIN;
                mLastContX = delta;
                mCursorTargetX += step;
                mGestureMoveX += step;
            }
            if (Math.hypot(mGestureMoveX, mGestureMoveY) >= CLICK_CANCEL_MOVE_PX) {
                mSuppressClickThisGesture = true;
            }
            View parent = (View) mCursorView.getParent();
            // Constrain X to the per-eye half-width so the pointer stays on the
            // real (left) content and never wanders onto the mirror.
            float maxX = mHalfWidth > 0 ? mHalfWidth : (parent != null ? parent.getWidth() : mCursorTargetX);
            float maxY = parent != null ? parent.getHeight() : mCursorTargetY;
            mCursorTargetX = Math.max(0f, Math.min(maxX, mCursorTargetX));
            mCursorTargetY = Math.max(0f, Math.min(maxY, mCursorTargetY));
            startCursorAnimation();
        });
    }

    /** Start the per-frame easing loop if it isn't already running. */
    private void startCursorAnimation() {
        if (mCursorAnimating || mCursorView == null) {
            return;
        }
        mCursorAnimating = true;
        mCursorView.postOnAnimation(mCursorAnimRunnable);
    }

    private void clickAtCursor() {
        mActivity.runOnUiThread(() -> {
            try {
                if (mActivity.getWindow() == null) return;
                View decor = mActivity.getWindow().getDecorView();
                if (decor == null) return;
                // Click where the tap BEGAN (snapshot), not the post-jitter spot,
                // clamped to the left-eye content so it lands on a real control.
                float cx = mClickX >= 0f ? mClickX : mCursorX;
                float cy = mClickY >= 0f ? mClickY : mCursorY;
                if (mHalfWidth > 0) cx = Math.max(0f, Math.min(mHalfWidth - 2f, cx));
                // Hold the visible pointer there too, so a tap can't drift it
                // (and stop any in-flight easing from sliding it away).
                mCursorX = cx;
                mCursorY = cy;
                mCursorTargetX = cx;
                mCursorTargetY = cy;
                positionCursor();
                showCursorAndScheduleHide();

                float decorX = cx;
                float decorY = cy;
                if (mCursorHost != null) {
                    int[] hostLoc = new int[2];
                    int[] decorLoc = new int[2];
                    mCursorHost.getLocationOnScreen(hostLoc);
                    decor.getLocationOnScreen(decorLoc);
                    decorX = hostLoc[0] - decorLoc[0] + cx;
                    decorY = hostLoc[1] - decorLoc[1] + cy;
                }

                View target = findClickableAt(decor, decorX, decorY);
                if (target != null) {
                    if (target.performClick()) {
                        android.util.Log.d(TAG, "clickAtCursor performClick target="
                                + target.getClass().getSimpleName() + " at " + decorX + "," + decorY);
                        return;
                    }
                }

                dispatchMouseClick(decor, decorX, decorY);
                android.util.Log.d(TAG, "clickAtCursor fallback mouse at " + decorX + "," + decorY);
            } catch (Throwable e) {
                android.util.Log.e(TAG, "clickAtCursor failed: " + e.getMessage());
            }
        });
    }

    private View findClickableAt(View view, float x, float y) {
        if (view == null || view != mCursorView && view.getVisibility() != View.VISIBLE) {
            return null;
        }

        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                if (child == mCursorView || child.getVisibility() != View.VISIBLE) {
                    continue;
                }
                float childX = x - child.getX();
                float childY = y - child.getY();
                if (childX >= 0 && childY >= 0 && childX < child.getWidth() && childY < child.getHeight()) {
                    View target = findClickableAt(child, childX, childY);
                    if (target != null) {
                        return target;
                    }
                }
            }
        }

        if (view != mCursorView && (view.isClickable() || view.isLongClickable())) {
            return view;
        }

        return null;
    }

    private void dispatchMouseClick(View decor, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        down.setSource(android.view.InputDevice.SOURCE_MOUSE);
        MotionEvent up = MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_UP, x, y, 0);
        up.setSource(android.view.InputDevice.SOURCE_MOUSE);
        boolean wasVisible = mCursorView != null && mCursorView.getVisibility() == View.VISIBLE;
        if (wasVisible) {
            mCursorView.setVisibility(View.INVISIBLE);
        }
        decor.dispatchTouchEvent(down);
        decor.dispatchTouchEvent(up);
        if (wasVisible) {
            mCursorView.setVisibility(View.VISIBLE);
        }
        down.recycle();
        up.recycle();
    }

    private void showCursorAndScheduleHide() {
        if (mCursorView == null) {
            return;
        }

        mCursorView.setVisibility(View.VISIBLE);
        mUiHandler.removeCallbacks(mHideCursorRunnable);
        mUiHandler.postDelayed(mHideCursorRunnable, CURSOR_AUTO_HIDE_MS);
    }
}
