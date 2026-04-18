# Cortex Pipeline ↔ UI Specification

> **Status:** Draft  
> **Scope:** Shared contract between the Loom backend (Cortex pipeline) and the Loom UI for exchanging pipeline graphs and node descriptors as JSON.

---

## 1. Problem Statement

The pipeline UI (`PipelineArea.tsx`) currently hard-codes a static graph (`INITIAL_NODES`, `EDGES`).
It cannot discover what node kinds exist, what parameters they accept, what connectors they expose, or what live events they emit.
We need a **shared specification** so the UI can:

1. Dynamically render node palettes ("what nodes can I add?").
2. Generate per-node edit forms ("what options does this node have?").
3. Validate connections ("is this output compatible with that input?").
4. Show live status indicators ("what events does this node emit?").

---

## 2. Central Concept: `NodeDescriptor`

Every node _kind_ (e.g. `sha512`, `facedetect`, `whisper`) publishes a **NodeDescriptor** — a JSON document that fully describes the node to the UI. The descriptor is the single source of truth for rendering and validation.

### 2.1 NodeDescriptor JSON Shape

```jsonc
{
  // ── Identity ──────────────────────────────────────────────
  "kind":        "facedetect",          // unique machine-readable kind
  "name":        "Face Detection",      // human-readable display name
  "description": "Detect and cluster faces in images and video frames.",
  "icon":        "face",                // icon key (material-icons or custom)
  "category":    "analysis",            // palette grouping: source | filter | analysis | transform | output

  // ── Typing ────────────────────────────────────────────────
  "inputs": [
    { "name": "media",  "contentType": "media/image",  "required": true },
    { "name": "media",  "contentType": "media/video",  "required": true }
  ],
  "outputs": [
    { "name": "face_count",      "contentType": "data/integer"  },
    { "name": "facedetect_flag", "contentType": "data/string"   },
    { "name": "face_description","contentType": "data/string"   }
  ],

  // ── Parameters (drives the edit form) ─────────────────────
  "parameters": [
    { "key": "enabled",            "type": "boolean", "default": true,   "label": "Enabled" },
    { "key": "videoChopRate",      "type": "integer", "default": 5,      "label": "Video Chop Rate",
      "description": "Process every Nth video frame", "min": 1 },
    { "key": "faceClusterMinimum", "type": "integer", "default": 2,      "label": "Min cluster size" },
    { "key": "faceClusterEPS",     "type": "number",  "default": 0.6,    "label": "Cluster radius",
      "min": 0.0, "max": 2.0, "step": 0.05 },
    { "key": "videoScaleSize",     "type": "integer", "default": 384,    "label": "Scale size (px)" },
    { "key": "minFaceHeightFactor","type": "number",  "default": 0.05,   "label": "Min face height factor" },
    { "key": "inspirefacePackPath","type": "string",  "default": "packs/Pikachu", "label": "Model pack" },
    { "key": "capabilities",      "type": "enum-set","values": ["INSPIREFACE","DLIB"],
      "default": ["INSPIREFACE"], "label": "Backends" }
  ],

  // ── Concurrency / execution hints ─────────────────────────
  "defaultConcurrency": 2,
  "defaultMode":        "PARALLEL",
  "defaultBlocking":    true,

  // ── Events the UI can visualise ───────────────────────────
  "events": [
    "NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED", "NODE_STATS"
  ]
}
```

### 2.2 Common vs. Node-Specific Fields

| Field group | Common to all nodes | Node-specific |
|-------------|:---:|:---:|
| `kind`, `name`, `description`, `icon`, `category` | ✓ | |
| `inputs`, `outputs` | shape is common | values differ per kind |
| `parameters` – `enabled`, `processIncomplete`, `retryFailed` | ✓ (from `AbstractNodeOptions`) | |
| `parameters` – everything else | | ✓ |
| `defaultConcurrency`, `defaultMode`, `defaultBlocking` | ✓ (with defaults) | override |
| `events` | ✓ (standard set) | may add custom events |

---

## 3. Content Types

Content types classify what flows between nodes. They are used for **connector compatibility checks** in the UI (you cannot connect a node that produces `data/embedding` to one that requires `media/audio`).

### 3.1 Content Type Registry

