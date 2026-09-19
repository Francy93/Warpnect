package io.warpnect.debug.directshell;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DirectShellRuntimeApiTest {
    @Test
    public void parsesPositiveRuntimeSdkValues() {
        assertEquals(36, DirectShellRuntimeApi.parseSdkInt("36\n"));
        assertEquals(31, DirectShellRuntimeApi.parseSdkInt(" 31 "));
    }

    @Test
    public void rejectsMalformedRuntimeSdkValues() {
        assertEquals(-1, DirectShellRuntimeApi.parseSdkInt(null));
        assertEquals(-1, DirectShellRuntimeApi.parseSdkInt("0"));
        assertEquals(-1, DirectShellRuntimeApi.parseSdkInt("api36"));
    }
}
