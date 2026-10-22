# System diagrams

These views explain the current system instead of listing every Java member.

- [System overview](system-overview.svg) ([PlantUML source](system-overview.puml)): request pipeline and replaceable storage adapters.
- [Request sequence](recommendation-flow.svg) ([PlantUML source](recommendation-flow.puml)): session locking, cached MORE, REPEAT, retrieval and commit ordering.
- [Maintenance jobs](maintenance-jobs.svg) ([PlantUML source](maintenance-jobs.puml)): the four separate jobs and their shared dependencies.

Open the .puml files with a PlantUML renderer. The Java-to-PlantUML parser is not required:
these files already contain PlantUML source. The overview and job view use PlantUML's
Smetana layout engine, so they do not require Graphviz.

Labels name the important production classes. Framework wiring, getters, setters,
exceptions and implementation helpers are intentionally omitted.

Runtime orchestration classes live in `application`; query-vector code lives in `embedding`,
search execution and plans in `retrieval`, and session adapters in `storage.session`.
