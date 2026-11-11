package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SigV4SignerTest {

    private static final AwsCredentials KEYS =
            new AwsCredentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", null, null);
    private static final Instant WHEN = Instant.parse("2015-08-30T12:36:00Z");

    /** AWS Signature V4 test suite, case "get-vanilla". */
    @Test
    void matchesTheAwsGetVanillaVector() {
        Map<String, String> h = SigV4Signer.sign("GET", URI.create("https://example.amazonaws.com/"), Map.of(), new byte[0], KEYS, "us-east-1", "service", WHEN);
        assertEquals("20150830T123600Z", h.get("x-amz-date"));
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, "
                + "SignedHeaders=host;x-amz-date, "
                + "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31", h.get("Authorization"));
    }

    /** AWS Signature V4 test suite, case "post-x-www-form-urlencoded" (signed Content-Type and a body). */
    @Test
    void matchesTheAwsPostFormVector() {
        Map<String, String> h = SigV4Signer.sign(
                "POST", URI.create("https://example.amazonaws.com/"),
                Map.of("Content-Type", "application/x-www-form-urlencoded"),
                "Param1=value1".getBytes(java.nio.charset.StandardCharsets.UTF_8), KEYS, "us-east-1", "service", WHEN);
        assertTrue(h.get("Authorization").contains("SignedHeaders=content-type;host;x-amz-date"), h.get("Authorization"));
        assertTrue(h.get("Authorization").endsWith("Signature=ff11897932ad3f4e8b18135d722051e5ac45fc38421b1da7b9d196a0fe09473a"), h.get("Authorization"));
    }

    @Test
    void sessionTokensAreSignedAndSentAndQueryParametersAreSorted() {
        var temp = new AwsCredentials("ASIAEXAMPLE", "secret", "token-123", null);
        Map<String, String> h = SigV4Signer.sign("GET", URI.create("https://sts.eu-west-1.amazonaws.com/?b=2&a=1"), Map.of(), new byte[0], temp, "eu-west-1", "sts", WHEN);
        assertEquals("token-123", h.get("x-amz-security-token"));
        assertTrue(h.get("Authorization").contains("SignedHeaders=host;x-amz-date;x-amz-security-token"));
    }

    @Test
    void credentialsNeverPrintTheirSecrets() {
        var temp = new AwsCredentials("ASIAEXAMPLE", "very-secret", "tok-secret", null);
        assertFalse(temp.toString().contains("very-secret"));
        assertFalse(temp.toString().contains("tok-secret"));
    }
}
