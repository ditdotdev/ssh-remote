// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.remote.ssh.server

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dev.dit.remote.RemoteOperation
import dev.dit.remote.RemoteOperationType
import dev.dit.remote.RemoteServerUtil
import dev.dit.remote.rsync.RsyncExecutor
import dev.dit.remote.rsync.RsyncRemote
import dev.dit.shell.CommandException
import dev.dit.shell.CommandExecutor
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempFile

class SshRemoteServer : RsyncRemote() {
    internal var executor = CommandExecutor()
    internal val gson = GsonBuilder().create()
    internal val util = RemoteServerUtil()

    companion object {
        /**
         * Allowlist for commit identifiers that will be interpolated into a shell
         * command on the remote side. Mirrors the regex used in `ssh-remote-go`
         * (`validateCommitID`). Anything outside this set risks command injection
         * through the unquoted `commitId` argument used in
         * `cat "<path>/<id>/metadata.json"`.
         */
        internal val COMMIT_ID_PATTERN = Regex("^[A-Za-z0-9._-]+\$")

        /**
         * Allowlist for paths that will be interpolated into `sh -c "cat > $path"`
         * inside [writeFileSsh]. Shell metacharacters that could break out of the
         * single-quoted context (anything other than path-safe characters) are
         * rejected up front so even an attacker who controls the path argument
         * cannot inject commands. The set is intentionally narrow: this provider
         * only writes `metadata.json` under `<remote.path>/<commitId>/`, both of
         * which are validated.
         */
        internal val SAFE_PATH_PATTERN = Regex("^[A-Za-z0-9._/-]+\$")

        /**
         * Single-quote-escape a string for safe inclusion inside a POSIX shell
         * single-quoted literal. Each embedded single quote is replaced with the
         * canonical `'\''` sequence: close the quoted string, emit a literal
         * quote, then reopen.
         */
        internal fun shellSingleQuote(s: String): String = s.replace("'", "'\\''")

        /**
         * Throw [IllegalArgumentException] if [commitId] is empty or contains any
         * character outside [COMMIT_ID_PATTERN].
         */
        internal fun validateCommitId(commitId: String) {
            if (commitId.isEmpty()) {
                throw IllegalArgumentException("commitId is required")
            }
            if (!COMMIT_ID_PATTERN.matches(commitId)) {
                throw IllegalArgumentException("invalid commitId '$commitId': must match ${COMMIT_ID_PATTERN.pattern}")
            }
        }

        /**
         * Throw [IllegalArgumentException] if [path] contains characters outside
         * [SAFE_PATH_PATTERN]. Used to gate paths handed to `sh -c` in
         * [writeFileSsh].
         */
        internal fun validateRemotePath(path: String) {
            if (path.isEmpty()) {
                throw IllegalArgumentException("path is required")
            }
            if (!SAFE_PATH_PATTERN.matches(path)) {
                throw IllegalArgumentException("invalid remote path '$path': must match ${SAFE_PATH_PATTERN.pattern}")
            }
        }

        /**
         * Coerce a JSON-deserialized value into a [Boolean]. Map<String, Any>
         * payloads can carry either a real [Boolean] (most parsers) or the
         * literal strings `"true"`/`"false"` (some serializers). Anything else
         * is rejected so a typo like `"yes"` does not silently disable a
         * security control.
         */
        internal fun coerceBoolean(
            prop: String,
            value: Any,
        ): Boolean =
            when (value) {
                is Boolean -> {
                    value
                }

                is String -> {
                    when (value.lowercase()) {
                        "true" -> {
                            true
                        }

                        "false" -> {
                            false
                        }

                        else -> {
                            throw IllegalArgumentException(
                                "'$prop' must be a boolean (true/false), got '$value'",
                            )
                        }
                    }
                }

                else -> {
                    throw IllegalArgumentException("'$prop' must be a boolean, got ${value::class.simpleName}")
                }
            }

        /**
         * Default known_hosts path used when `knownHostsFile` is not explicitly
         * configured on the remote. Mirrors OpenSSH's default of
         * `~/.ssh/known_hosts`. The value is resolved at call time, not at class
         * load, so test runners that override `user.home` continue to work.
         */
        internal fun defaultKnownHostsFile(): String = System.getProperty("user.home") + "/.ssh/known_hosts"
    }

