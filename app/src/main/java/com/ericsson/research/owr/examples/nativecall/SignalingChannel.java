/*
 * Copyright (c) 2014, Ericsson AB. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this
 * list of conditions and the following disclaimer in the documentation and/or other
 * materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
 * OF SUCH DAMAGE.
 */

package com.ericsson.research.owr.examples.nativecall;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.client.CookieStore;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.StringBuilder;
import java.math.BigInteger;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import javax.net.SocketFactory;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class SignalingChannel {
    public static final String TAG = "EventSource";

    private final HttpClient mHttpSendClient = new DefaultHttpClient();
    private final Handler mMainHandler;
    private final String mClientToServerUrl;
    private final String mServerToClientUrl;
    private Handler mSendHandler;
    private InputStream mEventStream;
    private Map<String, PeerChannel> mPeerChannels = new HashMap<>();
    private JoinListener mJoinListener;
    private DisconnectListener mDisconnectListener;
    private SessionFullListener mSessionFullListener;

    public SignalingChannel(String baseUrl, String session) {
        String userId = new BigInteger(40, new Random()).toString(32);
        mServerToClientUrl = baseUrl + "/stoc/" + session + "/" + userId;
        mClientToServerUrl = baseUrl + "/ctos/" + session + "/" + userId;
        mMainHandler = new Handler(Looper.getMainLooper());
        Thread sendThread = new SendThread();
        sendThread.start();
        open();
    }

    private class SendThread extends Thread {
        @Override
        public void run() {
            Looper.prepare();
            mSendHandler = new Handler();
            Looper.loop();
            Log.d(TAG, "SendThread: quit");
        }
    }

    private HttpClient getHttpClient() {
        SSLContext ctx = null;
        try {
            ctx = SSLContext.getInstance("TLS");
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        try {
            ctx.init(null, new TrustManager[]{new CustomX509TrustManager()},
                    new SecureRandom());
        } catch (KeyManagementException e) {
            e.printStackTrace();
        }

        HttpClient client = new DefaultHttpClient();

        SSLSocketFactory ssf = null;
        try {
            ssf = new CustomSSLSocketFactory(ctx);
        } catch (KeyManagementException e) {
            e.printStackTrace();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (KeyStoreException e) {
            e.printStackTrace();
        } catch (UnrecoverableKeyException e) {
            e.printStackTrace();
        }
        ssf.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
        ClientConnectionManager ccm = client.getConnectionManager();
        SchemeRegistry sr = ccm.getSchemeRegistry();
        sr.register(new Scheme("https", ssf, 443));
        DefaultHttpClient sslClient = new DefaultHttpClient(ccm,
                client.getParams());
        return sslClient;
    }




    private void open() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpClient httpClient = getHttpClient(); //snew DefaultHttpClient();
                HttpGet httpGet = new HttpGet(mServerToClientUrl);

                try {

                    HttpResponse httpResponse = httpClient.execute(httpGet);
                    HttpEntity httpEntity = httpResponse.getEntity();

                    if (httpEntity != null) {
                        mEventStream = httpEntity.getContent();
                        BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(mEventStream));
                        readEventStream(bufferedReader);
                    }
                } catch (IOException exception) {
                    Log.e(TAG, "SSE: " + exception);
                    exception.printStackTrace();
                } finally {
                    if (mEventStream != null) {
                        try {
                            mEventStream.close();
                        } catch (IOException ignored) {
                        }
                        mEventStream = null;
                    }
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            for (PeerChannel peerChannel : mPeerChannels.values()) {
                                peerChannel.onDisconnect();
                            }
                            if (mDisconnectListener != null) {
                                mDisconnectListener.onDisconnect();
                            }
                        }
                    });
                }
            }
        }).start();
    }

    private void readEventStream(final BufferedReader bufferedReader) throws IOException {
        String line;
        while ((line = bufferedReader.readLine()) != null) {
            if (line.length() > 1) {
                final String[] eventSplit = line.split(":", 2);
                final StringBuilder data = new StringBuilder();

                if (eventSplit.length != 2 || !eventSplit[0].equals("event")) {
                    Log.w(TAG, "SSE: invalid event: " + line + " => " + Arrays.toString(eventSplit));
                    while (!(line = bufferedReader.readLine()).isEmpty()) {
                        Log.w(TAG, "SSE: skipped after malformed event: " + line);
                    }
                    break;
                }

                final String event = eventSplit[1];

                while ((line = bufferedReader.readLine()) != null) {
                    if (line.length() > 1) {
                        final String[] dataSplit = line.split(":", 2);

                        if (dataSplit.length != 2 || !dataSplit[0].equals("data")) {
                            Log.w(TAG, "SSE: invalid data: " + line + " => " + Arrays.toString(dataSplit));
                        }
                        data.append(dataSplit[1]);
                    } else {
                        mMainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                handleEvent(event, data.toString());
                            }
                        });
                        break;
                    }
                }
            }
        }
    }

    private void handleEvent(final String event, final String data) {
        if (event.startsWith("user-")) {
            String peer = event.substring(5);
            PeerChannel peerChannel = mPeerChannels.get(peer);
            if (peerChannel != null) {
                peerChannel.onMessage(data);
            }
        } else if (event.equals("join")) {
            PeerChannel peerChannel = new PeerChannel(data);
            mPeerChannels.put(data, peerChannel);
            if (mJoinListener != null) {
                mJoinListener.onPeerJoin(peerChannel);
            }
        } else if (event.equals("leave")) {
            PeerChannel peerChannel = mPeerChannels.remove(data);
            if (peerChannel != null) {
                peerChannel.onDisconnect();
            }
        } else if (event.equals("sessionfull")) {
            if (mSessionFullListener != null) {
                mSessionFullListener.onSessionFull();
            }
        } else {
            Log.w(TAG, "unhandled event: " + event);
        }
    }

    public void setJoinListener(JoinListener joinListener) {
        mJoinListener = joinListener;
    }

    public void setDisconnectListener(DisconnectListener onDisconnectListener) {
        mDisconnectListener = onDisconnectListener;
    }

    public void setSessionFullListener(final SessionFullListener sessionFullListener) {
        mSessionFullListener = sessionFullListener;
    }

    public interface MessageListener {
        public void onMessage(JSONObject data);
    }

    public interface JoinListener {
        public void onPeerJoin(final PeerChannel peerChannel);
    }

    public interface SessionFullListener {
        public void onSessionFull();
    }

    public interface DisconnectListener {
        public void onDisconnect();
    }

    public interface PeerDisconnectListener {
        public void onPeerDisconnect(final PeerChannel peerChannel);
    }

    public class PeerChannel {
        private final String mPeerId;
        private MessageListener mMessageListener;
        private PeerDisconnectListener mDisconnectListener;
        private boolean mDisconnected = false;

        private PeerChannel(String peerId) {
            mPeerId = peerId;
        }

        public void send(final JSONObject message) {
            if (mDisconnected) {
                Log.w(TAG, "tried to send message to disconnected peer: " + mPeerId);
                return;
            }
            mSendHandler.post(new Runnable() {
                @Override
                public void run() {
                    HttpPost httpPost = new HttpPost(mClientToServerUrl + "/" + mPeerId);
                    try {
                        httpPost.setEntity(new ByteArrayEntity(message.toString().getBytes("UTF8")));
                        mHttpSendClient.execute(httpPost);
                    } catch (IOException exception) {
                        Log.e(TAG, "failed to send message to " + mPeerId + ": " + exception.toString());
                    }
                }
            });
        }

        private void onMessage(String message) {
            if (mDisconnected) {
                Log.w(TAG, "got message from disconnected peer: " + mPeerId);
                return;
            }
            if (mMessageListener != null) {
                try {
                    JSONObject json = new JSONObject(message);
                    mMessageListener.onMessage(json);
                } catch (JSONException exception) {
                    Log.w(TAG, "failed to decode message: " + exception);
                }
            }
        }

        private void onDisconnect() {
            mDisconnected = true;
            if (mDisconnectListener != null) {
                mDisconnectListener.onPeerDisconnect(this);
                mDisconnectListener = null;
                mMessageListener = null;
            }
        }

        public void setMessageListener(final MessageListener messageListener) {
            mMessageListener = messageListener;
        }

        public void setDisconnectListener(final PeerDisconnectListener onDisconnectListener) {
            mDisconnectListener = onDisconnectListener;
        }

        public String getPeerId() {
            return mPeerId;
        }

        @Override
        public String toString() {
            return "User[" + mPeerId + "]";
        }
    }
}
