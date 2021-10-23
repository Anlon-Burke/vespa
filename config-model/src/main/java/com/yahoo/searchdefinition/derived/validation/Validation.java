// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.derived.validation;

import com.yahoo.searchdefinition.Schema;
import com.yahoo.searchdefinition.derived.DerivedConfiguration;

public class Validation {

    public static void validate(DerivedConfiguration config, Schema schema) {
        new IndexStructureValidator(config, schema).validate();
    }
}
