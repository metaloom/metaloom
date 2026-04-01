# Nodes

List of pipeline node types

## Source Nodes

### From Collection

Create a stream from all assets that belong to a collection.

> **Depends on**: DB

```json
{
    "id": "source-by-collection",
    "type": "source/collection",
    "collection": ["summer-photos", "winter-photos"], // Collections to source assets from
    "next": ["<next-node>"]
}
```

### From Tag

Create a stream from all assets that are tagged

> **Depends on**: DB

```json
{
    "id": "source-by-tag",
    "type": "source/tag",
    "tags": ["red", "green", "blue"], // Tags to source assets from
    "next": ["<next-node>"]
}
```

### From Folder

Create a stream from a folder

> **Depends on**: Filesystem

```json
{
    "id": "source-by-fs",
    "type": "source/fs",
    "path": "<filesystem-path>",
    "next": ["<next-node>"]
}
```

### From Asset

Create a stream from asset/s.

> **Depends on**: DB

```json
{
    "id": "source-by-asset",
    "type": "source/asset",
    "asset": "<reference-to-asset>", // UUIDs of the assets
    "next": ["<next-node>"]
}
```

### From Content

Create a stream from content/s.

> **Depends on**: DB

```json
{
    "id": "source-by-content",
    "type": "source/content",
    "content": "<reference-to-content>", // UUIDs of the contents
    "next": ["<next-node>"]
}
```

## Filter Nodes

_Filters the pipeline (e.g. has two output nodes)_

### Property Filter

Can be used to filter assets.

> **Depends on**: Entity (DB), Filter configuration

Filter by:

* name
* size
* mimeType
* dominant color
* any meta property value
* hash

```json
{
    "id": "property-filter",
    "type": "filter/property",
    "size-range-max": "100MB",
    "size-range-min": "10MB",
    "name-regex": "<regex-for-assetname>",
    "pass": ["…"],
    "reject": ["…"]
}
```

### Geo Fence

Filter asset by using the Geo information.

> **Depends on**: Entity (DB), Area data, Search?

```json
{
    "id": "filter-by-area",
    "type": "filter/geo",
    "area": […], // Geo Area (user can choose it on a map)
    "pass": ["…"], // Node to be invoked for passed elements
    "reject": ["…"] // Node to be invoked for rejected elements
}
```

### Deduplication Filter

> **Depends on**: Entity (DB), Previously registed assets (DB)

```json
{
    "id": "deduplication-filter",
    "type": "filter/deduplication",
    "hash": "sha512", //sha512, sha256, md5, short hash - Hash to select deduplication criteria
    "pass": ["…"], // Element is not duplicate
    "reject": ["…"] // Element is duplicate, TODO: onDuplicate?
}
```


### Whitelist / Blacklist

Whitelist/blacklist assets by hash or fingerprint.

> **Depends on**: Entity (DB), Whitelist/Blacklist (DB)

```json
{
    "id": "whitelist-filter",
    "type": "filter/whitelist",
    "whitelistRef": ["…"],
    "blacklistRef": ["…"],
    "mode": "blacklist", // Whitelist / Blacklist mode
    "pass": ["…"],
    "reject": ["…"]
}
```
## Assign Nodes

### Assign to Collection Action

Assigns the asset to a collection based on meta information

Format
```json
{ 
    "id": "assign-to-raw",
    "type": "assign/collection",
    "collection":  "raw", // Collection to assign to asset to
    "next": ["<next-node>"]
}
```

### Review Action

Assigns the Asset to a user for review

> **Depends on**: Entity (DB), User (DB)

```json
{
    "id": "assign-to-joe",
    "type": "assign/user",
    "user": "joeDoe",
    "next": ["<next-node>"]
}
```

## Script Nodes

### Event Action

Dispatches a custom event

### Lua Action

Invokes a LUA script

```json
{
    "id": "run-script",
    "type": "script/lua",
    "script": "<LUA Script>",
    "next": ["<next-node>"]
}
```

