// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.persistence;

import com.yahoo.vespa.hosted.controller.auditlog.AuditLog;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.time.temporal.ChronoUnit.MILLIS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author mpolden
 */
public class AuditLogSerializerTest {

    @Test
    public void test_serialization() {
        Instant i1 = Instant.now();
        Instant i2 = i1.minus(Duration.ofHours(1));
        Instant i3 = i1.minus(Duration.ofHours(2));
        Instant i4 = i1.minus(Duration.ofHours(3));

        AuditLog log = new AuditLog(List.of(
                new AuditLog.Entry(i1, "bar", AuditLog.Entry.Method.POST,
                                   "/bar/baz/",
                                   Optional.of("0".repeat(2048))),
                new AuditLog.Entry(i2, "foo", AuditLog.Entry.Method.POST,
                                   "/foo/bar/",
                                   Optional.of("{\"foo\":\"bar\"}")),
                new AuditLog.Entry(i3, "baz", AuditLog.Entry.Method.POST,
                                   "/foo/baz/",
                                   Optional.of("")),
                new AuditLog.Entry(i4, "baz", AuditLog.Entry.Method.POST,
                                   "/foo/baz/",
                                   Optional.of("\ufdff\ufeff\uffff")) // non-ascii
        ));

        AuditLogSerializer serializer = new AuditLogSerializer();
        AuditLog serialized = serializer.fromSlime(serializer.toSlime(log));
        assertEquals(log.entries().size(), serialized.entries().size());

        for (int i = 0; i < log.entries().size(); i++) {
            AuditLog.Entry entry = log.entries().get(i);
            AuditLog.Entry serializedEntry = serialized.entries().get(i);

            assertEquals(entry.at().truncatedTo(MILLIS), serializedEntry.at());
            assertEquals(entry.principal(), serializedEntry.principal());
            assertEquals(entry.method(), serializedEntry.method());
            assertEquals(entry.resource(), serializedEntry.resource());
            assertEquals(entry.data(), serializedEntry.data());
        }

        assertEquals(1024, log.entries().get(0).data().get().length());
        assertTrue(log.entries().get(2).data().isEmpty());
        assertTrue(log.entries().get(3).data().isEmpty());
    }

}
