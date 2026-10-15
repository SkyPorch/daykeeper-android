package com.skyporch.daykeeper;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/** Compiles only if each documented Java call shape resolves to exactly one constructor. */
public class DaykeeperClientJavaCallersTest {
    @Test
    public void javaCallShapesResolve() {
        String url = "https://support.example/gateway";
        DaykeeperTokenProvider tokens = (forceRefresh, continuation) -> "synthetic";
        assertNotNull(new DaykeeperClient(url, tokens));
        assertNotNull(new DaykeeperClient(url, tokens, 10_000L));
        assertNotNull(new DaykeeperClient(url, tokens, 10_000L, null));
        assertNotNull(new DaykeeperClient(url, tokens, 10_000L, null, false));
    }
}
