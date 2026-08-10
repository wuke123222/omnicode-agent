package dev.omnicode.commercial;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.cert.CertPath;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertPathValidator;
import java.security.cert.CertStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Verifies JetBrains Marketplace confirmation stamps for the OmniCode product code.
 *
 * <p>Adapted from JetBrains' Apache-2.0 licensed Marketplace paid-plugin example:
 * https://github.com/JetBrains/marketplace-makemecoffee-plugin</p>
 */
public final class JetBrainsLicenseStampVerifier {
    private static final String KEY_PREFIX = "key:";
    private static final String STAMP_PREFIX = "stamp:";
    private static final int MAX_STAMP_CHARS = 128 * 1024;
    private static final long SERVER_STAMP_VALIDITY_MILLIS = 60L * 60L * 1000L;

    private static final String[] ROOT_CERTIFICATES = new String[]{
        "-----BEGIN CERTIFICATE-----\n" +
            "MIIFOzCCAyOgAwIBAgIJANJssYOyg3nhMA0GCSqGSIb3DQEBCwUAMBgxFjAUBgNV\n" +
            "BAMMDUpldFByb2ZpbGUgQ0EwHhcNMTUxMDAyMTEwMDU2WhcNNDUxMDI0MTEwMDU2\n" +
            "WjAYMRYwFAYDVQQDDA1KZXRQcm9maWxlIENBMIICIjANBgkqhkiG9w0BAQEFAAOC\n" +
            "Ag8AMIICCgKCAgEA0tQuEA8784NabB1+T2XBhpB+2P1qjewHiSajAV8dfIeWJOYG\n" +
            "y+ShXiuedj8rL8VCdU+yH7Ux/6IvTcT3nwM/E/3rjJIgLnbZNerFm15Eez+XpWBl\n" +
            "m5fDBJhEGhPc89Y31GpTzW0vCLmhJ44XwvYPntWxYISUrqeR3zoUQrCEp1C6mXNX\n" +
            "EpqIGIVbJ6JVa/YI+pwbfuP51o0ZtF2rzvgfPzKtkpYQ7m7KgA8g8ktRXyNrz8bo\n" +
            "iwg7RRPeqs4uL/RK8d2KLpgLqcAB9WDpcEQzPWegbDrFO1F3z4UVNH6hrMfOLGVA\n" +
            "xoiQhNFhZj6RumBXlPS0rmCOCkUkWrDr3l6Z3spUVgoeea+QdX682j6t7JnakaOw\n" +
            "jzwY777SrZoi9mFFpLVhfb4haq4IWyKSHR3/0BlWXgcgI6w6LXm+V+ZgLVDON52F\n" +
            "LcxnfftaBJz2yclEwBohq38rYEpb+28+JBvHJYqcZRaldHYLjjmb8XXvf2MyFeXr\n" +
            "SopYkdzCvzmiEJAewrEbPUaTllogUQmnv7Rv9sZ9jfdJ/cEn8e7GSGjHIbnjV2ZM\n" +
            "Q9vTpWjvsT/cqatbxzdBo/iEg5i9yohOC9aBfpIHPXFw+fEj7VLvktxZY6qThYXR\n" +
            "Rus1WErPgxDzVpNp+4gXovAYOxsZak5oTV74ynv1aQ93HSndGkKUE/qA/JECAwEA\n" +
            "AaOBhzCBhDAdBgNVHQ4EFgQUo562SGdCEjZBvW3gubSgUouX8bMwSAYDVR0jBEEw\n" +
            "P4AUo562SGdCEjZBvW3gubSgUouX8bOhHKQaMBgxFjAUBgNVBAMMDUpldFByb2Zp\n" +
            "bGUgQ0GCCQDSbLGDsoN54TAMBgNVHRMEBTADAQH/MAsGA1UdDwQEAwIBBjANBgkq\n" +
            "hkiG9w0BAQsFAAOCAgEAjrPAZ4xC7sNiSSqh69s3KJD3Ti4etaxcrSnD7r9rJYpK\n" +
            "BMviCKZRKFbLv+iaF5JK5QWuWdlgA37ol7mLeoF7aIA9b60Ag2OpgRICRG79QY7o\n" +
            "uLviF/yRMqm6yno7NYkGLd61e5Huu+BfT459MWG9RVkG/DY0sGfkyTHJS5xrjBV6\n" +
            "hjLG0lf3orwqOlqSNRmhvn9sMzwAP3ILLM5VJC5jNF1zAk0jrqKz64vuA8PLJZlL\n" +
            "S9TZJIYwdesCGfnN2AETvzf3qxLcGTF038zKOHUMnjZuFW1ba/12fDK5GJ4i5y+n\n" +
            "fDWVZVUDYOPUixEZ1cwzmf9Tx3hR8tRjMWQmHixcNC8XEkVfztID5XeHtDeQ+uPk\n" +
            "X+jTDXbRb+77BP6n41briXhm57AwUI3TqqJFvoiFyx5JvVWG3ZqlVaeU/U9e0gxn\n" +
            "8qyR+ZA3BGbtUSDDs8LDnE67URzK+L+q0F2BC758lSPNB2qsJeQ63bYyzf0du3wB\n" +
            "/gb2+xJijAvscU3KgNpkxfGklvJD/oDUIqZQAnNcHe7QEf8iG2WqaMJIyXZlW3me\n" +
            "0rn+cgvxHPt6N4EBh5GgNZR4l0eaFEV+fxVsydOQYo1RIyFMXtafFBqQl6DDxujl\n" +
            "FeU3FZ+Bcp12t7dlM4E0/sS1XdL47CfGVj4Bp+/VbF862HmkAbd7shs7sDQkHbU=\n" +
            "-----END CERTIFICATE-----\n",
        "-----BEGIN CERTIFICATE-----\n" +
            "MIIFTDCCAzSgAwIBAgIJAMCrW9HV+hjZMA0GCSqGSIb3DQEBCwUAMB0xGzAZBgNV\n" +
            "BAMMEkxpY2Vuc2UgU2VydmVycyBDQTAgFw0xNjEwMTIxNDMwNTRaGA8yMTE2MTIy\n" +
            "NzE0MzA1NFowHTEbMBkGA1UEAwwSTGljZW5zZSBTZXJ2ZXJzIENBMIICIjANBgkq\n" +
            "hkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAoT7LvHj3JKK2pgc5f02z+xEiJDcvlBi6\n" +
            "fIwrg/504UaMx3xWXAE5CEPelFty+QPRJnTNnSxqKQQmg2s/5tMJpL9lzGwXaV7a\n" +
            "rrcsEDbzV4el5mIXUnk77Bm/QVv48s63iQqUjVmvjQt9SWG2J7+h6X3ICRvF1sQB\n" +
            "yeat/cO7tkpz1aXXbvbAws7/3dXLTgAZTAmBXWNEZHVUTcwSg2IziYxL8HRFOH0+\n" +
            "GMBhHqa0ySmF1UTnTV4atIXrvjpABsoUvGxw+qOO2qnwe6ENEFWFz1a7pryVOHXg\n" +
            "P+4JyPkI1hdAhAqT2kOKbTHvlXDMUaxAPlriOVw+vaIjIVlNHpBGhqTj1aqfJpLj\n" +
            "qfDFcuqQSI4O1W5tVPRNFrjr74nDwLDZnOF+oSy4E1/WhL85FfP3IeQAIHdswNMJ\n" +
            "y+RdkPZCfXzSUhBKRtiM+yjpIn5RBY+8z+9yeGocoxPf7l0or3YF4GUpud202zgy\n" +
            "Y3sJqEsZksB750M0hx+vMMC9GD5nkzm9BykJS25hZOSsRNhX9InPWYYIi6mFm8QA\n" +
            "2Dnv8wxAwt2tDNgqa0v/N8OxHglPcK/VO9kXrUBtwCIfZigO//N3hqzfRNbTv/ZO\n" +
            "k9lArqGtcu1hSa78U4fuu7lIHi+u5rgXbB6HMVT3g5GQ1L9xxT1xad76k2EGEi3F\n" +
            "9B+tSrvru70CAwEAAaOBjDCBiTAdBgNVHQ4EFgQUpsRiEz+uvh6TsQqurtwXMd4J\n" +
            "8VEwTQYDVR0jBEYwRIAUpsRiEz+uvh6TsQqurtwXMd4J8VGhIaQfMB0xGzAZBgNV\n" +
            "BAMMEkxpY2Vuc2UgU2VydmVycyBDQYIJAMCrW9HV+hjZMAwGA1UdEwQFMAMBAf8w\n" +
            "CwYDVR0PBAQDAgEGMA0GCSqGSIb3DQEBCwUAA4ICAQCJ9+GQWvBS3zsgPB+1PCVc\n" +
            "oG6FY87N6nb3ZgNTHrUMNYdo7FDeol2DSB4wh/6rsP9Z4FqVlpGkckB+QHCvqU+d\n" +
            "rYPe6QWHIb1kE8ftTnwapj/ZaBtF80NWUfYBER/9c6To5moW63O7q6cmKgaGk6zv\n" +
            "St2IhwNdTX0Q5cib9ytE4XROeVwPUn6RdU/+AVqSOspSMc1WQxkPVGRF7HPCoGhd\n" +
            "vqebbYhpahiMWfClEuv1I37gJaRtsoNpx3f/jleoC/vDvXjAznfO497YTf/GgSM2\n" +
            "LCnVtpPQQ2vQbOfTjaBYO2MpibQlYpbkbjkd5ZcO5U5PGrQpPFrWcylz7eUC3c05\n" +
            "UVeygGIthsA/0hMCioYz4UjWTgi9NQLbhVkfmVQ5lCVxTotyBzoubh3FBz+wq2Qt\n" +
            "iElsBrCMR7UwmIu79UYzmLGt3/gBdHxaImrT9SQ8uqzP5eit54LlGbvGekVdAL5l\n" +
            "DFwPcSB1IKauXZvi1DwFGPeemcSAndy+Uoqw5XGRqE6jBxS7XVI7/4BSMDDRBz1u\n" +
            "a+JMGZXS8yyYT+7HdsybfsZLvkVmc9zVSDI7/MjVPdk6h0sLn+vuPC1bIi5edoNy\n" +
            "PdiG2uPH5eDO6INcisyPpLS4yFKliaO4Jjap7yzLU9pbItoWgCAYa2NpxuxHJ0tB\n" +
            "7tlDFnvaRnQukqSG+VqNWg==\n" +
            "-----END CERTIFICATE-----"
    };

