import { describe, expect, it } from "vitest";
import { count, share, signed, title } from "./format";

describe("format", () => {
  it("signs changes with a true minus and never shows negative zero", () => {
    expect(signed(0.2504)).toBe("+0.250");
    expect(signed(-0.0123)).toBe("−0.012");
    expect(signed(-0.0001)).toBe("0.000");
  });
  it("writes shares and counts the way a reader expects", () => {
    expect(share(0.9333)).toBe("93%");
    expect(count(10000)).toBe("10,000");
    expect(share(0.0015)).toBe("under 1%");
    expect(share(0)).toBe("0%");
  });
  it("turns proposal ids into names", () => {
    expect(title("raise-four-eyes-limit")).toBe("Raise four eyes limit");
  });
});
