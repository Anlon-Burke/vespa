// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.restapi;

import com.yahoo.config.provision.ClusterResources;
import com.yahoo.slime.Cursor;
import com.yahoo.slime.Slime;
import com.yahoo.vespa.hosted.provision.NodeList;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.applications.Application;
import com.yahoo.vespa.hosted.provision.applications.Cluster;
import com.yahoo.vespa.hosted.provision.applications.ScalingEvent;
import com.yahoo.vespa.hosted.provision.autoscale.ClusterModel;
import com.yahoo.vespa.hosted.provision.autoscale.MetricsDb;

import java.net.URI;
import java.util.List;

/**
 * Serializes application information for nodes/v2/application responses
 *
 * @author bratseth
 */
public class ApplicationSerializer {

    public static Slime toSlime(Application application,
                                NodeList applicationNodes,
                                MetricsDb metricsDb,
                                NodeRepository nodeRepository,
                                URI applicationUri) {
        Slime slime = new Slime();
        toSlime(application, applicationNodes, metricsDb, nodeRepository, slime.setObject(), applicationUri);
        return slime;
    }

    private static void toSlime(Application application,
                                NodeList applicationNodes,
                                MetricsDb metricsDb,
                                NodeRepository nodeRepository,
                                Cursor object,
                                URI applicationUri) {
        object.setString("url", applicationUri.toString());
        object.setString("id", application.id().toFullString());
        clustersToSlime(application, applicationNodes, metricsDb, nodeRepository, object.setObject("clusters"));
    }

    private static void clustersToSlime(Application application,
                                        NodeList applicationNodes,
                                        MetricsDb metricsDb,
                                        NodeRepository nodeRepository,
                                        Cursor clustersObject) {
        application.clusters().values().forEach(cluster -> toSlime(application, cluster, applicationNodes, metricsDb, nodeRepository, clustersObject));
    }

    private static void toSlime(Application application,
                                Cluster cluster,
                                NodeList applicationNodes,
                                MetricsDb metricsDb,
                                NodeRepository nodeRepository,
                                Cursor clustersObject) {
        NodeList nodes = applicationNodes.not().retired().cluster(cluster.id());
        if (nodes.isEmpty()) return;
        ClusterResources currentResources = nodes.toResources();
        ClusterModel clusterModel = new ClusterModel(application, cluster, nodes.clusterSpec(), nodes, metricsDb, nodeRepository.clock());

        Cursor clusterObject = clustersObject.setObject(cluster.id().value());
        clusterObject.setString("type", nodes.clusterSpec().type().name());
        toSlime(cluster.minResources(), clusterObject.setObject("min"));
        toSlime(cluster.maxResources(), clusterObject.setObject("max"));
        toSlime(currentResources, clusterObject.setObject("current"));
        if (cluster.shouldSuggestResources(currentResources))
            cluster.suggestedResources().ifPresent(suggested -> toSlime(suggested.resources(), clusterObject.setObject("suggested")));
        cluster.targetResources().ifPresent(target -> toSlime(target, clusterObject.setObject("target")));
        clusterUtilizationToSlime(clusterModel, clusterObject.setObject("utilization"));
        scalingEventsToSlime(cluster.scalingEvents(), clusterObject.setArray("scalingEvents"));
        clusterObject.setString("autoscalingStatus", cluster.autoscalingStatus());
        clusterObject.setLong("scalingDuration", clusterModel.scalingDuration().toMillis());
        clusterObject.setDouble("maxQueryGrowthRate", clusterModel.maxQueryGrowthRate());
        clusterObject.setDouble("currentQueryFractionOfMax", clusterModel.queryFractionOfMax());
    }

    private static void toSlime(ClusterResources resources, Cursor clusterResourcesObject) {
        clusterResourcesObject.setLong("nodes", resources.nodes());
        clusterResourcesObject.setLong("groups", resources.groups());
        NodeResourcesSerializer.toSlime(resources.nodeResources(), clusterResourcesObject.setObject("resources"));
    }

    private static void clusterUtilizationToSlime(ClusterModel clusterModel, Cursor utilizationObject) {
        utilizationObject.setDouble("cpu", clusterModel.averageLoad().cpu());
        utilizationObject.setDouble("idealCpu", clusterModel.idealLoad().cpu());
        utilizationObject.setDouble("memory", clusterModel.averageLoad().memory());
        utilizationObject.setDouble("idealMemory", clusterModel.idealLoad().memory());
        utilizationObject.setDouble("disk", clusterModel.averageLoad().disk());
        utilizationObject.setDouble("idealDisk", clusterModel.idealLoad().disk());
    }

    private static void scalingEventsToSlime(List<ScalingEvent> scalingEvents, Cursor scalingEventsArray) {
        for (ScalingEvent scalingEvent : scalingEvents) {
            Cursor scalingEventObject = scalingEventsArray.addObject();
            toSlime(scalingEvent.from(), scalingEventObject.setObject("from"));
            toSlime(scalingEvent.to(), scalingEventObject.setObject("to"));
            scalingEventObject.setLong("at", scalingEvent.at().toEpochMilli());
        }
    }

}
