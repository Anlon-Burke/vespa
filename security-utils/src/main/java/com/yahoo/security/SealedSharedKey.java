// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.security;

import java.nio.ByteBuffer;
import java.util.Base64;

/**
 * A SealedSharedKey represents the public part of a secure one-way ephemeral key exchange.
 *
 * It is "sealed" in the sense that it is expected to be computationally infeasible
 * for anyone to derive the correct shared key from the sealed key without holding
 * the correct private key.
 *
 * A SealedSharedKey can be converted to--and from--an opaque string token representation.
 * This token representation is expected to be used as a convenient serialization
 * form when communicating shared keys.
 */
public record SealedSharedKey(int keyId, byte[] enc, byte[] ciphertext) {

    /** Current encoding version of opaque sealed key tokens. Must be less than 256. */
    public static final int CURRENT_TOKEN_VERSION = 1;

    private static final int MAX_ENC_CONTEXT_LENGTH = Short.MAX_VALUE;

    /**
     * Creates an opaque URL-safe string token that contains enough information to losslessly
     * reconstruct the SealedSharedKey instance when passed verbatim to fromTokenString().
     */
    public String toTokenString() {
        if (keyId >= (1 << 24)) {
            throw new IllegalArgumentException("Key id is too large to be encoded");
        }
        if (enc.length > MAX_ENC_CONTEXT_LENGTH) {
            throw new IllegalArgumentException("Encryption context is too large to be encoded");
        }

        // i32 header || i16 length(enc) || enc || ciphertext
        ByteBuffer encoded = ByteBuffer.allocate(4 + 2 + enc.length + ciphertext.length);
        encoded.putInt((CURRENT_TOKEN_VERSION << 24) | keyId);
        encoded.putShort((short)enc.length);
        encoded.put(enc);
        encoded.put(ciphertext);
        encoded.flip();

        byte[] encBytes = new byte[encoded.remaining()];
        encoded.get(encBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encBytes);
    }

    /**
     * Attempts to unwrap a SealedSharedKey opaque token representation that was previously
     * created by a call to toTokenString().
     */
    public static SealedSharedKey fromTokenString(String tokenString) {
        byte[] rawTokenBytes = Base64.getUrlDecoder().decode(tokenString);
        if (rawTokenBytes.length < 4) {
            throw new IllegalArgumentException("Decoded token too small to contain a header");
        }
        ByteBuffer decoded = ByteBuffer.wrap(rawTokenBytes);
        int versionAndKeyId = decoded.getInt();
        int version = versionAndKeyId >>> 24;
        if (version != CURRENT_TOKEN_VERSION) {
            throw new IllegalArgumentException("Token had unexpected version. Expected %d, was %d"
                                               .formatted(CURRENT_TOKEN_VERSION, version));
        }
        short encLen = decoded.getShort();
        if (encLen <= 0) {
            throw new IllegalArgumentException("Token encryption context does not have a valid length");
        }
        byte[] enc = new byte[encLen];
        decoded.get(enc);
        byte[] ciphertext = new byte[decoded.remaining()];
        decoded.get(ciphertext);

        int keyId = versionAndKeyId & 0xffffff;
        return new SealedSharedKey(keyId, enc, ciphertext);
    }

}
