# Loom - GraphQL Service

This service adds GraphQL support to Loom, providing a flexible query API for
asset data with nested component and location information.

## Overview

| Property | Value |
|----------|-------|
| **Module** | `loom/services/graphql` |
| **Artifact** | `io.metaloom.loom.service:loom-service-graphql` |
| **GraphQL Java** | 25.0 |
| **Schema** | SDL (`src/main/resources/loom.graphqls`) |
| **Status** | Implemented, **not yet wired** to HTTP endpoint |

## Features

- **Schema-first** GraphQL API using SDL
- **Asset queries** - fetch assets by UUID or list all
- **Nested data** - image/video/audio components, locations, hashes
- **Custom `Long` scalar** - for file sizes exceeding 32-bit range
- **DAO integration** - wired to `DaoCollection` (AssetDao, AssetBinaryDao, AssetComponentDao)
- **Unit tests** - with mocked DAOs

## Schema Summary

```graphql
type Query {
  asset(uuid: ID!): Asset
  assets: [Asset!]!
}

type Asset {
  uuid: ID!
  filename: String
  mimeType: String
  size: Long
  sha512: String
  sha256: String
  md5: String
  initialOrigin: String
  imageComponents: [ImageComponent!]!
  videoComponents: [VideoComponent!]!
  audioComponents: [AudioComponent!]!
  locations: [AssetLocation!]!
}

type AssetLocation { uuid: ID! path: String assetUuid: ID libraryUuid: ID mimeType: String }
type ImageComponent { uuid: ID! source: String dominantColor: String width: Int height: Int }
type VideoComponent { uuid: ID! source: String }
type AudioComponent { uuid: ID! source: String }
scalar Long
```

## Example Queries

**Get asset with all components:**
```graphql
query GetAsset($uuid: ID!) {
  asset(uuid: $uuid) {
    uuid filename mimeType size sha512 sha256 md5
    imageComponents { uuid dominantColor width height }
    videoComponents { uuid source }
    audioComponents { uuid source }
    locations { uuid path libraryUuid mimeType }
  }
}
```

**List all assets (minimal):**
```graphql
query ListAssets {
  assets { uuid filename mimeType size }
}
```

## Architecture

```
Client → HTTP POST /graphql → LoomGraphQLProvider → GraphQL Engine
                                                    ↓
                                            DataFetchers → DaoCollection
                                                    ↓
                                              PostgreSQL (via jOOQ)
```

**Key Class:** `LoomGraphQLProvider` - builds executable schema, loads SDL, wires DataFetchers to DAOs.

## Current Limitations

| Limitation | Description |
|------------|-------------|
| **No authentication** | JWT/OAuth2 not integrated (REST has this) |
| **No GraphiQL** | No playground for development |
| **N+1 queries** | List queries trigger multiple DAO calls per asset |
| **No complexity limiting** | Vulnerable to DoS via deep/nested queries |

## Building & Testing

```bash
# Build
cd loom/services/graphql
mvn clean install

# Run tests
mvn test
```

## Client Usage

```java
// Execute a GraphQL query
GraphQLRequest request = new GraphQLRequest("{ assets { uuid filename } }");
GraphQLResponse response = client.executeGraphQL(request).sync().body();

// With variables
String query = "query GetAsset($uuid: ID!) { asset(uuid: $uuid) { uuid filename } }";
JsonObject variables = new JsonObject().put("uuid", assetUuid);
GraphQLRequest request = new GraphQLRequest(query, variables);
GraphQLResponse response = client.executeGraphQL(request).sync().body();
```

## Test Infrastructure

- `AbstractGraphQLEndpointTest` - Base class for GraphQL endpoint tests
- `GraphQLEndpointTestcases` - Interface defining test contract
- `GraphQLEndpointTest` - Integration tests covering:
  - Basic assets query
  - Query with variables
  - Nested components (image, video, audio, locations)
  - Asset not found handling
  - Invalid query error handling

## Documentation

See the full specification: [spec/loom/GRAPHQL.md](../../spec/loom/GRAPHQL.md)

## Related Specs

- [REST API](RESTAPI.md) - Authentication, CORS, routing patterns to reuse
- [Loom Architecture](LOOM.md) - Module layout, server lifecycle
- [Persistence](PERSISTENCE.md) - DAO layer used by GraphQL
