// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.node;

import com.yahoo.vespa.hosted.provision.node.History.Event;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

/**
 * @author mpolden
 */
public class HistoryTest {

    @Test
    public void truncate_events() {
        assertEquals(0, new History(List.of(), 2).asList().size());
        assertEquals(1, new History(shuffledEvents(1), 2).asList().size());
        assertEquals(2, new History(shuffledEvents(2), 2).asList().size());

        History history = new History(shuffledEvents(5), 3);
        assertEquals(3, history.asList().size());
        assertEquals("Most recent events are kept",
                     List.of(2L, 3L, 4L),
                     history.asList().stream().map(e -> e.at().toEpochMilli()).collect(Collectors.toList()));
    }

    @Test
    public void repeating_event_overwrites_existing() {
        Instant i0 = Instant.ofEpochMilli(1);
        History history = new History(List.of(new Event(Event.Type.readied, Agent.system, i0)));

        Instant i1 = Instant.ofEpochMilli(2);
        history = history.with(new Event(Event.Type.reserved, Agent.system, i1));
        assertEquals(2, history.asList().size());

        Instant i2 = Instant.ofEpochMilli(3);
        history = history.with(new Event(Event.Type.reserved, Agent.system, i2));

        assertEquals(2, history.asList().size());
        assertEquals(i2, history.asList().get(1).at());
    }

    private static List<Event> shuffledEvents(int count) {
        Instant start = Instant.ofEpochMilli(0);
        List<Event> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(new Event(Event.Type.values()[i], Agent.system, start.plusMillis(i)));
        }
        Collections.shuffle(events);
        return events;
    }

}
