# VCF Content Factory Example Adapter — Reference

Generated from `describe.xml` and `resources.properties` for build 0.0.0.2.

## Adapter

| Field | Value |
|---|---|
| Adapter Kind | `example_adapter` |
| Tier | 2 (Java SDK) |
| Monitoring Interval | 5 minutes |
| License Required | No |

### Credentials

| Field | Key | Type |
|---|---|---|
| Username | `username` | string |
| Password | `password` | string (masked) |

### Connection Settings

| Field | Key | Default | Required |
|---|---|---|---|
| Host / IP Address | `host` | — | Yes |
| Port (HTTPS) | `port` | 443 | No |
| Allow Insecure SSL | `allowInsecure` | false | No |

---

## Object Types

### Example World

**Identifier**: `world_id` (World ID)

---

### Example Resource

**Identifier**: `id` (Resource ID)

#### Status

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `value` | Value | metric | — | yes |

#### Configuration

| Key | Label | Type | Unit | Monitored |
|---|---|---|---|---|
| `name` | Name | property | — | — |

---

## Traversal Spec

**Name**: Example Infrastructure

```
Example World
    └── Example Resource
```

Rooted at the World kind (`rootResourceKind="World"`, path
`example_adapter::World||example_adapter::ExampleResource::child`), the way
VMware's vSphere adapter roots "vSphere Hosts and Clusters" at
`VMWARE::vSphere World`. Rooting at the adapter instance kind leaves the
tree empty below the instance: the instance resource has no children.
