# Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
vespa_add_library(storage_visitor OBJECT
    SOURCES
    ${CMAKE_CURRENT_BINARY_DIR}/config-stor-visitor.h
    countvisitor.cpp
    dumpvisitorsingle.cpp
    memory_bounded_trace.cpp
    recoveryvisitor.cpp
    reindexing_visitor.cpp
    testvisitor.cpp
    visitor.cpp
    visitormanager.cpp
    visitormetrics.cpp
    visitorthreadmetrics.cpp
    visitorthread.cpp
    DEPENDS
)
vespa_generate_config(storage_visitor stor-visitor.def)
install_config_definition(stor-visitor.def vespa.config.content.core.stor-visitor.def)