    /**
     * Validate remote configuration. Required parameters include (username, address, path). Optional parameters include
     * (password, port, keyFile). For the port, we have to
     */
    override fun validateRemote(remote: Map<String, Any>): Map<String, Any> {
        val validated = mutableMapOf<String, Any>()
        for (prop in listOf("username", "address", "path")) {
            val value =
                remote[prop]
                    ?: throw IllegalArgumentException("missing required remote property '$prop'")
            validated[prop] = value.toString()
        }

        for (prop in listOf("password", "port", "keyFile", "knownHostsFile", "skipHostCheck")) {
            val value = remote[prop] ?: continue
            when (prop) {
                "port" -> {
                    val port =
                        when (value) {
                            is Double -> value.toInt()
                            is Int -> value
                            else -> throw IllegalArgumentException("port must be a number or integer")
                        }
                    validated[prop] = port
                }

                "skipHostCheck" -> {
                    validated[prop] = coerceBoolean(prop, value)
                }

                else -> {
                    validated[prop] = value.toString()
                }
            }
        }

        for (prop in remote.keys) {
            if (!validated.containsKey(prop)) {
                throw IllegalArgumentException("invalid property '$prop'")
            }
        }
        return validated
    }

    /**
     * Validate parameters, which can optionall contain either (password, key)
     */
    override fun validateParameters(parameters: Map<String, Any>?): Map<String, Any> {
        val params = parameters ?: emptyMap()
        for (prop in params.keys) {
            if (prop != "password" && prop != "key") {
                throw IllegalArgumentException("invalid property '$prop'")
            }
        }
        return params
    }

    /**
     * This method will parse the remote configuration and parameters to determine if we should use password
     * authentication or key-based authentication. It returns a pair where exactly one element must be set, either
     * the first (password) or second (key).
     */
    internal fun getSshAuth(
        remote: Map<String, Any>,
        parameters: Map<String, Any>,
    ): Pair<String?, String?> {
        if (parameters["password"] != null && parameters["key"] != null) {
            throw IllegalArgumentException("only one of password or key can be specified")
        } else if (remote["password"] != null || parameters["password"] != null) {
            return Pair((parameters["password"] ?: remote["password"]) as String, null)
        } else if (parameters["key"] != null) {
            return Pair(null, parameters["key"] as String)
        } else {
            throw IllegalArgumentException("one of password or key must be specified")
        }
    }

    internal fun buildSshCommand(
        remote: Map<String, Any>,
        parameters: Map<String, Any>,
        file: File,
        includeAddress: Boolean,
        vararg command: String,
    ): List<String> {
        val args = mutableListOf<String>()

        val (password, key) = getSshAuth(remote, parameters)

        if (password != null) {
            file.writeText(password)
            args.addAll(arrayOf("sshpass", "-f", file.path, "ssh"))
        } else {
            // getSshAuth guarantees exactly one of password/key is non-null,
            // so reaching this branch with key == null would indicate a contract
            // violation. checkNotNull surfaces that as a clear error rather than
            // the previous unchecked `!!` operator.
            val authKey = checkNotNull(key) { "getSshAuth returned no password and no key" }
            file.writeText(authKey)
            args.addAll(arrayOf("ssh", "-i", file.path))
        }

        // Set file permissions only on POSIX-compliant systems (not Windows)
        try {
            Files.setPosixFilePermissions(
                file.toPath(),
                mutableSetOf(
                    PosixFilePermission.OWNER_READ,
                ),
            )
        } catch (e: UnsupportedOperationException) {
            // POSIX file permissions not supported on this platform (e.g., Windows)
            // File permissions will be handled by the OS defaults
        }

        if (remote["port"] != null) {
            args.addAll(arrayOf("-p", remote["port"].toString()))
        }

        // Host-key verification policy (see issue #60 item #3):
        // - Default (secure): StrictHostKeyChecking=yes + known_hosts lookup.
        //   Unknown hosts cause ssh to abort with a "Host key verification
        //   failed." error that we rewrite in [runSsh] with remediation steps.
        // - Opt-out (`skipHostCheck=true`): preserves the pre-fix behavior of
        //   silently accepting any host key. Intended for trusted networks /
        //   CI where TOFU is acceptable.
        val rawSkipHostCheck = remote["skipHostCheck"]
        val skipHostCheck = rawSkipHostCheck != null && coerceBoolean("skipHostCheck", rawSkipHostCheck)
        if (skipHostCheck) {
            args.addAll(arrayOf("-o", "StrictHostKeyChecking=no"))
            args.addAll(arrayOf("-o", "UserKnownHostsFile=/dev/null"))
        } else {
            val knownHostsFile = remote["knownHostsFile"]?.toString() ?: defaultKnownHostsFile()
            args.addAll(arrayOf("-o", "StrictHostKeyChecking=yes"))
            args.addAll(arrayOf("-o", "UserKnownHostsFile=$knownHostsFile"))
        }
        if (includeAddress) {
            args.add("${remote["username"]}@${remote["address"]}")
        }
        args.addAll(command)

        return args
    }

