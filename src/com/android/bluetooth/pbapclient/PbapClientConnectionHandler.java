/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.bluetooth.pbapclient;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.SdpPseRecord;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.CallLog;
import android.provider.CallLog.Calls;
import android.util.Log;

import com.android.bluetooth.BluetoothObexTransport;
import com.android.bluetooth.R;
import com.android.vcard.VCardEntry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import com.android.obex.ClientSession;
import com.android.obex.HeaderSet;
import com.android.obex.ResponseCodes;

/* Bluetooth/pbapclient/PbapClientConnectionHandler is responsible
 * for connecting, disconnecting and downloading contacts from the
 * PBAP PSE when commanded. It receives all direction from the
 * controlling state machine.
 */
class PbapClientConnectionHandler extends Handler {
    // Tradeoff: larger BATCH_SIZE leads to faster download rates, while smaller
    // BATCH_SIZE is less prone to IO Exceptions if there is a download in
    // progress when Bluetooth stack is torn down.
    private static final int DEFAULT_BATCH_SIZE = 250;

    // Upper limit on the indices of the vcf cards/entries, inclusive,
    // i.e., valid indices are [0, 1, ... , UPPER_LIMIT]
    private static final int UPPER_LIMIT = 65535;

    static final String TAG = "PbapClientConnHandler";
    static final boolean DBG = Utils.DBG;
    static final boolean VDBG = Utils.VDBG;
    static final int MSG_CONNECT = 1;
    static final int MSG_DISCONNECT = 2;
    static final int MSG_DOWNLOAD = 3;

    // The following constants are pulled from the Bluetooth Phone Book Access Profile specification
    // 1.1
    private static final byte[] PBAP_TARGET = new byte[]{
            0x79,
            0x61,
            0x35,
            (byte) 0xf0,
            (byte) 0xf0,
            (byte) 0xc5,
            0x11,
            (byte) 0xd8,
            0x09,
            0x66,
            0x08,
            0x00,
            0x20,
            0x0c,
            (byte) 0x9a,
            0x66
    };

    private static final int PBAP_FEATURE_DEFAULT_IMAGE_FORMAT = 0x00000200;
    private static final int PBAP_FEATURE_BROWSING = 0x00000002;
    private static final int PBAP_FEATURE_DOWNLOADING = 0x00000001;

    private static final long PBAP_FILTER_VERSION = 1 << 0;
    private static final long PBAP_FILTER_FN = 1 << 1;
    private static final long PBAP_FILTER_N = 1 << 2;
    private static final long PBAP_FILTER_PHOTO = 1 << 3;
    private static final long PBAP_FILTER_ADR = 1 << 5;
    private static final long PBAP_FILTER_TEL = 1 << 7;
    private static final long PBAP_FILTER_EMAIL = 1 << 8;
    private static final long PBAP_FILTER_NICKNAME = 1 << 23;

    private static final int PBAP_SUPPORTED_FEATURE =
            PBAP_FEATURE_DEFAULT_IMAGE_FORMAT | PBAP_FEATURE_DOWNLOADING;
    private static final long PBAP_REQUESTED_FIELDS =
            PBAP_FILTER_VERSION | PBAP_FILTER_FN | PBAP_FILTER_N | PBAP_FILTER_PHOTO
                    | PBAP_FILTER_ADR | PBAP_FILTER_EMAIL | PBAP_FILTER_TEL | PBAP_FILTER_NICKNAME;
    private static final int L2CAP_INVALID_PSM = -1;

    public static final String PB_PATH = "telecom/pb.vcf";
    public static final String FAV_PATH = "telecom/fav.vcf";
    public static final String MCH_PATH = "telecom/mch.vcf";
    public static final String ICH_PATH = "telecom/ich.vcf";
    public static final String OCH_PATH = "telecom/och.vcf";
    public static final String SIM_PB_PATH = "SIM1/telecom/pb.vcf";
    public static final String SIM_MCH_PATH = "SIM1/telecom/mch.vcf";
    public static final String SIM_ICH_PATH = "SIM1/telecom/ich.vcf";
    public static final String SIM_OCH_PATH = "SIM1/telecom/och.vcf";

    // PBAP v1.2.3 Sec. 7.1.2
    private static final int SUPPORTED_REPOSITORIES_LOCALPHONEBOOK = 1 << 0;
    private static final int SUPPORTED_REPOSITORIES_SIMCARD = 1 << 1;
    private static final int SUPPORTED_REPOSITORIES_FAVORITES = 1 << 3;

