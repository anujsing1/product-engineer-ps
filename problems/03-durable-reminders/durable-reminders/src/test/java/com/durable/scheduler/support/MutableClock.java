package com.durable.scheduler.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Test clock that can be advanced explicitly without {@code Thread.sleep()}.
 */
public class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant) {
        this(instant, ZoneOffset.UTC);
    }

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = Objects.requireNonNull(instant, "instant");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    public void setInstant(Instant instant) {
        this.instant = Objects.requireNonNull(instant, "instant");
    }

    public void advanceBy(java.time.Duration duration) {
        this.instant = this.instant.plus(Objects.requireNonNull(duration, "duration"));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
