package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class AwsEndpointsTest {

    @Test
    void readsTheRegionFromAnEcrHost() {
        assertEquals(Optional.of("eu-west-1"), AwsEndpoints.ecrRegion("123456789012.dkr.ecr.eu-west-1.amazonaws.com"));
        assertEquals(Optional.of("us-gov-west-1"), AwsEndpoints.ecrRegion("123456789012.dkr.ecr-fips.us-gov-west-1.amazonaws.com:443"));
        assertEquals(Optional.of("cn-north-1"), AwsEndpoints.ecrRegion("123456789012.dkr.ecr.cn-north-1.amazonaws.com.cn"));
    }

    @Test
    void otherHostsAreNotEcr() {
        assertTrue(AwsEndpoints.ecrRegion("ghcr.io").isEmpty());
        assertTrue(AwsEndpoints.ecrRegion("127.0.0.1:5000").isEmpty());
        assertTrue(AwsEndpoints.ecrRegion("evil.example/123456789012.dkr.ecr.eu-west-1.amazonaws.com").isEmpty());
        assertTrue(AwsEndpoints.ecrRegion("123456789012.dkr.ecr.eu-west-1.amazonaws.com.evil.example").isEmpty());
    }

    @Test
    void theChinaPartitionUsesItsOwnDnsSuffix() {
        assertEquals("https://sts.eu-west-1.amazonaws.com/", AwsEndpoints.sts("eu-west-1").toString());
        assertEquals("https://sts.cn-north-1.amazonaws.com.cn/", AwsEndpoints.sts("cn-north-1").toString());
        assertEquals("https://api.ecr.us-gov-east-1.amazonaws.com/", AwsEndpoints.ecrApi("us-gov-east-1").toString());
    }
}
