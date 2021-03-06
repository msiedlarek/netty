/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.ssl;

import io.netty.buffer.ByteBufAllocator;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.apache.tomcat.jni.Pool;
import org.apache.tomcat.jni.SSL;
import org.apache.tomcat.jni.SSLContext;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import static io.netty.util.internal.ObjectUtil.checkNotNull;

public abstract class OpenSslContext extends SslContext {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(OpenSslContext.class);
    private static final List<String> DEFAULT_CIPHERS;
    private static final AtomicIntegerFieldUpdater<OpenSslContext> DESTROY_UPDATER;

    // TODO: Maybe make configurable ?
    protected static final int VERIFY_DEPTH = 10;

    private final long aprPool;
    @SuppressWarnings("unused")
    private volatile int aprPoolDestroyed;
    private final List<String> ciphers = new ArrayList<String>();
    private final List<String> unmodifiableCiphers = Collections.unmodifiableList(ciphers);
    private final long sessionCacheSize;
    private final long sessionTimeout;
    private final OpenSslApplicationProtocolNegotiator apn;
    /** The OpenSSL SSL_CTX object */
    protected final long ctx;
    private final int mode;

    static {
        List<String> ciphers = new ArrayList<String>();
        // XXX: Make sure to sync this list with JdkSslEngineFactory.
        Collections.addAll(
                ciphers,
                "ECDHE-RSA-AES128-GCM-SHA256",
                "ECDHE-RSA-AES128-SHA",
                "ECDHE-RSA-AES256-SHA",
                "AES128-GCM-SHA256",
                "AES128-SHA",
                "AES256-SHA",
                "DES-CBC3-SHA",
                "RC4-SHA");
        DEFAULT_CIPHERS = Collections.unmodifiableList(ciphers);

        if (logger.isDebugEnabled()) {
            logger.debug("Default cipher suite (OpenSSL): " + ciphers);
        }

        AtomicIntegerFieldUpdater<OpenSslContext> updater =
                PlatformDependent.newAtomicIntegerFieldUpdater(OpenSslContext.class, "aprPoolDestroyed");
        if (updater == null) {
            updater = AtomicIntegerFieldUpdater.newUpdater(OpenSslContext.class, "aprPoolDestroyed");
        }
        DESTROY_UPDATER = updater;
    }

    OpenSslContext(Iterable<String> ciphers, ApplicationProtocolConfig apnCfg, long sessionCacheSize,
                   long sessionTimeout, int mode) throws SSLException {
        this(ciphers, toNegotiator(apnCfg, mode == SSL.SSL_MODE_SERVER), sessionCacheSize, sessionTimeout, mode);
    }

    OpenSslContext(Iterable<String> ciphers, OpenSslApplicationProtocolNegotiator apn, long sessionCacheSize,
                   long sessionTimeout, int mode) throws SSLException {
        OpenSsl.ensureAvailability();

        if (mode != SSL.SSL_MODE_SERVER && mode != SSL.SSL_MODE_CLIENT) {
            throw new IllegalArgumentException("mode most be either SSL.SSL_MODE_SERVER or SSL.SSL_MODE_CLIENT");
        }
        this.mode = mode;

        if (ciphers == null) {
            ciphers = DEFAULT_CIPHERS;
        }

        for (String c: ciphers) {
            if (c == null) {
                break;
            }
            this.ciphers.add(c);
        }

        this.apn = checkNotNull(apn, "apn");

        // Allocate a new APR pool.
        aprPool = Pool.create(0);

        // Create a new SSL_CTX and configure it.
        boolean success = false;
        try {
            synchronized (OpenSslContext.class) {
                try {
                    ctx = SSLContext.make(aprPool, SSL.SSL_PROTOCOL_ALL, mode);
                } catch (Exception e) {
                    throw new SSLException("failed to create an SSL_CTX", e);
                }

                SSLContext.setOptions(ctx, SSL.SSL_OP_ALL);
                SSLContext.setOptions(ctx, SSL.SSL_OP_NO_SSLv2);
                SSLContext.setOptions(ctx, SSL.SSL_OP_NO_SSLv3);
                SSLContext.setOptions(ctx, SSL.SSL_OP_CIPHER_SERVER_PREFERENCE);
                SSLContext.setOptions(ctx, SSL.SSL_OP_SINGLE_ECDH_USE);
                SSLContext.setOptions(ctx, SSL.SSL_OP_SINGLE_DH_USE);
                SSLContext.setOptions(ctx, SSL.SSL_OP_NO_SESSION_RESUMPTION_ON_RENEGOTIATION);

                /* List the ciphers that are permitted to negotiate. */
                try {
                    // Convert the cipher list into a colon-separated string.
                    StringBuilder cipherBuf = new StringBuilder();
                    for (String c: this.ciphers) {
                        cipherBuf.append(c);
                        cipherBuf.append(':');
                    }
                    cipherBuf.setLength(cipherBuf.length() - 1);

                    SSLContext.setCipherSuite(ctx, cipherBuf.toString());
                } catch (SSLException e) {
                    throw e;
                } catch (Exception e) {
                    throw new SSLException("failed to set cipher suite: " + this.ciphers, e);
                }

                List<String> nextProtoList = apn.protocols();
                /* Set next protocols for next protocol negotiation extension, if specified */
                if (!nextProtoList.isEmpty()) {
                    // Convert the protocol list into a comma-separated string.
                    StringBuilder nextProtocolBuf = new StringBuilder();
                    for (String p: nextProtoList) {
                        nextProtocolBuf.append(p);
                        nextProtocolBuf.append(',');
                    }
                    nextProtocolBuf.setLength(nextProtocolBuf.length() - 1);

                    SSLContext.setNextProtos(ctx, nextProtocolBuf.toString());
                }

                /* Set session cache size, if specified */
                if (sessionCacheSize > 0) {
                    this.sessionCacheSize = sessionCacheSize;
                    SSLContext.setSessionCacheSize(ctx, sessionCacheSize);
                } else {
                    // Get the default session cache size using SSLContext.setSessionCacheSize()
                    this.sessionCacheSize = sessionCacheSize = SSLContext.setSessionCacheSize(ctx, 20480);
                    // Revert the session cache size to the default value.
                    SSLContext.setSessionCacheSize(ctx, sessionCacheSize);
                }

                /* Set session timeout, if specified */
                if (sessionTimeout > 0) {
                    this.sessionTimeout = sessionTimeout;
                    SSLContext.setSessionCacheTimeout(ctx, sessionTimeout);
                } else {
                    // Get the default session timeout using SSLContext.setSessionCacheTimeout()
                    this.sessionTimeout = sessionTimeout = SSLContext.setSessionCacheTimeout(ctx, 300);
                    // Revert the session timeout to the default value.
                    SSLContext.setSessionCacheTimeout(ctx, sessionTimeout);
                }
            }
            success = true;
        } finally {
            if (!success) {
                destroyPools();
            }
        }
    }

