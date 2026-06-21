package com.liskovsoft.smartyoutubetv2.tv.util;

import android.app.Activity;
import android.os.Bundle;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.common.utils.RayNeoDeviceUtil;
import com.liskovsoft.smartyoutubetv2.tv.ui.common.keyhandler.RayNeoInputInterceptor;

/**
 * Orchestrates RayNeo X3 Pro hardware-specific features (input interception, binocular mirroring)
 * across the activity lifecycle.
 */
public class RayNeoHardwareManager {
    private final Activity mActivity;
    private RayNeoInputInterceptor mInputInterceptor;
    private RayNeoMirrorHelper mMirrorHelper;
    private final boolean mIsRayNeo;

    public RayNeoHardwareManager(Activity activity) {
        mActivity = activity;
        mIsRayNeo = RayNeoDeviceUtil.isRayNeoDevice();
    }

    public void onCreate(Bundle savedInstanceState) {
        if (!mIsRayNeo) {
            return;
        }

        // Wire temple touch gestures to DPAD KeyEvents
        mInputInterceptor = new RayNeoInputInterceptor(mActivity);
        if (mInputInterceptor.isActive() && mActivity instanceof MotherActivity) {
            ((MotherActivity) mActivity).setTouchEventInterceptor(mInputInterceptor::onTouchEvent);
        }

        // MOD: pointer mode in the video player ONLY — the pad moves a cursor to
        // click the playbar/captions instead of seeking the timeline. Everywhere
        // else keeps the D-pad navigation above.
        if (mInputInterceptor.isActive() && mActivity.getClass().getName().endsWith(".PlaybackActivity")) {
            mInputInterceptor.setCursorMode(true);
        }

        // Initialize mirroring helper
        mMirrorHelper = new RayNeoMirrorHelper();
    }

    public void onPostCreate(Bundle savedInstanceState) {
        if (!mIsRayNeo) {
            return;
        }

        // Wrap content view for binocular display mirroring
        if (mMirrorHelper != null) {
            mMirrorHelper.wrapContentView(mActivity);
        }
    }

    public void onResume() {
        if (!mIsRayNeo) {
            return;
        }
        if (mMirrorHelper != null) {
            mMirrorHelper.onResume();
        }
    }

    public void onPause() {
        if (!mIsRayNeo) {
            return;
        }
        if (mMirrorHelper != null) {
            mMirrorHelper.onPause();
        }
    }

    public void onDestroy() {
        if (mMirrorHelper != null) {
            mMirrorHelper.destroy();
        }
        mInputInterceptor = null;
        mMirrorHelper = null;
    }
}
