// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.prelude.fastsearch.test;

import com.google.common.collect.ImmutableList;
import com.yahoo.component.chain.Chain;
import com.yahoo.config.subscription.ConfigGetter;
import com.yahoo.container.QrSearchersConfig;
import com.yahoo.container.handler.VipStatus;
import com.yahoo.container.protect.Error;
import com.yahoo.language.simple.SimpleLinguistics;
import com.yahoo.prelude.fastsearch.ClusterParams;
import com.yahoo.prelude.fastsearch.DocumentdbInfoConfig;
import com.yahoo.prelude.fastsearch.FastSearcher;
import com.yahoo.prelude.fastsearch.SummaryParameters;
import com.yahoo.prelude.fastsearch.VespaBackEndSearcher;
import com.yahoo.search.Query;
import com.yahoo.search.Result;
import com.yahoo.search.Searcher;
import com.yahoo.search.dispatch.rpc.RpcResourcePool;
import com.yahoo.search.dispatch.searchcluster.Node;
import com.yahoo.search.grouping.GroupingRequest;
import com.yahoo.search.grouping.request.AllOperation;
import com.yahoo.search.grouping.request.EachOperation;
import com.yahoo.search.grouping.request.GroupingOperation;
import com.yahoo.search.rendering.RendererRegistry;
import com.yahoo.search.result.ErrorMessage;
import com.yahoo.search.schema.DocumentSummary;
import com.yahoo.search.schema.RankProfile;
import com.yahoo.search.schema.Schema;
import com.yahoo.search.schema.SchemaInfo;
import com.yahoo.search.searchchain.Execution;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;


/**
 * Tests the Fast searcher
 *
 * @author bratseth
 */
public class FastSearcherTestCase {

    private final static DocumentdbInfoConfig documentdbInfoConfig = new DocumentdbInfoConfig(new DocumentdbInfoConfig.Builder());


    @Test
    public void testNullQuery() {
        Logger.getLogger(FastSearcher.class.getName()).setLevel(Level.ALL);
        FastSearcher fastSearcher = new FastSearcher("container.0",
                                                     MockDispatcher.create(Collections.emptyList()),
                                                     new SummaryParameters(null),
                                                     new ClusterParams("testhittype"),
                                                     documentdbInfoConfig,
                                                     SchemaInfo.empty());

        String query = "?junkparam=ignored";
        Result result = doSearch(fastSearcher,new Query(query), 0, 10);
        ErrorMessage message = result.hits().getError();

        assertNotNull("Got error", message);
        assertEquals("Null query", message.getMessage());
        assertEquals(query, message.getDetailedMessage());
        assertEquals(Error.NULL_QUERY.code, message.getCode());
    }

    private Chain<Searcher> chainedAsSearchChain(Searcher topOfChain) {
        List<Searcher> searchers = new ArrayList<>();
        searchers.add(topOfChain);
        return new Chain<>(searchers);
    }

    private Result doSearch(Searcher searcher, Query query, int offset, int hits) {
        query.setOffset(offset);
        query.setHits(hits);
        return createExecution(searcher).search(query);
    }

    private Execution createExecution(Searcher searcher) {
        return new Execution(chainedAsSearchChain(searcher), Execution.Context.createContextStub());
    }

    @Test
    public void testSinglePassGroupingIsForcedWithSingleNodeGroups() {
        FastSearcher fastSearcher = new FastSearcher("container.0",
                                                     MockDispatcher.create(Collections.singletonList(new Node(0, "host0", 0))),
                                                     new SummaryParameters(null),
                                                     new ClusterParams("testhittype"),
                                                     documentdbInfoConfig,
                                                     SchemaInfo.empty());
        Query q = new Query("?query=foo");
        GroupingRequest request1 = GroupingRequest.newInstance(q);
        request1.setRootOperation(new AllOperation());

        GroupingRequest request2 = GroupingRequest.newInstance(q);
        AllOperation all = new AllOperation();
        all.addChild(new EachOperation());
        all.addChild(new EachOperation());
        request2.setRootOperation(all);

        assertForceSinglePassIs(false, q);
        fastSearcher.search(q, new Execution(Execution.Context.createContextStub()));
        assertForceSinglePassIs(true, q);
    }