    public static final int PBAP_V1_2 = 0x0102;
    public static final byte VCARD_TYPE_21 = 0;
    public static final byte VCARD_TYPE_30 = 1;

    private Account mAccount;
    private AccountManager mAccountManager;
    private BluetoothSocket mSocket;
    private final BluetoothAdapter mAdapter;
    private final BluetoothDevice mDevice;
    // PSE SDP Record for current device.
    private SdpPseRecord mPseRec = null;
    private ClientSession mObexSession;
    private Context mContext;
    private BluetoothPbapObexAuthenticator mAuth = null;
    private final PbapClientStateMachine mPbapClientStateMachine;
    private boolean mAccountCreated;

    PbapClientConnectionHandler(Looper looper, Context context, PbapClientStateMachine stateMachine,
            BluetoothDevice device) {
        super(looper);
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mDevice = device;
        mContext = context;
        mPbapClientStateMachine = stateMachine;
        mAuth = new BluetoothPbapObexAuthenticator(this);
        mAccountManager = AccountManager.get(mPbapClientStateMachine.getContext());
        mAccount =
                new Account(mDevice.getAddress(), mContext.getString(R.string.pbap_account_type));
    }

    /**
     * Constructs PCEConnectionHandler object
     *
     * @param Builder To build  BluetoothPbapClientHandler Instance.
     */
    PbapClientConnectionHandler(Builder pceHandlerbuild) {
        super(pceHandlerbuild.mLooper);
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mDevice = pceHandlerbuild.mDevice;
        mContext = pceHandlerbuild.mContext;
        mPbapClientStateMachine = pceHandlerbuild.mClientStateMachine;
        mAuth = new BluetoothPbapObexAuthenticator(this);
        mAccountManager = AccountManager.get(mPbapClientStateMachine.getContext());
        mAccount =
                new Account(mDevice.getAddress(), mContext.getString(R.string.pbap_account_type));
    }

    public static class Builder {

        private Looper mLooper;
        private Context mContext;
        private BluetoothDevice mDevice;
        private PbapClientStateMachine mClientStateMachine;

        public Builder setLooper(Looper loop) {
            this.mLooper = loop;
            return this;
        }

        public Builder setClientSM(PbapClientStateMachine clientStateMachine) {
            this.mClientStateMachine = clientStateMachine;
            return this;
        }

        public Builder setRemoteDevice(BluetoothDevice device) {
            this.mDevice = device;
            return this;
        }

        public Builder setContext(Context context) {
            this.mContext = context;
            return this;
        }

        public PbapClientConnectionHandler build() {
            PbapClientConnectionHandler pbapClientHandler = new PbapClientConnectionHandler(this);
            return pbapClientHandler;
        }

    }

    @Override
    public void handleMessage(Message msg) {
        if (DBG) {
            Log.d(TAG, "Handling Message = " + msg.what);
        }
        switch (msg.what) {
            case MSG_CONNECT:
                mPseRec = (SdpPseRecord) msg.obj;
                /* To establish a connection, first open a socket and then create an OBEX session */
                if (connectSocket()) {
                    if (DBG) {
                        Log.d(TAG, "Socket connected");
                    }
                } else {
                    Log.w(TAG, "Socket CONNECT Failure ");
                    mPbapClientStateMachine.sendMessage(
                            PbapClientStateMachine.MSG_CONNECTION_FAILED);
                    return;
                }

                if (connectObexSession()) {
                    mPbapClientStateMachine.sendMessage(
                            PbapClientStateMachine.MSG_CONNECTION_COMPLETE);
                } else {
                    mPbapClientStateMachine.sendMessage(
                            PbapClientStateMachine.MSG_CONNECTION_FAILED);
                }
                break;

            case MSG_DISCONNECT:
                if (DBG) {
                    Log.d(TAG, "Starting Disconnect");
                }
                try {
                    if (mObexSession != null) {
                        if (DBG) {
                            Log.d(TAG, "obexSessionDisconnect" + mObexSession);
                        }
                        mObexSession.disconnect(null);
                        mObexSession.close();
                    }

                    if (DBG) {
                        Log.d(TAG, "Closing Socket");
                    }
                    closeSocket();
                } catch (IOException e) {
                    Log.w(TAG, "DISCONNECT Failure ", e);
                }
                if (DBG) {
                    Log.d(TAG, "Completing Disconnect");
                }
                removeAccount(mAccount);
                removeCallLog(mAccount);

                mPbapClientStateMachine.sendMessage(PbapClientStateMachine.MSG_CONNECTION_CLOSED);
                break;

            case MSG_DOWNLOAD:
                mAccountCreated = addAccount(mAccount);
                if (!mAccountCreated) {
                    Log.e(TAG, "Account creation failed.");
                    return;
                }
                if (isRepositorySupported(SUPPORTED_REPOSITORIES_FAVORITES)) {
                    downloadContacts(FAV_PATH);
                }
                if (isRepositorySupported(SUPPORTED_REPOSITORIES_LOCALPHONEBOOK)) {
                    downloadContacts(PB_PATH);
                }
                if (isRepositorySupported(SUPPORTED_REPOSITORIES_SIMCARD)) {
                    downloadContacts(SIM_PB_PATH);
                }

                HashMap<String, Integer> callCounter = new HashMap<>();
                downloadCallLog(MCH_PATH, callCounter);
                downloadCallLog(ICH_PATH, callCounter);
                downloadCallLog(OCH_PATH, callCounter);
                break;

            default:
                Log.w(TAG, "Received Unexpected Message");
        }
        return;
    }

