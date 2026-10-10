import { describe, expect, it } from "vitest";
import { isInlineImageAllowed } from "./markdownUrls";

const ORIGIN = "https://loom.example";

describe("isInlineImageAllowed", () => {
  it("allows a same-origin path", () => {
    expect(isInlineImageAllowed("/api/v1/assets/a1/binary", ORIGIN)).toBe(true);
    expect(isInlineImageAllowed("thumb.png", ORIGIN)).toBe(true);
  });

  it("allows an absolute URL on the same origin", () => {
    expect(isInlineImageAllowed("https://loom.example/x.png", ORIGIN)).toBe(true);
  });

  it("refuses another host, which is how a prompt-injected answer exfiltrates the chat", () => {
    expect(isInlineImageAllowed("https://attacker.example/p.png?q=secret", ORIGIN)).toBe(false);
    expect(isInlineImageAllowed("http://loom.example/x.png", ORIGIN)).toBe(false);
  });

  it("refuses a protocol-relative URL", () => {
    expect(isInlineImageAllowed("//attacker.example/p.png", ORIGIN)).toBe(false);
    expect(isInlineImageAllowed("\\\\attacker.example/p.png", ORIGIN)).toBe(false);
  });

  it("refuses non-http schemes and empty values", () => {
    expect(isInlineImageAllowed("javascript:alert(1)", ORIGIN)).toBe(false);
    expect(isInlineImageAllowed("data:image/png;base64,AAAA", ORIGIN)).toBe(false);
    expect(isInlineImageAllowed("", ORIGIN)).toBe(false);
    expect(isInlineImageAllowed(undefined, ORIGIN)).toBe(false);
  });
});
