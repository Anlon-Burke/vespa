// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

package com.yahoo.search.logging;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.yahoo.search.Query;
import com.yahoo.slime.JsonDecoder;
import com.yahoo.slime.Slime;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Base64;

public class LoggerEntry {

    private final Long timestamp;
    private final Query query;
    private final ByteBuffer blob;

    private LoggerEntry(Builder builder) {
        timestamp = builder.timestamp;  // or set automatically if not set
        query = builder.query;
        blob = builder.blob;
    }

    public Long timestamp() {
        return timestamp;
    }

    public Query query() {
        return query;
    }

    public String queryString() {
        String queryString = null;
        if (query != null) {
            if (query.getHttpRequest() != null && query.getHttpRequest().getUri() != null) {
                queryString = query.getHttpRequest().getUri().getPath();
                if (query.getHttpRequest().getUri().getQuery() != null) {
                    queryString += "?" + query.getHttpRequest().getUri().getRawQuery();
                }
            }
        }
        return queryString;
    }

    public ByteBuffer blob() {
        return blob;
    }

    public String toString() {
        return toJson();
    }

    public String toJson() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JsonGenerator g = new JsonFactory().createGenerator(out, JsonEncoding.UTF8);
            g.writeStartObject();

            g.writeNumberField("timestamp", timestamp == null ? 0 : timestamp);
            g.writeStringField("query", queryString());
            g.writeStringField("blob", Base64.getEncoder().encodeToString(blob.array()));

            g.writeEndObject();
            g.close();
            return out.toString();

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // TODO: Rename method here and above? (serialize/deserialize)
    // TODO: Use Slime or Jackson for both
    public static LoggerEntry fromJson(byte[] content) throws IOException {
        var decoder = new JsonDecoder();
        var slime = decoder.decode(new Slime(), content);

        var timestamp = slime.get().field("timestamp").asLong();
        var query = new Query(slime.get().field("query").asString());
        var blob = slime.get().field("blob").asData();

        return new LoggerEntry(new Builder().timestamp(timestamp).query(query).blob(blob));
    }

    public static class Builder {

        private final Logger logger;

        private Long timestamp;
        private Query query;
        private ByteBuffer blob;

        // For testing
        public Builder() { this(entry -> false); }

        public Builder(Logger logger) {
            this.logger = logger;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder query(Query query) {
            this.query = query;
            return this;
        }

        public Builder blob(byte[] bytes) {
            blob = ByteBuffer.allocate(bytes.length);
            blob.put(bytes).limit(blob.position()).position(0);
            return this;
        }

        public boolean send() {
            return logger.send(new LoggerEntry(this));
        }

    }

}
