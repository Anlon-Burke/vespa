// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.concurrent;

/**
 * This interface wraps access to some timer that can be used to measure elapsed time, in milliseconds. This
 * abstraction allows for unit testing the behavior of time-based constructs.
 *
 * @author Simon Thoresen Hult
 */
public interface Timer {

    /**
     * Returns the current value of some arbitrary timer, in milliseconds. This method can only be used to measure
     * elapsed time and is not related to any other notion of system or wall-clock time.
     *
     * @return The current value of the timer, in milliseconds.
     */
    long milliTime();

}
