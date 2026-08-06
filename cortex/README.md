# MetaLoom // Cortex

*MetaLoom // Cortex* is an un-opinionated media processing worker. It can analyze and parse massive amounts of media efficiently.

It supports a range of functions, including **face detection**, **thumbnail generation**, **consistency checking**, **hashing**, **metadata extraction** and **media fingerprinting**, all of which are exposed as pipeline nodes that a *Loom* server dispatches to a Cortex worker.

*MetaLoom // Cortex* is the central media asset parser component of the *Loom* Headless Media Asset Management System from [MetaLoom](https://metaloom.io/).

[![](https://dcbadge.vercel.app/api/server/3Dy2SxKUtw)](https://discord.gg/3Dy2SxKUtw)

## Features 

**Processing** - Cortex runs as a **long-running worker daemon**, not a one-shot CLI batch tool. On startup it opens a persistent WebSocket to a *Loom* server, registers itself along with the node kinds it can run, announces what those nodes look like so they can be authored in the pipeline editor, and then waits for work. Loom *pushes* source, node and segment tasks down that connection; Cortex never polls. Because the work is placed rather than pulled, bulk media processing scales horizontally: run many Cortex instances, each advertising a subset of node kinds (e.g. a GPU box that only runs `facedetect` and `whisper`), and Loom routes each task to a worker that accepts its kind.

**Hashing** - One of the standout features of this application is its asset hashing capability, which supports multiple hashing methods such as sha512, sha256, and md5. This enables deduplication of assets, as identical files will produce the same hash value regardless of the hashing method used. By detecting and eliminating duplicate files through hashing, users can save valuable storage space and simplify file management.

**Fingerprinting** - *MetaLoom // Cortex*'s hashing and fingerprinting capabilities enable users to identify unique media content, which is particularly useful for tracking copyrighted material or monitoring user-generated content. Cortex also supports the extraction of metadata, enabling users to organize and search for their content with greater ease.

**Facedetection** - This application offers a powerful face detection feature that can automatically detect and locate faces within media assets. The feature can extract embeddings, which are numerical representations of the facial features that can be used to recognize and compare faces across different media assets. This functionality is particularly useful for applications such as security systems, image search engines, and social media platforms. Detected faces may also be used to automatically compute the optimal focal point for cropped and resized images without manual input.

**Thumbnail** - It includes a thumbnail generation feature that enables users to create and store thumbnail images for media assets. Users can customize the size, format, and quality of the thumbnail images to suit their specific needs. 

**Un-opinionated** - *MetaLoom // Cortex* is considered un-opinionated because it doesn't require the storage, import, or movement of parsed content. Loom hands the worker a *reference* to the media — a path or an object key — never the bytes, so files stay where they are. Extracted information can additionally be cached alongside the file itself (xattr), without altering the file's location.

**Loom Integration** - Cortex has no standalone mode: the pipeline graph lives on *Loom*, not on Cortex. Loom owns the DAG and dispatches individual tasks — a source task to enumerate media, then one node task per graph node per item — while Cortex only ever sees one node (or affinity segment) at a time and answers with a result. Those results are synced back to the *MetaLoom // Loom* Server, which stores the media data and provides the options for navigating, managing and visualizing the extracted data. A worker that has no reachable Loom server simply idles — nothing drives it, and there is no offline batch mode.

**Consistency checks** - This feature allows users to perform consistency checks on their media assets. It can detect and highlight inconsistencies in the file format, metadata, and content of media assets. By running consistency checks, users can ensure that their media assets are valid and reliable, minimizing the risk of data corruption, file errors, and other issues that may affect the quality of their work.

**Metadata extraction** - *MetaLoom // Cortex* can extract metadata from media assets. This includes information such as file format, resolution, date created, camera settings, and more. By extracting metadata, users can quickly and easily organize and manage their media assets, as well as search and filter them based on specific criteria.

## Deployment

*MetaLoom // Cortex* is distributed as the `metaloom/cortex-server` container image. It has no command-line interface and no subcommands — the process is configured entirely via `cortex.yml` and environment variables (`LOOM_HOST`, `LOOM_PORT`, `CORTEX_NODE_ID`, …).

Because it is a long-running worker and not a batch job, it is deployed as a Kubernetes `Deployment` (or a plain long-running container) and scaled by replica count to add processing capacity — not via Cron or a K8S `Job`. Liveness and readiness probes are served on the monitoring port (`/api/health` and `/api/ready`, default `8093`).

See the [Cortex documentation](https://metaloom.io/docs/cortex/) for configuration and [container deployment](https://metaloom.io/docs/cortex/containers/).

## State

* In development

## Releasing 

TBD

## License

Apache License, Version 2.0.
