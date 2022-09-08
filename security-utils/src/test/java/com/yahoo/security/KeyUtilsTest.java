// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.security;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author bjorncs
 */
public class KeyUtilsTest {

    @Test
    void can_extract_public_key_from_rsa_private() {
        KeyPair keyPair = KeyUtils.generateKeypair(KeyAlgorithm.RSA);
        PublicKey publicKey = KeyUtils.extractPublicKey(keyPair.getPrivate());
        assertNotNull(publicKey);
    }

    @Test
    void can_extract_public_key_from_ecdsa_private() {
        KeyPair keyPair = KeyUtils.generateKeypair(KeyAlgorithm.EC);
        PublicKey publicKey = KeyUtils.extractPublicKey(keyPair.getPrivate());
        assertNotNull(publicKey);
    }

    @Test
    void can_serialize_and_deserialize_rsa_privatekey_using_pkcs1_pem_format() {
        testPrivateKeySerialization(KeyAlgorithm.RSA, KeyFormat.PKCS1, "RSA PRIVATE KEY");
    }

    @Test
    void can_serialize_and_deserialize_rsa_privatekey_using_pkcs8_pem_format() {
        testPrivateKeySerialization(KeyAlgorithm.RSA, KeyFormat.PKCS8, "PRIVATE KEY");
    }

    @Test
    void can_serialize_and_deserialize_ec_privatekey_using_pkcs1_pem_format() {
        testPrivateKeySerialization(KeyAlgorithm.EC, KeyFormat.PKCS1, "EC PRIVATE KEY");
    }

    @Test
    void can_serialize_and_deserialize_ec_privatekey_using_pkcs8_pem_format() {
        testPrivateKeySerialization(KeyAlgorithm.EC, KeyFormat.PKCS8, "PRIVATE KEY");
    }

    @Test
    void can_serialize_and_deserialize_rsa_publickey_using_pem_format() {
        KeyPair keyPair = KeyUtils.generateKeypair(KeyAlgorithm.RSA);
        String pem = KeyUtils.toPem(keyPair.getPublic());
        assertTrue(pem.contains("BEGIN PUBLIC KEY"));
        assertTrue(pem.contains("END PUBLIC KEY"));
        PublicKey deserializedKey = KeyUtils.fromPemEncodedPublicKey(pem);
        assertEquals(keyPair.getPublic(), deserializedKey);
        assertEquals(KeyAlgorithm.RSA.getAlgorithmName(), deserializedKey.getAlgorithm());
    }

    @Test
    void can_serialize_and_deserialize_ec_publickey_using_pem_format() {
        KeyPair keyPair = KeyUtils.generateKeypair(KeyAlgorithm.EC);
        String pem = KeyUtils.toPem(keyPair.getPublic());
        assertTrue(pem.contains("BEGIN PUBLIC KEY"));
        assertTrue(pem.contains("END PUBLIC KEY"));
        PublicKey deserializedKey = KeyUtils.fromPemEncodedPublicKey(pem);
        assertEquals(keyPair.getPublic(), deserializedKey);
        assertEquals(KeyAlgorithm.EC.getAlgorithmName(), deserializedKey.getAlgorithm());
    }

    private static void testPrivateKeySerialization(KeyAlgorithm keyAlgorithm, KeyFormat keyFormat, String pemLabel) {
        KeyPair keyPair = KeyUtils.generateKeypair(keyAlgorithm);
        String pem = KeyUtils.toPem(keyPair.getPrivate(), keyFormat);
        assertTrue(pem.contains("BEGIN " + pemLabel));
        assertTrue(pem.contains("END " + pemLabel));
        PrivateKey deserializedKey = KeyUtils.fromPemEncodedPrivateKey(pem);
        assertEquals(keyPair.getPrivate(), deserializedKey);
        assertEquals(keyAlgorithm.getAlgorithmName(), deserializedKey.getAlgorithm());
    }

}
