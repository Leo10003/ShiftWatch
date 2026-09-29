# ShiftWatch v20.8.31 — Scoring-branch characterization

Behavior-preserving extraction of the production separation adjustment into a pure
Kotlin function with unit tests. No scoring thresholds, acceptance gates, export
schemas, or saved recognition profiles are changed.

Production compares pre-penalty raw separation to the dynamic requiredSeparation
from RotaIdentityPolicy.boundary(...). Below that boundary gives -0.12; strictly
above boundary + 0.13 gives +0.025; otherwise zero. Confuser penalty remains
independent, applied before this adjustment.

The private v30 export lacks requiredSeparation, so synthetic thresholds in the
new tests must not be interpreted as reference-photo threshold measurements.
Friday OFF protection and Saturday recognition remain unchanged.

The provided source archive only includes three Kotlin files, not the PlannerActivity
exporter. Adding privacy-safe required-boundary export requires that additional
source file and is deliberately deferred.