    @Test
    public void testSummaryNeedsQuery() {
        var documentDb = new DocumentdbInfoConfig(new DocumentdbInfoConfig.Builder().documentdb(new DocumentdbInfoConfig.Documentdb.Builder().name("test")));
        var schema = new Schema.Builder("test")
                               .add(new DocumentSummary.Builder("default").build())
                               .add(new RankProfile.Builder("default").setHasRankFeatures(false)
                                                                            .setHasSummaryFeatures(false)
                                                                            .build());
        FastSearcher backend = new FastSearcher("container.0",
                                                MockDispatcher.create(Collections.singletonList(new Node(0, "host0", 0))),
                                                new SummaryParameters(null),
                                                new ClusterParams("testhittype"),
                                                documentDb,
                                                new SchemaInfo(List.of(schema.build()), Map.of()));
        Query q = new Query("?query=foo");
        Result result = doSearch(backend, q, 0, 10);
        assertFalse(backend.summaryNeedsQuery(q));

        q = new Query("?query=select+*+from+source+where+title+contains+%22foobar%22+and++geoLocation%28myfieldname%2C+63.5%2C+10.5%2C+%22999+km%22%29%3B");
        q.getModel().setType(Query.Type.YQL);
        result = doSearch(backend, q, 0, 10);
        assertTrue(backend.summaryNeedsQuery(q));
    }

    @Test
    public void testSinglePassGroupingIsNotForcedWithSingleNodeGroups() {
        MockDispatcher dispatcher = MockDispatcher.create(ImmutableList.of(new Node(0, "host0", 0), new Node(2, "host1", 0)));

        FastSearcher fastSearcher = new FastSearcher("container.0",
                                                     dispatcher,
                                                     new SummaryParameters(null),
                                                     new ClusterParams("testhittype"),
                                                     documentdbInfoConfig,
                                                     SchemaInfo.empty());
        Query q = new Query("?query=foo");
        GroupingRequest request1 = GroupingRequest.newInstance(q);
        request1.setRootOperation(new AllOperation());

        GroupingRequest request2 = GroupingRequest.newInstance(q);
        AllOperation all = new AllOperation();
        all.addChild(new EachOperation());
        all.addChild(new EachOperation());
        request2.setRootOperation(all);

        assertForceSinglePassIs(false, q);
        fastSearcher.search(q, new Execution(Execution.Context.createContextStub()));
        assertForceSinglePassIs(false, q);
    }

    private void assertForceSinglePassIs(boolean expected, Query query) {
        for (GroupingRequest request : query.getSelect().getGrouping())
            assertForceSinglePassIs(expected, request.getRootOperation());
    }

    private void assertForceSinglePassIs(boolean expected, GroupingOperation operation) {
        assertEquals("Force single pass is " + expected + " in " + operation,
                     expected, operation.getForceSinglePass());
        for (GroupingOperation child : operation.getChildren())
            assertForceSinglePassIs(expected, child);
    }

    @Test
    public void testDispatchReconfig() {
        String clusterName = "a";
        var b = new QrSearchersConfig.Builder();
        var searchClusterB = new QrSearchersConfig.Searchcluster.Builder();
        searchClusterB.name(clusterName);
        b.searchcluster(searchClusterB);
        VipStatus vipStatus = new VipStatus(b.build());
        List<Node> nodes_1 = ImmutableList.of(new Node(0, "host0", 0));
        RpcResourcePool rpcPool_1 = new RpcResourcePool(MockDispatcher.toDispatchConfig(nodes_1));
        MockDispatcher dispatch_1 = MockDispatcher.create(nodes_1, rpcPool_1, vipStatus);
        dispatch_1.clusterMonitor.shutdown();
        vipStatus.addToRotation(clusterName);
        assertTrue(vipStatus.isInRotation());
        dispatch_1.deconstruct();
        assertTrue(vipStatus.isInRotation()); //Verify that deconstruct does not touch vipstatus
    }

}
