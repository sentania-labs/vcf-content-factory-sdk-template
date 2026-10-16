# Changelog

## 0.1.0.2 (2026-10-16)

- fix(adapter): `allowInsecure` now defaults to `false` in `describe.xml`, so
  a new instance validates the target certificate (#8). The parse stays
  strict (only `true` turns validation off) and configure logs one WARN while
  it is off. **Upgrade note:** an existing instance keeps its stored value; an
  instance saved with the build 1 default of `true` still skips validation
  until it is edited and set to `false`.
- fix(adapter): `mapCollectException` walks the cause chain and maps
  `ConnectException`, `UnknownHostException`, `NoRouteToHostException`,
  `HttpConnectTimeoutException`, `HttpTimeoutException` and
  `SocketTimeoutException` to DOWN, wrapped or not (#13). New
  `ExampleFailures` helper, same shape as the UniFi and Synology ones.
- fix(adapter): `Status|value` is published only when the field is present
  and numeric (#10). New `metricIfPresent(out, key, SimpleJson)` helper skips
  a missing, null or non-numeric field instead of publishing the `0.0` that
  `SimpleJson.asDouble()` returns for it; a real zero is still published.
- fix(adapter): one immutable snapshot per collection cycle (#9). `rediscover`
  starts the cycle and reads `/api/items` once; every `collect` in the cycle
  reads the same snapshot through a `Map<id, item>` index (was one full-list
  GET per resource plus a linear scan). A failed read is remembered for the
  rest of its cycle, so a down target costs one read per cycle. New
  `CycleCache` helper; the children's `SnapshotCache` is referenced for the
  production version (cross-cycle back-off, maximum age).
- fix(adapter): a cycle running while the instance is discarded stops with
  `InterruptedException("discarded")` instead of an NPE (the snapshot read
  copies the volatile `api` field to a local and checks it), and `rediscover`
  checks `isAbortRequested()` at entry (#14).
- fix(adapter): response bodies are read through a bounded handler (#12). New
  `BoundedBody` caps a 200 body at 8 MB (`DEFAULT_MAX_BYTES`, one constant)
  and fails with `IOException` past it, without buffering the rest; non-200
  bodies are discarded unread. `ExampleApiClient.get()` rejects a 200 with a
  non-JSON `Content-Type` (for example an HTML proxy page) before parsing.

## 0.1.0.1 (2026-06-10)

- feat(framework): modernize the SDK adapter template to framework v2 + C2 pak
  shape + current CI (build 1). Replaces the stale onDiscover-only HelloWorld
  skeleton with a minimal framework-v2 ExampleAdapter:
  - extends `VcfCfAdapter` with keyed constructors `super(ADAPTER_KIND[, ...])`,
    framework default `onDescribe()` (no override), and the `com.vcfcf.adapter.spi`
    roles (`VcfCfTester` / `VcfCfDiscoverer` / `VcfCfCollector`);
  - **collect-path discovery** (`needsRediscovery()=true` + `rediscover()` →
    shared `enumerateResources(snapshot, sink)` used by both discovery and
    `registerNewResource`), so a fresh instance populates on its first collect on
    VCF Ops 9.0.2, which never calls `onDiscover()` for these collectors;
  - `componentLogger(Class)` for the helper logger, cooperative cancellation,
    loud-failure posture (unreadable payload throws — no 0.0 sentinels), and
    `// REDACT-SECRET` credential markers.
- feat(framework): byte-copy the canonical `build-pak-on-tag.yml` (private SDK-jar
  fetch via `SDK_RUNTIME_TOKEN` + `--sdk-jar`).
- feat(framework): C2 pak shape — no bundled jars; `vcfcf-adapter-base.jar` comes
  from the buildkit and `vrops-adapters-sdk-*.jar` is consumer-supplied. `.gitignore`
  ignores `*.generated.md`, dist/, build artifacts, and `lib/*.jar`.
- docs: README rewritten with instantiation steps, local dev build
  (`build-sdk --sdk-jar` / `VCFCF_SDK_JAR`), and the v*-tag CI release contract.
