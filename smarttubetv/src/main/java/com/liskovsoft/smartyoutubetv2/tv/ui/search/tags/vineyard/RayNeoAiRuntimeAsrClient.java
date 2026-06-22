package com.liskovsoft.smartyoutubetv2.tv.ui.search.tags.vineyard;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

final class RayNeoAiRuntimeAsrClient {
    interface Callback {
        void onConnected();
        void onAudioStart();
        void onAudioEnd();
        void onResult(String text, boolean finished);
        void onError(String message);
    }

    private static final String RUNTIME_PACKAGE = "com.rayneo.airuntime";
    private static final String RUNTIME_SERVICE = "com.rayneo.airuntime.AiService";
    private static final String AI_DESCRIPTOR = "com.rayneo.common.IAiComponent";
    private static final String RESULT_DESCRIPTOR = "com.rayneo.common.IAiResultListener";
    private static final int TRANSACTION_INIT_WORKFLOW = 1;
    private static final int TRANSACTION_START_WORKFLOW = 2;
    private static final int TRANSACTION_STOP_WORKFLOW = 3;
    private static final int TRANSACTION_DESTROY_WORKFLOW = 6;

    private final Context mContext;
    private final Callback mCallback;
    private final String mConfigJson;
    private IBinder mAiBinder;
    private boolean mBound;
    private boolean mStartWhenConnected;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mAiBinder = service;
            mCallback.onConnected();
            if (mStartWhenConnected) {
                mStartWhenConnected = false;
                initAndStart();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mAiBinder = null;
            mBound = false;
            mCallback.onError("airuntime service disconnected");
        }
    };

    RayNeoAiRuntimeAsrClient(Context context, Callback callback) {
        mContext = context.getApplicationContext();
        mCallback = callback;
        mConfigJson = createConfigJson(mContext.getPackageName());
    }

    boolean isActive() {
        return mBound || mAiBinder != null;
    }

    void start() {
        if (mAiBinder != null && mAiBinder.isBinderAlive()) {
            initAndStart();
            return;
        }

        mStartWhenConnected = true;
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(RUNTIME_PACKAGE, RUNTIME_SERVICE));
        mBound = mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        if (!mBound) {
            mStartWhenConnected = false;
            mCallback.onError("airuntime bind returned false");
        }
    }

    void stop() {
        mStartWhenConnected = false;
        stopWorkflow();
        destroyWorkflow();
        if (mBound) {
            try {
                mContext.unbindService(mConnection);
            } catch (Throwable ignored) {
            }
        }
        mBound = false;
        mAiBinder = null;
    }

    private void initAndStart() {
        IBinder binder = mAiBinder;
        if (binder == null || !binder.isBinderAlive()) {
            mCallback.onError("airuntime binder unavailable");
            return;
        }

        try {
            boolean initOk = transactInitWorkflow(binder);
            mCallback.onResult("initWorkflow=" + initOk, false);
            if (!initOk) {
                mCallback.onError("airuntime initWorkflow returned false");
                return;
            }

            boolean startOk = transactStartWorkflow(binder);
            mCallback.onResult("startWorkflow=" + startOk, false);
            if (!startOk) {
                mCallback.onError("airuntime startWorkflow returned false");
            }
        } catch (Throwable e) {
            mCallback.onError("airuntime start failed " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private boolean transactInitWorkflow(IBinder binder) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(AI_DESCRIPTOR);
            data.writeString(mConfigJson);
            data.writeStrongBinder(new ResultListenerBinder());
            binder.transact(TRANSACTION_INIT_WORKFLOW, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private boolean transactStartWorkflow(IBinder binder) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(AI_DESCRIPTOR);
            data.writeString(mConfigJson);
            data.writeString(null);
            data.writeInt(-1);
            binder.transact(TRANSACTION_START_WORKFLOW, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void stopWorkflow() {
        transactConfigOnly(TRANSACTION_STOP_WORKFLOW);
    }

    private void destroyWorkflow() {
        transactConfigOnly(TRANSACTION_DESTROY_WORKFLOW);
    }

    private void transactConfigOnly(int code) {
        IBinder binder = mAiBinder;
        if (binder == null || !binder.isBinderAlive()) {
            return;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(AI_DESCRIPTOR);
            data.writeString(mConfigJson);
            binder.transact(code, data, reply, 0);
            reply.readException();
        } catch (Throwable ignored) {
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static String createConfigJson(String packageName) {
        return "{"
                + "\"sceneName\":\"SmartTubeVoiceSearch\","
                + "\"appName\":\"" + escape(packageName) + "\","
                + "\"workflowMode\":\"ONLY_ASR\","
                + "\"interactMode\":\"ONESHOT\","
                + "\"audioMode\":\"DIRECT_PICKUP\","
                + "\"vadConfig\":{\"enable\":true,\"bos\":3000,\"eos\":1000,\"threshold\":0.5,\"speechTimeout\":60000,\"endSpeechTimeout\":10000,\"vadType\":\"WEBRTC\"},"
                + "\"asrConfig\":{\"useCloud\":true,\"language\":\"en-US\"}"
                + "}";
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private final class ResultListenerBinder extends Binder implements IInterface {
        ResultListenerBinder() {
            attachInterface(this, RESULT_DESCRIPTOR);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(RESULT_DESCRIPTOR);
                return true;
            }

            data.enforceInterface(RESULT_DESCRIPTOR);
            switch (code) {
                case 1:
                    mCallback.onAudioStart();
                    break;
                case 3:
                    mCallback.onAudioEnd();
                    break;
                case 4:
                    mCallback.onResult("vadStatus=" + data.readInt(), false);
                    break;
                case 6:
                    String text = data.readString();
                    boolean finished = data.readInt() != 0;
                    String sid = data.readString();
                    mCallback.onResult(text, finished);
                    break;
                case 16:
                    int error = data.readInt();
                    String message = data.readString();
                    mCallback.onError("airuntime error " + error + ": " + message);
                    break;
                case 17:
                    int type = data.readInt();
                    int arg = data.readInt();
                    String info = data.readString();
                    mCallback.onResult("notify type=" + type + " arg=" + arg + " info=" + info, false);
                    break;
                default:
                    skipKnownPayload(code, data);
                    break;
            }

            if (reply != null) {
                reply.writeNoException();
            }
            return true;
        }

        private void skipKnownPayload(int code, Parcel data) {
            switch (code) {
                case 2:
                    data.createByteArray();
                    break;
                case 5:
                    data.readInt();
                    data.readString();
                    break;
                case 7:
                case 8:
                case 10:
                case 11:
                case 13:
                case 14:
                case 15:
                    data.readString();
                    break;
                case 9:
                case 12:
                case 18:
                    data.readInt();
                    if (code == 18) {
                        data.readString();
                    }
                    break;
                default:
                    break;
            }
        }
    }
}
