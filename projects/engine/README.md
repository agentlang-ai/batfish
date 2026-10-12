# Engine

NOCFlow's per-tenant what-if engine: Batfish embedded as a library behind a small
HTTP API. The contract is [`openapi.yaml`](openapi.yaml).

- `SnapshotBuilder` writes each device's config text with its format pinned, the
  layer-1 topology and the observed environment, and loads them through Batfish's
  normal parse and storage path. What Batfish could not model becomes coverage
  holes per device and fact family.
- `Lifter` produces each device's neutral facts, including a readable routing
  policy view taken from the parser's defined structures.
- `Deriver` accepts complete candidate config text, or legacy config lines for
  explicitly validated operations. Complete configs replace only the changed
  device's text; other inputs remain pinned. Holes the change introduces make
  its verdict unknown. Appending CLI is not a general device configuration editor.
- `ModelInjector` injects self-built models (no config text) through storage, so
  load-time post-processing still runs.

## Build, test, run

```
bazel test //projects/engine:engine_tests
bazel run //projects/engine:update_goldens     # after an intended change; review the diff
bazel build //projects/engine:engine_main_deploy.jar
ENGINE_PORT=9800 java -jar bazel-bin/projects/engine/engine_main_deploy.jar
```

## Dialects

| Dialect | Input | Notes |
| --- | --- | --- |
| `ARISTA_EOS`, `CISCO_IOS`, `CISCO_NXOS` | running config | |
| `JUNOS` | `show configuration \| display set` | set form, so proposed `set`/`delete` lines append |
| `FRR_LINUX` | `vtysh -c "show running-config"` plus kernel interfaces | composed into Batfish's Cumulus concatenated format; standalone FRR is not parsed by Batfish |

Known FRR gaps, reported rather than hidden: the default `bgp ebgp-requires-policy`,
`neighbor ... shutdown`, and `no route-map ...` are not modeled by Batfish's FRR grammar.

## Portable analysis inputs

`GET /snapshots/{id}/export` returns a standard Batfish ZIP with `snapshot/configs/`
and `snapshot/batfish/` inputs. It reads the exact files analyzed, including FRR
composition and runtime state; it does not regenerate them from current evidence.
`X-Snapshot-SHA256` identifies the returned bytes. Export baseline and candidate
before snapshot deletion and retain them with question parameters and the exact
engine build. This endpoint does not persist the in-memory snapshot/job registry.

API 1.1.0 accepts `{device, configText}` as an alternative to `{device, lines}` in
derive requests. Supply exactly one form. Replacement text is a full native
configuration, not a command delta; deployment commands belong to the caller's
reviewed proposal. Linux interfaces with `up: false` are modeled line-down even
when not separately listed in `environment.interfacesDown`.