    /* Utilize SDP, if available, to create a socket connection over L2CAP, RFCOMM specified
     * channel, or RFCOMM default channel. */
    private synchronized boolean connectSocket() {
        try {
            /* Use BluetoothSocket to connect */
            if (mPseRec == null) {
                // BackWardCompatability: Fall back to create RFCOMM through UUID.
                if (VDBG) Log.v(TAG, "connectSocket: UUID: " + BluetoothUuid.PBAP_PSE.getUuid());
                mSocket =
                        mDevice.createRfcommSocketToServiceRecord(BluetoothUuid.PBAP_PSE.getUuid());
            } else if (mPseRec.getL2capPsm() != L2CAP_INVALID_PSM) {
                if (VDBG) Log.v(TAG, "connectSocket: PSM: " + mPseRec.getL2capPsm());
                mSocket = mDevice.createL2capSocket(mPseRec.getL2capPsm());
            } else {
                if (VDBG) Log.v(TAG, "connectSocket: channel: " + mPseRec.getRfcommChannelNumber());
                mSocket = mDevice.createRfcommSocket(mPseRec.getRfcommChannelNumber());
            }

            if (mSocket != null) {
                mSocket.connect();
                return true;
            } else {
                Log.w(TAG, "Could not create socket");
            }
        } catch (IOException e) {
            Log.e(TAG, "Error while connecting socket", e);
        }
        return false;
    }

    /* Connect an OBEX session over the already connected socket.  First establish an OBEX Transport
     * abstraction, then establish a Bluetooth Authenticator, and finally issue the connect call */
    private boolean connectObexSession() {
        boolean connectionSuccessful = false;

        try {
            if (VDBG) {
                Log.v(TAG, "Start Obex Client Session");
            }
            BluetoothObexTransport transport = new BluetoothObexTransport(mSocket);
            mObexSession = new ClientSession(transport);
            mObexSession.setAuthenticator(mAuth);

            HeaderSet connectionRequest = new HeaderSet();
            connectionRequest.setHeader(HeaderSet.TARGET, PBAP_TARGET);

            if (mPseRec != null) {
                if (DBG) {
                    Log.d(TAG, "Remote PbapSupportedFeatures " + mPseRec.getSupportedFeatures());
                }

                ObexAppParameters oap = new ObexAppParameters();

                if (mPseRec.getProfileVersion() >= PBAP_V1_2) {
                    oap.add(BluetoothPbapRequest.OAP_TAGID_PBAP_SUPPORTED_FEATURES,
                            PBAP_SUPPORTED_FEATURE);
                }

                oap.addToHeaderSet(connectionRequest);
            }
            HeaderSet connectionResponse = mObexSession.connect(connectionRequest);

            connectionSuccessful =
                    (connectionResponse.getResponseCode() == ResponseCodes.OBEX_HTTP_OK);
            if (DBG) {
                Log.d(TAG, "Success = " + Boolean.toString(connectionSuccessful));
            }
        } catch (IOException | NullPointerException e) {
            // Will get NPE if a null mSocket is passed to BluetoothObexTransport.
            // mSocket can be set to null if an abort() --> closeSocket() was called between
            // the calls to connectSocket() and connectObexSession().
            Log.w(TAG, "CONNECT Failure " + e.toString());
            closeSocket();
        }
        return connectionSuccessful;
    }

    public void abort() {
        // Perform forced cleanup, it is ok if the handler throws an exception this will free the
        // handler to complete what it is doing and finish with cleanup.
        closeSocket();
        this.getLooper().getThread().interrupt();
    }