    @Override
    public final List<String> cipherSuites() {
        return unmodifiableCiphers;
    }

    @Override
    public final long sessionCacheSize() {
        return sessionCacheSize;
    }

    @Override
    public final long sessionTimeout() {
        return sessionTimeout;
    }

    @Override
    public ApplicationProtocolNegotiator applicationProtocolNegotiator() {
        return apn;
    }

    @Override
    public final boolean isClient() {
        return mode == SSL.SSL_MODE_CLIENT;
    }

    @Override
    public final SSLEngine newEngine(ByteBufAllocator alloc, String peerHost, int peerPort) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns a new server-side {@link javax.net.ssl.SSLEngine} with the current configuration.
     */
    @Override
    public final SSLEngine newEngine(ByteBufAllocator alloc) {
        List<String> protos = applicationProtocolNegotiator().protocols();
        if (protos.isEmpty()) {
            return new OpenSslEngine(ctx, alloc, null, isClient(), sessionContext());
        } else {
            return new OpenSslEngine(ctx, alloc, protos.get(protos.size() - 1), isClient(), sessionContext());
        }
    }

    /**
     * Returns the {@code SSL_CTX} object of this context.
     */
    public final long context() {
        return ctx;
    }

    /**
     * Returns the stats of this context.
     * @deprecated use {@link #sessionContext#stats()}
     */
    @Deprecated
    public final OpenSslSessionStats stats() {
        return sessionContext().stats();
    }

    @Override
    @SuppressWarnings("FinalizeDeclaration")
    protected final void finalize() throws Throwable {
        super.finalize();
        synchronized (OpenSslContext.class) {
            if (ctx != 0) {
                SSLContext.free(ctx);
            }
        }

        destroyPools();
    }

    /**
     * Sets the SSL session ticket keys of this context.
     * @deprecated use {@link OpenSslSessionContext#setTicketKeys(byte[])}
     */
    @Deprecated
    public final void setTicketKeys(byte[] keys) {
        sessionContext().setTicketKeys(keys);
    }

    @Override
    public abstract OpenSslSessionContext sessionContext();

    protected final void destroyPools() {
        // Guard against multiple destroyPools() calls triggered by construction exception and finalize() later
        if (aprPool != 0 && DESTROY_UPDATER.compareAndSet(this, 0, 1)) {
            Pool.destroy(aprPool);
        }
    }

    protected static X509Certificate[] certificates(byte[][] chain) {
        X509Certificate[] peerCerts = new X509Certificate[chain.length];
        for (int i = 0; i < peerCerts.length; i++) {
            peerCerts[i] = new OpenSslX509Certificate(chain[i]);
        }
        return peerCerts;
    }

    protected static X509TrustManager chooseTrustManager(TrustManager[] managers) {
        for (TrustManager m: managers) {
            if (m instanceof X509TrustManager) {
                return (X509TrustManager) m;
            }
        }
        throw new IllegalStateException("no X509TrustManager found");
    }

    /**
     * Translate a {@link ApplicationProtocolConfig} object to a
     * {@link OpenSslApplicationProtocolNegotiator} object.
     * @param config The configuration which defines the translation
     * @param isServer {@code true} if a server {@code false} otherwise.
     * @return The results of the translation
     */
    static OpenSslApplicationProtocolNegotiator toNegotiator(ApplicationProtocolConfig config,
                                                             boolean isServer) {
        if (config == null) {
            return OpenSslDefaultApplicationProtocolNegotiator.INSTANCE;
        }

        switch (config.protocol()) {
            case NONE:
                return OpenSslDefaultApplicationProtocolNegotiator.INSTANCE;
            case NPN:
                if (isServer) {
                    switch (config.selectedListenerFailureBehavior()) {
                        case CHOOSE_MY_LAST_PROTOCOL:
                            return new OpenSslNpnApplicationProtocolNegotiator(config.supportedProtocols());
                        default:
                            throw new UnsupportedOperationException(
                                    new StringBuilder("OpenSSL provider does not support ")
                                            .append(config.selectedListenerFailureBehavior())
                                            .append(" behavior").toString());
                    }
                } else {
                    throw new UnsupportedOperationException("OpenSSL provider does not support client mode");
                }
            default:
                throw new UnsupportedOperationException(new StringBuilder("OpenSSL provider does not support ")
                        .append(config.protocol()).append(" protocol").toString());
        }
    }
}
