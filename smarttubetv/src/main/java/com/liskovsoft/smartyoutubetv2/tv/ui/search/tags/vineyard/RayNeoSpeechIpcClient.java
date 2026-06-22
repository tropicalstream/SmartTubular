package com.liskovsoft.smartyoutubetv2.tv.ui.search.tags.vineyard;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

final class RayNeoSpeechIpcClient {
    interface Callback {
        void onConnected();
        void onResult(String text, boolean finished);
        void onError(String message);
    }

    private static final String LAUNCHER_PACKAGE = "com.ffalconxr.mercury.launcher";
    private static final String REMOTE_SERVICE = "com.ffalconxr.mercury.launcher.ipc.RemoteMultiService";
    private static final String BINDER_FACTORY_DESCRIPTOR = "com.ffalconxr.mercury.ipc.IBinderFactory";
    private static final String SPEECH_DESCRIPTOR = "com.ffalconxr.mercury.ipc.speech.ISpeechInterface";
    private static final String NLP_CALLBACK_DESCRIPTOR = "com.ffalconxr.mercury.ipc.speech.INLPCallback";
    private static final int BINDER_TYPE_SPEECH = 1;
    private static final int TRANSACTION_GENERATE_BINDER = 1;
    private static final int TRANSACTION_START_DIALOG = 1;
    private static final int TRANSACTION_STOP_DIALOG = 2;

    private final Context mContext;
    private final Callback mCallback;
    private IBinder mSpeechBinder;
    private boolean mBound;
    private boolean mStartWhenConnected;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                mSpeechBinder = generateSpeechBinder(service);
                if (mSpeechBinder == null || !mSpeechBinder.isBinderAlive()) {
                    mCallback.onError("ipc speech binder unavailable");
                    return;
                }

                mCallback.onConnected();
                if (mStartWhenConnected) {
                    mStartWhenConnected = false;
                    startDialog();
                }
            } catch (Throwable e) {
                mCallback.onError("ipc connect failed " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mSpeechBinder = null;
            mBound = false;
            mCallback.onError("ipc service disconnected");
        }
    };

    RayNeoSpeechIpcClient(Context context, Callback callback) {
        mContext = context.getApplicationContext();
        mCallback = callback;
    }

    boolean isActive() {
        return mBound || mSpeechBinder != null;
    }

    void start() {
        if (mSpeechBinder != null && mSpeechBinder.isBinderAlive()) {
            startDialog();
            return;
        }

        mStartWhenConnected = true;
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(LAUNCHER_PACKAGE, REMOTE_SERVICE));
        mBound = mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        if (!mBound) {
            mStartWhenConnected = false;
            mCallback.onError("ipc bind returned false");
        }
    }

    void stop() {
        mStartWhenConnected = false;
        stopDialog();
        if (mBound) {
            try {
                mContext.unbindService(mConnection);
            } catch (Throwable ignored) {
            }
        }
        mBound = false;
        mSpeechBinder = null;
    }

    private IBinder generateSpeechBinder(IBinder factoryBinder) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(BINDER_FACTORY_DESCRIPTOR);
            data.writeInt(BINDER_TYPE_SPEECH);
            factoryBinder.transact(TRANSACTION_GENERATE_BINDER, data, reply, 0);
            reply.readException();
            return reply.readStrongBinder();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void startDialog() {
        IBinder speechBinder = mSpeechBinder;
        if (speechBinder == null || !speechBinder.isBinderAlive()) {
            mCallback.onError("ipc start requested before binder ready");
            return;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SPEECH_DESCRIPTOR);
            data.writeStrongBinder(new NlpCallbackBinder());
            Bundle bundle = new Bundle();
            data.writeInt(1);
            bundle.writeToParcel(data, 0);
            speechBinder.transact(TRANSACTION_START_DIALOG, data, reply, 0);
            reply.readException();
        } catch (Throwable e) {
            mCallback.onError("ipc startDialog failed " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void stopDialog() {
        IBinder speechBinder = mSpeechBinder;
        if (speechBinder == null || !speechBinder.isBinderAlive()) {
            return;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SPEECH_DESCRIPTOR);
            speechBinder.transact(TRANSACTION_STOP_DIALOG, data, reply, 0);
            reply.readException();
        } catch (Throwable ignored) {
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private final class NlpCallbackBinder extends Binder implements IInterface {
        NlpCallbackBinder() {
            attachInterface(this, NLP_CALLBACK_DESCRIPTOR);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(NLP_CALLBACK_DESCRIPTOR);
                return true;
            }

            if (code == 1) {
                data.enforceInterface(NLP_CALLBACK_DESCRIPTOR);
                String text = null;
                int eventType = 0;
                int arg1 = 0;
                int arg2 = 0;
                String info = null;
                if (data.readInt() != 0) {
                    text = data.readString();
                    eventType = data.readInt();
                    arg1 = data.readInt();
                    arg2 = data.readInt();
                    info = data.readString();
                }
                boolean finished = data.readInt() != 0;
                if (reply != null) {
                    reply.writeNoException();
                }
                mCallback.onResult(text != null ? text : info, finished);
                return true;
            }

            return super.onTransact(code, data, reply, flags);
        }
    }
}
