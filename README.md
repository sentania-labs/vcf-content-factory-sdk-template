# VCF Content Factory — SDK adapter template

A starting skeleton for a **Tier 2 Java SDK management-pack adapter** for VCF
Operations, built and released independently of the VCF Content Factory.

Click **Use this template** to create a new pak repo named
`vcf-content-factory-sdk-<name>`.

## What's here

```
adapter.yaml                         # name, version, build_number, adapter_kind, entry_class
describe.xml                         # adapter descriptor (ResourceKinds, metrics, credentials)
src/…                                # adapter Java source
resources/                           # resources.properties (display strings)
.github/workflows/build-pak-on-tag.yml   # CI: tag v* -> build .pak -> attach to Release
```

For an adapter that bundles dashboards/views, add `bundled_content:` to
`adapter.yaml` and co-locate the YAML **in this repo** under `views/` and
`dashboards/` (paths resolve relative to `adapter.yaml`, not any external repo).

## Release model

This repo is the **single source of truth** for its `.pak`. The official
artifact is built by CI, not on a laptop:

1. Author the adapter (locally, or in the VCF Content Factory tree where the
   `sdk-adapter-author` agent and `build-sdk` dev preview live).
2. Commit + push to `main`.
3. **Tag `vX.Y.Z`** → CI pulls the published `sdk-buildkit` from the factory's
   Releases, builds the `.pak` deterministically (no agent, no factory
   checkout), gates on `pak-compare`, and attaches the `.pak` to the tag's
   GitHub Release. That Release asset is the shippable pak.

The VCF Content Factory references this pak by a **pointer to the latest
Release** — it never rebuilds or mirrors the binary.

## CI requirements

The `build-pak-on-tag` workflow needs, on its runner: a **JDK 11+**
(`javac`/`jar`), `python3` + pip, `gh`, and `tar`. It downloads the build
toolchain from `sentania-labs/vcf-content-factory` Releases
(`sdk-buildkit-v1` by default — pin a concrete `sdk-buildkit-vX.Y.Z` for
reproducibility). See the workflow header for the knobs.
