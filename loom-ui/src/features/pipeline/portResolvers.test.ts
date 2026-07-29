import { describe, it, expect } from "vitest";
import { SCRIPT_OUTPUT_TYPES, hasPortResolver, resolveOutputPorts } from "./portResolvers";
import type { NodeDescriptor, PortSpec } from "../../types/nodeDescriptors";

/**
 * Contract test for the TypeScript mirrors of the three Java `NodePortResolver`s. The cases below
 * are the ones `NodePortResolverTest` asserts on the Java side — the server resolver is
 * authoritative at save time, so any drift here means the canvas draws handles the server will
 * later refuse.
 */

function descriptor(kind: string, outputPorts: PortSpec[] = []): NodeDescriptor {
  return {
    kind, name: kind, description: "", icon: "", category: "TRANSFORM",
    inputPorts: [], outputPorts, inputGroups: [], outputGroups: [], dynamicPorts: true,
    parameters: [], defaultConcurrency: 1, defaultMode: "PARALLEL", defaultBlocking: false, events: [],
  };
}

const ids = (ports: PortSpec[]) => ports.map(p => p.id);

describe("script port resolver", () => {
  it("makes the list types MANY instead of collapsing them onto their scalar counterpart", () => {
    const ports = resolveOutputPorts(descriptor("script"), {
      outputs: [{ key: "paragraphs", type: "TEXT_LIST" }, { key: "frames", type: "IMAGE_LIST" }],
    });
    expect(ports).toHaveLength(2);
    expect(ports[0]).toMatchObject({ id: "paragraphs", contentType: "text/plain", cardinality: "MANY" });
    expect(ports[1]).toMatchObject({ id: "frames", contentType: "artifact/image", cardinality: "MANY" });
  });

  it.each([
    ["STRING", "scalar/string", "ONE"],
    ["TEXT", "text/plain", "ONE"],
    ["INTEGER", "scalar/integer", "ONE"],
    ["NUMBER", "scalar/number", "ONE"],
    ["BOOLEAN", "scalar/boolean", "ONE"],
    ["JSON", "struct/json", "ONE"],
    ["TEXT_LIST", "text/plain", "MANY"],
    ["TIMEFRAMES", "struct/segments", "ONE"],
    ["IMAGE", "artifact/image", "ONE"],
    ["IMAGE_LIST", "artifact/image", "MANY"],
    ["PATH", "artifact/file", "ONE"],
  ])("maps %s to %s %s", (declared, contentType, cardinality) => {
    expect(SCRIPT_OUTPUT_TYPES[declared]).toEqual({ contentType, cardinality });
    const [port] = resolveOutputPorts(descriptor("script"), { outputs: [{ key: "out", type: declared }] });
    expect(port).toMatchObject({ id: "out", contentType, cardinality });
    expect(port.description).toBeTruthy();
  });

  it("accepts the option as raw JSON text, which is how the parameter editor holds it", () => {
    const ports = resolveOutputPorts(descriptor("script"), { outputs: '[{"key": "result", "type": "TEXT"}]' });
    expect(ports).toMatchObject([{ id: "result", contentType: "text/plain", cardinality: "ONE" }]);
  });

  it("matches type names case-insensitively", () => {
    const [port] = resolveOutputPorts(descriptor("script"), { outputs: [{ key: "caption", type: "  text  " }] });
    expect(port.contentType).toBe("text/plain");
  });

  it("degrades to fewer ports rather than throwing on a mistyped definition", () => {
    expect(resolveOutputPorts(descriptor("script"), {})).toEqual([]);
    expect(resolveOutputPorts(descriptor("script"), { outputs: "not-a-list" })).toEqual([]);
    expect(resolveOutputPorts(descriptor("script"), { outputs: { key: "value" } })).toEqual([]);

    const ports = resolveOutputPorts(descriptor("script"), {
      outputs: [
        null,
        "a bare string",
        { key: "good", type: "TEXT" },
        { key: "no_type_at_all" },
        { type: "TEXT" },
        { key: "unknown_type", type: "NOT_A_TYPE" },
        { key: "Invalid Id", type: "TEXT" },
        { key: "good", type: "INTEGER" },
      ],
    });
    expect(ids(ports)).toEqual(["good"]);
    expect(ports[0].contentType).toBe("text/plain");
  });
});

describe("llm / vlm prompt port resolvers", () => {
  it("emits one result_<promptId> port per configured prompt", () => {
    const ports = resolveOutputPorts(descriptor("llm"), {
      prompts: { summary: { model: "llama3" }, topics: { model: "llama3" } },
    });
    expect(ids(ports)).toEqual(["result_summary", "result_topics"]);
    for (const port of ports) {
      expect(port).toMatchObject({ contentType: "text/plain", cardinality: "ONE" });
      expect(port.description).toBeTruthy();
    }
  });

  it("resolves vlm the same way", () => {
    const ports = resolveOutputPorts(descriptor("vlm"), { prompts: { olmocr: {} } });
    expect(ids(ports)).toEqual(["result_olmocr"]);
    expect(ports[0].contentType).toBe("text/plain");
  });

  it("falls back to a single result port so a freshly dropped node stays connectable", () => {
    for (const kind of ["llm", "vlm"]) {
      expect(ids(resolveOutputPorts(descriptor(kind), {}))).toEqual(["result"]);
      expect(ids(resolveOutputPorts(descriptor(kind), { prompts: {} }))).toEqual(["result"]);
      expect(ids(resolveOutputPorts(descriptor(kind), { prompts: "not-a-map" }))).toEqual(["result"]);
      expect(ids(resolveOutputPorts(descriptor(kind), { prompts: ["summary"] }))).toEqual(["result"]);
    }
  });

  it("skips prompt ids that cannot form a port id", () => {
    const ports = resolveOutputPorts(descriptor("llm"), {
      prompts: { "Not A Valid Id": {}, "": {}, usable: {} },
    });
    expect(ids(ports)).toEqual(["result_usable"]);
  });
});

describe("static kinds", () => {
  const whisper: NodeDescriptor = {
    ...descriptor("whisper", [{ id: "transcript", contentType: "text/transcript", cardinality: "ONE", required: true }]),
    dynamicPorts: false,
  };

  it("has no resolver, so the descriptor's own ports are used", () => {
    expect(hasPortResolver("whisper")).toBe(false);
    expect(ids(resolveOutputPorts(whisper, { outputs: [{ key: "ignored", type: "TEXT" }] }))).toEqual(["transcript"]);
  });

  it("returns nothing for an unknown kind", () => {
    expect(resolveOutputPorts(undefined, {})).toEqual([]);
  });
});
