// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.fieldoperation;

import com.yahoo.searchdefinition.document.Attribute;
import com.yahoo.searchdefinition.document.SDField;
import com.yahoo.tensor.TensorType;

import java.util.Locale;
import java.util.Optional;

/**
 * @author Einar M R Rosenvinge
 */
public class AttributeOperation implements FieldOperation, FieldOperationContainer {

    private final String name;
    private Boolean huge;
    private Boolean fastSearch;
    private Boolean fastAccess;
    private Boolean mutable;
    private Boolean paged;
    private Boolean enableBitVectors;
    private Boolean enableOnlyBitVector;
    //TODO: Remember sorting!!
    private boolean doAlias = false;
    private String alias;
    private String aliasedName;
    private Optional<TensorType> tensorType = Optional.empty();
    private Optional<String> distanceMetric = Optional.empty();

    public AttributeOperation(String name) {
        this.name = name;
    }

    @Override
    public void addOperation(FieldOperation op) {
        //TODO: Implement this method:

    }

    @Override
    public void applyOperations(SDField field) {
        //TODO: Implement this method:
    }

    @Override
    public String getName() {
        return name;
    }

    public Boolean getHuge() {
        return huge;
    }

    public void setHuge(Boolean huge) {
        this.huge = huge;
    }

    public Boolean getFastSearch() {
        return fastSearch;
    }

    public void setFastSearch(Boolean fastSearch) {
        this.fastSearch = fastSearch;
    }

    public Boolean getFastAccess() {
        return fastAccess;
    }

    public void setFastAccess(Boolean fastAccess) {
        this.fastAccess = fastAccess;
    }
    public void setMutable(Boolean mutable) {
        this.mutable = mutable;
    }
    public void setPaged(Boolean paged) {
        this.paged = paged;
    }

    public Boolean getEnableBitVectors() {
        return enableBitVectors;
    }

    public void setEnableBitVectors(Boolean enableBitVectors) {
        this.enableBitVectors = enableBitVectors;
    }

    public Boolean getEnableOnlyBitVector() {
        return enableOnlyBitVector;
    }

    public void setEnableOnlyBitVector(Boolean enableOnlyBitVector) {
        this.enableOnlyBitVector = enableOnlyBitVector;
    }

    public void setDoAlias(boolean doAlias) {
        this.doAlias = doAlias;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }


    public void setAliasedName(String aliasedName) {
        this.aliasedName = aliasedName;
    }

    public void setTensorType(TensorType tensorType) {
        this.tensorType = Optional.of(tensorType);
    }

    public void setDistanceMetric(String value) {
        this.distanceMetric = Optional.of(value);
    }

    public void apply(SDField field) {
        Attribute attribute = null;
        if (attributeIsSuffixOfStructField(field.getName())) {
            attribute = field.getAttributes().get(field.getName());
        }
        if (attribute == null) {
            attribute = field.getAttributes().get(name);
            if (attribute == null) {
                attribute = new Attribute(name, field.getDataType());
                field.addAttribute(attribute);
            }
        }

        if (huge != null) {
            attribute.setHuge(huge);
        }
        if (paged != null) {
            attribute.setPaged(paged);
        }
        if (fastSearch != null) {
            attribute.setFastSearch(fastSearch);
        }
        if (fastAccess != null) {
            attribute.setFastAccess(fastAccess);
        }
        if (mutable != null) {
            attribute.setMutable(mutable);
        }
        if (enableBitVectors != null) {
            attribute.setEnableBitVectors(enableBitVectors);
        }
        if (enableOnlyBitVector != null) {
            attribute.setEnableOnlyBitVector(enableOnlyBitVector);
        }
        if (doAlias) {
            field.getAliasToName().put(alias, aliasedName);
        }
        if (tensorType.isPresent()) {
            attribute.setTensorType(tensorType.get());
        }
        if (distanceMetric.isPresent()) {
            String upper = distanceMetric.get().toUpperCase(Locale.ENGLISH);
            attribute.setDistanceMetric(Attribute.DistanceMetric.valueOf(upper));
        }
    }

    private boolean attributeIsSuffixOfStructField(String fieldName) {
        return ((fieldName.indexOf('.') != -1) && fieldName.endsWith(name));
    }

}
