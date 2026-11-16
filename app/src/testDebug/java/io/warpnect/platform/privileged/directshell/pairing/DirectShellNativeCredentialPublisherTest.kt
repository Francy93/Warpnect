package io.warpnect.platform.privileged.directshell.pairing

import java.io.FileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No Android JNI on the host: only explicit gate, metadata and descriptor-ownership models. */
class DirectShellNativeCredentialPublisherTest {
    private class Fixture {
        var api = 30
        var loads = 0
        var duplicates = 0
        var calls = 0
        var closes = 0
        var loadFailure = false
        var duplicateFailure = false
        var closeFailure = false
        var callFailure = false
        var result = 1L
        var atCall: () -> Unit = {}
        val publisher = DirectShellNativeCredentialPublisher(
            apiLevel = { api },
            load = {
                loads++
                if (loadFailure) throw UnsatisfiedLinkError("synthetic")
            },
            duplicate = {
                duplicates++
                if (duplicateFailure) throw java.io.IOException("synthetic")
                object : DirectShellPublicationDescriptor {
                    override val number = 73
                    override fun close() {
                        closes++
                        if (closeFailure) throw java.io.IOException("synthetic")
                    }
                }
            },
            invoke = {
                assertEquals(73, it)
                calls++
                atCall()
                if (callFailure) throw java.io.IOException("synthetic_secret_not_observable")
                result
            },
        )
        fun publish() = publisher.publish(FileDescriptor())
    }

    @Test fun `construction and drain observation do not load or open`() {
        val f = Fixture()
        assertTrue(f.publisher.resourcesDrained())
        assertEquals(0, f.loads + f.calls + f.duplicates)
    }

    @Test fun `API26 through 29 never load duplicate or invoke`() {
        val f = Fixture()
        (26..29).forEach {
            f.api = it
            assertEquals(DirectShellCredentialPublishResult.UNAVAILABLE, f.publish().result)
        }
        assertEquals(0, f.loads + f.calls + f.duplicates)
    }

    @Test fun `native load failure is unavailable before any descriptor or publication`() {
        val f = Fixture().apply { loadFailure = true }
        assertEquals(DirectShellCredentialPublishResult.UNAVAILABLE, f.publish().result)
        assertEquals(0, f.calls + f.duplicates)
        assertTrue(f.publisher.resourcesDrained())
    }

    @Test fun `duplicate failure never invokes publication`() {
        val f = Fixture().apply { duplicateFailure = true }
        assertEquals(DirectShellCredentialPublishResult.NOT_ATTEMPTED, f.publish().result)
        assertEquals(0, f.calls)
        assertTrue(f.publisher.resourcesDrained())
    }

    @Test fun `success consumes source but does not promise sync`() {
        val f = Fixture()
        val result = f.publish()
        assertEquals(DirectShellCredentialPublishResult.PUBLISHED_CONSUMED, result.result)
        assertEquals(0, result.errno)
        assertEquals(1, f.calls)
        assertEquals(1, f.closes)
        assertTrue(f.publisher.resourcesDrained())
    }

    @Test fun `collision has exact EEXIST and source retained`() {
        val f = Fixture().apply { result = (17L shl 32) or 2 }
        assertEquals(DirectShellCredentialPublishResult.COLLISION, f.publish().result)
        assertEquals(DirectShellCredentialSource.RETAINED, f.publish().result.source)
    }

    @Test fun `unsupported syscall errors do not select a fallback`() {
        val f = Fixture()
        listOf(1, 13, 22, 38, 95).forEach {
            f.result = (it.toLong() shl 32) or 3
            assertEquals(DirectShellCredentialPublishResult.UNSUPPORTED, f.publish().result)
        }
        assertEquals(5, f.calls)
    }

    @Test fun `pre syscall validation failure is known not attempted`() {
        val f = Fixture().apply { result = (13L shl 32) or 4 }
        assertEquals(DirectShellCredentialPublishResult.NOT_ATTEMPTED, f.publish().result)
    }

    @Test fun `unknown metadata and contradictory native values stay unknown`() {
        listOf(0L, 8L, (17L shl 32) or 1, 2L, (9000L shl 32) or 4, 5L).forEach {
            assertEquals(
                DirectShellCredentialPublishResult.UNKNOWN,
                DirectShellNativeCredentialPublisher.decode(it).result,
            )
        }
        val knownAttempt = DirectShellNativeCredentialPublisher.decode((5L shl 32) or 5)
        assertEquals(DirectShellCredentialPublishResult.UNKNOWN_EFFECT, knownAttempt.result)
        assertEquals(true, knownAttempt.result.attempted)
    }

    @Test fun `exception after native entry is unknown and leaks no exception detail`() {
        val f = Fixture().apply { callFailure = true }
        val result = f.publish()
        assertEquals(DirectShellCredentialPublishResult.UNKNOWN, result.result)
        assertEquals(null, result.result.attempted)
        assertFalse(result.toString().contains("synthetic_secret"))
        assertTrue(f.publisher.resourcesDrained())
    }

    @Test fun `close uncertainty preserves known publication and prevents another call`() {
        val f = Fixture().apply { closeFailure = true }
        assertEquals(DirectShellCredentialPublishResult.PUBLISHED_CONSUMED, f.publish().result)
        assertFalse(f.publisher.resourcesDrained())
        assertEquals(DirectShellCredentialPublishResult.NOT_ATTEMPTED, f.publish().result)
        assertEquals(1, f.calls)
        assertEquals(1, f.closes)
    }

    @Test fun `reentrant publication has no second native execution or queue`() {
        val f = Fixture()
        f.atCall = { assertEquals(DirectShellCredentialPublishResult.NOT_ATTEMPTED, f.publish().result) }
        assertEquals(DirectShellCredentialPublishResult.PUBLISHED_CONSUMED, f.publish().result)
        assertEquals(1, f.calls)
        assertEquals(1, f.duplicates)
    }
}
