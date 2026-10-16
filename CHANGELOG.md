# Changelog

## 0.1.0.2 (2026-10-16)

- fix(adapter): `allowInsecure` now defaults to `false` in `describe.xml`, so
  a new instance validates the target certificate (#8). The parse stays
  strict (only `true`, in any letter case and with no surrounding spaces,
  turns validation off) and configure logs one WARN while
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
  and stops reading past it; `ExampleApiClient.get()` then fails with
  `IOException` after the framework's retry loop, so an oversized body is
  fetched once, not once per retry. A non-200 body is not read at all (the
  transfer is cancelled at once, so a slow or endless error body cannot hold
  the collector thread; review W1). `get()` rejects a 200 with a non-JSON
  `Content-Type` (for example an HTML proxy page) before parsing.
- fix(adapter): `ExampleConfig` validates host and port (#16). The host must
  be a bare hostname, IPv4 address or bracketed IPv6 address (no `/ @ ? #`,
  port or whitespace) and the port a number from 1 to 65535; a bad value
  throws an `IllegalArgumentException` naming the field, which Test
  Connection shows. The base URL is built with `new URI(...)`. A configure
  with an invalid value builds no client and every read fails with that
  message (ERROR) until the instance is edited. `port` is now an `int`.
- fix(adapter): `ExampleConfig.password` is private, read only through a
  package-private `password()` used by `buildHttpClient`, and `toString()`
  prints host, port, username, allowInsecure and `password=***` (#15).
- docs: correction to the 0.1.0.1 entry, which says the workflow fetches the
  SDK jar "via `SDK_RUNTIME_TOKEN`": it uses the `SDK_RUNTIME_SSH_KEY` deploy
  key (a shallow clone of the private sdk-runtime repo); the PAT is retired
  (#17).
- docs: README says CI clones the sdk-runtime repo (not a release), pins the
  buildkit download to a full `sdk-buildkit-vX.Y.Z` tag with a `sha256sum -c`
  step, and says the Broadcom jar belongs in a private repo only, never a
  workflow artifact; `docs/installing.md` states the `false` TLS default
  (#17). README lists the new helper classes and tests.
- fix(adapter): no host or port validation message ever echoes the rejected
  value, not even an all-digit port (a PIN pasted there); each names the rule
  broken (for example "must not contain a user name or password ('@')"), so a
  credential pasted into Host or Port is not logged every cycle or shown in
  Test Connection
  (review B1, #16). A host whose last label is all digits ("999", "10.1") is
  rejected unless it is a full dotted IPv4 address (review N6).
- fix(adapter): the cycle snapshot is never served once it is half a
  collection interval (2.5 minutes, `COLLECTION_INTERVAL_MS / 2`) old, even if
  no new cycle began, so a skipped rediscover never serves the previous
  cycle's data, and the `needsRediscovery` Javadoc and the `beginCycle` call say the
  snapshot cycle depends on `needsRediscovery()` returning true (review W2,
  #9).
- fix(adapter): `metricIfPresent` accepts plain decimal numbers only; Java-only
  forms such as `"42d"`, `"1f"` or `"0x1p3"` are skipped like any other
  non-number (review N1, #10).
- fix(adapter): `prop()` skips an absent (null) value instead of publishing
  `""`, so a missing item name is a gap, not an empty-string sentinel. Two
  comments corrected: the `rediscover` abort check only catches a stop in the
  moment after `onCollect` starts, and an item absent from the snapshot is
  still marked DATA_RECEIVING by the framework (review N3, N4).
- fix(adapter): `configureAdapter` drops the previous client before anything
  can fail, so a configure that throws never leaves reads going through the
  old, already-discarded HTTP client; a client that cannot be built fails
  every read ERROR with a reason, like an invalid host or port (review N5).
- docs: commit the generated dev-preview stamps (`0.0.0.2`) in
  `docs/README.md`, `docs/inventory-tree.md` and `REFERENCE.md`, as the
  children do; they move to the release stamp at release (review N7).
- fix(adapter): a snapshot whose `data` list is non-empty but has no item
  ids at all (schema drift, for example a renamed id field) now fails the
  read (resources go ERROR) instead of discovering only World and leaving
  every resource without data; an occasional id-less row is still skipped.
  New `ItemIndex` helper (peer review, #9).

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
