package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CacheTypeTest {

    @Test
    void parsesIgnoringCase() {
        assertEquals(CacheType.AUTO, CacheType.parse("auto"));
        assertEquals(CacheType.APPCDS, CacheType.parse(" AppCDS "));
        assertEquals(CacheType.AOT, CacheType.parse("AOT"));
    }

    @Test
    void rejectsUnknownValues() {
        assertThrows(IllegalArgumentException.class, () -> CacheType.parse("cds"));
        assertThrows(IllegalArgumentException.class, () -> CacheType.parse(null));
    }

    @Test
    void autoFollowsJdkVersion() {
        assertEquals(CacheStrategy.APP_CDS, CacheType.AUTO.resolve(21));
        assertEquals(CacheStrategy.APP_CDS, CacheType.AUTO.resolve(24));
        assertEquals(CacheStrategy.LEYDEN_AOT, CacheType.AUTO.resolve(25));
    }

    @Test
    void appCdsWorksOnEverySupportedJdk() {
        assertEquals(CacheStrategy.APP_CDS, CacheType.APPCDS.resolve(21));
        assertEquals(CacheStrategy.APP_CDS, CacheType.APPCDS.resolve(25));
        assertThrows(IllegalArgumentException.class, () -> CacheType.APPCDS.resolve(17));
    }

    @Test
    void aotNeedsJdk25() {
        assertEquals(CacheStrategy.LEYDEN_AOT, CacheType.AOT.resolve(25));
        assertEquals(CacheStrategy.LEYDEN_AOT, CacheType.AOT.resolve(26));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CacheType.AOT.resolve(21));
        assertEquals(true, e.getMessage().contains("JDK 25"));
    }
}
