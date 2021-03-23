<!-- Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root. -->
# List of possible future enhancements and features

This lists some possible improvements to Vespa which have been considered or requested, can be developed relatively 
independently of other work, and are not yet under development. For more information on the code structure in Vespa, see
[Code-map.md](Code-map.md).


## Support query profiles for document processors

**Effort:** Low<br/>
**Difficulty:** Low<br/>
**Skills:** Java

Query profiles make it simple to support multiple buckets, behavior profiles for different use cases etc by providing 
bundles of parameters accessible to Searchers processing queries. Writes go through a similar chain of processors - 
Document Processors, but have no equivalent support for parametrization. This is to allow configuration of document 
processor profiles by reusing the query profile support also for document processors.

**Code pointers:**
- [Query profiles](https://github.com/vespa-engine/vespa/blob/master/container-search/src/main/java/com/yahoo/search/query/profile/QueryProfile.java)
- [Document processors](https://github.com/vespa-engine/vespa/blob/master/docproc/src/main/java/com/yahoo/docproc/DocumentProcessor.java)


## Java implementation of the content layer for testing

**Effort:** Medium<br/>
**Difficulty:** Low<br/>
**Skills:** Java

There is currently support for creating Application instances programmatically in Java to unit test application package
functionality (see com.yahoo.application.Application). However, only Java component functionality can be tested in this 
way as the content layer is not available, being implemented in C++. A Java implementation, of some or all of the 
functionality would enable developers to do more testing locally within their IDE. This is medium effort because 
performance is not a concern and some components, such as ranking expressions and features are already available as 
libraries (see the searchlib module).

**Code pointers:**
- Content cluster mock in Java  (currently empy): [ContentCluster](https://github.com/vespa-engine/vespa/blob/master/application/src/main/java/com/yahoo/application/content/ContentCluster.java)
- The model of a search definition this must consume config from: [Search](https://github.com/vespa-engine/vespa/blob/master/config-model/src/main/java/com/yahoo/searchdefinition/Search.java)


## Indexed search in maps

**Effort:** Medium<br/>
**Difficulty:** Medium<br/>
**Skills:** C++, multithreading, performance, indexing, data structures

Vespa supports maps and and making them searchable in memory by declaring as an attribute. 
However, maps cannot be indexed as text-search disk indexes. 

**Code pointers:**
- [Current text indexes](https://github.com/vespa-engine/vespa/tree/master/searchlib/src/vespa/searchlib/index)


## Global writes

**Effort:** High<br/>
**Difficulty:** High<br/>
**Skills:** C++, Java, distributed systems, performance, multithreading, network, distributed consistency

Vespa instances distribute data automatically within clusters, but these clusters are meant to consist of co-located 
machines - the distribution algorithm is not suitable for global distribution across datacenters because it cannot 
seamlessly tolerate datacenter-wide outages and does not attempt to minimize bandwith usage between datacenters.
Application usually achieve global precense instead by setting up multiple independent instances in different 
datacenters and write to all in parallel. This is robust and works well on average, but puts additional burden on 
applications to achieve cross-datacenter data consistency on datacenter failures, and does not enable automatic 
data recovery across datacenters, such that data redundancy is effectively required within each datacenter. 
This is fine in most cases, but not in the case where storage space drives cost and intermittent loss of data coverage 
(completeness as seen from queries) is tolerable.

A solution should sustain current write rates (tens of thousands of writes per ndoe per second), sustain write and read 
rates on loss of connectivity to one (any) data center, re-establish global data consistency when a lost datacenter is 
recovered and support some degree of tradeoff between consistency and operation latency (although the exact modes to be 
supported is part of the design and analysis needed).

**Code pointers:**
- [Document API](https://github.com/vespa-engine/vespa/tree/master/documentapi/src/main/java/com/yahoo/documentapi)


## Global dynamic tensors

**Effort:** High<br/>
**Difficulty:** High<br/>
**Skills:** Java, C++, distributed systems, performance, networking, distributed consistency

Tensors in ranking models may either be passed with the query, be part of the document or be configured as part of the 
application package (global tensors). This is fine for many kinds of models but does not support the case of really 
large tensors (which barely fit in memory) and/or dynamically changing tensors (online learning of global models). 
These use cases require support for global tensors (tensors available locally on all content nodes during execution 
but not sent with the query or residing in documents) which are not configured as part of the application package but 
which are written independently and dynamically updateable at a high write rate. To support this at large scale, with a
high write rate, we need a small cluster of nodes storing the source of truth of the global tensor and which have 
perfect consistency. This in turn must push updates to all content nodes in a best effort fashion given a fixed bandwith
budget, such that query execution and document write traffic is prioritized over ensuring perfect consistency of global
model updates.

**Code pointers:**
- Tensor modify operation (for document tensors): [Java](https://github.com/vespa-engine/vespa/blob/master/document/src/main/java/com/yahoo/document/update/TensorModifyUpdate.java), [C++](https://github.com/vespa-engine/vespa/blob/master/document/src/vespa/document/update/tensor_modify_update.h)
