package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build.VERSION;
import android.util.TypedValue;
import android.view.View;
import android.view.accessibility.CaptioningManager;
import android.view.accessibility.CaptioningManager.CaptionStyle;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import com.google.android.exoplayer2.text.CaptionStyleCompat;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.TextOutput;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import com.liskovsoft.smartyoutubetv2.common.prefs.common.DataChangeBase.OnDataChange;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;

import java.util.ArrayList;
import java.util.List;

public class SubtitleManager implements TextOutput, OnDataChange {
    private static final String TAG = SubtitleManager.class.getSimpleName();
    private final SubtitleView mSubtitleView;
    private final Context mContext;
    private final List<SubtitleStyle> mSubtitleStyles = new ArrayList<>();
    private final AppPrefs mPrefs;
    private final PlayerData mPlayerData;
    private CharSequence subsBuffer;
    // MOD: mirror caption text into the dim-mode overlay when active.
    private android.widget.TextView mDimCaptionView;
    private boolean mDimActive;
    private boolean mSubtitleVisible = true;

    public static class SubtitleStyle {
        public final int nameResId;
        public final int subsColorResId;
        public final int backgroundColorResId;
        public final int captionStyle;

        public SubtitleStyle(int nameResId) {
            this(nameResId, -1, -1, -1);
        }

        public SubtitleStyle(int nameResId, int subsColorResId, int backgroundColorResId, int captionStyle) {
            this.nameResId = nameResId;
            this.subsColorResId = subsColorResId;
            this.backgroundColorResId = backgroundColorResId;
            this.captionStyle = captionStyle;
        }

        public boolean isSystem() {
            return subsColorResId == -1 && backgroundColorResId == -1 && captionStyle == -1;
        }
    }

    public SubtitleManager(SubtitleView subtitleView) {
        mContext = subtitleView.getContext();
        mSubtitleView = subtitleView;
        mPrefs = AppPrefs.instance(mContext);
        mPlayerData = PlayerData.instance(mContext);
        mPlayerData.setOnChange(this);
        configureSubtitleView();
    }

    @Override
    public void onDataChange() {
        configureSubtitleView();
    }

