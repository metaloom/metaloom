import { describe, expect, it } from "vitest";
import { formatBytes, percentOf, progressLabel } from "./uploadFormat";

describe("formatBytes", () => {
  it("scales through the decimal units", () => {
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(2048)).toBe("2 KB");
    expect(formatBytes(5.5e6)).toBe("5.5 MB");
    expect(formatBytes(3.2e9)).toBe("3.2 GB");
    expect(formatBytes(1.5e12)).toBe("1.5 TB");
  });

  it("renders a placeholder rather than NaN for a missing size", () => {
    expect(formatBytes(Number.NaN)).toBe("—");
    expect(formatBytes(-1)).toBe("—");
  });
});

describe("percentOf", () => {
  it("reports whole percentages", () => {
    expect(percentOf(0, 100)).toBe(0);
    expect(percentOf(50, 200)).toBe(25);
    expect(percentOf(100, 100)).toBe(100);
  });

  it("clamps so a bar never overruns its track", () => {
    // A progress event can report more bytes than the file size, because the multipart envelope
    // adds headers and boundaries to the request body.
    expect(percentOf(150, 100)).toBe(100);
    expect(percentOf(-5, 100)).toBe(0);
  });

  it("treats a zero-length file as 0 rather than dividing by zero", () => {
    expect(percentOf(0, 0)).toBe(0);
  });
});

describe("progressLabel", () => {
  it("shows sent-of-total while in flight", () => {
    expect(progressLabel(1e6, 4e6, false)).toBe("1.0 MB / 4.0 MB");
  });

  it("collapses to the plain size once settled", () => {
    expect(progressLabel(0, 4e6, true)).toBe("4.0 MB");
  });
});
