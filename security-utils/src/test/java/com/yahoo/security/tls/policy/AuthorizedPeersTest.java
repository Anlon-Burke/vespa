// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.security.tls.policy;

import org.junit.Test;

import java.util.HashSet;

import static com.yahoo.security.tls.policy.RequiredPeerCredential.Field.CN;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;

/**
 * @author bjorncs
 */
public class AuthorizedPeersTest {

    @Test(expected = IllegalArgumentException.class)
    public void throws_exception_on_peer_policies_with_duplicate_names() {
        PeerPolicy peerPolicy1 = new PeerPolicy("duplicate-name", singletonList(RequiredPeerCredential.of(CN, "mycfgserver")));
        PeerPolicy peerPolicy2 = new PeerPolicy("duplicate-name", singletonList(RequiredPeerCredential.of(CN, "myclient")));
        new AuthorizedPeers(new HashSet<>(asList(peerPolicy1, peerPolicy2)));
    }

}
