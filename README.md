# Dit SSH Provider

This is a basic Dit SSH provider. For more information on how it works,
consult the dit documentation.

## Remote configuration

The SSH provider accepts the following remote properties:

| Property         | Type    | Required | Description |
|------------------|---------|----------|-------------|
| `username`       | string  | yes      | SSH user on the remote host. |
| `address`        | string  | yes      | Hostname or IP of the remote host. |
| `path`           | string  | yes      | Absolute path on the remote host used to store commits. |
| `port`           | int     | no       | SSH port (defaults to 22). |
| `password`       | string  | no       | Password baked into the remote (use `parameters.password` instead for credentials). |
| `keyFile`        | string  | no       | Path to a private-key file on the local host. |
| `knownHostsFile` | string  | no       | Path to a `known_hosts` file used for host-key verification. Defaults to `~/.ssh/known_hosts`. |
| `skipHostCheck`  | bool    | no       | Disable host-key verification. **Default: `false`.** See below. |

### Host-key verification

Starting in `v0.3.0`, the SSH provider verifies the remote host against a
`known_hosts` file by default (`StrictHostKeyChecking=yes`). Connections to
hosts whose keys are not in `known_hosts` fail with a `Host key verification
failed.` error and a remediation message that points the operator at
`ssh-keyscan`.

**Before connecting to a new host, populate `known_hosts` and verify the
fingerprint out-of-band:**

```bash
ssh-keyscan -H remote.example.com >> ~/.ssh/known_hosts
ssh-keygen -lf ~/.ssh/known_hosts | grep remote.example.com
# Compare the fingerprint against a trusted source (the host operator,
# a configuration management system, etc.) before proceeding.
```

To override the file location for a single remote:

```yaml
knownHostsFile: /etc/ditdotdev/known_hosts
```

### Opting out (`skipHostCheck`)

For deployments where host-key verification is impractical — short-lived CI
runners, trusted private networks, ephemeral test hosts — set
`skipHostCheck: true` on the remote. This restores the legacy behavior of
`StrictHostKeyChecking=no` + `UserKnownHostsFile=/dev/null`.

```yaml
remote:
  username: ci
  address: build-host
  path: /var/dit
  skipHostCheck: true
```

The property accepts either booleans (`true` / `false`) or the string literals
`"true"` / `"false"` so JSON payloads serialized by either convention work
unchanged. Any other value is rejected at `validateRemote` time.

### Migrating from earlier versions

Before `v0.3.0` the provider unconditionally disabled host-key checking. To
upgrade without service interruption:

1. **Preferred:** populate `~/.ssh/known_hosts` (or a custom
   `knownHostsFile`) on every machine that runs `d3` against an SSH remote,
   then upgrade. No configuration change required.
2. **Bridge:** add `skipHostCheck: true` to existing remotes to preserve the
   old behavior, then incrementally migrate hosts onto `known_hosts` and
   remove the flag.

As of issue #63, `skipHostCheck` and `knownHostsFile` govern **both** the
metadata-reading SSH connections in this provider **and** the rsync
data-transfer path in `remote-sdk` — so a default remote enforces
`StrictHostKeyChecking=yes` end-to-end, and `skipHostCheck: true` is honored
on both paths consistently.

## Contributing

This project follows the Dit community best practices:

  * [Contributing](https://github.com/ditdotdev/.github/blob/master/CONTRIBUTING.md)
  * [Code of Conduct](https://github.com/ditdotdev/.github/blob/master/CODE_OF_CONDUCT.md)
  * [Community Support](https://github.com/ditdotdev/.github/blob/master/SUPPORT.md)

It is maintained by the [Dit community maintainers](https://github.com/ditdotdev/.github/blob/master/MAINTAINERS.md)

For more information on how it works, and how to build and release new versions,
see the [Development Guidelines](DEVELOPING.md).

## License

This project is licensed under the Business Source License 1.1 (BUSL-1.1).
On the Change Date (four years from the publication of each version), the
license for that version converts to the Mozilla Public License 2.0
(MPL-2.0). See [LICENSE](LICENSE) for the full terms.