```jsonc
{
  "contentTypes": [
    // ── Media (file-level) ──────────────────────────────────
    { "id": "media/image",     "label": "Image",       "superType": "media/*" },
    { "id": "media/video",     "label": "Video",       "superType": "media/*" },
    { "id": "media/audio",     "label": "Audio",       "superType": "media/*" },
    { "id": "media/document",  "label": "Document",    "superType": "media/*" },
    { "id": "media/*",         "label": "Any Media"    },

    // ── Scalar data ─────────────────────────────────────────
    { "id": "data/string",     "label": "String"       },
    { "id": "data/integer",    "label": "Integer"      },
    { "id": "data/number",     "label": "Number"       },
    { "id": "data/boolean",    "label": "Boolean"      },
    { "id": "data/hash",       "label": "Hash",        "superType": "data/string" },
    { "id": "data/path",       "label": "File Path",   "superType": "data/string" },

    // ── Structured / domain data ────────────────────────────
    { "id": "data/embedding",      "label": "Embedding Vector"  },
    { "id": "data/facedetection",  "label": "Face Detection"    },
    { "id": "data/objectdetection","label": "Object Detection"  },
    { "id": "data/imagearea",      "label": "Image Region"      },
    { "id": "data/text",           "label": "Extracted Text"     },
    { "id": "data/transcript",     "label": "Audio Transcript"   },
    { "id": "data/caption",        "label": "Image Caption"      },
    { "id": "data/scene",          "label": "Scene Boundaries"   },
    { "id": "data/fingerprint",    "label": "Media Fingerprint"  },
    { "id": "data/thumbnail",      "label": "Thumbnail Image"    },
    { "id": "data/quality",        "label": "Quality Metrics"    },

    // ── Filter ──────────────────────────────────────────────
    { "id": "control/filter_passed", "label": "Filter Result (bool)" }
  ]
}
```

A `superType` field enables **wildcard matching**: a node that accepts `media/*` can accept `media/image`, `media/video`, etc.

---

## 4. Approaches to Delivering Descriptors to the UI

### Option A — Server-Served Node Descriptors (Recommended)

```
GET /api/v1/pipeline/node-descriptors        → NodeDescriptor[]
GET /api/v1/pipeline/node-descriptors/{kind}  → NodeDescriptor
GET /api/v1/pipeline/content-types            → ContentType[]
```

**How it works:**

1. Each `CortexNode` implementation provides a `NodeDescriptor descriptor()` method.
2. The `CortexNodeAdapter` (which already wraps `CortexNode` → `PipelineNode`) forwards the descriptor.
3. A new `NodeDescriptorEndpoint` serves the aggregated list.
4. The UI fetches descriptors on load and caches them in React context.

**Where the descriptor comes from (Java side):**

```java
// In CortexNode interface — new method
default NodeDescriptor descriptor() {
    // Reflective default: derive from generics + options class.
    // Nodes can override for richer metadata.
}
```

Or, equivalently, a standalone `NodeDescriptorProvider` that inspects the
`CortexNode` class + its `CortexNodeOptions` via Jackson schema generation
(`JsonSchemaGenerator`) and enriches with hand-written metadata from a
`@NodeMeta` annotation.

**Pros:**

- Single source of truth lives _in_ the node code — cannot drift.
- The UI never hard-codes node kinds — new nodes appear automatically.
- Content type compatibility checked at connection time on both sides.
- Options schema can be generated from the existing `CortexNodeOptions` POJOs via `jackson-module-jsonSchema` or `jackson-jsonschema` with near-zero code.
- Descriptor changes ship with the backend; no coordinated UI deploy needed.

**Cons:**

- UI must wait for the API call before rendering the palette (mitigated by caching / loading skeleton).
- Descriptor generation is another thing to maintain per node (mitigated by sensible reflective defaults).
- Needs a versioning strategy if the schema evolves.

---

### Option B — JSON Schema Files Shipped as Static Resources

Each node module contains a `node-descriptor.json` alongside its Java code.
These files are collected at build time (Maven resource plugin) and bundled into a
`/descriptors/` classpath folder. The REST endpoint serves them directly.

```
cortex/nodes/facedetect/src/main/resources/
    node-descriptor.json
```

**Pros:**

- Descriptors are plain JSON — easy to review in PRs, no reflection magic.
- Can be consumed by non-Java tools (docs generators, CLI validators).
- Can be published as an npm package for the UI to import at build time as a fallback.

**Cons:**

- Duplication risk: options in Java class and in JSON can drift apart.
- No compile-time check that the JSON matches the actual Options class.
- Adding a new parameter requires editing two files.

---

### Option C — Shared JSON Schema Module (npm + Maven)

A standalone `pipeline-schema` module (no Java logic) contains:

- `node-descriptors/*.json` — one per node kind.
- `content-types.json` — the content type registry.
- TypeScript types generated from the JSON schemas via `json-schema-to-typescript`.

Published to **both** Maven Central (as a JAR with classpath resources) and npm (as `@metaloom/pipeline-schema`). The backend validates at startup; the UI imports types and descriptors at build time.

**Pros:**

