package com.liskovsoft.smartyoutubetv2.tv.util;

import android.app.Activity;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.ffalcon.mercury.android.sdk.ui.wiget.MirroringView;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.utils.RayNeoDeviceUtil;

/**
 * Enables binocular display for RayNeo X3 Pro by splitting the 1280×480 screen
 * into two 640×480 halves using the official SDK MirroringView component.
 * <p>
 * Does not re-parent the content view (preserves TextureView surfaces).
 * Call {@link #wrapContentView(Activity)} from onPostCreate().
 */
public class RayNeoMirrorHelper {
    private static final String TAG = RayNeoMirrorHelper.class.getSimpleName();

    private MirroringView mMirrorImageView;
    private android.widget.ImageView mSnapshotView;
    private View mSourceView;
    private boolean mActive;

    public void wrapContentView(Activity activity) {
        if (mActive || !RayNeoDeviceUtil.isRayNeoDevice()) {
            return;
        }

        try {
            FrameLayout contentFrame = activity.findViewById(android.R.id.content);
            if (contentFrame == null || contentFrame.getChildCount() == 0) {
                Log.w(TAG, "No content view found to wrap");
                return;
            }

            // Get the screen width. Since we overridden metrics.widthPixels to 640 in MotherActivity
            // to fix Leanback scrolling, dm.widthPixels is ALREADY the half-width.
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            int halfWidth = dm.widthPixels; // 640px

            // Resize the existing content view to the left half — NO re-parenting!
            mSourceView = contentFrame.getChildAt(0);
            FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(
                    halfWidth, FrameLayout.LayoutParams.MATCH_PARENT);
            contentParams.gravity = Gravity.LEFT;
            mSourceView.setLayoutParams(contentParams);

            // Add official SDK MirroringView as a sibling in the right half
            mMirrorImageView = new MirroringView(activity);
            mMirrorImageView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            FrameLayout.LayoutParams mirrorParams = new FrameLayout.LayoutParams(
                    halfWidth, FrameLayout.LayoutParams.MATCH_PARENT);
            mirrorParams.gravity = Gravity.RIGHT;
            mMirrorImageView.setLayoutParams(mirrorParams);
            mMirrorImageView.setElevation(1000f);
            contentFrame.addView(mMirrorImageView);

            // Bind it and start mirroring
            mMirrorImageView.setSource(mSourceView);
            mMirrorImageView.startMirroring();

            mActive = true;
            Log.d(TAG, "Binocular mirroring initialized using pure official MirroringView: " + halfWidth + "px per eye");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to setup binocular mirroring: " + e.getMessage());
            e.printStackTrace();
            mActive = false;
        }
    }

    public void onPause() {
        if (!mActive || mSourceView == null || mMirrorImageView == null) return;
        try {
            if (mSourceView.getWidth() <= 0 || mSourceView.getHeight() <= 0) return;
            // Take a snapshot of the current source view because MirroringView stops updating in onPause
            android.graphics.Bitmap snapshot = android.graphics.Bitmap.createBitmap(mSourceView.getWidth(), mSourceView.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(snapshot);
            mSourceView.draw(canvas);

            if (mSnapshotView == null) {
                mSnapshotView = new android.widget.ImageView(mSourceView.getContext());
                mSnapshotView.setLayoutParams(mMirrorImageView.getLayoutParams());
                mSnapshotView.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
                // Place snapshot directly above MirroringView
                mSnapshotView.setElevation(mMirrorImageView.getElevation() + 1);
                ((ViewGroup) mMirrorImageView.getParent()).addView(mSnapshotView);
            }
            mSnapshotView.setImageBitmap(snapshot);
            mSnapshotView.setVisibility(View.VISIBLE);
            mMirrorImageView.setVisibility(View.INVISIBLE);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to create mirror snapshot: " + e.getMessage());
        }
    }

    public void onResume() {
        if (!mActive || mSnapshotView == null || mMirrorImageView == null) return;
        mSnapshotView.setVisibility(View.GONE);
        mMirrorImageView.setVisibility(View.VISIBLE);
        // Free bitmap memory
        mSnapshotView.setImageBitmap(null);
    }

    public void destroy() {
        if (mMirrorImageView != null) {
            try {
                mMirrorImageView.stopMirroring();
            } catch (Throwable e) {
                Log.w(TAG, "stopMirroring failed: " + e.getMessage());
            }
            mMirrorImageView = null;
        }
        mSourceView = null;
        mActive = false;
    }

    public boolean isActive() {
        return mActive;
    }
}
