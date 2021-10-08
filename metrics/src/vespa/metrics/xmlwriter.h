// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/metrics/metric.h>
#include <vespa/vespalib/util/xmlserializable.h>

namespace metrics {

class XmlWriter : public MetricVisitor {
    vespalib::xml::XmlOutputStream& _xos;
    int _verbosity;

public:
    XmlWriter(vespalib::xml::XmlOutputStream& xos,
              uint32_t period, int verbosity);

    bool visitSnapshot(const MetricSnapshot&) override;
    void doneVisitingSnapshot(const MetricSnapshot&) override;
    bool visitMetricSet(const MetricSet& set, bool) override;
    void doneVisitingMetricSet(const MetricSet&) override;
    bool visitCountMetric(const AbstractCountMetric&, bool autoGenerated) override;
    bool visitValueMetric(const AbstractValueMetric&, bool autoGenerated) override;

private:
    void printCommonXmlParts(const Metric& metric) const;
};

}

