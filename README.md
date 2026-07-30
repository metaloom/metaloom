[![Maven Central](https://maven-badges.sml.io/sonatype-central/io.metaloom/loom/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.metaloom.loom/loom)
[![License](https://img.shields.io/:license-apache-brightgreen.svg)](http://www.apache.org/licenses/LICENSE-2.0.html)
[![Stack Overflow](https://img.shields.io/:stack%20overflow-metaloom-brightgreen.svg)](http://stackoverflow.com/questions/tagged/metaloom)

# MetaLoom

MetaLoom is a DAM which consists of Loom (Backend Server) and Cortex (Processing Node).

# MetaLoom - Loom

Loom is an advanced media asset management system designed to ease the management processes for digital media assets. With a decoupled processing mechanism, Loom separates processing of media assets, providing users with greater flexibility and scalability. Loom supports a wide range of industry-standard protocols, including REST, gRPC, and GraphQL, making it easy to integrate with other systems and workflows. In addition, Loom offers powerful features such as fingerprinting and face detection, enabling users to easily search, categorize, and manage their media assets.

[![](https://dcbadge.vercel.app/api/server/3Dy2SxKUtw)](https://discord.gg/3Dy2SxKUtw)

### **Loom is still under development and not yet in a usable state.**

## Features at a Glance

* [Metadata extraction](https://metaloom.io/docs/nodes/tika/)
* [Thumbnail generation](https://metaloom.io/docs/nodes/thumbnail/)
* [Video fingerprinting](https://metaloom.io/docs/nodes/fingerprint/)
* [Facedetection](https://metaloom.io/docs/nodes/facedetect/)
* [Similarity search](https://metaloom.io/docs/loom/features/#_search_and_similarity)
* [Tagging](https://metaloom.io/docs/loom/features/#_tags)
* [Permission System](https://metaloom.io/docs/loom/features/#_permissions)
* [REST API](https://metaloom.io/docs/loom/rest-api/)
* gRPC API (planned)
* [GraphQL API](https://metaloom.io/docs/loom/graphql-api/) (planned)
* [Consistency Checks](https://metaloom.io/docs/nodes/consistency/)
* [Asset Hashing](https://metaloom.io/docs/nodes/hash/)

See the [full documentation](https://metaloom.io/docs/) for [Loom](https://metaloom.io/docs/loom/),
[Cortex](https://metaloom.io/docs/cortex/) and all [processing nodes](https://metaloom.io/docs/nodes/).

## License

Apache License, Version 2.0

## Attribution

Portions of the code in this project were co-authored with the assistance of AI.

## Testing

All DAO/Database and some integration tests utilize the a prefilled database test pool.

1. This requires the start of the pool provider + database

```bash
cd test-database
podman-compose  up -d
```

2. The pool must initially be setup using the `io.metaloom.loom.test.PoolSetupRunner` from the `loom-fixture` project.