- Strongest contract: both sides import the _same_ artifact.
- TypeScript types generated automatically — no manual sync.
- Works offline (UI has descriptors at build time).

**Cons:**

- Most complex build setup (dual publish, code-gen step).
- Still risks drift from the Java options classes unless a CI check compares them.
- Slower iteration: changing a descriptor requires publishing the schema module first.

---

### Option D — Derive Descriptors Entirely from Java Generics + Annotations

Use the existing generic chain (`CortexNode<I, T extends CortexNodeOptions>`) and a small set of annotations to _generate_ descriptors at runtime with zero separate files:

```java
@NodeMeta(
    kind = "facedetect",
    name = "Face Detection",
    category = Category.ANALYSIS,
    icon = "face"
)
public class FacedetectNode extends AbstractMediaNode<FacedetectNodeOptions> {
    // existing code unchanged

    // Output keys are already declared as static fields:
    public static final NodeOutputKey<Integer> OUTPUT_FACE_COUNT = ...;
}
```

A `NodeDescriptorFactory` at startup:

1. Reads `@NodeMeta` for identity fields.
2. Inspects `T` (`FacedetectNodeOptions`) via Jackson `JsonSchemaGenerator` → `parameters[]`.
3. Reads declared `NodeOutputKey<?>` static fields → `outputs[]`.
4. Input types inferred from accepted mime types (node already has this logic).

**Pros:**

- Zero duplication — everything derived from existing code.
- Adding a new option field automatically appears in the UI.
- Annotation is tiny and lives right on the node class.

**Cons:**

- Requires robust reflection (but Jackson schema gen is mature).
- Custom UI hints (min/max/step, description) need annotation attributes or a supplementary `@Param` annotation on the options fields.
- Harder to share with non-Java consumers without the REST endpoint.

---

## 5. Recommendation

**Use Option A + D together.**

| Concern | Solution |
|---------|----------|
| Descriptor origin | `@NodeMeta` annotation + `NodeDescriptorFactory` reflective generation (Option D) |
| Delivery to UI | REST endpoint `GET /api/v1/pipeline/node-descriptors` (Option A) |
| Parameter schema | Generated from `CortexNodeOptions` POJO via Jackson JSON Schema |
| Output declarations | Read from existing `NodeOutputKey<T>` static fields |
| Content types | A small `ContentTypeRegistry` enum/class served alongside descriptors |
| UI caching | Fetch once on app load, store in React context; re-fetch on reconnect |

This combines the **zero-duplication** advantage of annotation-driven generation with the **dynamic delivery** of a REST endpoint, while keeping the door open for Option B (static JSON overrides) where hand-tuned UI hints are needed.

---

## 6. Detailed Design

### 6.1 Java: New Interfaces & Classes

```
cortex/
  api/src/main/java/io/metaloom/cortex/api/
    descriptor/
      NodeDescriptor.java            // POJO
      NodeInput.java                 // { name, contentType, required }
      NodeOutput.java                // { name, contentType }
      NodeParameter.java             // { key, type, default, label, description, min, max, ... }
      ContentType.java               // { id, label, superType }
      ContentTypeRegistry.java       // static registry of all known types
      NodeMeta.java                  // @interface annotation
```

#### `NodeDescriptor.java`

```java
public class NodeDescriptor {
    private String kind;
    private String name;
    private String description;
    private String icon;
    private String category;          // source | filter | analysis | transform | output
    private List<NodeInput> inputs;
    private List<NodeOutput> outputs;
    private List<NodeParameter> parameters;
    private int defaultConcurrency;
    private String defaultMode;       // PARALLEL | SEQUENTIAL
    private boolean defaultBlocking;
    private List<String> events;

    // Jackson serialization + builder or getters/setters
}
```

#### `@NodeMeta` Annotation

```java
@Retention(RUNTIME)
@Target(TYPE)
public @interface NodeMeta {
    String kind();
    String name();
    String description() default "";
    String icon()        default "settings";
    String category()    default "processor";
}
```

#### `NodeDescriptorFactory`

```java
@Singleton
public class NodeDescriptorFactory {

    /** Build a descriptor by inspecting the node class, its options, and its output keys. */
    public NodeDescriptor buildDescriptor(CortexNode<?, ?> node) { ... }
}
```

### 6.2 REST Endpoint

```java
// In loom/services/rest/
@Singleton
public class NodeDescriptorEndpoint {

    @Inject Set<CortexNode<?, ?>> nodes;
    @Inject NodeDescriptorFactory factory;

    // GET /api/v1/pipeline/node-descriptors
    public void list(RoutingContext rc) {
        List<NodeDescriptor> descriptors = nodes.stream()
            .map(factory::buildDescriptor)
            .toList();
        rc.response().end(Json.encode(descriptors));
    }

    // GET /api/v1/pipeline/content-types
    public void contentTypes(RoutingContext rc) {
        rc.response().end(Json.encode(ContentTypeRegistry.all()));
    }
}
```

