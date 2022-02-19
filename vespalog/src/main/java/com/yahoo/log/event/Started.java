// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.log.event;

/**
 *
 * @author  Bjorn Borud
 */
@Deprecated(forRemoval = true, since = "7")
public class Started extends Event {
    public Started () {
    }

    public Started (String name) {
        setValue("name", name);
    }
}
