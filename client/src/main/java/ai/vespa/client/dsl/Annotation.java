// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package ai.vespa.client.dsl;

import java.util.Collections;
import java.util.Map;

public class Annotation {

    Map<String, Object> annotations = Collections.emptyMap();

    Annotation() {
    }

    Annotation(Map<String, Object> annotations) {
        this.annotations = annotations;
    }

    public Annotation append(Annotation a) {
        this.annotations.putAll(a.annotations);
        return this;
    }

    public boolean contains(String key) {
        return annotations.containsKey(key);
    }

    @Override
    public String toString() {
        return annotations == null || annotations.isEmpty()
               ? ""
               : Q.gson.toJson(annotations);
    }
}
