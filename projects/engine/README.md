# Engine

NOCFlow's per-tenant what-if engine: Batfish embedded as a library behind a small
HTTP API. The contract is [`openapi.yaml`](openapi.yaml).

- `SnapshotBuilder` writes each device's config text with its format pinned, the
  layer-1 topology and the observed environment, and loads them through Batfish's
  normal parse and storage path. What Batfish could not model becomes coverage
  holes per device and fact family.
- `Lifter` produces each device's neutral facts, including a readable routing
  policy view taken from the parser's defined structures.
- `Deriver` applies a change request's config lines to derive a proposed snapshot.
  Holes the change introduces make its verdict unknown.
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
