# System diagrams

[README](../../README.md) · [Architecture](../architecture.md) · [Maintenance](../maintenance.md)

These diagrams show responsibilities and execution order rather than every Java member.

| View | What it explains | Files |
| --- | --- | --- |
| System overview | Request pipeline and replaceable storage adapters | [SVG](system-overview.svg) · [PlantUML](system-overview.puml) |
| Request sequence | Session locking, cached MORE, REPEAT, retrieval, and commit ordering | [SVG](recommendation-flow.svg) · [PlantUML](recommendation-flow.puml) |
| Maintenance jobs | Six launchers, snapshot publication/consumption and separate read-history imports | [SVG](maintenance-jobs.svg) · [PlantUML](maintenance-jobs.puml) |

These are the maintained views. Class/member diagrams are omitted because they duplicate source.
Commands and guarantees belong to the [job runbooks](../jobs/README.md).

## View and edit

Open SVG files directly, or open `.puml` files with a PlantUML renderer. The Java-to-PlantUML parser
plugin is unnecessary: the source files already contain PlantUML. The overview and maintenance
views use Smetana layout and do not require Graphviz.

Labels use production class names; wiring and implementation helpers are intentionally omitted.

**When architecture changes, update the affected `.puml` and regenerate its SVG.**
For example, with Java and a PlantUML JAR installed using the
[PlantUML quickstart](https://plantuml.com/starting):

```bash
java -Djava.awt.headless=true -jar /path/to/plantuml.jar -tsvg docs/diagrams/maintenance-jobs.puml
```

The checked-in rendered views use PlantUML 1.2025.8.
