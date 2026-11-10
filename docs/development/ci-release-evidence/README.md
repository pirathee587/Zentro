# Issue #184 evidence

These are selected, credential-free local results, not raw application logs. The parent [verification report](../ci-and-release.md) explains commands, source snapshots, limits and ownership.

- `backend-before/`: paired baseline JAR hashes and ZIP/content differences.
- `backend-after/`: paired final JAR hashes, complete inventory comparison result and manifest fields.
- `frontend/`: paired 51-file production output hashes and test/check summary.
- `images/`: all fourteen local image IDs/labels and sampled differing creation/layer metadata.
- `backend-*-results.txt`: actual reactor test summaries.
- `guard-results.txt`, `yaml-syntax.txt`: guard fixtures and structural YAML validation.
- `environment.txt`, `commands-and-snapshots.txt`: actual environment and exact source/command boundaries.
- `action-pins.tsv`, `owner-settings-observed.txt`: verified action references and read-only repository observations.

Empty diff files mean no differences were observed. Release policy remains open; no tag or release was created.