    private synchronized void closeSocket() {
        try {
            if (mSocket != null) {
                if (DBG) {
                    Log.d(TAG, "Closing socket" + mSocket);
                }
                mSocket.close();
                mSocket = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error when closing socket", e);
            mSocket = null;
        }
    }

    void downloadContacts(String path) {
        try {
            PhonebookPullRequest processor =
                    new PhonebookPullRequest(mPbapClientStateMachine.getContext(),
                            mAccount);

            // Download contacts in batches of size DEFAULT_BATCH_SIZE
            BluetoothPbapRequestPullPhoneBookSize requestPbSize =
                    new BluetoothPbapRequestPullPhoneBookSize(path,
                            PBAP_REQUESTED_FIELDS);
            requestPbSize.execute(mObexSession);

            int numberOfContactsRemaining = requestPbSize.getSize();
            int startOffset = 0;
            if (PB_PATH.equals(path)) {
                // PBAP v1.2.3, Sec 3.1.5. The first contact in pb is owner card 0.vcf, which we
                // do not want to download. The other phonebook objects (e.g., fav) don't have an
                // owner card, so they don't need an offset.
                startOffset = 1;
                // "-1" because Owner Card 0.vcf is also included in /pb, but not in /fav.
                numberOfContactsRemaining -= 1;
            }

            while ((numberOfContactsRemaining > 0) && (startOffset <= UPPER_LIMIT)) {
                int numberOfContactsToDownload =
                        Math.min(Math.min(DEFAULT_BATCH_SIZE, numberOfContactsRemaining),
                        UPPER_LIMIT - startOffset + 1);
                BluetoothPbapRequestPullPhoneBook request =
                        new BluetoothPbapRequestPullPhoneBook(path, mAccount,
                                PBAP_REQUESTED_FIELDS, VCARD_TYPE_30,
                                numberOfContactsToDownload, startOffset);
                request.execute(mObexSession);
                ArrayList<VCardEntry> vcards = request.getList();
                if (path == FAV_PATH) {
                    // mark each vcard as a favorite
                    for (VCardEntry v : vcards) {
                        v.setStarred(true);
                    }
                }
                processor.setResults(vcards);
                processor.onPullComplete();

                startOffset += numberOfContactsToDownload;
                numberOfContactsRemaining -= numberOfContactsToDownload;
            }
            if ((startOffset > UPPER_LIMIT) && (numberOfContactsRemaining > 0)) {
                Log.w(TAG, "Download contacts incomplete, index exceeded upper limit.");
            }
        } catch (IOException e) {
            Log.w(TAG, "Download contacts failure" + e.toString());
        }
    }

    void downloadCallLog(String path, HashMap<String, Integer> callCounter) {
        try {
            BluetoothPbapRequestPullPhoneBook request =
                    new BluetoothPbapRequestPullPhoneBook(path, mAccount, 0, VCARD_TYPE_30, 0, 0);
            request.execute(mObexSession);
            CallLogPullRequest processor =
                    new CallLogPullRequest(mPbapClientStateMachine.getContext(), path,
                        callCounter, mAccount);
            processor.setResults(request.getList());
            processor.onPullComplete();
        } catch (IOException e) {
            Log.w(TAG, "Download call log failure");
        }
    }

    private boolean addAccount(Account account) {
        if (mAccountManager.addAccountExplicitly(account, null, null)) {
            if (DBG) {
                Log.d(TAG, "Added account " + mAccount);
            }
            return true;
        }
        return false;
    }

    private void removeAccount(Account account) {
        if (mAccountManager.removeAccountExplicitly(account)) {
            if (DBG) {
                Log.d(TAG, "Removed account " + account);
            }
        } else {
            Log.e(TAG, "Failed to remove account " + mAccount);
        }
    }

    private void removeCallLog(Account account) {
        try {
            // need to check call table is exist ?
            if (mContext.getContentResolver() == null) {
                if (DBG) {
                    Log.d(TAG, "CallLog ContentResolver is not found");
                }
                return;
            }
            mContext.getContentResolver().delete(CallLog.Calls.CONTENT_URI,
                    Calls.PHONE_ACCOUNT_ID + "=?", new String[]{mAccount.name});
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "Call Logs could not be deleted, they may not exist yet.");
        }
    }

    private boolean isRepositorySupported(int mask) {
        if (mPseRec == null) {
            if (VDBG) Log.v(TAG, "No PBAP Server SDP Record");
            return false;
        }
        return (mask & mPseRec.getSupportedRepositories()) != 0;
    }
}
