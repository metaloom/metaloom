# Helm Chart for Loom — moved

The MetaLoom Helm charts now live in the **top-level [`helm/`](../../helm) directory**:

- [`helm/loom`](../../helm/loom) — the Loom backend server (+ optional bundled PostgreSQL)
- [`helm/cortex`](../../helm/cortex) — Cortex workers (with a custom-image override)

See [`helm/README.md`](../../helm/README.md) for the overview and the customer-facing
[Helm Charts guide](https://metaloom.io/docs/deployment/helm/).

This directory is kept only to avoid breaking old links; it contains no chart.
