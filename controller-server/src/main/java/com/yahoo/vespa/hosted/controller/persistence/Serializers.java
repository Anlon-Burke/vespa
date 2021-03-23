// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.persistence;

import com.yahoo.slime.Inspector;
import com.yahoo.slime.SlimeUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Reusable serialization logic.
 *
 * @author mpolden
 */
public class Serializers {

    private Serializers() {}

    public static Instant instant(Inspector field) {
        return Instant.ofEpochMilli(field.asLong());
    }

    public static OptionalLong optionalLong(Inspector field) {
        return field.valid() ? OptionalLong.of(field.asLong()) : OptionalLong.empty();
    }

    public static OptionalInt optionalInteger(Inspector field) {
        return field.valid() ? OptionalInt.of((int) field.asLong()) : OptionalInt.empty();
    }

    public static OptionalDouble optionalDouble(Inspector field) {
        return field.valid() ? OptionalDouble.of(field.asDouble()) : OptionalDouble.empty();
    }

    public static Optional<String> optionalString(Inspector field) {
        return SlimeUtils.optionalString(field);
    }

    public static Optional<Instant> optionalInstant(Inspector field) {
        return optionalLong(field).stream().mapToObj(Instant::ofEpochMilli).findFirst();
    }

    public static Optional<Duration> optionalDuration(Inspector field) {
        return optionalLong(field).stream().mapToObj(Duration::ofMillis).findFirst();
    }

}
