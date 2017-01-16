package com.ericsson.research.owr.examples.nativecall;

import org.apache.http.conn.ssl.SSLSocketFactory;
import javax.net.ssl.SSLContext;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.UnrecoverableKeyException;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import java.net.Socket;
import java.io.IOException;
import java.net.UnknownHostException;
/**
 * Taken from: http://janis.peisenieks.lv/en/76/english-making-an-ssl-connection-via-android/
 *
 */
public class CustomSSLSocketFactory extends SSLSocketFactory {
    SSLContext sslContext = SSLContext.getInstance("TLS");

    private javax.net.ssl.SSLSocketFactory socketfactory;

    public CustomSSLSocketFactory(KeyStore truststore)
            throws NoSuchAlgorithmException, KeyManagementException,
            KeyStoreException, UnrecoverableKeyException {
        super(truststore);

        TrustManager tm = new CustomX509TrustManager();

        sslContext.init(null, new TrustManager[] { tm }, null);
    }

    public CustomSSLSocketFactory(SSLContext context)
            throws KeyManagementException, NoSuchAlgorithmException,
            KeyStoreException, UnrecoverableKeyException {
        super(null);
        sslContext = context;
        this.socketfactory = sslContext.getSocketFactory();
    }

//    @Override
//    public Socket createSocket(Socket socket, String host, int port,
//                               boolean autoClose) throws IOException, UnknownHostException {
//        return sslContext.getSocketFactory().createSocket(socket, host, port,
//                autoClose);
//    }
//
//    @Override
//    public Socket createSocket() throws IOException {
//        return sslContext.getSocketFactory().createSocket();
//    }


    public Socket createSocket() throws IOException {
        SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket();

        socket.setEnabledProtocols(new String[] {"TLSv1"});

        return socket;
    }

    public Socket createSocket(
            final Socket socket,
            final String host,
            final int port,
            final boolean autoClose
    ) throws IOException, UnknownHostException {

        SSLSocket sslSocket =  (SSLSocket) sslContext.getSocketFactory().createSocket(socket, host, port,
                autoClose);

        sslSocket.setEnabledProtocols(new String[] { "TLSv1"});

       // getHostnameVerifier().verify(host, sslSocket);

        return sslSocket;
    }

}
