// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.config.server.deploy;

import com.google.common.collect.ImmutableSet;
import com.yahoo.component.Version;
import com.yahoo.config.application.api.ApplicationMetaData;
import com.yahoo.config.application.api.ApplicationPackage;
import com.yahoo.config.application.api.FileRegistry;
import com.yahoo.config.model.application.provider.BaseDeployLogger;
import com.yahoo.config.model.application.provider.DeployData;
import com.yahoo.config.model.application.provider.FilesApplicationPackage;
import com.yahoo.config.model.application.provider.MockFileRegistry;
import com.yahoo.config.provision.AllocatedHosts;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.HostSpec;
import com.yahoo.path.Path;
import com.yahoo.vespa.config.server.zookeeper.ConfigCurator;
import com.yahoo.vespa.config.server.zookeeper.ZKApplicationPackage;
import com.yahoo.vespa.curator.mock.MockCurator;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.yahoo.config.provision.serialization.AllocatedHostsSerializer.fromJson;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for ZooKeeperClient.
 *
 * @author hmusum
 */
public class ZooKeeperClientTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private ConfigCurator zk;
    private final String appPath = "/1";

    @Before
    public void setupZK() throws IOException {
        zk = ConfigCurator.create(new MockCurator());
        ZooKeeperClient zkc = new ZooKeeperClient(zk, new BaseDeployLogger(), Path.fromString(appPath));
        ApplicationPackage app = FilesApplicationPackage.fromFileWithDeployData(new File("src/test/apps/zkfeed"),
                                                                                new DeployData("foo",
                                                                                               "/bar/baz",
                                                                                               ApplicationId.from("default", "appName", "default"),
                                                                                               1345L,
                                                                                               true,
                                                                                               3L,
                                                                                               2L));
        Map<Version, FileRegistry> fileRegistries = createFileRegistries();
        app.writeMetaData();
        zkc.initialize();
        zkc.writeApplicationPackage(app);
        zkc.write(fileRegistries);
    }

    private Map<Version, FileRegistry> createFileRegistries() {
        FileRegistry a = new MockFileRegistry();
        a.addFile("fileA");
        FileRegistry b = new MockFileRegistry();
        b.addFile("fileB");
        Map<Version, FileRegistry> registryMap = new HashMap<>();
        registryMap.put(new Version(1, 2, 3), a);
        registryMap.put(new Version(3, 2, 1), b);
        return registryMap;
    }

    @Test
    public void testInitZooKeeper() {
        ConfigCurator zk = ConfigCurator.create(new MockCurator());
        BaseDeployLogger logger = new BaseDeployLogger();
        long generation = 1L;
        ZooKeeperClient zooKeeperClient = new ZooKeeperClient(zk, logger, Path.fromString("/1"));
        zooKeeperClient.initialize();
        String appPath = "/";
        assertThat(zk.getChildren(appPath).size(), is(1));
        assertTrue(zk.exists("/" + generation));
        String currentAppPath = appPath + generation;
        assertTrue(zk.exists(currentAppPath, ConfigCurator.DEFCONFIGS_ZK_SUBPATH.replaceFirst("/", "")));
        assertThat(zk.getChildren(currentAppPath).size(), is(4));
    }

    @Test
    public void testFeedDefFilesToZooKeeper() {
        String defsPath = appPath + ConfigCurator.DEFCONFIGS_ZK_SUBPATH;
        assertTrue(zk.exists(appPath, ConfigCurator.DEFCONFIGS_ZK_SUBPATH.replaceFirst("/", "")));
        List<String> children = zk.getChildren(defsPath);
        assertEquals(defsPath + " children", 1, children.size());
        Collections.sort(children);
        assertThat(children.get(0), is("a.b.test2"));

        assertTrue(zk.exists(appPath, ConfigCurator.USER_DEFCONFIGS_ZK_SUBPATH.replaceFirst("/", "")));
        String userDefsPath = appPath + ConfigCurator.USER_DEFCONFIGS_ZK_SUBPATH;
        children = zk.getChildren(userDefsPath);
        assertThat(children.size(), is(1));
        Collections.sort(children);
        assertThat(children.get(0), is("a.b.test2"));
    }

    @Test
    public void testFeedAppMetaDataToZooKeeper() {
        assertTrue(zk.exists(appPath, ConfigCurator.META_ZK_PATH));
        ApplicationMetaData metaData = ApplicationMetaData.fromJsonString(zk.getData(appPath, ConfigCurator.META_ZK_PATH));
        assertTrue(metaData.getChecksum().length() > 0);
        assertTrue(metaData.isInternalRedeploy());
        assertThat(metaData.getDeployedByUser(), is("foo"));
        assertThat(metaData.getDeployPath(), is("/bar/baz"));
        assertThat(metaData.getDeployTimestamp(), is(1345L));
        assertThat(metaData.getGeneration(), is(3L));
        assertThat(metaData.getPreviousActiveGeneration(), is(2L));
    }

    @Test
    public void testVersionedFileRegistry() {
        String fileRegPath = appPath + "/" + ZKApplicationPackage.fileRegistryNode;
        assertTrue(zk.exists(fileRegPath));
        assertTrue(zk.exists(fileRegPath + "/1.2.3"));
        assertTrue(zk.exists(fileRegPath + "/3.2.1"));
        // assertNull("Data at " + fileRegPath, zk.getData(fileRegPath)); Not null any more .. hm
    }

    @Test
    public void include_dirs_are_written_to_ZK() {
        assertTrue(zk.exists(appPath + ConfigCurator.USERAPP_ZK_SUBPATH + "/" + "dir1", "default.xml"));
        assertTrue(zk.exists(appPath + ConfigCurator.USERAPP_ZK_SUBPATH + "/nested/" + "dir2", "chain2.xml"));
        assertTrue(zk.exists(appPath + ConfigCurator.USERAPP_ZK_SUBPATH + "/nested/" + "dir2", "chain3.xml"));
    }

    @Test
    public void search_chain_dir_written_to_ZK() {
        assertTrue(zk.exists(appPath().append("search").append("chains").append("dir1").append("default.xml").getAbsolute()));
        assertTrue(zk.exists(appPath().append("search").append("chains").append("dir2").append("chain2.xml").getAbsolute()));
        assertTrue(zk.exists(appPath().append("search").append("chains").append("dir2").append("chain3.xml").getAbsolute()));
    }

    private Path appPath() {
        return Path.fromString(appPath).append(ConfigCurator.USERAPP_ZK_SUBPATH);
    }

    @Test
    public void testWritingHostNamesToZooKeeper() throws IOException {
        ConfigCurator zk = ConfigCurator.create(new MockCurator());
        BaseDeployLogger logger = new BaseDeployLogger();
        Path app = Path.fromString("/1");
        ZooKeeperClient zooKeeperClient = new ZooKeeperClient(zk, logger, app);
        zooKeeperClient.initialize();
        HostSpec host1 = new HostSpec("host1.yahoo.com", Collections.emptyList(), Optional.empty());
        HostSpec host2 = new HostSpec("host2.yahoo.com", Collections.emptyList(), Optional.empty());
        ImmutableSet<HostSpec> hosts = ImmutableSet.of(host1, host2);
        zooKeeperClient.write(AllocatedHosts.withHosts(hosts));
        Path hostsPath = app.append(ZKApplicationPackage.allocatedHostsNode);
        assertTrue(zk.exists(hostsPath.getAbsolute()));
        
        AllocatedHosts deserialized = fromJson(zk.getBytes(hostsPath.getAbsolute()));
        assertEquals(hosts, deserialized.getHosts());
    }

}
