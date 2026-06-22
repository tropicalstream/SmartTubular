package com.liskovsoft.smartyoutubetv2.tv.ui.search.tags.vineyard;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.leanback.app.RowsSupportFragment;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.HeaderItem;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.ListRowPresenter;
import androidx.leanback.widget.ObjectAdapter;
import androidx.leanback.widget.RowPresenter.ViewHolder;
import androidx.leanback.widget.SpeechRecognitionCallback;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.helpers.PermissionHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.search.SearchTagsProvider;
import com.liskovsoft.smartyoutubetv2.common.app.models.search.vineyard.Tag;
import com.liskovsoft.smartyoutubetv2.common.app.views.SearchView;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.prefs.SearchData;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.adapter.vineyard.PaginationAdapter;
import com.liskovsoft.smartyoutubetv2.tv.adapter.vineyard.TagAdapter;
import com.liskovsoft.smartyoutubetv2.tv.presenter.CustomListRowPresenter;
import com.liskovsoft.smartyoutubetv2.tv.presenter.base.OnItemLongPressedListener;
import com.liskovsoft.smartyoutubetv2.tv.presenter.vineyard.TagPresenter;
import com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.misc.ProgressBarManager;
import com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.search.SearchSupportFragment;

import net.gotev.speech.GoogleVoiceTypingDisabledException;
import net.gotev.speech.Speech;
import net.gotev.speech.SpeechDelegate;
import net.gotev.speech.SpeechRecognitionNotAvailable;
import net.gotev.speech.SpeechUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public abstract class SearchTagsFragmentBase extends SearchSupportFragment
        implements SearchSupportFragment.SearchResultProvider, SearchView {
    private static final String TAG = SearchTagsFragmentBase.class.getSimpleName();
    private static final String RAYNEO_VOICE_TAG = "RayNeoVoiceSearch";
    private static final int REQUEST_SPEECH = 0x00000010;
    private static final long RAYNEO_READY_TIMEOUT_MS = 4_000L;

    private TagAdapter mSearchTagsAdapter;
    //private ObjectAdapter mItemResultsAdapter;
    private ArrayObjectAdapter mResultsAdapter; // contains tags adapter and results adapter (see attachAdapter method)
    private ListRowPresenter mResultsPresenter;
    private TagPresenter mTagsPresenter;

    private boolean mIsStopping;
    private SearchTagsProvider mSearchTagsProvider;
    private ProgressBarManager mProgressBarManager;
    private final Handler mVoiceHandler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer mRayNeoSpeechRecognizer;
    private boolean mRayNeoRecognizerReady;
    private RayNeoSpeechIpcClient mRayNeoSpeechIpcClient;
    private RayNeoAiRuntimeAsrClient mRayNeoAiRuntimeAsrClient;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mProgressBarManager = new ProgressBarManager();
        mResultsPresenter = new CustomListRowPresenter();
        mResultsPresenter.enableChildRoundedCorners(getMainUIData().isUiTweakEnabled(MainUIData.UI_TWEAK_ROUNDED_CORNERS));
        mResultsAdapter = new ArrayObjectAdapter(mResultsPresenter);
        mTagsPresenter = new TagPresenter();
        mSearchTagsAdapter = new TagAdapter(getContext(), mTagsPresenter, "");
        setSearchResultProvider(this);
        setupListenersAndPermissions();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = super.onCreateView(inflater, container, savedInstanceState);

        mProgressBarManager.setRootView((ViewGroup) root);

        return root;
    }

    @Override
    public void onStart() {
        super.onStart();
        mIsStopping = false;
    }

    @Override
    public void onStop() {
        super.onStop();
        mIsStopping = true;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_SPEECH:
                switch (resultCode) {
                    case Activity.RESULT_OK:
                        setSearchQuery(data, true);
                        break;
                    case Activity.RESULT_CANCELED:
                        Log.i(TAG, "Recognizer canceled");
                        break;
                }
                break;
        }
    }

    @Override
    public ObjectAdapter getResultsAdapter() {
        return mResultsAdapter;
    }

    @Override
    public void showProgressBar(boolean show) {
        if (show) {
            mProgressBarManager.show();
        } else {
            mProgressBarManager.hide();
        }
    }

    protected abstract void onItemViewSelected(Object item);
    
    protected abstract void onItemViewClicked(Object item);

    protected void setSearchTagsProvider(SearchTagsProvider provider) {
        mSearchTagsProvider = provider;
    }

    protected void setSearchTagsLongPressListener(OnItemLongPressedListener listener) {
        mTagsPresenter.setOnItemViewLongPressedListener(listener);
    }

    public boolean isStopping() {
        return mIsStopping;
    }

    public boolean hasResults() {
        return mResultsAdapter.size() > 0;
    }

    @SuppressWarnings("deprecation")
    private void setupListenersAndPermissions() {
        setOnItemViewClickedListener((itemViewHolder, item, rowViewHolder, row) -> onItemViewClicked(item));
        setOnItemViewSelectedListener((itemViewHolder, item, rowViewHolder, row) -> onItemViewSelected(item));

        // All needed permissions acquired inside SearchBar component.
        // See: androidx.leanback.widget.SearchBar.startRecognition()
        //if (SpeechRecognizer.isRecognitionAvailable(getContext())) {
        //    PermissionHelpers.verifyMicPermissions(getContext());
        //}

        // NOTE: External recognizer makes voice search behave unexpectedly (broken by Google app updates).
        // You should avoid using it till there be a solution.
        SearchData searchData = getSearchData();
        searchData.setSpeechRecognizerType(SearchData.SPEECH_RECOGNIZER_GOTEV);
        searchData.setInstantVoiceSearchEnabled(true);
        searchData.setKeyboardAutoShowEnabled(false);

        switch (searchData.getSpeechRecognizerType()) {
            case SearchData.SPEECH_RECOGNIZER_SYSTEM:
                // Don't uncomment. Sometimes system recognizer works on lower api
                // Do nothing unless we have old api.
                // Internal recognizer needs API >= 23. See: androidx.leanback.widget.SearchBar.startRecognition()
                //if (Build.VERSION.SDK_INT < 23) {
                //    setSpeechRecognitionCallback(mDefaultCallback);
                //}
                break;
            case SearchData.SPEECH_RECOGNIZER_INTENT:
                setSpeechRecognitionCallback(mDefaultCallback);
                break;
            case SearchData.SPEECH_RECOGNIZER_GOTEV:
                Speech.init(getContext());
                setSpeechRecognitionCallback(mGotevCallback);
                break;
        }
    }

    protected void stopSpeechService() {
        // Note: Other services don't need to be stopped

        if (getSearchData().getSpeechRecognizerType() != SearchData.SPEECH_RECOGNIZER_GOTEV) {
            return;
        }

        try {
            Speech.getInstance().stopListening();
            stopRayNeoSpeechIpc();
            stopRayNeoAiRuntimeAsr();
            stopRayNeoSpeechRecognizer();
            setRayNeoMicMode(false);
        } catch (IllegalArgumentException | NoSuchMethodError e) { // Speech service not registered/Android 4 (no such method)
            e.printStackTrace();
        }
    }

    protected void loadSearchTags(String searchQuery) {
        searchTaggedPosts(searchQuery);
    }

    private void searchTaggedPosts(String query) {
        mSearchTagsAdapter.setTag(query);
        mResultsAdapter.clear();
        mSearchTagsAdapter.clear();
        performTagSearch(mSearchTagsAdapter);
    }

    private void performTagSearch(TagAdapter adapter) {
        if (mSearchTagsProvider == null) {
            return;
        }

        String query = adapter.getAdapterOptions().get(PaginationAdapter.KEY_TAG);
        mSearchTagsProvider.search(query, results -> {
            adapter.addAllItems(results);
            attachAdapter(0, adapter);
            // Same suggestions in the keyboard
            //displayCompletions(toCompletions(results));
        });
    }

    private List<String> toCompletions(List<Tag> results) {
        List<String> result = null;

        if (results != null) {
            result = new ArrayList<>();

            for (Tag tag : results) {
                result.add(tag.tag);
            }
        }

        return result;
    }

    /**
     * Disable scrolling on partially updated rows. This prevent controls from misbehaving.
     */
    protected void freeze(boolean freeze) {
        // Disable scrolling on partially updated rows. This prevent controls from misbehaving.
        RowsSupportFragment rowsSupportFragment = getRowsSupportFragment();
        if (mResultsPresenter != null && rowsSupportFragment != null) {
            ViewHolder vh = rowsSupportFragment.getRowViewHolder(rowsSupportFragment.getSelectedPosition());
            if (vh != null) {
                mResultsPresenter.freeze(vh, freeze);
            }
        }
    }

    protected void attachAdapter(int index, ObjectAdapter adapter) {
        if (mResultsAdapter != null) {
            if (!containsAdapter(adapter)) {
                index = Math.min(index, mResultsAdapter.size());
                mResultsAdapter.add(index, new ListRow(adapter));
            }
        }
    }

    protected void attachAdapter(int index, HeaderItem header, ObjectAdapter adapter) {
        if (mResultsAdapter != null) {
            if (!containsAdapter(adapter)) {
                index = Math.min(index, mResultsAdapter.size());
                mResultsAdapter.add(index, new ListRow(header, adapter));
            }
        }
    }

    protected void detachAdapter(int index) {
        if (mResultsAdapter != null && index < mResultsAdapter.size()) {
            mResultsAdapter.removeItems(index, 1);
        }
    }

    protected void clearTags() {
        if (containsAdapter(mSearchTagsAdapter)) {
            detachAdapter(0);
        }
    }

    protected boolean containsAdapter(ObjectAdapter adapter) {
        if (mResultsAdapter != null) {
            for (int i = 0; i < mResultsAdapter.size(); i++) {
                ListRow row = (ListRow) mResultsAdapter.get(i);
                if (row.getAdapter() == adapter) {
                    return true;
                }
            }
        }

        return false;
    }

    private MainUIData getMainUIData() {
        return MainUIData.instance(getContext());
    }

    private SearchData getSearchData() {
        return SearchData.instance(getContext());
    }

    @SuppressWarnings("deprecation")
    private final SpeechRecognitionCallback mDefaultCallback = () -> {
        if (isAdded()) {
            if (PermissionHelpers.hasMicPermissions(getContext())) {
                MessageHelpers.showMessage(getContext(), R.string.disable_mic_permission);
            }

            try {
                startActivityForResult(getRecognizerIntent(), REQUEST_SPEECH);
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "Cannot find activity for speech recognizer", e);
            } catch (NullPointerException e) {
                Log.e(TAG, "Speech recognizer can't obtain applicationInfo", e);
            }
        } else {
            Log.e(TAG, "Can't perform search. Fragment is detached.");
        }
    };

    @SuppressWarnings("deprecation")
    private final SpeechRecognitionCallback mGotevCallback = () -> {
        if (isAdded()) {
            startRayNeoSpeechRecognizer();
        } else {
            Log.e(TAG, "Can't perform search. Fragment is detached.");
        }
    };

    protected void startRayNeoVoiceSearch() {
        startRayNeoSpeechRecognizer();
    }

    protected boolean isRayNeoVoiceSearchActive() {
        return mRayNeoSpeechRecognizer != null
                || (mRayNeoSpeechIpcClient != null && mRayNeoSpeechIpcClient.isActive())
                || (mRayNeoAiRuntimeAsrClient != null && mRayNeoAiRuntimeAsrClient.isActive());
    }

    private void startRayNeoSpeechRecognizer() {
        try {
            if (isRayNeoVoiceSearchActive()) {
                voiceLog("start ignored; voice search already active");
                return;
            }

            PermissionHelpers.verifyMicPermissions(getContext());
            setRayNeoMicMode(true);
            beep();
            showListening();
            voiceLog("airuntime start package="
                    + (getContext() != null ? getContext().getPackageName() : "null"));
            mRayNeoAiRuntimeAsrClient = new RayNeoAiRuntimeAsrClient(getContext(), new RayNeoAiRuntimeAsrClient.Callback() {
                @Override
                public void onConnected() {
                    voiceLog("airuntime connected");
                }

                @Override
                public void onAudioStart() {
                    voiceLog("airuntime audio start");
                }

                @Override
                public void onAudioEnd() {
                    voiceLog("airuntime audio end");
                }

                @Override
                public void onResult(String text, boolean finished) {
                    voiceLog("airuntime result finished=" + finished + " text=" + text);
                    if (text != null && !text.startsWith("initWorkflow=")
                            && !text.startsWith("startWorkflow=")
                            && !text.startsWith("vadStatus=")
                            && !text.startsWith("notify type=")) {
                        applyVoiceQuery(text, finished);
                    }
                    if (finished) {
                        stopRayNeoAiRuntimeAsr();
                    }
                }

                @Override
                public void onError(String message) {
                    voiceLog(message);
                    showNotListening();
                    setRayNeoMicMode(false);
                    stopRayNeoAiRuntimeAsr();
                }
            });
            mRayNeoAiRuntimeAsrClient.start();
            mVoiceHandler.postDelayed(() -> {
                if (mRayNeoAiRuntimeAsrClient != null && mRayNeoAiRuntimeAsrClient.isActive()) {
                    voiceLog("airuntime timeout; no final result");
                    showNotListening();
                    setRayNeoMicMode(false);
                    stopRayNeoAiRuntimeAsr();
                }
            }, 15_000L);
        } catch (Throwable e) {
            Log.e(TAG, "Speech recognition start failed: " + e.getMessage());
            voiceLog("start failed " + e.getClass().getSimpleName() + ": " + e.getMessage());
            showNotListening();
            setRayNeoMicMode(false);
            stopRayNeoAiRuntimeAsr();
        }
    }

    private void stopRayNeoAiRuntimeAsr() {
        if (mRayNeoAiRuntimeAsrClient == null) {
            return;
        }

        try {
            mRayNeoAiRuntimeAsrClient.stop();
        } catch (Throwable ignored) {
        }
        mRayNeoAiRuntimeAsrClient = null;
    }

    private void stopRayNeoSpeechIpc() {
        if (mRayNeoSpeechIpcClient == null) {
            return;
        }

        try {
            mRayNeoSpeechIpcClient.stop();
        } catch (Throwable ignored) {
        }
        mRayNeoSpeechIpcClient = null;
    }

    private Intent createRayNeoRecognizerIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE,
                getContext() != null ? getContext().getPackageName() : "");
        return intent;
    }

    private String firstSpeechResult(Bundle bundle) {
        if (bundle == null) {
            return null;
        }

        ArrayList<String> matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return matches != null && matches.size() > 0 ? matches.get(0) : null;
    }

    private void stopRayNeoSpeechRecognizer() {
        if (mRayNeoSpeechRecognizer == null) {
            return;
        }

        try {
            mRayNeoSpeechRecognizer.cancel();
            mRayNeoSpeechRecognizer.destroy();
        } catch (Throwable ignored) {
        }
        mRayNeoSpeechRecognizer = null;
        mRayNeoRecognizerReady = false;
    }

    private String errorToString(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "ERROR_AUDIO";
            case SpeechRecognizer.ERROR_CLIENT: return "ERROR_CLIENT";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "ERROR_INSUFFICIENT_PERMISSIONS";
            case SpeechRecognizer.ERROR_NETWORK: return "ERROR_NETWORK";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "ERROR_NETWORK_TIMEOUT";
            case SpeechRecognizer.ERROR_NO_MATCH: return "ERROR_NO_MATCH";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "ERROR_RECOGNIZER_BUSY";
            case SpeechRecognizer.ERROR_SERVER: return "ERROR_SERVER";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "ERROR_SPEECH_TIMEOUT";
            default: return "ERROR_" + error;
        }
    }

    private void applyVoiceQuery(String result, boolean submit) {
        if (result == null || result.trim().length() == 0 || getActivity() == null) {
            if (submit) {
                setRayNeoMicMode(false);
                mVoiceHandler.post(this::showNotListening);
            }
            return;
        }

        mVoiceHandler.post(() -> {
            setSearchQuery(result.trim(), submit);
            if (submit) {
                showNotListening();
                setRayNeoMicMode(false);
            }
        });
    }

    private void setRayNeoMicMode(boolean enabled) {
        try {
            Context context = getContext();
            AudioManager audioManager = context != null
                    ? (AudioManager) context.getSystemService(Context.AUDIO_SERVICE) : null;
            if (audioManager != null) {
                String value = enabled ? "voiceassistant" : "off";
                audioManager.setParameters("audio_source_record=" + value);
                audioManager.setParameters("audio_source=" + value);
                Log.i(TAG, "RayNeo mic mode: " + value);
                voiceLog("mic mode " + value);
            }
        } catch (Throwable e) {
            Log.e(TAG, "Unable to set RayNeo mic mode: " + e.getMessage());
            voiceLog("mic mode failed " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void beep() {
        try {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 160);
        } catch (Throwable e) {
            Log.e(TAG, "Unable to play speech start beep: " + e.getMessage());
            voiceLog("beep failed " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void voiceLog(String msg) {
        android.util.Log.i(RAYNEO_VOICE_TAG, msg);
    }
}
