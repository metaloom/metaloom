# MetaLoom // Loom — REST Workflow Examples

**Version:** 1.0 (April 2026)
**Status:** In Development
**Base URL:** `https://loom.example.com/api/v1`

All secured endpoints require the `Authorization: Bearer <token>` header.

---

## Table of Contents

1. [Authentication](#1-authentication)
2. [Asset Creation](#2-asset-creation)
   - 2.1 [Single Asset](#21-single-asset)
   - 2.2 [Bulk Asset Creation](#22-bulk-asset-creation)
3. [Bulk Asset Updates](#3-bulk-asset-updates)
4. [Face Detection Tracking via Embeddings](#4-face-detection-tracking-via-embeddings)
5. [Face Cluster Reduction](#5-face-cluster-reduction)
6. [Reading Clustered Results from an Asset](#6-reading-clustered-results-from-an-asset)

---

## 1. Authentication

### Login

Obtain a JWT bearer token. All subsequent calls to secured endpoints must include this token.

**Request**

```http
POST /api/v1/login
Content-Type: application/json
```

```json
{
  "username": "jane.smith",
  "password": "s3cr3t!"
}
```

**Response** `200 OK`

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9.eyJ1dWlkIjoiYTFiMmMzZDQtZTVmNi03ODkwLWFiY2QtZWYxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
}
```

Store the token and supply it in the `Authorization` header for all subsequent requests:

```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

---

## 2. Asset Creation

### 2.1 Single Asset

Register a single media file with its technical metadata. The SHA-512 hash is the primary identity of an asset. Creating an asset with a hash that already exists returns the existing record (idempotent behaviour).

**Request**

```http
POST /api/v1/assets
Authorization: Bearer <token>
Content-Type: application/json
```

```json
{
  "hashes": {
    "sha512": "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e"
  },
  "file": {
    "filename": "interview_2026_03_15.mp4",
    "mimeType": "video/mp4",
    "size": 2147483648,
    "origin": "s3://media-bucket/raw/interview_2026_03_15.mp4"
  },
  "meta": {
    "project": "spring-campaign",
    "uploader": "jane.smith"
  }
}
```

**Response** `200 OK`

```json
{
  "uuid": "7f3a9b1c-44e2-4d8f-b623-0e5d7c2a1f90",
  "hashes": {
    "sha512": "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e"
  },
  "file": {
    "filename": "interview_2026_03_15.mp4",
    "mimeType": "video/mp4",
    "size": 2147483648,
    "origin": "s3://media-bucket/raw/interview_2026_03_15.mp4"
  },
  "meta": {
    "project": "spring-campaign",
    "uploader": "jane.smith"
  },
  "embeddings": [],
  "annotations": [],
  "tags": [],
  "status": {
    "creator": { "uuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890" },
    "created": "2026-04-01T09:00:00Z"
  }
}
```

### 2.2 Bulk Asset Creation

Register many assets in a single call. Each entry in `assets` follows the same schema as the single-asset request. The response contains per-asset results keyed by the same order.

**Request**

```http
POST /api/v1/assets/bulk/create
Authorization: Bearer <token>
Content-Type: application/json
```

```json
{
  "assets": [
    {
      "hashes": {
        "sha512": "aa00bb11cc22dd33ee44ff5500112233445566778899aabbccddeeff0011223344556677889900aabbccddeeff0011223344556677889900aabbccddeeff00112233"
      },
      "file": {
        "filename": "photo_001.jpg",
        "mimeType": "image/jpeg",
        "size": 3145728
      }
    },
    {
      "hashes": {
        "sha512": "bbcc11dd22ee33ff4400aa5511bb2233cc4455dd6677ee8899ff00aabb1122cc3344dd5566ee7788ff99aa00bb1122cc3344dd5566ee7788ff99aa00bb1122ccdd"
      },
      "file": {
        "filename": "photo_002.jpg",
        "mimeType": "image/jpeg",
        "size": 2097152
      }
    }
  ]
}
```

**Response** `200 OK`

```json
{
  "items": [
    {
      "sha512": "aa00bb11cc22dd33ee44ff5500112233445566778899aabbccddeeff0011223344556677889900aabbccddeeff0011223344556677889900aabbccddeeff00112233",
      "uuid": "11111111-aaaa-4bbb-8ccc-000000000001",
      "created": true
    },
    {
      "sha512": "bbcc11dd22ee33ff4400aa5511bb2233cc4455dd6677ee8899ff00aabb1122cc3344dd5566ee7788ff99aa00bb1122cc3344dd5566ee7788ff99aa00bb1122ccdd",
      "uuid": "22222222-bbbb-4ccc-8ddd-000000000002",
      "created": true
    }
  ]
}
```

---

## 3. Bulk Asset Updates

After initial ingest, Cortex processor nodes enrich assets with technical metadata (video dimensions, duration, dominant colour, fingerprints, etc.) using bulk updates. Each entry identifies the target asset by its SHA-512 hash and carries an `update` payload with the fields to change.

**Request**

```http
POST /api/v1/assets/bulk/update
Authorization: Bearer <token>
Content-Type: application/json
```

```json
{
  "assets": [
    {
      "hashes": {
        "sha512": "aa00bb11cc22dd33ee44ff5500112233445566778899aabbccddeeff0011223344556677889900aabbccddeeff0011223344556677889900aabbccddeeff00112233"
      },
      "update": {
        "meta": {
          "dominantColour": "#3a7bd5",
          "processedAt": "2026-04-01T09:05:00Z"
        },
        "video": {
          "width": 1920,
          "height": 1080,
          "duration": 180500,
          "fps": 25.0
        },
        "fingerprint": {
          "videoFingerprint": "v4jFP-ab12cd34ef56gh78"
        }
      }
    },
    {
      "hashes": {
        "sha512": "bbcc11dd22ee33ff4400aa5511bb2233cc4455dd6677ee8899ff00aabb1122cc3344dd5566ee7788ff99aa00bb1122cc3344dd5566ee7788ff99aa00bb1122ccdd"
      },
      "update": {
        "image": {
          "width": 4032,
          "height": 3024,
          "dominantColor": "#c0a080"
        }
      }
    }
  ]
}
```

**Response** `200 OK`

```json
{
  "items": [
    {
      "sha512": "aa00bb11cc22dd33ee44ff5500112233445566778899aabbccddeeff0011223344556677889900aabbccddeeff0011223344556677889900aabbccddeeff00112233",
      "uuid": "11111111-aaaa-4bbb-8ccc-000000000001",
      "updated": true
    },
    {
      "sha512": "bbcc11dd22ee33ff4400aa5511bb2233cc4455dd6677ee8899ff00aabb1122cc3344dd5566ee7788ff99aa00bb1122cc3344dd5566ee7788ff99aa00bb1122ccdd",
      "uuid": "22222222-bbbb-4ccc-8ddd-000000000002",
      "updated": true
    }
  ]
}
```

You can also update a single asset by UUID or SHA-512:

```http
POST /api/v1/assets/sha512/aa00bb11cc22...
POST /api/v1/assets/11111111-aaaa-4bbb-8ccc-000000000001
```

---

## 4. Face Detection Tracking via Embeddings

Face detections produced by a Cortex worker (e.g. using InsightFace or dlib) are stored as **Embeddings**. Each embedding records:

- the **asset** it belongs to (`assetUuid`)
- the **detection source** (`source`)
- the **embedding type** (`type` — `DLIB_FACE_RESNET_v1`)
- the **face vector** (`vector` — a 512-dimensional float array)
- the **spatial area** of the detected face in the frame (`area` — bounding box in pixels; for videos also the temporal range `from`/`to` in milliseconds)

### 4.1 Store a Face Detection (single image)

```http
POST /api/v1/embeddings
Authorization: Bearer <token>
Content-Type: application/json
```

```json
{
  "assetUuid": "22222222-bbbb-4ccc-8ddd-000000000002",
  "type": "DLIB_FACE_RESNET_v1",
  "source": "insightface-v3",
  "area": {
    "startX": 412,
    "startY": 98,
    "width":  154,
    "height": 192
  },
  "vector": [0.0312, -0.1145, 0.8731, 0.2204, "...512 floats total..."]
}
```

**Response** `200 OK`

```json
{
  "uuid": "eeee1111-face-4aaa-8bbb-000000000010",
  "assetUuid": "22222222-bbbb-4ccc-8ddd-000000000002",
  "type": "DLIB_FACE_RESNET_v1",
  "source": "insightface-v3",
  "area": {
    "startX": 412,
    "startY": 98,
    "width":  154,
    "height": 192
  },
  "vector": [0.0312, -0.1145, 0.8731, 0.2204, "..."],
  "status": {
    "creator": { "uuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890" },
    "created": "2026-04-01T09:10:00Z"
  }
}
```

### 4.2 Store a Face Detection in a Video (with temporal range)

For video frames, supply both the bounding box and the temporal range (`from`/`to` in milliseconds) so the detection can be located in time as well as space.

```http
POST /api/v1/embeddings
Authorization: Bearer <token>
Content-Type: application/json
```

```json
{
  "assetUuid": "7f3a9b1c-44e2-4d8f-b623-0e5d7c2a1f90",
  "type": "DLIB_FACE_RESNET_v1",
  "source": "insightface-v3",
  "area": {
    "from":   62400,
    "to":     62440,
    "startX": 820,
    "startY": 110,
    "width":  160,
    "height": 210
  },
  "vector": [-0.0541, 0.2201, 0.7712, -0.1039, "...512 floats total..."]
}
```

**Response** `200 OK`

```json
{
  "uuid": "eeee2222-face-4bbb-8ccc-000000000020",
  "assetUuid": "7f3a9b1c-44e2-4d8f-b623-0e5d7c2a1f90",
  "type": "DLIB_FACE_RESNET_v1",
  "source": "insightface-v3",
  "area": {
    "from":   62400,
    "to":     62440,
    "startX": 820,
    "startY": 110,
    "width":  160,
    "height": 210
  },
  "vector": [-0.0541, 0.2201, 0.7712, -0.1039, "..."]
}
```

### 4.3 List all Embeddings for a Video Asset

After processing, retrieve the full set of raw face detections stored for a video. Use standard paging parameters (`?page=0&perPage=25`) for large result sets.

```http
GET /api/v1/embeddings?assetUuid=7f3a9b1c-44e2-4d8f-b623-0e5d7c2a1f90
Authorization: Bearer <token>
```

**Response** `200 OK`

```json
{
  "data": [
    {
      "uuid": "eeee2222-face-4bbb-8ccc-000000000020",
      "assetUuid": "7f3a9b1c-44e2-4d8f-b623-0e5d7c2a1f90",
      "type": "DLIB_FACE_RESNET_v1",
      "source": "insightface-v3",
      "area": { "from": 62400, "to": 62440, "startX": 820, "startY": 110, "width": 160, "height": 210 },
      "vector": [-0.0541, 0.2201, "..."]
    },
    {
      "uuid": "eeee3333-face-4ccc-8ddd-000000000030",
      "assetUuid": "7f3a9b1c-44e2-4d8f-b623-0e5d7c2a1f90",
      "type": "DLIB_FACE_RESNET_v1",
      "source": "insightface-v3",
      "area": { "from": 88100, "to": 88140, "startX": 435, "startY": 95, "width": 148, "height": 198 },
      "vector": [-0.0538, 0.2198, "..."]
    }
  ],
  "paging": {
    "page": 0,
    "perPage": 25,
    "total": 2
  }
}
```

---

## 5. Face Cluster Reduction

Raw face detections (embeddings) are anonymous. A clustering pipeline groups similar vectors together into named **Clusters** — one cluster per person identity. This reduces hundreds of individual face detections to a small set of identified or labelled person clusters.

### 5.1 Create a Cluster for a Person Identity

First create an unnamed cluster. It will be labelled in a later step once identity is confirmed.

```http
POST /api/v1/clusters
Authorization: Bearer <token>
Content-Type: application/json
```

```json
{
  "name": "unknown-person-01",
  "type": "PERSON"
}
```

**Response** `200 OK`

```json
{
  "uuid": "cccc0001-c1a5-4def-9012-000000000100",
  "name": "unknown-person-01",
  "type": "PERSON",
  "status": {
    "creator": { "uuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890" },
    "created": "2026-04-01T09:15:00Z"
  }
}
```

### 5.2 Link Face Embeddings to the Cluster

Associate the raw face embeddings that belong to this person with the newly created cluster.

```http
POST /api/v1/clusters/cccc0001-c1a5-4def-9012-000000000100/embeddings
Authorization: Bearer <token>
Content-Type: application/json
```

```json
{
  "embeddingUuids": [
    "eeee1111-face-4aaa-8bbb-000000000010",
    "eeee2222-face-4bbb-8ccc-000000000020",
    "eeee3333-face-4ccc-8ddd-000000000030"
  ]
}
```

**Response** `204 No Content`

### 5.3 Name / Resolve the Cluster Identity

After a human reviewer (or a recognition pipeline) identifies the person, update the cluster name.

```http
POST /api/v1/clusters/cccc0001-c1a5-4def-9012-000000000100
Authorization: Bearer <token>
Content-Type: application/json
```

```json
{
  "name": "Jane Smith"
}
```

**Response** `200 OK`

```json
{
  "uuid": "cccc0001-c1a5-4def-9012-000000000100",
  "name": "Jane Smith",
  "type": "PERSON",
  "status": {
    "editor": { "uuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890" },
    "edited": "2026-04-01T10:00:00Z"
  }
}
```

### 5.4 Merge Clusters (false-split correction)

If the pipeline created two clusters for the same person, remove the duplicate and re-link its embeddings to the correct cluster.

```http
# Re-link embeddings from duplicate cluster cccc0002 to the canonical cluster cccc0001
POST /api/v1/clusters/cccc0001-c1a5-4def-9012-000000000100/embeddings
Authorization: Bearer <token>
Content-Type: application/json

{
  "embeddingUuids": [
    "eeee4444-face-4ddd-8eee-000000000040"
  ]
}
```

Then delete the now-empty duplicate cluster:

```http
DELETE /api/v1/clusters/cccc0002-c1a5-4def-9012-000000000200
Authorization: Bearer <token>
```

**Response** `204 No Content`

### 5.5 Unlink a Misidentified Embedding

If a detection was placed in the wrong cluster, unlink it:

```http
DELETE /api/v1/clusters/cccc0001-c1a5-4def-9012-000000000100/embeddings/eeee4444-face-4ddd-8eee-000000000040
Authorization: Bearer <token>
```

**Response** `204 No Content`

---

## 6. Reading Clustered Results from an Asset

Once embeddings have been clustered, clients should **not** work with the raw embedding list. Instead they use the clustered view to understand which identified persons appear in an asset.

### 6.1 Load an Asset with Embedded Summary

`GET /api/v1/assets/{uuid}` returns the asset including a summary of all embeddings stored against it. Each `EmbeddingInfo` entry in the `embeddings` array contains the embedding `uuid`, its `type`, `source`, and the `area` — but **not** the raw vector (omitted for payload efficiency). The cluster assignment is resolved separately (see §6.2).

```http
GET /api/v1/assets/7f3a9b1c-44e2-4d8f-b623-0e5d7c2a1f90
Authorization: Bearer <token>
```

**Response** `200 OK`

```json
{
  "uuid": "7f3a9b1c-44e2-4d8f-b623-0e5d7c2a1f90",
  "file": {
    "filename": "interview_2026_03_15.mp4",
    "mimeType": "video/mp4",
    "size": 2147483648
  },
  "video": {
    "width": 1920,
    "height": 1080,
    "duration": 180500,
    "fps": 25.0
  },
  "embeddings": [
    {
      "uuid": "eeee2222-face-4bbb-8ccc-000000000020",
      "type": "DLIB_FACE_RESNET_v1",
      "source": "insightface-v3",
      "area": { "from": 62400, "to": 62440, "startX": 820, "startY": 110, "width": 160, "height": 210 }
    },
    {
      "uuid": "eeee3333-face-4ccc-8ddd-000000000030",
      "type": "DLIB_FACE_RESNET_v1",
      "source": "insightface-v3",
      "area": { "from": 88100, "to": 88140, "startX": 435, "startY": 95, "width": 148, "height": 198 }
    }
  ],
  "annotations": [],
  "tags": [],
  "status": {
    "creator": { "uuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890" },
    "created": "2026-04-01T09:00:00Z"
  }
}
```

### 6.2 Identify Which Person Clusters Appear in the Asset

Resolve each embedding's cluster by fetching the cluster list filtered to the asset's embeddings, or by looking up individual clusters by UUID.

```http
GET /api/v1/clusters/cccc0001-c1a5-4def-9012-000000000100
Authorization: Bearer <token>
```

**Response** `200 OK`

```json
{
  "uuid": "cccc0001-c1a5-4def-9012-000000000100",
  "name": "Jane Smith",
  "type": "PERSON",
  "status": {
    "creator": { "uuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890" },
    "created": "2026-04-01T09:15:00Z",
    "editor": { "uuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890" },
    "edited": "2026-04-01T10:00:00Z"
  }
}
```

### 6.3 List All Embeddings Within a Cluster

To display all occurrences of "Jane Smith" across the entire library, list the cluster's embeddings. Each entry links back to an asset via `assetUuid`.

```http
GET /api/v1/clusters/cccc0001-c1a5-4def-9012-000000000100/embeddings
Authorization: Bearer <token>
```

**Response** `200 OK`

```json
{
  "data": [
    {
      "uuid": "eeee1111-face-4aaa-8bbb-000000000010",
      "assetUuid": "22222222-bbbb-4ccc-8ddd-000000000002",
      "type": "DLIB_FACE_RESNET_v1",
      "source": "insightface-v3",
      "area": { "startX": 412, "startY": 98, "width": 154, "height": 192 }
    },
    {
      "uuid": "eeee2222-face-4bbb-8ccc-000000000020",
      "assetUuid": "7f3a9b1c-44e2-4d8f-b623-0e5d7c2a1f90",
      "type": "DLIB_FACE_RESNET_v1",
      "source": "insightface-v3",
      "area": { "from": 62400, "to": 62440, "startX": 820, "startY": 110, "width": 160, "height": 210 }
    },
    {
      "uuid": "eeee3333-face-4ccc-8ddd-000000000030",
      "assetUuid": "7f3a9b1c-44e2-4d8f-b623-0e5d7c2a1f90",
      "type": "DLIB_FACE_RESNET_v1",
      "source": "insightface-v3",
      "area": { "from": 88100, "to": 88140, "startX": 435, "startY": 95, "width": 148, "height": 198 }
    }
  ],
  "paging": {
    "page": 0,
    "perPage": 25,
    "total": 3
  }
}
```

This tells the client: *"Jane Smith (cluster cccc0001) appears in two assets — once in photo_002.jpg at bounding box (412, 98, 154×192) and twice in interview_2026_03_15.mp4 at timestamps 62.4 s and 88.1 s."*

### 6.4 List All Clusters (Person Index)

Browse every named cluster — the complete resolved person index of the library.

```http
GET /api/v1/clusters
Authorization: Bearer <token>
```

**Response** `200 OK`

```json
{
  "data": [
    {
      "uuid": "cccc0001-c1a5-4def-9012-000000000100",
      "name": "Jane Smith",
      "type": "PERSON"
    },
    {
      "uuid": "cccc0002-c1a5-4def-9012-000000000200",
      "name": "John Doe",
      "type": "PERSON"
    }
  ],
  "paging": {
    "page": 0,
    "perPage": 25,
    "total": 2
  }
}
```

---

## Endpoint Summary

| Method   | Path                                              | Description                                     |
|----------|---------------------------------------------------|-------------------------------------------------|
| `POST`   | `/api/v1/login`                                   | Obtain a JWT bearer token                       |
| `POST`   | `/api/v1/assets`                                  | Create a single asset                           |
| `GET`    | `/api/v1/assets`                                  | List assets (paged)                             |
| `GET`    | `/api/v1/assets/{uuid}`                           | Load asset by UUID (includes embedding summary) |
| `POST`   | `/api/v1/assets/{uuid}`                           | Update asset by UUID                            |
| `DELETE` | `/api/v1/assets/{uuid}`                           | Delete asset by UUID                            |
| `GET`    | `/api/v1/assets/sha512/{sha512}`                  | Load asset by SHA-512 hash                      |
| `POST`   | `/api/v1/assets/sha512/{sha512}`                  | Update asset by SHA-512 hash                    |
| `POST`   | `/api/v1/assets/bulk/create`                      | Bulk create assets                              |
| `POST`   | `/api/v1/assets/bulk/update`                      | Bulk update assets                              |
| `POST`   | `/api/v1/assets/{uuid}/tags`                      | Add a tag to an asset                           |
| `DELETE` | `/api/v1/assets/{uuid}/tags/{tagUuid}`            | Remove a tag from an asset                      |
| `POST`   | `/api/v1/embeddings`                              | Create a face / vector embedding                |
| `GET`    | `/api/v1/embeddings`                              | List embeddings (paged)                         |
| `GET`    | `/api/v1/embeddings/{uuid}`                       | Load an embedding by UUID                       |
| `POST`   | `/api/v1/embeddings/{uuid}`                       | Update an embedding                             |
| `DELETE` | `/api/v1/embeddings/{uuid}`                       | Delete an embedding                             |
| `POST`   | `/api/v1/clusters`                                | Create a cluster                                |
| `GET`    | `/api/v1/clusters`                                | List clusters (paged)                           |
| `GET`    | `/api/v1/clusters/{uuid}`                         | Load a cluster by UUID                          |
| `POST`   | `/api/v1/clusters/{uuid}`                         | Update (rename) a cluster                       |
| `DELETE` | `/api/v1/clusters/{uuid}`                         | Delete a cluster                                |
| `POST`   | `/api/v1/clusters/{uuid}/embeddings`              | Link embeddings to a cluster                    |
| `GET`    | `/api/v1/clusters/{uuid}/embeddings`              | List embeddings in a cluster                    |
| `DELETE` | `/api/v1/clusters/{uuid}/embeddings/{embUuid}`    | Unlink an embedding from a cluster              |
| `POST`   | `/api/v1/annotations`                             | Create an annotation                            |
| `GET`    | `/api/v1/annotations`                             | List annotations (paged)                        |
| `GET`    | `/api/v1/annotations/{uuid}`                      | Load an annotation by UUID                      |
| `POST`   | `/api/v1/annotations/{uuid}`                      | Update an annotation                            |
| `DELETE` | `/api/v1/annotations/{uuid}`                      | Delete an annotation                            |