### Groovy Action

Invokes a Groovy script

```json
{
    "id": "run-script",
    "type": "script/groovy",
    "script": "<Groovy Script>",
    "next": ["<next-node>"]
}
```

## Other

### ~~Mail Action~~

~~Dispatches a mail~~

TODO: Maybe this should better be handled via webhook as it requires templates and net access.

### Auto Tag Action

Automatically adds tags to the media by using a configured regex filter

> **Depends on**: Entity (DB), Action configuration

### Webhook Action

Invokes external API

> **Depends on**: Entity (DB)

```json
{
    "id": "invoke-api",
    "type": "dispatch/webhook",
    "endpoint": "URL to endpoint",
    "next": ["<next-node>"]
}
```

## Worker Node

_Runs IO intensive operations_

### Image Annotation Action

Uses machine learning / ML API to add annotations to the media.

### PDF Converter Action

Convert the asset to PDF

> **Depends on**: Entity (DB), Data (FS,S3)

```json
{
    "id": "convert-to-pdf",
    "type": "generate/pdf",
    "qualityFactor": 0.90, 
    "next": ["<next-node>"]
}
```

### Format Conversion Action

> **Depends on**: Entity (DB), Data (FS,S3)

```json
{
    "id": "convert",
    "type": "generate/convert",
    "mappings": ["image/*:image/jpeg", "image/*:image/png"],
    "next": ["<next-node>"]
}
```

### Re-encode Action

> **Depends on**: Entity (DB), Data (FS,S3)

```json
{
    "id": "encode",
    "type": "generate/reencode",
    "next": ["<next-node>"]
}
```

### Transcribe Action

Automatically transscribe audio track of the media (if present).

```json
{
    "id": "transcribe",
    "type": "generate/transcription",
    "languages": ["en", "de"],
    "next": ["<next-node>"]
}
```

### Thumbnail Generator Action

Generate a thumbnail of the media.

```json
{
    "id": "generate-thumbnail",
    "type": "generate/thumbnail",
    "cols": 2,
    "rows": 2,
    "tileSize": 512,
    "next": ["<next-node>"]
}
```

### Fingerprint Action

Generate Media Fingerprint of the asset.

> **Depends on**: Entity (DB), Data (FS,S3)

```json
{
    "id": "fingerprint",
    "type": "generate/fingerprint",
    "algo": "v3",
    "next": ["<next-node>"]
}
```

### Dominant Color Action

Generate the dominant color information.

> **Depends on**: Entity (DB), Data (FS,S3)

```json
{
    "id": "extract-dominant-color",
    "type": "generate/dominant-color",
    "next": ["<next-node>"]
}
```

### Hash Action 

Hashes the asset via SHA512, SHA256, MD5.

> **Depends on**: Entity (DB), Data (FS,S3)

```json
{
    "id": "hash",
    "type": "generate/hash",
    "md5": true,
    "sha256": true,
    "sha512": true,
    "next": ["<next-node>"]
}
```

### S3 Action

Copy or move the asset via S3 API.

> **Depends on**: Entity (DB), Data (FS,S3)

```json
{
    "id": "s3-operation",
    "type": "?/s3",
    "action": "copy|move",
    "target": "<Target Bucket Location>",
    "next": ["<next-node>"]
}
```

### Upload Action

Upload the asset somewhere (e.g. S3).

### Filesystem Action

Moves, Deletes, Copies assets.

> **Depends on**: Entity (DB), Data (FS)

```json
{
    "id": "fs-operation",
    "type": "?/fs",
    "targetFolder": "/tank/assets",
    "copy": false,
    "move": true,
    "next": ["<next-node>"]
}
```

## Node Properties

* id - Id of the node
* Abort on error - Whether to abort processing if an error occurs. Otherwise the next node is invoked.
* Invoke Next - Whether to continue processing
* Parallel - Whether to process the stream in-parallel

Read Only:
* Processed Items
* Total running time
