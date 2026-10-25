# System diagrams

[README](../../README.md) · [Architecture](../architecture.md) · [Maintenance](../maintenance.md)

These diagrams show responsibilities and execution order rather than every Java member.

| View | What it explains | Files |
| --- | --- | --- |
| System overview | Request pipeline and replaceable storage adapters | [SVG](system-overview.svg) · [PlantUML](system-overview.puml) |
| Request sequence | Session locking, cached MORE, REPEAT, retrieval, and commit ordering | [SVG](recommendation-flow.svg) · [PlantUML](recommendation-flow.puml) |
| Maintenance jobs | Four separate jobs and their shared dependencies | [SVG](maintenance-jobs.svg) · [PlantUML](maintenance-jobs.puml) |

## View and edit

Open SVG files directly, or open `.puml` files with a PlantUML renderer. The Java-to-PlantUML parser
plugin is unnecessary: the source files already contain PlantUML. The overview and maintenance
views use Smetana layout and do not require Graphviz.

Labels use production class names. Wiring, getters/setters, exceptions, and implementation helpers
are omitted. Runtime orchestration lives in `application`, query vectors in `embedding`, search
execution/plans in `retrieval`, session adapters in `storage.session`, and shared audiobook
models/adapters/vector utilities in sibling `catalogue`.

When changing the architecture, update the affected `.puml` source and regenerate its SVG.
