package io.github.baokhang83.mnemo.warmup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CacheStrategyTest {

    @Test
    void jdk21UsesAppCds() {
        assertEquals(CacheStrategy.APP_CDS, CacheStrategy.forFeatureVersion(21));
        assertEquals(CacheStrategy.APP_CDS, CacheStrategy.forFeatureVersion(24));
    }

    @Test
    void jdk25AndLaterUseLeyden() {
        assertEquals(CacheStrategy.LEYDEN_AOT, CacheStrategy.forFeatureVersion(26));
        assertEquals(CacheStrategy.LEYDEN_AOT, CacheStrategy.forFeatureVersion(25));
    }

    @Test
    void rejectsOldJdk() {
        assertThrows(IllegalArgumentException.class, () -> CacheStrategy.forFeatureVersion(17));
    }

    @Test
    void flags() {
        assertEquals("-XX:ArchiveClassesAtExit=a.jsa", CacheStrategy.APP_CDS.trainingFlag("a.jsa"));
        assertEquals("-XX:AOTCacheOutput=a.aot", CacheStrategy.LEYDEN_AOT.trainingFlag("a.aot"));
        assertEquals("-XX:SharedArchiveFile=a.jsa", CacheStrategy.APP_CDS.runtimeFlag("a.jsa"));
        assertEquals("-XX:AOTCache=a.aot", CacheStrategy.LEYDEN_AOT.runtimeFlag("a.aot"));
    }
}