    @Override
    public void onCues(List<Cue> cues) {
        List<Cue> centeredCues = forceCenterAlignment(cues);
        if (mSubtitleView != null) {
            mSubtitleView.setCues(centeredCues);
        }
        // MOD: feed the same caption text to the dim-mode overlay.
        if (mDimActive && mDimCaptionView != null) {
            StringBuilder sb = new StringBuilder();
            if (centeredCues != null) {
                for (Cue c : centeredCues) {
                    if (c != null && c.text != null) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(c.text);
                    }
                }
            }
            mDimCaptionView.setText(sb.toString());
        }
    }

    // MOD: dim-mode caption mirror hooks (used by the player's dim overlay).
    public void setDimCaptionView(android.widget.TextView view) {
        mDimCaptionView = view;
        configureDimCaptionView();
    }

    public void setDimActive(boolean active) {
        mDimActive = active;
        if (mSubtitleView != null) {
            mSubtitleView.setVisibility(active || !mSubtitleVisible ? View.GONE : View.VISIBLE);
        }
        if (!active && mDimCaptionView != null) {
            mDimCaptionView.setText("");
        }
    }

    public void show(boolean show) {
        mSubtitleVisible = show;
        if (mSubtitleView != null) {
            mSubtitleView.setVisibility(show && !mDimActive ? View.VISIBLE : View.GONE);
        }
    }

    private List<SubtitleStyle> getSubtitleStyles() {
        return mSubtitleStyles;
    }

    private SubtitleStyle getSubtitleStyle() {
        return mPlayerData.getSubtitleStyle();
    }

    private void setSubtitleStyle(SubtitleStyle subtitleStyle) {
        mPlayerData.setSubtitleStyle(subtitleStyle);
        configureSubtitleView();
    }

    private List<Cue> forceCenterAlignment(List<Cue> cues) {
        List<Cue> result = new ArrayList<>();

        for (Cue cue : cues) {
            // Autogenerated subs repeated lines fix
            final String textStr = cue.text.toString();
            if (Helpers.endsWithAny(textStr, "\n", " ")) { // vtt subs format
                subsBuffer = textStr;
            } else if (textStr.contains("\n")) { // ttml subs format
                //CharSequence text = subsBuffer != null ? textStr.replace(subsBuffer, "").replace("\n", "") : textStr;

                CharSequence text;

                if (subsBuffer != null && textStr.contains(subsBuffer)) {
                    text = textStr.replace(subsBuffer, "").replace("\n", "");
                } else {
                    text = textStr;
                }

                result.add(new Cue(text)); // sub centered by default

                String[] split = textStr.split("\n");
                subsBuffer = split.length == 2 ? split[1] : textStr;
            } else {
                CharSequence text = subsBuffer != null ? textStr.replace(subsBuffer, "") : textStr;
                result.add(new Cue(text)); // sub centered by default
                subsBuffer = text;
            }
        }

        return result;
    }

    private void configureSubtitleView() {
        if (mSubtitleView != null) {
            // disable default style
            mSubtitleView.setApplyEmbeddedStyles(false);

            SubtitleStyle subtitleStyle = getSubtitleStyle();

            if (subtitleStyle.isSystem()) {
                if (VERSION.SDK_INT >= 19) {
                    applySystemStyle();
                }
            } else {
                applyStyle(subtitleStyle);
            }

            mSubtitleView.setBottomPaddingFraction(mPlayerData.getSubtitlePosition());
            configureDimCaptionView();
        }
    }

    private void applyStyle(SubtitleStyle subtitleStyle) {
        int textColor = ContextCompat.getColor(mContext, subtitleStyle.subsColorResId);
        int outlineColor = ContextCompat.getColor(mContext, R.color.black);
        int backgroundColor = ContextCompat.getColor(mContext, subtitleStyle.backgroundColorResId);

        CaptionStyleCompat style =
                new CaptionStyleCompat(textColor,
                        backgroundColor, Color.TRANSPARENT,
                        subtitleStyle.captionStyle,
                        outlineColor, Typeface.DEFAULT_BOLD);
        mSubtitleView.setStyle(style);

        float textSize = getTextSizePx();
        mSubtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
    }

    private void configureDimCaptionView() {
        if (mDimCaptionView == null) {
            return;
        }

        SubtitleStyle subtitleStyle = getSubtitleStyle();
        float textSize = getTextSizePx();
        mDimCaptionView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
        mDimCaptionView.setTypeface(Typeface.DEFAULT_BOLD);

        if (subtitleStyle.isSystem()) {
            if (VERSION.SDK_INT >= 19) {
                applySystemStyleToDimCaption();
            }
            return;
        }

        int textColor = ContextCompat.getColor(mContext, subtitleStyle.subsColorResId);
        int backgroundColor = ContextCompat.getColor(mContext, subtitleStyle.backgroundColorResId);
        mDimCaptionView.setTextColor(textColor);
        mDimCaptionView.setBackgroundColor(backgroundColor);
        mDimCaptionView.setShadowLayer(2f, 1f, 1f, ContextCompat.getColor(mContext, R.color.black));
    }

    @RequiresApi(19)
    private void applySystemStyleToDimCaption() {
        CaptioningManager captioningManager =
                (CaptioningManager) mContext.getSystemService(Context.CAPTIONING_SERVICE);

        if (captioningManager == null || mDimCaptionView == null) {
            return;
        }

        CaptionStyle userStyle = captioningManager.getUserStyle();
        mDimCaptionView.setTextColor(userStyle.foregroundColor);
        mDimCaptionView.setBackgroundColor(userStyle.backgroundColor);
        mDimCaptionView.setShadowLayer(2f, 1f, 1f, userStyle.edgeColor);
        mDimCaptionView.setTypeface(userStyle.getTypeface());
        mDimCaptionView.setTextSize(TypedValue.COMPLEX_UNIT_PX, getTextSizePx() * captioningManager.getFontScale());
    }

    @RequiresApi(19)
    private void applySystemStyle() {
        CaptioningManager captioningManager =
                (CaptioningManager) mContext.getSystemService(Context.CAPTIONING_SERVICE);

        if (captioningManager != null) {
            CaptionStyle userStyle = captioningManager.getUserStyle();

            CaptionStyleCompat style =
                    new CaptionStyleCompat(userStyle.foregroundColor,
                            userStyle.backgroundColor, VERSION.SDK_INT >= 21 ? userStyle.windowColor : Color.TRANSPARENT,
                            userStyle.edgeType,
                            userStyle.edgeColor, userStyle.getTypeface());
            mSubtitleView.setStyle(style);

            float textSizePx = getTextSizePx();
            mSubtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx * captioningManager.getFontScale());
        }
    }

    private float getTextSizePx() {
        float textSizePx = mSubtitleView.getContext().getResources().getDimension(R.dimen.subtitle_text_size);
        return textSizePx * mPlayerData.getSubtitleScale();
    }
}
