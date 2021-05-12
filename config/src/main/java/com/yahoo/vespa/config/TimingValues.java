// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config;

import java.util.Random;

/**
 * Timeouts, delays and retries used in RPC config protocol.
 *
 * @author Gunnar Gauslaa Bergem
 */
public class TimingValues {
    public static final long defaultNextConfigTimeout = 1000;
    // See getters below for an explanation of how these values are used and interpreted
    // All time values in milliseconds.
    private final long successTimeout;
    private final long errorTimeout;
    private final long initialTimeout;
    private long subscribeTimeout = 55000;
    private long configuredErrorTimeout = -1;  // Don't ever timeout (and do not use error response) when we are already configured

    private long fixedDelay = 5000;
    private long unconfiguredDelay = 1000;
    private long configuredErrorDelay = 15000;
    private final Random rand;

    public TimingValues() {
        successTimeout = 600000;
        errorTimeout = 20000;
        initialTimeout = 15000;
        this.rand = new Random(System.currentTimeMillis());
    }

    // TODO Should add nextConfigTimeout in all constructors
    public TimingValues(long successTimeout,
                        long errorTimeout,
                        long initialTimeout,
                        long subscribeTimeout,
                        long unconfiguredDelay,
                        long configuredErrorDelay,
                        long fixedDelay) {
        this.successTimeout = successTimeout;
        this.errorTimeout = errorTimeout;
        this.initialTimeout = initialTimeout;
        this.subscribeTimeout = subscribeTimeout;
        this.unconfiguredDelay = unconfiguredDelay;
        this.configuredErrorDelay = configuredErrorDelay;
        this.fixedDelay = fixedDelay;
        this.rand = new Random(System.currentTimeMillis());
    }

    private TimingValues(long successTimeout,
                         long errorTimeout,
                         long initialTimeout,
                         long subscribeTimeout,
                         long unconfiguredDelay,
                         long configuredErrorDelay,
                         long fixedDelay,
                         Random rand) {
        this.successTimeout = successTimeout;
        this.errorTimeout = errorTimeout;
        this.initialTimeout = initialTimeout;
        this.subscribeTimeout = subscribeTimeout;
        this.unconfiguredDelay = unconfiguredDelay;
        this.configuredErrorDelay = configuredErrorDelay;
        this.fixedDelay = fixedDelay;
        this.rand = rand;
    }

    public TimingValues(TimingValues tv, Random random) {
        this(tv.successTimeout,
                tv.errorTimeout,
                tv.initialTimeout,
                tv.subscribeTimeout,
                tv.unconfiguredDelay,
                tv.configuredErrorDelay,
                tv.fixedDelay,
                random);
    }

    /**
     * Returns timeout to use as server timeout when previous config request was a success.
     *
     * @return timeout in milliseconds.
     */
    public long getSuccessTimeout() {
        return successTimeout;
    }

    /**
     * Returns timeout to use as server timeout when we got an error with the previous config request.
     *
     * @return timeout in milliseconds.
     */
    public long getErrorTimeout() {
        return errorTimeout;
    }

    /**
     * Returns timeout to use as server timeout when subscribing for the first time.
     *
     * @return timeout in milliseconds.
     */
    public long getSubscribeTimeout() {
        return subscribeTimeout;
    }

    public TimingValues setSubscribeTimeout(long t) {
        subscribeTimeout = t;
        return this;
    }

    public TimingValues setConfiguredErrorTimeout(long t) {
        configuredErrorTimeout = t;
        return this;
    }

    /**
     * Returns time to wait until next attempt to get config after a failed request when the client has not
     * gotten a successful response to a config subscription (i.e, the client has not been configured).
     * A negative value means that there will never be a next attempt. If a negative value is set, the
     * user must also setSubscribeTimeout(0) to prevent a deadlock while subscribing.
     *
     * @return delay in milliseconds, a negative value means never.
     */
    public long getUnconfiguredDelay() {
        return unconfiguredDelay;
    }

    public TimingValues setUnconfiguredDelay(long d) {
        unconfiguredDelay = d;
        return this;
    }

    /**
     * Returns time to wait until next attempt to get config after a failed request when the client has
     * previously gotten a successful response to a config subscription (i.e, the client is configured).
     * A negative value means that there will never be a next attempt.
     *
     * @return delay in milliseconds, a negative value means never.
     */
    public long getConfiguredErrorDelay() {
        return configuredErrorDelay;
    }

    public TimingValues setConfiguredErrorDelay(long d) {
        configuredErrorDelay = d;
        return this;
    }

    /**
     * Returns fixed delay that is used when retrying getting config no matter if it was a success or an error
     * and independent of number of retries.
     *
     * @return timeout in milliseconds.
     */
    public long getFixedDelay() {
        return fixedDelay;
    }

    /**
     * Returns a number +/- a random component
     *
     * @param value      input
     * @param fraction for instance 0.1 for +/- 10%
     * @return a number
     */
    public long getPlusMinusFractionRandom(long value, float fraction) {
        return Math.round(value - (value * fraction) + (rand.nextFloat() * 2L * value * fraction));
    }

    /**
     * Returns a number between 0 and maxValue
     *
     * @param maxValue max maxValue
     * @return a number
     */
    public long getRandomTransientDelay(long maxValue) {
        return Math.round(rand.nextFloat() * maxValue);
    }

    @Override
    public String toString() {
        return "TimingValues [successTimeout=" + successTimeout
               + ", errorTimeout=" + errorTimeout
               + ", initialTimeout=" + initialTimeout
               + ", subscribeTimeout=" + subscribeTimeout
               + ", configuredErrorTimeout=" + configuredErrorTimeout
               + ", fixedDelay=" + fixedDelay
               + ", unconfiguredDelay=" + unconfiguredDelay
               + ", configuredErrorDelay=" + configuredErrorDelay
               + ", rand=" + rand + "]";
    }


}