    private JetBrainsLicenseStampVerifier() {
    }

    public static boolean isValid(String confirmationStamp) {
        if (confirmationStamp == null || confirmationStamp.isBlank() || confirmationStamp.length() > MAX_STAMP_CHARS) {
            return false;
        }
        if (confirmationStamp.startsWith(KEY_PREFIX)) {
            return isKeyValid(confirmationStamp.substring(KEY_PREFIX.length()));
        }
        if (confirmationStamp.startsWith(STAMP_PREFIX)) {
            return isLicenseServerStampValid(confirmationStamp.substring(STAMP_PREFIX.length()));
        }
        return false;
    }

    private static boolean isKeyValid(String key) {
        String[] licenseParts = key.split("-");
        if (licenseParts.length != 4) {
            return false;
        }
        try {
            String licenseId = licenseParts[0];
            byte[] licenseBytes = Base64.getMimeDecoder().decode(licenseParts[1].getBytes(StandardCharsets.UTF_8));
            byte[] signatureBytes = Base64.getMimeDecoder().decode(licenseParts[2].getBytes(StandardCharsets.UTF_8));
            byte[] certificateBytes = Base64.getMimeDecoder().decode(licenseParts[3].getBytes(StandardCharsets.UTF_8));
            Signature signature = Signature.getInstance("SHA1withRSA");
            signature.initVerify(createCertificate(certificateBytes, Collections.emptySet(), false));
            signature.update(licenseBytes);
            if (!signature.verify(signatureBytes)) {
                return false;
            }
            String licenseData = new String(licenseBytes, StandardCharsets.UTF_8);
            return licenseData.contains("\"licenseId\":\"" + licenseId + "\"");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isLicenseServerStampValid(String serverStamp) {
        try {
            String[] parts = serverStamp.split(":");
            if (parts.length < 6 || parts.length > 16) {
                return false;
            }
            Base64.Decoder base64 = Base64.getMimeDecoder();
            String expectedMachineId = parts[0];
            long timestamp = Long.parseLong(parts[1]);
            String machineId = parts[2];
            String signatureType = parts[3];
            byte[] signatureBytes = base64.decode(parts[4].getBytes(StandardCharsets.UTF_8));
            byte[] certificateBytes = base64.decode(parts[5].getBytes(StandardCharsets.UTF_8));
            Collection<byte[]> intermediates = new ArrayList<>();
            for (int index = 6; index < parts.length; index++) {
                intermediates.add(base64.decode(parts[index].getBytes(StandardCharsets.UTF_8)));
            }
            Signature signature = Signature.getInstance(signatureType);
            signature.initVerify(createCertificate(certificateBytes, intermediates, true));
            signature.update((timestamp + ":" + machineId).getBytes(StandardCharsets.UTF_8));
            return signature.verify(signatureBytes)
                && expectedMachineId.equals(machineId)
                && Math.abs(System.currentTimeMillis() - timestamp) < SERVER_STAMP_VALIDITY_MILLIS;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @NotNull
    private static X509Certificate createCertificate(
        byte[] certificateBytes,
        Collection<byte[]> intermediateCertificateBytes,
        boolean checkValidityAtCurrentDate
    ) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate certificate = (X509Certificate) factory.generateCertificate(
            new ByteArrayInputStream(certificateBytes)
        );
        Collection<Certificate> allCertificates = new HashSet<>();
        allCertificates.add(certificate);
        for (byte[] bytes : intermediateCertificateBytes) {
            allCertificates.add(factory.generateCertificate(new ByteArrayInputStream(bytes)));
        }

        X509CertSelector selector = new X509CertSelector();
        selector.setCertificate(certificate);
        Set<TrustAnchor> trustAnchors = new HashSet<>();
        for (String rootCertificate : ROOT_CERTIFICATES) {
            trustAnchors.add(new TrustAnchor(
                (X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(rootCertificate.getBytes(StandardCharsets.UTF_8))
                ),
                null
            ));
        }
        PKIXBuilderParameters parameters = new PKIXBuilderParameters(trustAnchors, selector);
        parameters.setRevocationEnabled(false);
        if (!checkValidityAtCurrentDate) {
            parameters.setDate(certificate.getNotBefore());
        }
        parameters.addCertStore(
            CertStore.getInstance("Collection", new CollectionCertStoreParameters(allCertificates))
        );
        CertPath path = CertPathBuilder.getInstance("PKIX").build(parameters).getCertPath();
        CertPathValidator.getInstance("PKIX").validate(path, parameters);
        return certificate;
    }
}
