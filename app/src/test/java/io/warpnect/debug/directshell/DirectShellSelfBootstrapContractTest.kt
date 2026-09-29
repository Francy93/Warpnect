package io.warpnect.debug.directshell

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectShellSelfBootstrapContractTest {
    @Test
    fun installedArtifactAcceptsOnlyAnInstalledBaseApkWithSha256() {
        val artifact = DirectShellSelfBootstrapContract.installedArtifact(
            "/data/app/~~abc/io.warpnect-def/base.apk",
            "A".repeat(64),
        )

        assertEquals("a".repeat(64), artifact.sha256)
        assertEquals("/data/app/~~abc/io.warpnect-def/base.apk", artifact.sourcePath)
    }

    @Test
    fun installedArtifactRejectsArbitraryPaths() {
        assertFails { DirectShellSelfBootstrapContract.installedArtifact("/data/local/tmp/other.jar", "a".repeat(64)) }
        assertFails {
            DirectShellSelfBootstrapContract.installedArtifact(
                "/data/app/~~abc/io.warpnect-def/base.apk;id",
                "a".repeat(64),
            )
        }
    }

    @Test
    fun pairingValuesAreBoundedAndStrict() {
        assertEquals(37_001, DirectShellSelfBootstrapContract.pairingPort("37001"))
        assertEquals("123456", DirectShellSelfBootstrapContract.pairingCode("123456"))
        assertEquals("123456", DirectShellSelfBootstrapContract.pairingCode("123456\n"))
        assertFails { DirectShellSelfBootstrapContract.connectionPort("1023") }
        assertFails { DirectShellSelfBootstrapContract.pairingCode("12345a") }
    }

    @Test
    fun checksumOutputRequiresExactlyOneSha256Digest() {
        assertEquals(
            "b".repeat(64),
            DirectShellSelfBootstrapContract.parseChecksumOutput("${"B".repeat(64)}  /data/app/example/base.apk\n"),
        )
        assertFails { DirectShellSelfBootstrapContract.parseChecksumOutput("not-a-checksum") }
    }

    @Test
    fun launchCommandIsClosedToTheKnownServerAndInstalledArtifact() {
        val artifact = DirectShellSelfBootstrapContract.installedArtifact(
            "/data/app/~~abc/io.warpnect-def/base.apk",
            "c".repeat(64),
        )
        val launch = DirectShellSelfBootstrapContract.newLaunch(artifact)
        try {
            val encodedSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(launch.secret)
            assertTrue(launch.command.contains("CLASSPATH='/data/app/~~abc/io.warpnect-def/base.apk'"))
            assertTrue(launch.command.contains("/system/bin/nohup sh -c"))
            assertTrue(launch.command.contains("WRAPPER_START pid="))
            assertTrue(launch.command.contains("launch_failure=wrapper_exited"))
            assertTrue(launch.command.contains("launch_failure=wrapper_start_timeout"))
            assertTrue(launch.command.contains("while [ \$attempt -lt 80 ]; do"))
            assertTrue(launch.command.contains("app_process /system/bin"))
            assertTrue(launch.command.contains("WRAPPER_EXIT_status="))
            assertTrue(launch.command.contains(DirectShellSelfBootstrapContract.SERVER_MAIN_CLASS))
            assertTrue(launch.command.contains("--declared-package io.warpnect"))
            assertTrue(launch.command.contains("--secret-file"))
            assertTrue(launch.command.contains(launch.secretFilePath))
            assertTrue(launch.secretFilePath.startsWith("/data/local/tmp/warpnect-directshell-secret-"))
            assertTrue(launch.startupLogPath.startsWith("/data/local/tmp/warpnect-directshell-bootstrap-"))
            assertFalse(launch.command.substringBefore("CLASSPATH").contains(launch.secretFilePath))
            assertFalse(launch.command.contains("shell:"))
            assertFalse(launch.command.contains("--secret-base64"))
            assertFalse(launch.command.contains(encodedSecret))
            assertEquals(DirectShellProbeProtocol.SECRET_BYTES, launch.secret.size)
            val provision = DirectShellSelfBootstrapContract.secretProvisionCommand(launch)
            assertTrue(provision.contains("head -c ${DirectShellProbeProtocol.SECRET_BYTES}"))
            assertTrue(provision.contains(launch.secretFilePath))
            assertTrue(provision.contains("secret_file=READY"))
            assertFalse(provision.contains(encodedSecret))
        } finally {
            launch.secret.fill(0)
        }
    }

    @Test
    fun inputSmokeLaunchIsClosedToTheKnownInputServerAndSecretFile() {
        val artifact = DirectShellSelfBootstrapContract.installedArtifact(
            "/data/app/~~abc/io.warpnect-def/base.apk",
            "d".repeat(64),
        )
        val launch = DirectShellSelfBootstrapContract.newLaunch(
            artifact,
            DirectShellSelfBootstrapContract.ServerRole.InputSmoke,
        )
        try {
            assertEquals(DirectShellSelfBootstrapContract.ServerRole.InputSmoke, launch.serverRole)
            assertTrue(launch.command.contains(DirectShellSelfBootstrapContract.INPUT_SMOKE_SERVER_MAIN_CLASS))
            assertTrue(launch.command.contains("--generation ${launch.generation}"))
            assertTrue(launch.command.contains("--display-id 0"))
            assertTrue(launch.command.contains("--secret-file ${launch.secretFilePath}"))
            assertFalse(launch.command.contains("--declared-package"))
            assertFalse(launch.command.contains("--secret-base64"))
        } finally {
            launch.secret.fill(0)
        }
    }

    @Test
    fun launchProcessIdRequiresTheClosedLaunchOutputFormat() {
        assertEquals(42_001, DirectShellSelfBootstrapContract.parseLaunchProcessId("pid=42001\n"))
        assertFails { DirectShellSelfBootstrapContract.parseLaunchProcessId("pid=42001") }
        assertFails { DirectShellSelfBootstrapContract.parseLaunchProcessId("pid=0") }
        assertFails {
            DirectShellSelfBootstrapContract.parseLaunchProcessId(
                "launch_failure=wrapper_start_timeout\n",
            )
        }
        assertFails { DirectShellSelfBootstrapContract.parseLaunchProcessId("42001") }
    }

    @Test
    fun fragmentedLaunchResponseIsNotAcceptedBeforeItsLineTerminator() {
        val output = StringBuilder()
        listOf("p", "id=", "42", "001").forEach { fragment ->
            output.append(fragment)
            assertFalse(DirectShellSelfBootstrapContract.hasCompleteLaunchResponse(output.toString()))
        }
        output.append('\n')
        assertTrue(DirectShellSelfBootstrapContract.hasCompleteLaunchResponse(output.toString()))
        assertEquals(42_001, DirectShellSelfBootstrapContract.parseLaunchProcessId(output.toString()))
        assertFalse(DirectShellSelfBootstrapContract.hasCompleteLaunchResponse("launch_failure=wrapper_exited"))
        assertTrue(DirectShellSelfBootstrapContract.hasCompleteLaunchResponse("launch_failure=wrapper_exited\n"))
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
