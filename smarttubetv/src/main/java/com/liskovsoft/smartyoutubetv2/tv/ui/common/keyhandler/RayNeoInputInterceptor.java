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
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.tv.R;

import androidx.leanback.widget.HorizontalGridView;
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

    // MOD: bridge to the player fragment so the pad can drive player-only
    // actions (skip next/prev video, seek the scrubber, hide the cursor while
    // dim mode is up) without the keyhandler holding a direct fragment ref.
    public interface PlayerActionBridge {
        boolean isDimActive();
        void skipNext();
        void skipPrevious();
        void onControlInteraction();
        void play();
        void togglePlayback();
        /** Seek if (screenX,screenY) lands on the timeline; return true if handled. */
        boolean seekToScreenX(float screenX, float screenY);
        /** True if the screen point is inside the suggestion (thumbnail) rows. */
        boolean isInSuggestions(float screenX, float screenY);
        /** Close the player deterministically (used by double-tap to exit). */
        void exitPlayer();
        /** True when the related-videos gallery below the player is showing. */
        boolean isSuggestionsShown();
        /** Reveal the related-videos gallery below the player. */
        void showSuggestions();
    }

    private static volatile PlayerActionBridge sPlayerBridge;
    private static volatile RayNeoInputInterceptor sActiveCursorInstance;
    public static void setPlayerBridge(PlayerActionBridge bridge) { sPlayerBridge = bridge; }
    public static void clearPlayerBridge(PlayerActionBridge bridge) {
        if (sPlayerBridge == bridge) sPlayerBridge = null;
    }
    /** Called by the player when dim mode toggles, to hide/restore the pointer. */
    public static void onDimModeChanged(boolean dimActive) {
        RayNeoInputInterceptor inst = sActiveCursorInstance;
        if (inst != null) inst.applyDimCursor(dimActive);
    }

    /**
     * Pointer is the active model only while the player controls/video have
     * focus. Once the related-videos gallery is showing (scrolled below the
     * controls), fall back to plain D-pad scrolling like the home screen so each
     * swipe moves one item — no cursor. The reveal still works because at the
     * controls (row 0) this is true, so the edge-scroll runs.
     */
    private boolean cursorActive() {
        if (!mCursorMode) {
            return false;
        }
        PlayerActionBridge bridge = sPlayerBridge;
        return !(bridge != null && bridge.isSuggestionsShown());
    }

    // ===== Cursor calibration (temporary tuning aid) =====
    // When true, calibration walks the REAL player controls (play, CC, etc.):
    // each in turn gets a GREEN outline drawn in its own content space (so it
    // marks exactly where the control truly is). You aim the cursor at the
    // outlined control and click; we log how far the click landed from that
    // control's true centre (deltaScreen). Set false again after calibrating.
    private static final boolean CALIBRATION_MODE = false;
    private int mCalibIndex = 0;
    private java.util.List<View> mCalibControls;
    private android.graphics.drawable.GradientDrawable mCalibRing;
    private View mCalibHighlighted;

    private View mCursorView;
    private View mCursorHost;
    private float mCursorX = -1f;
    private float mCursorY = -1f;
    private float mCursorTargetX = -1f;
    private float mCursorTargetY = -1f;
    private boolean mCursorAnimating = false;
    private float mClickX = -1f;
    private float mClickY = -1f;
    private View mDownFocusView;
    private float mLastContX = 0f;
    private float mLastContY = 0f;
    private float mGestureMoveX = 0f;
    private float mGestureMoveY = 0f;
    private boolean mSuppressClickThisGesture;
    private long mSuppressClickUntilMs;
    private long mLastSwipeKeyTimeMs;
    private int mPendingSwipeKeyCode;
    private float mSwipeStartX;
    private float mSwipeStartY;
    private float mTouchDownX;
    private float mTouchDownY;
    private boolean mTitleSearchLocked = true;
    private int mHalfWidth = 0; // per-eye width (X3 Pro mirrors the left 640px)
    // Lower gain = steadier, slower pointer (user preference). Smoothing eases
    // the displayed cursor toward its target each animation frame so it never
    // lags-then-races when the pad's deltas arrive in bursts.
    private static final float CURSOR_GAIN = 0.55f;
    private static final float CURSOR_SMOOTHING = 0.35f;
    private static final float CLICK_CANCEL_MOVE_PX = 18f;
    private static final long CURSOR_AUTO_HIDE_MS = 3_000L;
    private static final float SUGGESTIONS_REVEAL_BOTTOM_ZONE_PX = 28f;
    // Edge-scroll: pushing the pointer past a screen edge drives the app's
    // built-in D-pad navigation, so off-screen content (related videos /
    // chapters below, side menus) stays reachable while in cursor mode.
    // Lower threshold + cooldown so a sustained downward pull steps through both
    // control rows to the suggestions quickly (was getting stuck on the controls).
    private static final float EDGE_SCROLL_THRESHOLD_PX = 50f;
    private static final long EDGE_SCROLL_COOLDOWN_MS = 170L;
    private float mEdgeAccumX = 0f;
    private float mEdgeAccumY = 0f;
    private long mLastEdgeScrollMs = 0L;
    private static final long SWIPE_CLICK_SUPPRESS_MS = 450L;
    private static final long SWIPE_NAV_COOLDOWN_MS = 320L;
    private static final long SEARCH_ORB_FOCUS_LATCH_MS = 8_000L;
    private long mLastCursorActivityMs;
    private long mSearchOrbFocusUntilMs;
    private final Runnable mHideCursorRunnable = new Runnable() {
        @Override
        public void run() {
            if (mCursorView == null) {
                return;
            }
            // Only hide once it's really been CURSOR_AUTO_HIDE_MS since the last
            // interaction; otherwise re-arm for the remaining time. This makes the
            // countdown immune to any missed/duplicate reschedules.
            long remaining = CURSOR_AUTO_HIDE_MS - (SystemClock.uptimeMillis() - mLastCursorActivityMs);
            if (remaining <= 0) {
                mCursorView.setVisibility(View.GONE);
            } else {
                mUiHandler.postDelayed(this, remaining);
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
        // Voice-first search on the glasses: use the embedded (offline) speech
        // recognizer (the system one needs Google services the RayNeo lacks) and
        // auto-start it the moment the search screen opens, so no system keyboard
        // (which can't render across both lenses) is ever needed. Settings persist
        // in prefs, so doing this on init is enough.
        try {
            com.liskovsoft.smartyoutubetv2.common.prefs.SearchData sd =
                    com.liskovsoft.smartyoutubetv2.common.prefs.SearchData.instance(activity);
            // Voice is handled by GeminiVoiceSearch (record + Gemini). Use the
            // GOTEV recognizer type so the search bar's mic click routes through
            // our callback (which calls Gemini), and disable instant voice so the
            // broken on-device recognizer never auto-fires.
            sd.setSpeechRecognizerType(com.liskovsoft.smartyoutubetv2.common.prefs.SearchData.SPEECH_RECOGNIZER_GOTEV);
            sd.setInstantVoiceSearchEnabled(false);
        } catch (Throwable e) {
            android.util.Log.e(TAG, "voice-first setup failed: " + e.getMessage());
        }
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
                mSwipeStartX = event.getX();
                mSwipeStartY = event.getY();
                mTouchDownX = event.getX();
                mTouchDownY = event.getY();
                // Snapshot where the pointer is aimed at the START of this
                // gesture, so a tap clicks there even if the tap jitters it.
                mClickX = mCursorX;
                mClickY = mCursorY;
                // Snapshot the focused view BEFORE the tap can flip the window
                // into touch mode (which clears focus / lets leanback re-pick the
                // content rows). A non-cursor tap activates THIS exact view, so
                // e.g. the search orb opens search instead of a video below it.
                mDownFocusView = mActivity.getWindow() != null
                        ? mActivity.getWindow().getCurrentFocus() : null;
                if (isContentFocus(mDownFocusView)) {
                    if (mTitleSearchLocked) {
                        android.util.Log.d(TAG, "title search lock cleared: content already focused");
                    }
                    mTitleSearchLocked = false;
                } else if (mTitleSearchLocked && getBrowseTitleSearchSection() != 0) {
                    requestTitleSearchFocus("touchDownLock");
                    mDownFocusView = findTitleSearchOrb();
                }
                mInjectedContinuousThisSwipe = false;
            }

            mTouchDispatcher.onMotionEvent(event, mTouchCallback);

            // Any temple-pad interaction (re)starts the pointer auto-hide
            // countdown, so the 3s always begins from the LAST interaction —
            // including the lift at the end of a gesture.
            if (mCursorMode) {
                showCursorAndScheduleHide();
            }

            if ((event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
                    && mPendingSwipeKeyCode != 0) {
                // Re-derive the direction from the WHOLE gesture's net
                // displacement (with a strong vertical bias) so an early or
                // ambiguous SDK classification can't turn a vertical swipe into
                // a left/right — which in a menu exits it.
                float gestureDx = event.getX() - mSwipeStartX;
                float gestureDy = event.getY() - mSwipeStartY;
                final int keyCode = directionKeyFromDisplacement(gestureDx, gestureDy, mPendingSwipeKeyCode);
                mPendingSwipeKeyCode = 0;
                updateTitleSearchLockForNavKey(keyCode);
                log("dispatch pending swipe after " + motionActionToString(event.getActionMasked())
                        + " key=" + keyToString(keyCode)
                        + " dx=" + gestureDx + " dy=" + gestureDy);
                mUiHandler.postDelayed(() -> {
                    if (moveFocusForNavigationKey(keyCode)) {
                        // Focus moved directly (e.g. a vertical settings list).
                        // Leave touch mode so the focus highlight actually DRAWS
                        // on the new item — otherwise the selection moves but the
                        // highlight only appears on the next swipe (which falls
                        // through to injectKeyEventAsync and clears touch mode).
                        exitTouchModeAsync();
                    } else {
                        injectNavKeyWithVerify(keyCode);
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

                if (cursorActive()) {
                    if (mSuppressClickThisGesture) {
                        android.util.Log.d(TAG, "onTPClick suppressed after cursor swipe");
                        return true;
                    }
                    if (CALIBRATION_MODE) {
                        android.util.Log.d(TAG, "onTPClick (calibration) - recording point");
                        recordCalibrationClick();
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
                log("callback onTPDoubleClick focus=" + describeFocus());
                // In the player, exit deterministically: a plain BACK only hides
                // the controls overlay when it's showing (which the first tap
                // often tickles up), so the video wouldn't close. Outside the
                // player, fall back to BACK.
                PlayerActionBridge bridge = sPlayerBridge;
                if (bridge != null) {
                    android.util.Log.d(TAG, "onTPDoubleClick -> exitPlayer");
                    bridge.exitPlayer();
                } else {
                    android.util.Log.d(TAG, "onTPDoubleClick -> BACK");
                    injectKeyEventAsync(KeyEvent.KEYCODE_BACK);
                }
                return true;
            }

            @Override
            public boolean onTPSlideForward(FlingArgs flingArgs) {
                log("callback onTPSlideForward injectedThisSwipe=" + mInjectedContinuousThisSwipe
                        + " focus=" + describeFocus());
                if (cursorActive()) {
                    // Only skip videos while dim caption mode is up; with the
                    // video visible a right swipe does nothing (no accidental skip).
                    PlayerActionBridge b = sPlayerBridge;
                    if (b != null && b.isDimActive()) {
                        b.skipNext();
                        mSuppressClickThisGesture = true;
                    }
                    return true;
                }
                if (!mInjectedContinuousThisSwipe) {
                    updateTitleSearchLockForNavKey(KeyEvent.KEYCODE_DPAD_RIGHT);
                    mInjectedContinuousThisSwipe = injectSwipeKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT);
                }
                return true;
            }

            @Override
            public boolean onTPSlideBackward(FlingArgs flingArgs) {
                log("callback onTPSlideBackward injectedThisSwipe=" + mInjectedContinuousThisSwipe
                        + " focus=" + describeFocus());
                if (cursorActive()) {
                    // Only skip videos while dim caption mode is up.
                    PlayerActionBridge b = sPlayerBridge;
                    if (b != null && b.isDimActive()) {
                        b.skipPrevious();
                        mSuppressClickThisGesture = true;
                    }
                    return true;
                }
                if (!mInjectedContinuousThisSwipe) {
                    updateTitleSearchLockForNavKey(KeyEvent.KEYCODE_DPAD_LEFT);
                    mInjectedContinuousThisSwipe = injectSwipeKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT);
                }
                return true;
            }

            @Override
            public boolean onTPSlideUpwards(FlingArgs flingArgs) {
                log("callback onTPSlideUpwards injectedThisSwipe=" + mInjectedContinuousThisSwipe
                        + " focus=" + describeFocus());
                if (cursorActive()) return true;
                if (!mInjectedContinuousThisSwipe) {
                    updateTitleSearchLockForNavKey(KeyEvent.KEYCODE_DPAD_UP);
                    mInjectedContinuousThisSwipe = injectSwipeKeyEvent(KeyEvent.KEYCODE_DPAD_UP);
                }
                return true;
            }

            @Override
            public boolean onTPSlideDownwards(FlingArgs flingArgs) {
                log("callback onTPSlideDownwards injectedThisSwipe=" + mInjectedContinuousThisSwipe
                        + " focus=" + describeFocus());
                PlayerActionBridge bridge = sPlayerBridge;
                if (cursorActive() && bridge != null && isCursorAtBottomRevealEdge()) {
                    log("down swipe at bottom edge -> show suggestions");
                    bridge.showSuggestions();
                    mSuppressClickThisGesture = true;
                    return true;
                }
                if (cursorActive()) return true;
                if (!mInjectedContinuousThisSwipe) {
                    updateTitleSearchLockForNavKey(KeyEvent.KEYCODE_DPAD_DOWN);
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
                if (cursorActive()) {
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

    /** True if the view (or an ancestor) is a leanback search orb. */
    private boolean isSearchOrb(View v) {
        View cur = v;
        while (cur != null) {
            if (cur.getId() == R.id.title_orb) {
                return true;
            }
            if (cur.getClass().getName().contains("SearchOrb")) {
                return true;
            }
            ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    /** True if the view (or an ancestor) is leanback's SearchBar (search text field + magnifying orb). */
    private boolean isInsideSearchBar(View v) {
        View cur = v;
        while (cur != null) {
            if (cur.getClass().getName().contains("SearchBar")) {
                return true;
            }
            ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    /** True if the view (or an ancestor) is the leanback speech (mic) orb. */
    private boolean isSpeechOrb(View v) {
        View cur = v;
        while (cur != null) {
            if (cur.getClass().getName().contains("SpeechOrb")) {
                return true;
            }
            ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    /** True if the view (or an ancestor) has the given id. */
    private boolean isInsideViewId(View v, int id) {
        View cur = v;
        while (cur != null) {
            if (cur.getId() == id) {
                return true;
            }
            ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    private View findTitleSearchOrb() {
        if (mActivity.getWindow() == null) {
            return null;
        }

        View decor = mActivity.getWindow().getDecorView();
        return decor != null ? decor.findViewById(R.id.title_orb) : null;
    }

    private boolean isTitleSearchOrbActive(View target) {
        View orb = findTitleSearchOrb();
        if (orb == null || !orb.isShown()) {
            return false;
        }

        boolean latched = SystemClock.uptimeMillis() < mSearchOrbFocusUntilMs;
        boolean active = isDescendantOf(target, orb)
                || latched
                || orb.isFocused()
                || orb.hasFocus()
                || orb.isSelected()
                || orb.isActivated()
                || orb.getScaleX() > 1.01f
                || orb.getScaleY() > 1.01f;

        if (active) {
            android.util.Log.d(TAG, "title search orb active: target="
                    + describeViewForLog(target)
                    + " focused=" + orb.isFocused()
                    + " hasFocus=" + orb.hasFocus()
                    + " selected=" + orb.isSelected()
                    + " activated=" + orb.isActivated()
                    + " latched=" + latched
                    + " scale=" + orb.getScaleX() + "," + orb.getScaleY());
        }

        return active;
    }

    /**
     * True if the search-bar magnifying-glass orb is the visually-focused control, even when
     * Android focus or the cursor has drifted to a result tile below it. On the results screen
     * there's no cursor, so a tap resolves to whatever is focused; without this, a tap aimed at
     * the orb could fall through and play the video below. The leanback orb scales up only while
     * focused, so a >1.0 scale is a reliable "the orb is the active control" signal.
     */
    private boolean isSearchBarSearchOrbActive() {
        if (mActivity.getWindow() == null) {
            return false;
        }
        View decor = mActivity.getWindow().getDecorView();
        View orb = decor != null ? decor.findViewById(R.id.lb_search_bar_search_orb) : null;
        if (orb == null || !orb.isShown()) {
            return false;
        }
        boolean active = orb.isFocused() || orb.hasFocus() || orb.isSelected() || orb.isActivated()
                || orb.getScaleX() > 1.01f || orb.getScaleY() > 1.01f;
        if (active) {
            android.util.Log.d(TAG, "search-bar orb active: focused=" + orb.isFocused()
                    + " hasFocus=" + orb.hasFocus()
                    + " selected=" + orb.isSelected()
                    + " activated=" + orb.isActivated()
                    + " scale=" + orb.getScaleX() + "," + orb.getScaleY());
        }
        return active;
    }

    private boolean isDescendantOf(View child, View ancestor) {
        View cur = child;
        while (cur != null) {
            if (cur == ancestor) {
                return true;
            }
            ViewParent parent = cur.getParent();
            cur = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private void rememberTitleSearchOrbFocus(String reason) {
        try {
            View focus = mActivity.getWindow() != null ? mActivity.getWindow().getCurrentFocus() : null;
            View orb = findTitleSearchOrb();
            boolean active = orb != null && orb.isShown()
                    && (isSearchOrb(focus) || orb.isFocused() || orb.hasFocus()
                    || orb.isSelected() || orb.isActivated()
                    || orb.getScaleX() > 1.01f || orb.getScaleY() > 1.01f);
            if (active) {
                mSearchOrbFocusUntilMs = SystemClock.uptimeMillis() + SEARCH_ORB_FOCUS_LATCH_MS;
                android.util.Log.d(TAG, "latched title search orb focus reason=" + reason
                        + " until=" + mSearchOrbFocusUntilMs
                        + " focus=" + describeViewForLog(focus)
                        + " orbFocus=" + orb.isFocused()
                        + " orbHasFocus=" + orb.hasFocus()
                        + " orbScale=" + orb.getScaleX() + "," + orb.getScaleY());
            } else if (reason != null && reason.startsWith("keyAfterSend")) {
                mSearchOrbFocusUntilMs = 0L;
            }
        } catch (Throwable ignored) {
        }
    }

    private String describeViewForLog(View view) {
        if (view == null) {
            return "null";
        }

        return view.getClass().getSimpleName()
                + "#" + view.getId()
                + (view.getContentDescription() != null ? "[" + view.getContentDescription() + "]" : "");
    }

    private void injectClick() {
        mActivity.runOnUiThread(() -> {
            try {
                int touchTitleSearchSection = getTouchTitleSearchSection();
                if (touchTitleSearchSection != 0) {
                    mTitleSearchLocked = true;
                    requestTitleSearchFocus("injectClickTouchZone");
                    if (touchTitleSearchSection == 2) {
                        android.util.Log.d(TAG, "injectClick -> Gemini voice search (settings title touch zone)");
                        GeminiVoiceSearch.start(mActivity);
                    } else {
                        android.util.Log.d(TAG, "injectClick -> Gemini voice search on Home (title touch zone)");
                        GeminiVoiceSearch.startOnHome(mActivity);
                    }
                    return;
                }

                int lockedTitleSearchSection = mTitleSearchLocked
                        && isTitleSearchOrbActive(mDownFocusView)
                        ? getBrowseTitleSearchSection() : 0;
                if (lockedTitleSearchSection != 0) {
                    requestTitleSearchFocus("injectClickLock");
                    if (lockedTitleSearchSection == 2) {
                        android.util.Log.d(TAG, "injectClick -> Gemini voice search (locked settings title search)");
                        GeminiVoiceSearch.start(mActivity);
                    } else {
                        android.util.Log.d(TAG, "injectClick -> Gemini voice search on Home (locked title search)");
                        GeminiVoiceSearch.startOnHome(mActivity);
                    }
                    return;
                }

                final View focus = mDownFocusView != null ? mDownFocusView
                        : (mActivity.getWindow() != null ? mActivity.getWindow().getCurrentFocus() : null);
                // Cursor clicks must hit-test by pointer position first. Focus
                // can remain on the text/results while the pointer is visibly on
                // the mic, which was causing old query submission.
                final View hit = findClickableAtCursor();
                final View target = hit != null ? hit : focus;
                android.util.Log.d(TAG, "injectClick target hit=" + describeViewForLog(hit)
                        + " focus=" + describeViewForLog(focus)
                        + " chosen=" + describeViewForLog(target)
                        + " cursor=" + mClickX + "," + mClickY);

                // Home/settings top search orb: reserve the top-left search band
                // before any focused/captured card is allowed to click. On RayNeo
                // the visual pointer can be on the orb while Android focus has
                // already jumped to the first video card below.
                int titleSearchMode = getBrowseTitleSearchMode(target);
                if (titleSearchMode != 0) {
                    if (titleSearchMode == 2) {
                        android.util.Log.d(TAG, "injectClick -> Gemini voice search (settings title search orb)");
                        GeminiVoiceSearch.start(mActivity);
                    } else {
                        android.util.Log.d(TAG, "injectClick -> Gemini voice search on Home (title search orb)");
                        GeminiVoiceSearch.startOnHome(mActivity);
                    }
                    return;
                }

                // On the search results screen, the right magnifying-glass orb is
                // the "record a new Gemini search" control. Detect it by BOTH the
                // cursor (player) and the focused view (search screen has no
                // cursor, so the cursor check alone never fired there).
                if (isCursorInsideViewId(R.id.lb_search_bar_search_orb)
                        || isInsideViewId(target, R.id.lb_search_bar_search_orb)
                        || isSearchBarSearchOrbActive()) {
                    android.util.Log.d(TAG, "injectClick -> Gemini voice search (search magnifying glass)");
                    GeminiVoiceSearch.start(mActivity);
                    return;
                }

                // On the search results screen, focus stays in the search bar until the user swipes
                // down into the results. So ANY tap while focus is inside the SearchBar (text field
                // or orb) starts a new voice search; taps once focus has moved into the results below
                // fall through and play the focused video. This is the reliable "stays on the search
                // button unless you swiped down" rule — it doesn't depend on flaky orb focus/scale.
                if (isInsideSearchBar(target) || isInsideSearchBar(focus)) {
                    android.util.Log.d(TAG, "injectClick -> Gemini voice search (focus in search bar)");
                    GeminiVoiceSearch.start(mActivity);
                    return;
                }

                // Legacy fallback for layouts that still expose a speech orb.
                if (isSpeechOrb(target)) {
                    android.util.Log.d(TAG, "injectClick -> Gemini voice search (speech orb)");
                    GeminiVoiceSearch.start(mActivity);
                    return;
                }

                // Search text field is display-only on the glasses. On the search screen a tap on
                // it should start a NEW voice search (rather than do nothing); elsewhere, ignore it.
                if (target instanceof android.widget.EditText) {
                    View searchDecor = mActivity.getWindow() != null ? mActivity.getWindow().getDecorView() : null;
                    if (searchDecor != null && searchDecor.findViewById(R.id.lb_search_bar_search_orb) != null) {
                        android.util.Log.d(TAG, "injectClick -> Gemini voice search (search text field)");
                        GeminiVoiceSearch.start(mActivity);
                    } else {
                        android.util.Log.d(TAG, "injectClick ignored search text display");
                    }
                    return;
                }

                if (target != null && target.isShown()) {
                    android.util.Log.d(TAG, "injectClick performClick captured="
                            + target.getClass().getSimpleName()
                            + (target.getContentDescription() != null ? "[" + target.getContentDescription() + "]" : ""));
                    if (target.performClick()) {
                        return;
                    }
                    // Some views need a real key; refocus and center on it.
                    target.requestFocus();
                    injectKeyEventAsync(KeyEvent.KEYCODE_DPAD_CENTER, null, true);
                    return;
                }
                android.util.Log.d(TAG, "injectClick -> DPAD_CENTER (no captured focus)");
                injectKeyEventAsync(KeyEvent.KEYCODE_DPAD_CENTER, null, true);
            } catch (Throwable e) {
                android.util.Log.e(TAG, "injectClick failed: " + e.getMessage());
                injectKeyEventAsync(KeyEvent.KEYCODE_DPAD_CENTER, null, true);
            }
        });
    }

    private float[] getCursorDecorPoint() {
        if (mActivity.getWindow() == null) {
            return null;
        }

        View decor = mActivity.getWindow().getDecorView();
        if (decor == null) {
            return null;
        }

        float cx = mClickX >= 0f ? mClickX : mCursorX;
        float cy = mClickY >= 0f ? mClickY : mCursorY;
        if (cx < 0f || cy < 0f) {
            return null;
        }

        if (mHalfWidth > 0) {
            cx = Math.max(0f, Math.min(mHalfWidth - 2f, cx));
        }

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

        return new float[] {decorX, decorY};
    }

    private float[] getCursorScreenPoint() {
        float cx = mClickX >= 0f ? mClickX : mCursorX;
        float cy = mClickY >= 0f ? mClickY : mCursorY;
        if (cx < 0f || cy < 0f) {
            return null;
        }

        if (mHalfWidth > 0) {
            cx = Math.max(0f, Math.min(mHalfWidth - 2f, cx));
        }

        if (mCursorHost != null) {
            int[] hostLoc = new int[2];
            mCursorHost.getLocationOnScreen(hostLoc);
            return new float[] {hostLoc[0] + cx, hostLoc[1] + cy};
        }

        View decor = mActivity.getWindow() != null ? mActivity.getWindow().getDecorView() : null;
        if (decor != null) {
            int[] decorLoc = new int[2];
            decor.getLocationOnScreen(decorLoc);
            return new float[] {decorLoc[0] + cx, decorLoc[1] + cy};
        }

        return new float[] {cx, cy};
    }

    private boolean isCursorInsideViewId(int viewId) {
        if (mActivity.getWindow() == null) {
            return false;
        }

        View decor = mActivity.getWindow().getDecorView();
        View target = decor != null ? decor.findViewById(viewId) : null;
        float[] point = getCursorScreenPoint();
        if (target == null || !target.isShown() || point == null) {
            return false;
        }

        Rect rect = new Rect();
        if (!target.getGlobalVisibleRect(rect)) {
            return false;
        }

        boolean inside = rect.contains(Math.round(point[0]), Math.round(point[1]));
        if (inside) {
            android.util.Log.d(TAG, "cursor inside "
                    + describeViewForLog(target)
                    + " screenRect=" + rect
                    + " screenPoint=" + point[0] + "," + point[1]);
        }
        return inside;
    }

    /**
     * Returns 1 for Home top search, 2 for Settings top search, or 0 when the
     * current click should fall through. This is intentionally based on a
     * reserved screen band plus the real title_orb bounds, not on current focus.
     */
    private int getBrowseTitleSearchMode(View target) {
        int section = getBrowseTitleSearchSection();
        if (section == 0) {
            return 0;
        }

        View orb = findTitleSearchOrb();
        boolean onOrb = isCursorInsideViewId(R.id.title_orb) || isDescendantOf(target, orb);
        boolean inBand = isCursorInTitleSearchBand();
        if (onOrb || inBand) {
            android.util.Log.d(TAG, "title search guard hit mode=" + (section == 2 ? "settings" : "home")
                    + " onOrb=" + onOrb
                    + " inBand=" + inBand
                    + " target=" + describeViewForLog(target));
            return section;
        }
        return 0;
    }

    private int getBrowseTitleSearchSection() {
        View orb = findTitleSearchOrb();
        if (orb == null || !orb.isShown()) {
            return 0;
        }

        try {
            BrowsePresenter browse = BrowsePresenter.instance(mActivity);
            if (browse.isSettingsSection()) {
                return 2;
            }
            if (browse.isHomeSection()) {
                return 1;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private int getTouchTitleSearchSection() {
        int section = getBrowseTitleSearchSection();
        if (section == 0) {
            return 0;
        }

        // RayNeo Home taps often have no cursor coordinates and focus may still
        // be on a video card. Use a calibrated touch-pad area around the actual
        // title search tap, not a broad top-left band that can catch video rows.
        boolean inTopSearchBand = mTouchDownY >= 130f && mTouchDownY <= 225f
                && mTouchDownX >= 520f && mTouchDownX <= 835f;
        if (inTopSearchBand) {
            android.util.Log.d(TAG, "title search touch zone hit section=" + section
                    + " down=" + mTouchDownX + "," + mTouchDownY
                    + " focus=" + describeFocus());
            return section;
        }
        return 0;
    }

    private void requestTitleSearchFocus(String reason) {
        try {
            View orb = findTitleSearchOrb();
            if (orb != null && orb.isShown()) {
                orb.setFocusable(true);
                orb.setFocusableInTouchMode(true);
                orb.requestFocus();
                mSearchOrbFocusUntilMs = SystemClock.uptimeMillis() + SEARCH_ORB_FOCUS_LATCH_MS;
                android.util.Log.d(TAG, "title search focus lock reason=" + reason
                        + " section=" + getBrowseTitleSearchSection()
                        + " focus=" + describeFocus());
            }
        } catch (Throwable e) {
            android.util.Log.d(TAG, "title search focus lock failed reason=" + reason
                    + " error=" + e.getMessage());
        }
    }

    private void updateTitleSearchLockForNavKey(int keyCode) {
        int section = getBrowseTitleSearchSection();
        if (section == 0) {
            return;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (mTitleSearchLocked) {
                android.util.Log.d(TAG, "title search unlocked by explicit DOWN swipe");
            }
            mTitleSearchLocked = false;
            return;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            // When the user navigates back upward to the top controls, re-arm the
            // lock so a tap on the top search area cannot fall through to row 1.
            View orb = findTitleSearchOrb();
            View focus = mActivity.getWindow() != null ? mActivity.getWindow().getCurrentFocus() : null;
            if (orb != null && (isDescendantOf(focus, orb) || orb.isFocused() || orb.hasFocus())) {
                mTitleSearchLocked = true;
                requestTitleSearchFocus("upToTitleSearch");
            }
        }
    }

    private boolean isCursorInTitleSearchBand() {
        if (mActivity.getWindow() == null) {
            return false;
        }

        View decor = mActivity.getWindow().getDecorView();
        float[] point = getCursorScreenPoint();
        if (decor == null || point == null) {
            return false;
        }

        Rect decorRect = new Rect();
        if (!decor.getGlobalVisibleRect(decorRect)) {
            return false;
        }

        View orb = decor.findViewById(R.id.title_orb);
        if (orb != null && orb.isShown()) {
            Rect orbRect = new Rect();
            if (orb.getGlobalVisibleRect(orbRect)) {
                int padX = Math.max(24, orbRect.width() / 2);
                int padY = Math.max(18, orbRect.height() / 2);
                orbRect.inset(-padX, -padY);
                if (orbRect.contains(Math.round(point[0]), Math.round(point[1]))) {
                    android.util.Log.d(TAG, "cursor in expanded title_orb band rect=" + orbRect
                            + " point=" + point[0] + "," + point[1]);
                    return true;
                }
            }
        }

        float w = decorRect.width();
        float h = decorRect.height();
        float x = point[0] - decorRect.left;
        float y = point[1] - decorRect.top;
        boolean inside = x >= w * 0.16f && x <= w * 0.31f && y >= 0 && y <= h * 0.26f;
        if (inside) {
            android.util.Log.d(TAG, "cursor in fallback title search band decor=" + decorRect
                    + " point=" + point[0] + "," + point[1]);
        }
        return inside;
    }

    private View findClickableAtCursor() {
        if (mActivity.getWindow() == null) {
            return null;
        }

        View decor = mActivity.getWindow().getDecorView();
        if (decor == null) {
            return null;
        }

        float[] point = getCursorDecorPoint();
        if (point == null) {
            return null;
        }

        float decorX = point[0];
        float decorY = point[1];
        return findClickableAt(decor, decorX, decorY);
    }

    private void injectKeyEventAsync(int keyCode) {
        injectKeyEventAsync(keyCode, null, false);
    }

    private void injectKeyEventAsync(int keyCode, final Runnable afterSent) {
        injectKeyEventAsync(keyCode, afterSent, false);
    }

    private void injectKeyEventAsync(int keyCode, final Runnable afterSent, final boolean preserveFocus) {
        log("queue key=" + keyToString(keyCode) + " focus=" + describeFocus());
        sInputExecutor.execute(() -> {
            if (mActivity.isFinishing() || mActivity.isDestroyed()) {
                log("drop key: activity finishing/destroyed key=" + keyToString(keyCode));
                return;
            }

            try {
                // Ensure focus highlight is visible before injecting. Skip when
                // preserving the current selection (e.g. activating the focused
                // search orb): requestFocus on the decor would grab the first
                // content item (a video) and play it instead.
                if (!preserveFocus) {
                    ensureFocus();
                }

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

                // Send exactly ONE key here. The "first key wasted exiting touch
                // mode vs. first key navigates" ambiguity is resolved by the
                // caller (injectNavKeyWithVerify): it checks whether the list's
                // selected position actually moved and only re-sends if it
                // didn't — so we never blindly double-send and skip an item.
                long now = SystemClock.uptimeMillis();
                KeyEvent downEvent = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0,
                        android.view.InputDevice.SOURCE_KEYBOARD);
                KeyEvent upEvent = new KeyEvent(now, now + 10, KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, 0,
                        android.view.InputDevice.SOURCE_KEYBOARD);
                inst.sendKeySync(downEvent);
                inst.sendKeySync(upEvent);
                log("sent instrumentation key=" + keyToString(keyCode)
                        + " focusAfterSend=" + describeFocus());
                if (isDpadNavigationKey(keyCode)) {
                    mActivity.runOnUiThread(() ->
                            rememberTitleSearchOrbFocus("keyAfterSend:" + keyToString(keyCode)));
                }
                
                // SmartTube dialogs/menus sometimes prefer ENTER over DPAD_CENTER
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    inst.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
                }
                // Notify the caller (e.g. the nav verifier) that the key has
                // ACTUALLY been injected — so it can check whether the move
                // happened instead of guessing a fixed delay.
                if (afterSent != null) {
                    mActivity.runOnUiThread(afterSent);
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
                    if (isDpadNavigationKey(keyCode)) {
                        rememberTitleSearchOrbFocus("keyAfterSendFallback:" + keyToString(keyCode));
                    }
                });
            }
        });
    }

    /** Send a nav key, then verify the list's selected position actually moved;
     *  if it didn't (the first key was consumed leaving touch mode), send once
     *  more. Avoids both "didn't move" (menu) and "moved twice / skipped a row"
     *  (video rows) — the two screens consume the first key differently. */
    private void injectNavKeyWithVerify(final int keyCode) {
        if (!isDpadNavigationKey(keyCode)) {
            injectKeyEventAsync(keyCode);
            return;
        }
        final int before = navGridSelectedPosition(keyCode);
        // Verify only AFTER the key is actually injected (the injection waits on
        // a touch-mode poll, so a fixed timer fires too early and always resends
        // → skipped rows). The callback runs once the key has been sent; give
        // leanback a brief moment to update the selection, then check.
        injectKeyEventAsync(keyCode, () -> mUiHandler.postDelayed(() -> {
            int after = navGridSelectedPosition(keyCode);
            if (before != Integer.MIN_VALUE && after == before) {
                log("nav verify: selection unchanged (pos=" + before + "), resending " + keyToString(keyCode));
                injectKeyEventAsync(keyCode);
            } else {
                log("nav verify: moved " + before + " -> " + after + " key=" + keyToString(keyCode));
            }
        }, 90L));
    }

    /** Selected position of the grid holding the current focus — used to detect whether a nav
     *  key actually moved. For LEFT/RIGHT it reads the inner HorizontalGridView (the row), since
     *  a sideways move changes the position WITHIN the row, not the outer VerticalGridView's row
     *  index. Reading the vertical grid for a horizontal key would always look "unchanged" and
     *  trigger a resend → every gallery swipe skips an item. */
    private int navGridSelectedPosition(int keyCode) {
        if (mActivity.getWindow() == null) {
            return Integer.MIN_VALUE;
        }
        View focus = mActivity.getWindow().getCurrentFocus();
        View decor = mActivity.getWindow().getDecorView();
        if (focus == null && decor != null) {
            focus = decor.findFocus();
        }

        boolean horizontal = keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (horizontal) {
            HorizontalGridView row = focus != null ? findAncestorHorizontalGridView(focus) : null;
            return row != null ? row.getSelectedPosition() : Integer.MIN_VALUE;
        }

        VerticalGridView grid = focus != null ? findAncestorVerticalGridView(focus) : null;
        if (grid == null) {
            grid = findVisibleVerticalGridView(decor);
        }
        return grid != null ? grid.getSelectedPosition() : Integer.MIN_VALUE;
    }

    private HorizontalGridView findAncestorHorizontalGridView(View view) {
        View current = view;
        while (current != null) {
            if (current instanceof HorizontalGridView) {
                return (HorizontalGridView) current;
            }

            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }

        return null;
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

    /** Nearest focusable view to the left/right of {@code current} on a similar row. */
    private View findNearestFocusableHorizontal(View current, int direction) {
        if (mActivity.getWindow() == null || mActivity.getWindow().getDecorView() == null) {
            return null;
        }
        int[] cl = new int[2];
        current.getLocationOnScreen(cl);
        float ccx = cl[0] + current.getWidth() / 2f;
        float ccy = cl[1] + current.getHeight() / 2f;
        java.util.List<View> focusables = new java.util.ArrayList<>();
        collectFocusables(mActivity.getWindow().getDecorView(), focusables);
        View best = null;
        float bestDist = Float.MAX_VALUE;
        for (View v : focusables) {
            if (v == current) {
                continue;
            }
            int[] vl = new int[2];
            v.getLocationOnScreen(vl);
            float vx = vl[0] + v.getWidth() / 2f;
            float vy = vl[1] + v.getHeight() / 2f;
            boolean rightWay = direction == View.FOCUS_LEFT ? vx < ccx - 4f : vx > ccx + 4f;
            if (!rightWay) {
                continue;
            }
            // Keep to roughly the same row as the search bar.
            if (Math.abs(vy - ccy) > Math.max(current.getHeight(), v.getHeight()) + 8f) {
                continue;
            }
            float dist = Math.abs(vx - ccx) + Math.abs(vy - ccy) * 0.5f;
            if (dist < bestDist) {
                bestDist = dist;
                best = v;
            }
        }
        return best;
    }

    private void collectFocusables(View v, java.util.List<View> out) {
        if (v == null || v.getVisibility() != View.VISIBLE || v == mCursorView) {
            return;
        }
        if (v.isFocusable() && v.isShown() && v.getWidth() > 0 && v.getHeight() > 0) {
            out.add(v);
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectFocusables(g.getChildAt(i), out);
            }
        }
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

        if (mTitleSearchLocked
                && getBrowseTitleSearchSection() != 0
                && (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT)
                && !isContentFocus(current)) {
            requestTitleSearchFocus("consumeHorizontalWhileTitleLocked");
            log("title search lock consumed horizontal key=" + keyToString(keyCode));
            return true;
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

        // Horizontal moves inside a gallery row must never fall through to the
        // nav drawer. Move exactly one item ourselves; at the first item, consume
        // LEFT instead of letting leanback open the main menu.
        if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) {
            if (current != null && moveHorizontalGridSelection(current, direction)) {
                return true;
            }
            if (direction == View.FOCUS_RIGHT && moveFocusOutOfLeftMenu()) {
                return true;
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
            // The search EditText swallows LEFT/RIGHT for its text caret, so
            // focusSearch returns null or itself and you can't reach the mic /
            // settings / submit. Fall back to the nearest focusable control in
            // that direction.
            if ((direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT)
                    && (target == null || target == current)
                    && current instanceof android.widget.EditText) {
                target = findNearestFocusableHorizontal(current, direction);
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

    private boolean isContentFocus(View view) {
        if (view == null) {
            return false;
        }
        if (isSearchOrb(view) || isInsideSearchBar(view) || isSpeechOrb(view)) {
            return false;
        }
        return findAncestorHorizontalGridView(view) != null
                || findAncestorVerticalGridView(view) != null
                || view instanceof HorizontalGridView
                || view instanceof VerticalGridView;
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

    private boolean moveHorizontalGridSelection(View current, int direction) {
        if (direction != View.FOCUS_LEFT && direction != View.FOCUS_RIGHT) {
            return false;
        }

        HorizontalGridView row = findAncestorHorizontalGridView(current);
        if (row == null || row.getAdapter() == null || row.getAdapter().getItemCount() == 0) {
            return false;
        }

        int position = row.getSelectedPosition();
        if (position < 0) {
            position = findChildAdapterPosition(row, current);
        }
        if (position < 0) {
            log("horizontal row selection failed: no adapter position current=" + describeView(current));
            return true;
        }

        int nextPosition = direction == View.FOCUS_RIGHT ? position + 1 : position - 1;
        int itemCount = row.getAdapter().getItemCount();
        if (nextPosition < 0 || nextPosition >= itemCount) {
            log("horizontal row edge consumed position=" + position
                    + " next=" + nextPosition
                    + " count=" + itemCount
                    + " direction=" + direction);
            return true;
        }

        log("horizontal row selection move position=" + position
                + " next=" + nextPosition
                + " direction=" + direction
                + " row=" + describeView(row));
        row.setSelectedPosition(nextPosition);
        focusSelectedHorizontalChild(row, nextPosition, direction, 0);
        return true;
    }

    private boolean moveFocusOutOfLeftMenu() {
        if (mActivity.getWindow() == null) {
            return false;
        }

        View decor = mActivity.getWindow().getDecorView();
        View focus = mActivity.getWindow().getCurrentFocus();
        if (decor == null || focus == null || findAncestorHorizontalGridView(focus) != null) {
            return false;
        }

        Rect decorRect = new Rect();
        Rect focusRect = new Rect();
        if (!decor.getGlobalVisibleRect(decorRect) || !focus.getGlobalVisibleRect(focusRect)) {
            return false;
        }
        if (focusRect.centerX() > decorRect.left + decorRect.width() * 0.42f) {
            return false;
        }

        HorizontalGridView row = findVisibleHorizontalGridView(decor);
        if (row == null || row.getAdapter() == null || row.getAdapter().getItemCount() == 0) {
            return false;
        }

        int position = Math.max(0, row.getSelectedPosition());
        log("right swipe exits left menu to content row position=" + position
                + " row=" + describeView(row)
                + " focusBefore=" + describeFocus());
        row.setSelectedPosition(position);
        focusSelectedHorizontalChild(row, position, View.FOCUS_RIGHT, 0);
        return true;
    }

    private HorizontalGridView findVisibleHorizontalGridView(View root) {
        if (root == null || !root.isShown()) {
            return null;
        }
        if (root instanceof HorizontalGridView) {
            HorizontalGridView row = (HorizontalGridView) root;
            if (row.getAdapter() != null && row.getAdapter().getItemCount() > 0) {
                return row;
            }
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                HorizontalGridView found = findVisibleHorizontalGridView(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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

    private int findChildAdapterPosition(RecyclerView grid, View current) {
        View view = current;
        while (view != null && view.getParent() != grid) {
            ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }

        return view != null ? grid.getChildAdapterPosition(view) : -1;
    }

    private void focusSelectedHorizontalChild(HorizontalGridView row, int position, int direction, int attempt) {
        row.postDelayed(() -> {
            if (mActivity.isFinishing() || mActivity.isDestroyed()) {
                return;
            }

            exitTouchModeAsync();

            RecyclerView.ViewHolder viewHolder = row.findViewHolderForAdapterPosition(position);
            View itemView = viewHolder != null ? viewHolder.itemView : null;
            if (itemView != null && itemView.isShown()) {
                boolean focused = itemView.requestFocus(direction);
                if (!focused) {
                    focused = itemView.requestFocus();
                }
                if (focused) {
                    log("horizontal row focus selected position=" + position
                            + " attempt=" + attempt
                            + " focused=true item=" + describeView(itemView)
                            + " focusAfter=" + describeFocus());
                    return;
                }
            }

            if (attempt < 4) {
                log("horizontal row focus retry position=" + position
                        + " attempt=" + attempt
                        + " holder=" + (viewHolder != null)
                        + " item=" + describeView(itemView));
                focusSelectedHorizontalChild(row, position, direction, attempt + 1);
            } else {
                log("horizontal row focus failed position=" + position
                        + " holder=" + (viewHolder != null)
                        + " item=" + describeView(itemView));
            }
        }, attempt == 0 ? 35L : 55L);
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

    /** Choose the DPAD direction from a gesture's net displacement, biased
     *  toward vertical so a mostly-vertical swipe is never read as left/right
     *  (which exits menus). Only a clearly sideways swipe counts as horizontal. */
    private static int directionKeyFromDisplacement(float dx, float dy, int fallback) {
        float adx = Math.abs(dx);
        float ady = Math.abs(dy);
        if (adx < 1f && ady < 1f) {
            return fallback; // no usable displacement — trust the SDK's guess
        }
        if (adx > ady * 1.6f) {
            return dx < 0f ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
        }
        return dy < 0f ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN;
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
        if (enabled) sActiveCursorInstance = this;
        else if (sActiveCursorInstance == this) sActiveCursorInstance = null;
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
            if (CALIBRATION_MODE) {
                // Controls may not be laid out yet; start once they're up.
                mUiHandler.postDelayed(this::startCalibration, 1500);
            }
        } catch (Throwable e) {
            android.util.Log.e(TAG, "ensureCursorView failed: " + e.getMessage());
        }
    }

    /** Collect the real, labelled player controls (play, CC, settings, ...). */
    private java.util.List<View> collectControls() {
        java.util.List<View> out = new java.util.ArrayList<>();
        View decor = mActivity.getWindow() != null ? mActivity.getWindow().getDecorView() : null;
        if (decor != null) {
            collectControlsRec(decor, out);
            java.util.Collections.sort(out, (a, b) -> {
                int[] la = new int[2];
                int[] lb = new int[2];
                a.getLocationOnScreen(la);
                b.getLocationOnScreen(lb);
                if (Math.abs(la[1] - lb[1]) > 12) return Integer.compare(la[1], lb[1]);
                return Integer.compare(la[0], lb[0]);
            });
        }
        return out;
    }

    private void collectControlsRec(View v, java.util.List<View> out) {
        if (v == null || v.getVisibility() != View.VISIBLE || v == mCursorView) {
            return;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectControlsRec(g.getChildAt(i), out);
            }
        }
        CharSequence desc = v.getContentDescription();
        // Labelled, tappable, button-sized => a real control (not a card/background).
        if ((v.isClickable() || v.isFocusable()) && desc != null && desc.length() > 0
                && v.getWidth() > 0 && v.getHeight() > 0
                && v.getWidth() < (mHalfWidth > 0 ? mHalfWidth : 640)) {
            out.add(v);
        }
    }

    /** (Re)gather the controls and outline the first one to aim at. */
    private void startCalibration() {
        mCalibControls = collectControls();
        mCalibIndex = 0;
        if (mCalibControls.isEmpty()) {
            android.util.Log.d(TAG, "CALIB: no controls visible yet — move the pad to bring up the "
                    + "player controls, then click once to (re)start calibration.");
            return;
        }
        android.util.Log.d(TAG, "CALIB START: " + mCalibControls.size()
                + " controls. Aim the cursor at the GREEN-outlined control and click.");
        highlightCalibControl(0);
    }

    /** Draw a green outline on control i, in the control's own coordinate space. */
    private void highlightCalibControl(int i) {
        if (mCalibControls == null || i < 0 || i >= mCalibControls.size()) {
            return;
        }
        if (mCalibHighlighted != null && mCalibRing != null) {
            mCalibHighlighted.getOverlay().remove(mCalibRing);
        }
        View c = mCalibControls.get(i);
        if (mCalibRing == null) {
            float density = mActivity.getResources().getDisplayMetrics().density;
            mCalibRing = new android.graphics.drawable.GradientDrawable();
            mCalibRing.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            mCalibRing.setStroke(Math.round(density * 3f), 0xFF00FF00);
        }
        mCalibRing.setBounds(0, 0, c.getWidth(), c.getHeight());
        c.getOverlay().add(mCalibRing);
        mCalibHighlighted = c;
        int[] loc = new int[2];
        c.getLocationOnScreen(loc);
        android.util.Log.d(TAG, "CALIB target " + (i + 1) + "/" + mCalibControls.size()
                + " desc=" + c.getContentDescription()
                + " centerScreen=(" + (loc[0] + c.getWidth() / 2f) + "," + (loc[1] + c.getHeight() / 2f) + ")"
                + " size=" + c.getWidth() + "x" + c.getHeight());
    }

    /** Log how far the click landed from the outlined control's true centre. */
    private void recordCalibrationClick() {
        mActivity.runOnUiThread(() -> {
            try {
                if (mCalibControls == null || mCalibControls.isEmpty()) {
                    startCalibration();
                    return;
                }
                int i = mCalibIndex;
                View c = mCalibControls.get(i);
                int[] cl = new int[2];
                c.getLocationOnScreen(cl);
                float aimX = cl[0] + c.getWidth() / 2f;
                float aimY = cl[1] + c.getHeight() / 2f;

                float cx = mClickX >= 0f ? mClickX : mCursorX;
                float cy = mClickY >= 0f ? mClickY : mCursorY;
                View decor = mActivity.getWindow() != null ? mActivity.getWindow().getDecorView() : null;
                float decorX = cx, decorY = cy, screenX = cx, screenY = cy;
                if (mCursorHost != null && decor != null) {
                    int[] hostLoc = new int[2];
                    int[] decorLoc = new int[2];
                    mCursorHost.getLocationOnScreen(hostLoc);
                    decor.getLocationOnScreen(decorLoc);
                    decorX = hostLoc[0] - decorLoc[0] + cx;
                    decorY = hostLoc[1] - decorLoc[1] + cy;
                    screenX = hostLoc[0] + cx;
                    screenY = hostLoc[1] + cy;
                }
                String hit = "none";
                if (decor != null) {
                    View t = findClickableAt(decor, decorX, decorY);
                    if (t != null) {
                        hit = t.getClass().getSimpleName()
                                + (t.getContentDescription() != null ? "[" + t.getContentDescription() + "]" : "");
                    }
                }
                // CSV: CALIB,<n>,aim,<desc>,aimCenter,ax,ay,cursor,cx,cy,clickScreen,sx,sy,deltaScreen,dx,dy,hit,<view>
                android.util.Log.d(TAG, "CALIB," + (i + 1)
                        + ",aim," + c.getContentDescription()
                        + ",aimCenter," + aimX + "," + aimY
                        + ",cursor," + cx + "," + cy
                        + ",clickScreen," + screenX + "," + screenY
                        + ",deltaScreen," + (screenX - aimX) + "," + (screenY - aimY)
                        + ",hit," + hit);

                mCalibIndex++;
                if (mCalibIndex >= mCalibControls.size()) {
                    android.util.Log.d(TAG, "CALIB COMPLETE (" + mCalibControls.size()
                            + " controls). Restarting at control 1.");
                    mCalibControls = collectControls();
                    mCalibIndex = 0;
                }
                highlightCalibControl(mCalibIndex);
            } catch (Throwable e) {
                android.util.Log.e(TAG, "recordCalibrationClick failed: " + e.getMessage());
            }
        });
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
            // Before clamping, see if the user is pushing past an edge; if so,
            // drive the built-in D-pad navigation in that direction.
            maybeEdgeScroll(-mCursorTargetX, mCursorTargetX - maxX,
                    -mCursorTargetY, mCursorTargetY - maxY);
            mCursorTargetX = Math.max(0f, Math.min(maxX, mCursorTargetX));
            mCursorTargetY = Math.max(0f, Math.min(maxY, mCursorTargetY));
            startCursorAnimation();
        });
    }

    private void notifyPlayerControlInteraction() {
        PlayerActionBridge bridge = sPlayerBridge;
        if (bridge != null && !bridge.isDimActive()) {
            bridge.onControlInteraction();
        }
    }

    private boolean isCursorAtBottomRevealEdge() {
        View host = mCursorHost;
        if (host == null || host.getHeight() <= 0) {
            return false;
        }

        float bottom = host.getHeight();
        float cursorY = Math.max(mCursorY, mCursorTargetY);
        boolean atBottom = cursorY >= bottom - SUGGESTIONS_REVEAL_BOTTOM_ZONE_PX;
        if (!atBottom) {
            log("down swipe ignored for suggestions; cursorY=" + cursorY
                    + " bottom=" + bottom
                    + " zone=" + SUGGESTIONS_REVEAL_BOTTOM_ZONE_PX);
        }
        return atBottom;
    }

    /**
     * Accumulate how far the pointer is being pushed past each edge and, once a
     * direction passes the threshold, inject a single D-pad key so the app's
     * own navigation scrolls/opens whatever lives off-screen. Sustained push is
     * required (the accumulator resets the moment you stop pushing that way), so
     * it won't fire from normal pointer movement.
     */
    private void maybeEdgeScroll(float overLeft, float overRight, float overUp, float overDown) {
        PlayerActionBridge bridge = sPlayerBridge;
        if (bridge != null && bridge.isDimActive()) {
            mEdgeAccumX = 0f;
            mEdgeAccumY = 0f;
            return;
        }
        if (overRight > 0f) mEdgeAccumX += overRight;
        else if (overLeft > 0f) mEdgeAccumX -= overLeft;
        else mEdgeAccumX = 0f;
        if (overDown > 0f) mEdgeAccumY += overDown;
        else if (overUp > 0f) mEdgeAccumY -= overUp;
        else mEdgeAccumY = 0f;

        long now = SystemClock.uptimeMillis();
        if (now - mLastEdgeScrollMs < EDGE_SCROLL_COOLDOWN_MS) {
            return;
        }
        int key = 0;
        if (mEdgeAccumY >= EDGE_SCROLL_THRESHOLD_PX) key = KeyEvent.KEYCODE_DPAD_DOWN;
        else if (mEdgeAccumY <= -EDGE_SCROLL_THRESHOLD_PX) key = KeyEvent.KEYCODE_DPAD_UP;
        else if (mEdgeAccumX >= EDGE_SCROLL_THRESHOLD_PX) key = KeyEvent.KEYCODE_DPAD_RIGHT;
        else if (mEdgeAccumX <= -EDGE_SCROLL_THRESHOLD_PX) key = KeyEvent.KEYCODE_DPAD_LEFT;
        if (key != 0) {
            mLastEdgeScrollMs = now;
            mEdgeAccumX = 0f;
            mEdgeAccumY = 0f;
            log("edge-scroll inject " + keyToString(key));
            // After the D-pad moves focus, snap the pointer onto the newly
            // focused control so a tap lands on it (otherwise the pointer is
            // left at the edge and the tap misses the highlighted item).
            injectKeyEventAsync(key, () -> mUiHandler.postDelayed(this::snapCursorToFocus, 90L));
        }
    }

    /** Move the visible pointer onto whatever view currently has focus. */
    private void snapCursorToFocus() {
        try {
            if (mCursorView == null || mCursorHost == null || mActivity.getWindow() == null) {
                return;
            }
            View focus = mActivity.getWindow().getCurrentFocus();
            if (focus == null || focus.getWidth() == 0 || focus.getHeight() == 0) {
                return;
            }
            int[] fl = new int[2];
            int[] hl = new int[2];
            focus.getLocationOnScreen(fl);
            mCursorHost.getLocationOnScreen(hl);
            float cx = fl[0] - hl[0] + focus.getWidth() / 2f;
            float cy = fl[1] - hl[1] + focus.getHeight() / 2f;
            float maxX = mHalfWidth > 0 ? mHalfWidth : mCursorHost.getWidth();
            cx = Math.max(0f, Math.min(maxX, cx));
            cy = Math.max(0f, Math.min(mCursorHost.getHeight(), cy));
            mCursorX = cx;
            mCursorY = cy;
            mCursorTargetX = cx;
            mCursorTargetY = cy;
            positionCursor();
            showCursorAndScheduleHide();
            log("snap cursor to focus " + focus.getClass().getSimpleName() + " at " + cx + "," + cy);
        } catch (Throwable ignored) {
        }
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
                float screenX = cx;
                float screenY = cy;
                if (mCursorHost != null) {
                    int[] hostLoc = new int[2];
                    int[] decorLoc = new int[2];
                    mCursorHost.getLocationOnScreen(hostLoc);
                    decor.getLocationOnScreen(decorLoc);
                    decorX = hostLoc[0] - decorLoc[0] + cx;
                    decorY = hostLoc[1] - decorLoc[1] + cy;
                    screenX = hostLoc[0] + cx;
                    screenY = hostLoc[1] + cy;
                }

                PlayerActionBridge bridge = sPlayerBridge;
                View target = findClickableAt(decor, decorX, decorY);

                // 1. Tap on the progress bar itself -> seek.
                if (target != null && isSeekBarTarget(target)) {
                    if (bridge != null && bridge.seekToScreenX(screenX, screenY)) {
                        android.util.Log.d(TAG, "clickAtCursor seekbar seek at " + screenX + "," + screenY);
                    }
                    return;
                }

                // 2. A labelled control button (Play, CC, Settings, ...) wins over
                // everything else. These are clickable views WITH a content
                // description; the video surface and bare progress track are not,
                // so they fall through. This MUST come before the coordinate seek
                // below, whose vertical zone can overlap the control rows.
                if (target != null && target.getContentDescription() != null
                        && target.getContentDescription().length() > 0) {
                    if (target.performClick()) {
                        android.util.Log.d(TAG, "clickAtCursor performClick target="
                                + target.getClass().getSimpleName()
                                + "[" + target.getContentDescription() + "] at " + decorX + "," + decorY);
                        return;
                    }
                    // Play/Pause sometimes ignores performClick -> media key.
                    android.util.Log.d(TAG, "clickAtCursor control via media key: " + target.getContentDescription());
                    injectKeyEventAsync(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                    return;
                }

                // 3. Tap on the timeline area where the track isn't the clickable
                // target -> seek by coordinate (tight vertical zone).
                if (bridge != null && bridge.seekToScreenX(screenX, screenY)) {
                    android.util.Log.d(TAG, "clickAtCursor seek handled at screen " + screenX + "," + screenY);
                    return;
                }

                // 4. A suggestion thumbnail -> focus it and press DPAD_CENTER so
                // leanback fires onItemClicked -> onSuggestionItemClicked, which
                // OPENS and AUTOPLAYS the video. Grid cards are individually
                // focusable, so center hits the right item.
                if (bridge != null && bridge.isInSuggestions(screenX, screenY)) {
                    View card = findFocusableAt(decor, decorX, decorY);
                    if (card != null) {
                        android.util.Log.d(TAG, "clickAtCursor open suggestion target="
                                + card.getClass().getSimpleName() + " at " + decorX + "," + decorY);
                        clickFocusableAt(card);
                        return;
                    }
                }

                // 5. Video surface -> toggle play via the media key.
                android.util.Log.d(TAG, "clickAtCursor play/pause toggle at " + decorX + "," + decorY);
                injectKeyEventAsync(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
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

    private boolean isSeekBarTarget(View target) {
        View current = target;
        while (current != null) {
            if (current.getId() == R.id.playback_progress) {
                return true;
            }

            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }

        return false;
    }

    private boolean isBackgroundClickTarget(View target, View decor) {
        if (target == null || decor == null) {
            return false;
        }

        int decorArea = decor.getWidth() * decor.getHeight();
        int targetArea = target.getWidth() * target.getHeight();
        return decorArea > 0 && targetArea > decorArea * 0.35f;
    }

    private boolean isPlayPauseTarget(View target) {
        View current = target;
        while (current != null) {
            CharSequence desc = current.getContentDescription();
            if (desc != null) {
                String text = desc.toString().toLowerCase(java.util.Locale.US);
                if (text.contains("play") || text.contains("pause")) {
                    return true;
                }
            }

            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }

        return false;
    }

    /** Deepest VISIBLE, focusable view containing the point (cursor excluded). */
    private View findFocusableAt(View view, float x, float y) {
        if (view == null || (view != mCursorView && view.getVisibility() != View.VISIBLE)) {
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
                    View target = findFocusableAt(child, childX, childY);
                    if (target != null) {
                        return target;
                    }
                }
            }
        }
        if (view != mCursorView && view.isFocusable() && view.getVisibility() == View.VISIBLE) {
            return view;
        }
        return null;
    }

    /**
     * Move focus to the view under the pointer and press DPAD_CENTER, like the
     * remote would. Done off the UI thread so we can leave touch mode (required
     * for the D-pad key to register) before focusing and sending the key.
     */
    private void clickFocusableAt(final View targetView) {
        sInputExecutor.execute(() -> {
            if (mActivity.isFinishing() || mActivity.isDestroyed()) {
                return;
            }
            try {
                android.app.Instrumentation inst = new android.app.Instrumentation();
                inst.setInTouchMode(false);
                View decor = mActivity.getWindow() != null ? mActivity.getWindow().getDecorView() : null;
                for (int i = 0; i < 30 && decor != null && decor.isInTouchMode(); i++) {
                    try { Thread.sleep(8); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
                }
                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                final boolean[] focused = {false};
                mActivity.runOnUiThread(() -> {
                    try {
                        focused[0] = targetView.requestFocus();
                        if (!focused[0]) {
                            focused[0] = targetView.requestFocusFromTouch();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
                try { latch.await(220, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
                log("clickFocusableAt focused=" + focused[0]
                        + " view=" + targetView.getClass().getSimpleName());
                // Exactly ONE center press — sending ENTER too would toggle the
                // Play button twice (play then pause).
                long now = SystemClock.uptimeMillis();
                inst.sendKeySync(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER,
                        0, 0, -1, 0, 0, android.view.InputDevice.SOURCE_KEYBOARD));
                inst.sendKeySync(new KeyEvent(now, now + 10, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER,
                        0, 0, -1, 0, 0, android.view.InputDevice.SOURCE_KEYBOARD));
            } catch (Throwable e) {
                android.util.Log.e(TAG, "clickFocusableAt failed: " + e.getMessage());
            }
        });
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

        // Keep the pointer hidden while dim caption mode is up, or while the
        // related-videos gallery is showing (there we're in plain D-pad mode).
        PlayerActionBridge bridge = sPlayerBridge;
        if (bridge != null && (bridge.isDimActive() || bridge.isSuggestionsShown())) {
            mUiHandler.removeCallbacks(mHideCursorRunnable);
            mCursorView.setVisibility(View.GONE);
            return;
        }

        mLastCursorActivityMs = SystemClock.uptimeMillis();
        mCursorView.setVisibility(View.VISIBLE);
        mUiHandler.removeCallbacks(mHideCursorRunnable);
        mUiHandler.postDelayed(mHideCursorRunnable, CURSOR_AUTO_HIDE_MS);
        notifyPlayerControlInteraction();
    }

    /** Immediately hide the pointer when dim mode turns on (push from player). */
    void applyDimCursor(final boolean dimActive) {
        mActivity.runOnUiThread(() -> {
            if (mCursorView == null) return;
            if (dimActive) {
                mUiHandler.removeCallbacks(mHideCursorRunnable);
                mCursorView.setVisibility(View.GONE);
            }
        });
    }
}
