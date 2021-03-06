// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package ai.vespa.feed.client;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Statistics for feed operations over HTTP against a Vespa cluster.
 *
 * @author jonmv
 */
public class OperationStats {

    private final long requests;
    private final Map<Integer, Long> responsesByCode;
    private final long inflight;
    private final long exceptions;
    private final long averageLatencyMillis;
    private final long minLatencyMillis;
    private final long maxLatencyMillis;
    private final long bytesSent;
    private final long bytesReceived;

    public OperationStats(long requests, Map<Integer, Long> responsesByCode, long exceptions, long inflight,
                          long averageLatencyMillis, long minLatencyMillis, long maxLatencyMillis,
                          long bytesSent, long bytesReceived) {
        this.requests = requests;
        this.responsesByCode = responsesByCode;
        this.exceptions = exceptions;
        this.inflight = inflight;
        this.averageLatencyMillis = averageLatencyMillis;
        this.minLatencyMillis = minLatencyMillis;
        this.maxLatencyMillis = maxLatencyMillis;
        this.bytesSent = bytesSent;
        this.bytesReceived = bytesReceived;
    }

    /** Returns the difference between this and the initial. Min and max latency are not modified. */
    public OperationStats since(OperationStats initial) {
        return new OperationStats(requests - initial.requests,
                                  responsesByCode.entrySet().stream()
                                                 .collect(Collectors.toMap(entry -> entry.getKey(),
                                                                           entry -> entry.getValue() - initial.responsesByCode.getOrDefault(entry.getKey(), 0L))),
                                  exceptions - initial.exceptions,
                                  inflight - initial.inflight,
                                  responsesByCode.size() == initial.responsesByCode.size() ? 0 :
                                    (averageLatencyMillis * responsesByCode.size() - initial.averageLatencyMillis * initial.responsesByCode.size())
                                  / (responsesByCode.size() - initial.responsesByCode.size()),
                                  minLatencyMillis,
                                  maxLatencyMillis,
                                  bytesSent - initial.bytesSent,
                                  bytesReceived - initial.bytesReceived);
    }

    public long requests() {
        return requests;
    }

    public long responses() {
        return requests - inflight;
    }

    public long successes() {
        return responsesByCode.getOrDefault(200, 0L);
    }

    public Map<Integer, Long> responsesByCode() {
        return responsesByCode;
    }

    public long exceptions() {
        return exceptions;
    }

    public long inflight() {
        return inflight;
    }

    public long averageLatencyMillis() {
        return averageLatencyMillis;
    }

    public long minLatencyMillis() {
        return minLatencyMillis;
    }

    public long maxLatencyMillis() {
        return maxLatencyMillis;
    }

    public long bytesSent() {
        return bytesSent;
    }

    public long bytesReceived() {
        return bytesReceived;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OperationStats that = (OperationStats) o;
        return requests == that.requests && inflight == that.inflight && exceptions == that.exceptions && averageLatencyMillis == that.averageLatencyMillis && minLatencyMillis == that.minLatencyMillis && maxLatencyMillis == that.maxLatencyMillis && bytesSent == that.bytesSent && bytesReceived == that.bytesReceived && responsesByCode.equals(that.responsesByCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(requests, responsesByCode, inflight, exceptions, averageLatencyMillis, minLatencyMillis, maxLatencyMillis, bytesSent, bytesReceived);
    }

    @Override
    public String toString() {
        return "Stats{" +
               "requests=" + requests +
               ", responsesByCode=" + responsesByCode +
               ", exceptions=" + exceptions +
               ", inflight=" + inflight +
               ", averageLatencyMillis=" + averageLatencyMillis +
               ", minLatencyMillis=" + minLatencyMillis +
               ", maxLatencyMillis=" + maxLatencyMillis +
               ", bytesSent=" + bytesSent +
               ", bytesReceived=" + bytesReceived +
               '}';
    }

}