### 6.3 Pipeline JSON — Extended with Node Kind

The existing `PipelineSerializer` node block gains a `kind` field linking back to the descriptor:

```jsonc
{
  "id": "face-1",
  "kind": "facedetect",        // ← NEW: links to NodeDescriptor.kind
  "name": "Face Detection",
  "type": "processor",
  "mode": "PARALLEL",
  "blocking": true,
  "concurrency": 2,
  "syncToLoom": true,
  "dependencies": ["filter-1"],
  "options": {
    "videoChopRate": 5,
    "capabilities": ["INSPIREFACE"]
  },
  "children": ["loom-sync"]
}
```

The UI reads `kind` → looks up cached `NodeDescriptor` → renders the correct component, form, and connectors.

### 6.4 UI: Consuming Descriptors

```typescript
// api/nodeDescriptors.ts
export interface NodeDescriptor {
  kind: string;
  name: string;
  description: string;
  icon: string;
  category: "source" | "filter" | "analysis" | "transform" | "output";
  inputs:  { name: string; contentType: string; required: boolean }[];
  outputs: { name: string; contentType: string }[];
  parameters: NodeParameter[];
  defaultConcurrency: number;
  defaultMode: "PARALLEL" | "SEQUENTIAL";
  defaultBlocking: boolean;
  events: string[];
}

export interface NodeParameter {
  key: string;
  type: "string" | "integer" | "number" | "boolean" | "enum" | "enum-set";
  default?: unknown;
  label: string;
  description?: string;
  min?: number;
  max?: number;
  step?: number;
  values?: string[];         // for enum / enum-set
}

export async function fetchNodeDescriptors(): Promise<NodeDescriptor[]> {
  const res = await fetch("/api/v1/pipeline/node-descriptors");
  return res.json();
}
```

```typescript
// Pipeline/useNodeDescriptors.ts  (React context hook)
const NodeDescriptorContext = React.createContext<Map<string, NodeDescriptor>>(new Map());

export function useNodeDescriptor(kind: string): NodeDescriptor | undefined {
  return useContext(NodeDescriptorContext).get(kind);
}
```

The `PipelineArea` would then:

1. Fetch descriptors and pipeline JSON on mount.
2. For each node in the pipeline JSON, look up `descriptors[node.kind]`.
3. Render the node component using the descriptor's `icon`, `category`, `outputs` (as handles), etc.
4. On node click, generate an edit form from `descriptor.parameters`.
5. On edge creation, validate `source.outputs[i].contentType` ↔ `target.inputs[j].contentType` compatibility using the content-type `superType` hierarchy.

### 6.5 Connection Validation

```typescript
function isCompatible(outputType: string, inputType: string): boolean {
  if (inputType === outputType) return true;
  if (inputType.endsWith("/*")) {
    const prefix = inputType.slice(0, -1);  // "media/"
    return outputType.startsWith(prefix);
  }
  // Check superType chain from ContentTypeRegistry
  const outputDef = contentTypes.get(outputType);
  return outputDef?.superType ? isCompatible(outputDef.superType, inputType) : false;
}
```

---

## 7. Summary of Pros and Cons

| | Pros | Cons |
|---|------|------|
| **A+D (recommended)** | Zero duplication; auto-discovery of new nodes; type-safe outputs; works today with existing `CortexNodeOptions` POJOs | Requires reflection + annotation work; UI needs initial API call |
| **B (static JSON)** | Simple; reviewable in PRs; no reflection | Drift risk; two files per node |
| **C (shared module)** | Strongest compile-time contract | Complex dual-publish build; slower iteration |

---

## 8. Implementation Order

1. Define `NodeDescriptor`, `NodeInput`, `NodeOutput`, `NodeParameter`, `ContentType` POJOs in `cortex/api`.
2. Create `@NodeMeta` annotation; annotate existing nodes.
3. Implement `NodeDescriptorFactory` (reflection + Jackson schema gen).
4. Add `kind` field to `PipelineSerializer` / `PipelineDeserializer`.
5. Create `NodeDescriptorEndpoint` + `ContentTypeEndpoint` REST routes.
6. Add `fetchNodeDescriptors()` and `NodeDescriptorContext` in `loom-ui`.
7. Refactor `PipelineArea.tsx` to build the graph dynamically from pipeline JSON + descriptors.
8. Build the node edit panel driven by `parameters[]`.
9. Add connector compatibility validation using content types.
