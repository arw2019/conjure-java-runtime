/*
 * (c) Copyright 2017 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.conjure.java.okhttp;

import static com.palantir.logsafe.Preconditions.checkArgument;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HttpHeaders;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.palantir.conjure.java.api.config.service.BasicCredentials;
import com.palantir.conjure.java.api.config.service.UserAgent;
import com.palantir.conjure.java.api.config.service.UserAgent.Agent;
import com.palantir.conjure.java.api.config.service.UserAgents;
import com.palantir.conjure.java.client.config.CipherSuites;
import com.palantir.conjure.java.client.config.ClientConfiguration;
import com.palantir.conjure.java.client.config.NodeSelectionStrategy;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.exceptions.SafeIllegalArgumentException;
import com.palantir.logsafe.exceptions.SafeIllegalStateException;
import com.palantir.logsafe.logger.SafeLogger;
import com.palantir.logsafe.logger.SafeLoggerFactory;
import com.palantir.tracing.Tracers;
import com.palantir.tritium.metrics.MetricRegistries;
import com.palantir.tritium.metrics.registry.SharedTaggedMetricRegistries;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.ConnectionPool;
import okhttp3.ConnectionSpec;
import okhttp3.Credentials;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.TlsVersion;
import okhttp3.internal.Util;

public final class OkHttpClients {
    private static final SafeLogger log = SafeLoggerFactory.get(OkHttpClients.class);
    private static final boolean RANDOMIZE = true;
    private static final boolean RESHUFFLE = true;

    @VisibleForTesting
    static final int NUM_SCHEDULING_THREADS = 5;

    private static final boolean DEFAULT_ENABLE_HTTP2 = false;

    private static final ThreadFactory executionThreads = instrument(
            new ThreadFactoryBuilder()
                    .setUncaughtExceptionHandler((_thread, uncaughtException) -> log.error(
                            "An exception was uncaught in an execution thread. "
                                    + "This likely left a thread blocked, and is as such a serious bug "
                                    + "which requires debugging.",
                            uncaughtException))
                    .setNameFormat("remoting-okhttp-dispatcher-%d")
                    // This diverges from the OkHttp default value, allowing the JVM to cleanly exit
                    // while idle dispatcher threads are still alive.
                    .setDaemon(true)
                    .build(),
            "remoting-okhttp-dispatcher");

    /**
     * The {@link ExecutorService} used for the {@link Dispatcher}s of all OkHttp clients created through this class.
     * Similar to OkHttp's default, but with two modifications:
     *
     * <ol>
     *   <li>A logging uncaught exception handler
     *   <li>Daemon threads: active request will not block JVM shutdown <b>unless</b> another non-daemon thread blocks
     *       waiting for the result. Most of our usage falls into this category. This allows JVM shutdown to occur
     *       cleanly without waiting a full minute after the last request completes.
     * </ol>
     */
    private static final ExecutorService executionExecutor = Executors.newCachedThreadPool(executionThreads);

    /** Shared dispatcher with static executor service. */
    private static final Dispatcher dispatcher;

    /** Shared connection pool. */
    private static final ConnectionPool connectionPool = new ConnectionPool(
            1000,
            // Most servers use a one minute keepalive for idle connections, by using a shorter keepalive on
            // clients we can avoid race conditions where the attempts to reuse a connection as the server
            // closes it, resulting in unnecessary I/O exceptions and retrial.
            55,
            TimeUnit.SECONDS);

    private static DispatcherMetricSet dispatcherMetricSet;

    static {
        dispatcher = new Dispatcher(executionExecutor);
        // Restricting concurrency is done elsewhere in ConcurrencyLimiters.
        dispatcher.setMaxRequests(Integer.MAX_VALUE);
        // Must be less than maxRequests so a single slow host does not block all requests
        dispatcher.setMaxRequestsPerHost(256);

        dispatcherMetricSet = new DispatcherMetricSet(dispatcher, connectionPool);
    }

    /** The {@link ScheduledExecutorService} used for recovering leaked limits. */
    private static final Supplier<ScheduledExecutorService> limitReviver =
            Suppliers.memoize(() -> Tracers.wrap(Executors.newSingleThreadScheduledExecutor(instrument(
                    Util.threadFactory("conjure-java-runtime/leaked limit reviver", true),
                    "conjure-java-runtime/leaked limit reviver"))));

    /**
     * The {@link ScheduledExecutorService} used for scheduling call retries. This thread pool is distinct from OkHttp's
     * internal thread pool and from the thread pool used by {@link #executionExecutor}.
     *
     * <p>Note: In contrast to the {@link java.util.concurrent.ThreadPoolExecutor} used by OkHttp's
     * {@link #executionExecutor}, {@code corePoolSize} must not be zero for a {@link ScheduledThreadPoolExecutor}, see
     * its Javadoc. Since this executor will never hit zero threads, it must use daemon threads.
     */
    private static final Supplier<ScheduledExecutorService> schedulingExecutor =
            Suppliers.memoize(() -> Tracers.wrap(Executors.newScheduledThreadPool(
                    NUM_SCHEDULING_THREADS,
                    instrument(
                            Util.threadFactory("conjure-java-runtime/OkHttp Scheduler", true),
                            "conjure-java-runtime/OkHttp Scheduler"))));

    private OkHttpClients() {}

    /**
     * Creates an OkHttp client from the given {@link ClientConfiguration}. Note that the configured
     * {@link ClientConfiguration#uris URIs} are initialized in random order.
     */
    public static OkHttpClient create(
            ClientConfiguration config, UserAgent userAgent, HostEventsSink hostEventsSink, Class<?> serviceClass) {
        return create(new OkHttpClient.Builder(), config, userAgent, hostEventsSink, serviceClass);
    }

    /**
     * Creates an OkHttp client from the given {@link ClientConfiguration} and {@link OkHttpClient.Builder}.
     *
     * <p>Note that the builder configuration will be overwritten by any options required by Conjure. Note that the
     * configured {@link ClientConfiguration#uris URIs} are initialized in random order.
     */
    public static OkHttpClient create(
            OkHttpClient.Builder client,
            ClientConfiguration config,
            UserAgent userAgent,
            HostEventsSink hostEventsSink,
            Class<?> serviceClass) {
        boolean reshuffle =
                !config.nodeSelectionStrategy().equals(NodeSelectionStrategy.PIN_UNTIL_ERROR_WITHOUT_RESHUFFLE);

        ClientConfiguration config1 = ClientConfiguration.builder()
                .from(config)
                .userAgent(userAgent)
                .hostEventsSink(Optional.ofNullable(hostEventsSink))
                .build();

        return createInternal(
                client,
                config1,
                serviceClass,
                RANDOMIZE,
                reshuffle,
                () -> new ExponentialBackoff(config1.maxNumRetries(), config1.backoffSlotSize()));
    }

    @VisibleForTesting
    static RemotingOkHttpClient withStableUris(
            ClientConfiguration config, HostEventsSink hostEventsSink, Class<?> serviceClass) {
        return createInternal(
                new OkHttpClient.Builder(),
                ClientConfiguration.builder()
                        .from(config)
                        .hostEventsSink(hostEventsSink)
                        .build(),
                serviceClass,
                !RANDOMIZE,
                !RESHUFFLE,
                () -> new ExponentialBackoff(config.maxNumRetries(), config.backoffSlotSize()));
    }

    @VisibleForTesting
    static RemotingOkHttpClient withStableUrisAndBackoff(
            ClientConfiguration config, Class<?> serviceClass, Supplier<BackoffStrategy> backoffStrategy) {
        return createInternal(
                new OkHttpClient.Builder(), config, serviceClass, !RANDOMIZE, !RESHUFFLE, backoffStrategy);
    }

    private static RemotingOkHttpClient createInternal(
            OkHttpClient.Builder client,
            ClientConfiguration config,
            Class<?> serviceClass,
            boolean randomizeUrlOrder,
            boolean reshuffle,
            Supplier<BackoffStrategy> backoffStrategyFunction) {
        boolean enableClientQoS = shouldEnableQos(config.clientQoS());
        ConcurrencyLimiters concurrencyLimiters = new ConcurrencyLimiters(
                limitReviver.get(), config.taggedMetricRegistry(), serviceClass, enableClientQoS);

        client.addInterceptor(CatchThrowableInterceptor.INSTANCE);
        client.addInterceptor(SpanTerminatingInterceptor.INSTANCE);
        // Order is important, this interceptor must be applied prior to ConcurrencyLimitingInterceptor
        // in order to prevent concurrency limiters from leaking.
        client.addInterceptor(ResponseCapturingInterceptor.INSTANCE);

        // Routing
        if (config.nodeSelectionStrategy().equals(NodeSelectionStrategy.ROUND_ROBIN)) {
            checkArgument(
                    !config.failedUrlCooldown().isZero(),
                    "If nodeSelectionStrategy is ROUND_ROBIN then failedUrlCooldown must be positive");
        }
        UrlSelectorImpl urlSelector = UrlSelectorImpl.createWithFailedUrlCooldown(
                randomizeUrlOrder ? UrlSelectorImpl.shuffle(config.uris()) : config.uris(),
                reshuffle,
                config.failedUrlCooldown(),
                Clock.systemUTC());
        if (config.meshProxy().isPresent()) {
            // TODO(rfink): Should this go into the call itself?
            client.addInterceptor(new MeshProxyInterceptor(config.meshProxy().get()));
        }
        client.followRedirects(false); // We implement our own redirect logic.

        // SSL
        SSLSocketFactory sslSocketFactory = MetricRegistries.instrument(
                config.taggedMetricRegistry(),
                new KeepAliveSslSocketFactory(config.sslSocketFactory()),
                serviceClass.getSimpleName());
        client.sslSocketFactory(sslSocketFactory, config.trustManager());
        if (config.fallbackToCommonNameVerification()) {
            client.hostnameVerifier(Okhttp39HostnameVerifier.INSTANCE);
        }

        // Intercept calls to augment request meta data
        if (enableClientQoS) {
            client.addInterceptor(new ConcurrencyLimitingInterceptor());
        }
        ClientMetrics clientMetrics = ClientMetrics.of(config.taggedMetricRegistry());
        client.addInterceptor(DeprecationWarningInterceptor.create(clientMetrics, serviceClass));
        client.addInterceptor(InstrumentedInterceptor.create(
                clientMetrics, config.hostEventsSink().orElse(NoOpHostEventsSink.INSTANCE), serviceClass));
        client.addInterceptor(OkhttpTraceInterceptor.INSTANCE);
        UserAgent agent =
                config.userAgent().orElseThrow(() -> new SafeIllegalArgumentException("UserAgent is required"));
        client.addInterceptor(UserAgentInterceptor.of(augmentUserAgent(agent, serviceClass)));

        // timeouts
        // Note that Feign overrides OkHttp timeouts with the timeouts given in FeignBuilder#Options if given, or
        // with its own default otherwise.
        client.connectTimeout(config.connectTimeout());
        client.readTimeout(config.readTimeout());
        client.writeTimeout(config.writeTimeout());

        // proxy
        client.proxySelector(config.proxy());
        if (config.proxyCredentials().isPresent()) {
            BasicCredentials basicCreds = config.proxyCredentials().get();
            final String credentials = Credentials.basic(basicCreds.username(), basicCreds.password());
            client.proxyAuthenticator((_route, response) -> response.request()
                    .newBuilder()
                    .header(HttpHeaders.PROXY_AUTHORIZATION, credentials)
                    .build());
        }

        client.connectionSpecs(createConnectionSpecs());
        if (!config.enableHttp2().orElse(DEFAULT_ENABLE_HTTP2)) {
            client.protocols(ImmutableList.of(Protocol.HTTP_1_1));
        }

        // increase default connection pool from 5 @ 5 minutes to 100 @ 10 minutes
        client.connectionPool(connectionPool);

        client.dispatcher(dispatcher);

        // global metrics (addMetrics is idempotent, so this works even when multiple clients are created)
        config.taggedMetricRegistry()
                .addMetrics("from", DispatcherMetricSet.class.getSimpleName(), dispatcherMetricSet);

        return new RemotingOkHttpClient(
                client.build(),
                backoffStrategyFunction,
                config.nodeSelectionStrategy(),
                urlSelector,
                schedulingExecutor.get(),
                executionExecutor,
                concurrencyLimiters,
                config.serverQoS(),
                config.retryOnTimeout(),
                config.retryOnSocketException());
    }

    private static boolean shouldEnableQos(ClientConfiguration.ClientQoS clientQoS) {
        switch (clientQoS) {
            case ENABLED:
                return true;
            case DANGEROUS_DISABLE_SYMPATHETIC_CLIENT_QOS:
                return false;
        }

        throw new SafeIllegalStateException(
                "Encountered unknown client QoS configuration", SafeArg.of("ClientQoS", clientQoS));
    }

    /**
     * Adds informational {@link Agent}s to the given {@link UserAgent}, one for the conjure-java-runtime library and
     * one for the given service class. Version strings are extracted from the packages'
     * {@link Package#getImplementationVersion implementation version}, defaulting to 0.0.0 if no version can be found.
     */
    private static UserAgent augmentUserAgent(UserAgent agent, Class<?> serviceClass) {
        UserAgent augmentedAgent = agent;

        String maybeServiceVersion = serviceClass.getPackage().getImplementationVersion();
        augmentedAgent = augmentedAgent.addAgent(UserAgent.Agent.of(
                serviceClass.getSimpleName(), maybeServiceVersion != null ? maybeServiceVersion : "0.0.0"));

        String maybeRemotingVersion = OkHttpClients.class.getPackage().getImplementationVersion();
        augmentedAgent = augmentedAgent.addAgent(UserAgent.Agent.of(
                UserAgents.CONJURE_AGENT_NAME, maybeRemotingVersion != null ? maybeRemotingVersion : "0.0.0"));
        return augmentedAgent;
    }

    private static ImmutableList<ConnectionSpec> createConnectionSpecs() {
        return ImmutableList.of(
                new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                        .tlsVersions(TlsVersion.TLS_1_2)
                        .cipherSuites(CipherSuites.allCipherSuites())
                        .build(),
                ConnectionSpec.CLEARTEXT);
    }

    @SuppressWarnings("deprecation") // Singleton registry for a singleton executor
    private static ThreadFactory instrument(ThreadFactory threadFactory, String name) {
        return MetricRegistries.instrument(SharedTaggedMetricRegistries.getSingleton(), threadFactory, name);
    }
}
