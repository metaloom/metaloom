# Generated Reports

Output directory for analysis runs. Nothing here is hand-written.

| Report | Produced by | File name |
|---|---|---|
| Static code analysis (AI-generated-defect audit) | [../guidelines/METALOOM_STATIC_CODE_ANALYSIS.md](../guidelines/METALOOM_STATIC_CODE_ANALYSIS.md) | `static-analysis-<SHORT_HEAD>-<YYYY-MM-DD_HHMM>.html` |

Each file name carries the git HEAD revision and the run timestamp, and the same two values appear in
the report header — reports accumulate so two runs can be diffed. Never overwrite an existing report.
