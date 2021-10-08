// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.athenz.tls;

import com.yahoo.vespa.athenz.api.AthenzIdentity;
import com.yahoo.vespa.athenz.api.AthenzRole;
import com.yahoo.vespa.athenz.utils.AthenzIdentities;

import java.security.cert.X509Certificate;
import java.util.List;

import static com.yahoo.security.SubjectAlternativeName.Type.RFC822_NAME;

/**
 * Utility methods for Athenz issued x509 certificates
 *
 * @author bjorncs
 */
public class AthenzX509CertificateUtils {

    private AthenzX509CertificateUtils() {}

    public static AthenzIdentity getIdentityFromRoleCertificate(X509Certificate certificate) {
        List<com.yahoo.security.SubjectAlternativeName> sans = com.yahoo.security.X509CertificateUtils.getSubjectAlternativeNames(certificate);
        return sans.stream()
                .filter(san -> san.getType() == RFC822_NAME)
                .map(com.yahoo.security.SubjectAlternativeName::getValue)
                .map(AthenzX509CertificateUtils::getIdentityFromSanEmail)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Could not find identity in SAN: " + sans));
    }

    public static AthenzRole getRolesFromRoleCertificate(X509Certificate certificate) {
        String commonName = com.yahoo.security.X509CertificateUtils.getSubjectCommonNames(certificate).get(0);
        return AthenzRole.fromResourceNameString(commonName);
    }

    private static AthenzIdentity getIdentityFromSanEmail(String email) {
        int separator = email.indexOf('@');
        if (separator == -1) throw new IllegalArgumentException("Invalid SAN email: " + email);
        return AthenzIdentities.from(email.substring(0, separator));
    }

}