    internal fun runSsh(
        remote: Map<String, Any>,
        parameters: Map<String, Any>,
        vararg command: String,
    ): String {
        val file = createTempFile().toFile()
        file.deleteOnExit()
        try {
            val args = buildSshCommand(remote, parameters, file, true, *command)
            try {
                return executor.exec(*args.toTypedArray())
            } catch (e: CommandException) {
                // Translate the canonical OpenSSH "Host key verification
                // failed." stderr into a CommandException whose `output` tells
                // the operator exactly how to recover. Without this, callers
                // see only `exit 255` and a one-line stderr that is easy to
                // misdiagnose as a generic connection error.
                if (e.output.contains("Host key verification failed")) {
                    val host = remote["address"]?.toString() ?: "<host>"
                    val knownHosts = remote["knownHostsFile"]?.toString() ?: defaultKnownHostsFile()
                    val guidance =
                        buildString {
                            append(e.output.trimEnd())
                            append("\n\n")
                            append("Host '$host' is not in $knownHosts (or its key has changed). ")
                            append("To accept the host, run:\n")
                            append("    ssh-keyscan -H '$host' >> '$knownHosts'\n")
                            append("Then verify the fingerprint out-of-band before retrying. ")
                            append("To skip host-key checking entirely (NOT recommended outside ")
                            append("trusted networks), set `skipHostCheck: true` on the remote.")
                        }
                    // e.message is the originating command string (set by
                    // CommandException's primary constructor) and is never
                    // null in practice; the `?: ""` is purely a Kotlin/Java
                    // nullability bridge for Throwable.getMessage's `String?`.
                    throw CommandException(e.message ?: "", e.exitCode, guidance)
                }
                throw e
            }
        } finally {
            file.delete()
        }
    }

    override fun getProvider(): String = "ssh"

    /**
     * To get a commit, we look up the metadata.json file in the directory named by the given commit ID.
     */
    override fun getCommit(
        remote: Map<String, Any>,
        parameters: Map<String, Any>,
        commitId: String,
    ): Map<String, Any>? {
        // Allowlist validation MUST run before any shell interpolation. Mirrors
        // ssh-remote-go's validateCommitID (PR #54).
        validateCommitId(commitId)
        try {
            val json = runSsh(remote, parameters, "cat", "${remote["path"]}/$commitId/metadata.json")
            return gson.fromJson(json, object : TypeToken<Map<String, Any>>() {}.type)
        } catch (e: CommandException) {
            if (e.output.contains("No such file or directory")) {
                return null
            }
            throw e
        }
    }

    /**
     * To list commits, we first iterate over all directory entries in the target path. We then have to invoke
     * getCommit() for each one to read the contents of the files. There are certainly more efficient methods, but
     * this is straightforward and sufficient for this simplistic remote provider.
     */
    override fun listCommits(
        remote: Map<String, Any>,
        parameters: Map<String, Any>,
        tags: List<Pair<String, String?>>,
    ): List<Pair<String, Map<String, Any>>> {
        val output = runSsh(remote, parameters, "ls", "-1", remote["path"] as String)
        val commits = mutableListOf<Pair<String, Map<String, Any>>>()
        for (line in output.lines()) {
            val commitId = line.trim()
            if (commitId == "") continue
            // Skip entries whose names would be unsafe to interpolate into a
            // shell command. We never want a malicious directory name on the
            // remote to turn `ls` results into RCE.
            if (!COMMIT_ID_PATTERN.matches(commitId)) {
                continue
            }
            val commit = getCommit(remote, parameters, commitId)
            if (commit != null && util.matchTags(commit, tags)) {
                commits.add(commitId to commit)
            }
        }

        return util.sortDescending(commits)
    }

