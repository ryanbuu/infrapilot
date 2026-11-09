# BIND management

InfraPilot administers the two configured DNS nodes with a separate restricted SSH
credential. The web service runs as UID 10001; its key cannot open a shell, forward
ports, or authenticate from outside the configured Docker host. Each DNS node has
a root-owned forced command and a root-owned path/role manifest.

## Coverage

| Page | Capabilities |
| --- | --- |
| Records | A/AAAA/CNAME/MX/TXT/PTR/SRV/CAA, TLSA/SSHFP/NAPTR/URI/SVCB/HTTPS and other ordinary record types; revision-checked RFC2136 updates |
| Authoritative zones | Primary/secondary pair creation, initial standard zone-file import, per-view zone configuration, transfer and notify policy, safe zone removal, record-page registration |
| Resolver | All native `options` and `server` clauses: recursion, cache, forwarding, serve-stale, QNAME minimization, RRL, RPZ references and performance limits |
| Access and Views | ACLs, control channel, view matching, ordering and per-view configuration |
| DNSSEC and keys | Policies, trust anchors, keystores, TSIG creation/import, masked key editing and restoration; signing and trust-anchor operations |
| Encrypted transport | Native `tls` and `http` clauses and listener references for DoT/DoH |
| Logs and statistics | Local BIND JSON counters, bounded service journal, logging categories/channels, statistics endpoint and dnstap settings |
| Operations | Typed RNDC commands for cache, transfers, signing, validation, logs, statistics and query diagnostics; peer-checked service restart; standard zone-data export |
| Native configuration | All named.conf blocks, including catalog zones, RPZ, DLZ/dyndb/plugins and version-specific options, preserving unknown syntax and comments |
| Backups | Durable per-node preparation, coordinated commit/recovery, redacted web diffs and private raw node backups |

The native editor uses the **installed BIND parser**, not a reduced JSON model.
Capabilities requiring an uninstalled driver, external certificate, PKCS#11
provider, HSM or external key consumer still require that dependency to be supplied.
The UI does not install arbitrary plugins, expose a shell, or stop both resolvers.
Software/package upgrades remain host maintenance operations.

Existing dynamic-registration zones, DHCID ownership protections, forwarding
policies and query-log collection retain their current behavior. Initial zone
imports cannot use `$INCLUDE` or `$GENERATE`. Existing live zone data is edited
through RFC2136 rather than by overwriting journaled master files. Zone deletion
and configuration rollback retain zone data on disk for recovery. A configuration
rollback does not rewind subsequent DDNS updates or undo external consumers of a
rotated key.

## Transaction and permission model

1. An administrator submits a revision-checked change; mutation endpoints require CSRF.
2. Each changed node stages the whole include graph, resolves known secret placeholders
   locally, validates with `named-checkconf`, and validates new master data with
   `named-checkzone`. Native validators run as the `bind` account, so configured
   plugins are never loaded by a root validation process.
3. The application writes a durable intent before preparing any node. Raw secrets
   stay in root-only node transaction files; HTTP diffs and application journals
   are redacted.
4. Nodes persist rollback data before atomic file replacement and `rndc reconfig`.
   On a failed or uncertain commit, the application requests rollback on every
   participant. A committed transaction is finalized after readback.
5. Pending markers fence other mutations across requests and process restarts.
   Conditional forwarding shares the same lock and observes BIND pending markers.
   External edits cause a revision conflict; rollback refuses to overwrite a third
   configuration.
6. The managed zone catalog is persisted with the transaction and loaded on restart.

The adapter can modify only currently included BIND configuration files or new
`.conf` files inside its configured management directory. New master files go
only into the configured zone directory. Include traversal, arbitrary file reads,
caller-supplied shell commands, arbitrary service names and SSH command overrides
are rejected. Generated forwarding files are read-only here.
The existing loopback RNDC channel is retained so a configuration change cannot
remove the path needed to commit or roll back the transaction.

## Installation

Install `deploy/bind/agent.py` root-owned at
`/usr/local/libexec/infrapilot-bind/agent.py` and the matching forwarding adapter at
`/usr/local/libexec/infrapilot-forwarding/agent.py`. A root-only
`/etc/infrapilot-bind.json` supplies node identity, main/config/state/data paths,
local/peer addresses, roles, views and existing transfer/application key names.
The node state directory is mode 0700. Configuration is root:bind 0640; the
managed zone directory is root:bind 0770 to permit journals and transfers.

The public SSH key must have `restrict`, a fixed `from` address and a fixed
`command="/usr/bin/python3 -I /usr/local/libexec/infrapilot-bind/agent.py"`.
The application uses strict pinned host keys, no SSH agent and no remote command.
Never reuse an unrestricted administrator key.

Configure `infrapilot.bind.nodes` in the private live YAML and these environment
variables in Compose:

```
INFRAPILOT_BIND_ENABLED=true
INFRAPILOT_BIND_PRIVATE_KEY_FILE=/app/config/bind/id_ed25519
INFRAPILOT_BIND_KNOWN_HOSTS_FILE=/app/config/bind/known_hosts
INFRAPILOT_BIND_BACKUP_DIRECTORY=/app/data/bind
```

Before enabling writes, back up `/etc/bind`, both adapters, their root-only
manifests, authorized_keys and the application/DB. Verify the effective
configuration has not changed merely by installing the adapter. Run acceptance
changes against a disposable zone and remove them afterwards.

## References

- [BIND 9.20.29 configuration reference](https://bind9.readthedocs.io/en/v9.20.29/reference.html)
- [BIND 9.20.29 command manuals](https://bind9.readthedocs.io/en/v9.20.29/manpages.html)