    /**
     * Write to a file over SSH. While there are certainly ways to do this natively in java, we keep it simple
     * and just use command line tools as we are elsewhere.
     */
    fun writeFileSsh(
        remote: Map<String, Any>,
        params: Map<String, Any>,
        path: String,
        content: String,
    ) {
        // Defense-in-depth: validate the path first so even shell metacharacters
        // that the quoting below would handle never reach the remote. Then
        // single-quote-escape the (now-safe) path to keep `sh -c` parsing the
        // entire argument as a literal filename. Mirrors the ssh-remote-go
        // approach where `cat > "<path>"` is built from a validated commitId.
        validateRemotePath(path)
        val escapedPath = shellSingleQuote(path)
        val file = createTempFile().toFile()
        file.deleteOnExit()
        try {
            val args = buildSshCommand(remote, params, file, true, "sh", "-c", "cat > '$escapedPath'")
            val process = executor.start(*args.toTypedArray())
            process.outputStream.use { out ->
                out.bufferedWriter().use { writer ->
                    writer.write(content)
                }
            }
            process.waitFor(10L, TimeUnit.SECONDS)

            if (process.isAlive) {
                throw IOException("Timed out waiting for command: $args")
            }
            executor.checkResult(process)
        } finally {
            file.delete()
        }
    }

    override fun syncDataEnd(
        operation: RemoteOperation,
        operationData: Any?,
        isSuccessful: Boolean,
    ) {
        // Nothing to do
    }

    override fun syncDataStart(operation: RemoteOperation) {
        // Nothing to do
    }

    override fun getRemotePath(
        operation: RemoteOperation,
        operationData: Any?,
        volume: String,
    ): String {
        val remoteDir = "${operation.remote["path"]}/${operation.commitId}/data/$volume"
        return "${operation.remote["username"]}@${operation.remote["address"]}:$remoteDir/"
    }

    override fun getRsync(
        operation: RemoteOperation,
        operationData: Any?,
        src: String,
        dst: String,
        executor: CommandExecutor,
    ): RsyncExecutor {
        if (operation.type == RemoteOperationType.PUSH) {
            val remoteDir = dst.substringAfter(":")
            runSsh(operation.remote, operation.parameters, "mkdir", "-p", remoteDir)
        }

        val (password, key) = getSshAuth(operation.remote, operation.parameters)

        // Thread `skipHostCheck` and `knownHostsFile` through to the rsync
        // data-transfer SSH invocation. Issue #62 made the metadata-reading
        // SSH path (runSsh / writeFileSsh) default-secure; without this
        // plumbing, the rsync data path silently used the SDK's default
        // (secure since remote-sdk PR #47, but with no opt-out reachable from
        // the consumer). The two paths must respect the same operator choice
        // so a `skipHostCheck=true` remote works end-to-end and, more
        // importantly, a default remote enforces host-key verification on
        // *both* paths.
        //
        // `validateRemote` has already coerced `skipHostCheck` to a Boolean
        // and rejected any unknown property values, so we can read it
        // directly here without a second coercion. An unset property leaves
        // `skipHostCheck = false`, which is the secure default.
        val skipHostCheck = operation.remote["skipHostCheck"] as Boolean? ?: false
        val knownHostsFile = operation.remote["knownHostsFile"] as String?

        return RsyncExecutor(
            operation.updateProgress,
            operation.remote["port"] as Int?,
            password,
            key,
            "$src/",
            dst,
            executor,
            skipHostCheck = skipHostCheck,
            knownHostsFile = knownHostsFile,
        )
    }

    override fun pushMetadata(
        operation: RemoteOperation,
        commit: Map<String, Any>,
        isUpdate: Boolean,
    ) {
        val json = gson.toJson(commit)
        writeFileSsh(
            operation.remote,
            operation.parameters,
            "${operation.remote["path"]}/${operation.commitId}/metadata.json",
            json,
        )
    }
}
