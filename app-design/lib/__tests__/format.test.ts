import { describe, expect, test } from "vitest";
import {
  deltaTone,
  formatDelta,
  formatDateLabel,
  formatFullDate,
  formatWeight,
} from "../format";

describe("formatWeight", () => {
  test("renders one decimal plus kg unit", () => {
    expect(formatWeight(72.4)).toBe("72.4 kg");
  });

  test("rounds to one decimal place", () => {
    expect(formatWeight(72.449)).toBe("72.4 kg");
    expect(formatWeight(72.45)).toBe("72.5 kg");
  });
});

describe("formatDelta", () => {
  test("negative delta uses typographic minus", () => {
    expect(formatDelta(-0.2)).toBe("\u22120.2 kg");
  });

  test("positive delta uses plus sign", () => {
    expect(formatDelta(0.3)).toBe("+0.3 kg");
  });

  test("zero delta has no sign", () => {
    expect(formatDelta(0)).toBe("0.0 kg");
  });

  test("null delta renders an em dash", () => {
    expect(formatDelta(null)).toBe("\u2014");
  });
});

describe("deltaTone", () => {
  test("negative delta is down", () => {
    expect(deltaTone(-0.2)).toBe("down");
  });

  test("positive delta is up", () => {
    expect(deltaTone(0.2)).toBe("up");
  });

  test("zero delta is flat", () => {
    expect(deltaTone(0)).toBe("flat");
  });

  test("null delta is none", () => {
    expect(deltaTone(null)).toBe("none");
  });
});

describe("formatDateLabel", () => {
  const today = new Date(2026, 9, 13); // Tue Oct 13 2026, local

  test("same calendar day is Today", () => {
    expect(formatDateLabel(new Date(2026, 9, 13, 7, 15), today)).toBe("Today");
  });

  test("previous calendar day is Yest.", () => {
    expect(formatDateLabel(new Date(2026, 9, 12, 7, 2), today)).toBe("Yest.");
  });

  test("earlier day in the same year is a short weekday", () => {
    expect(formatDateLabel(new Date(2026, 9, 11, 8, 0), today)).toBe("Sun");
    expect(formatDateLabel(new Date(2026, 9, 6, 8, 0), today)).toBe("Tue");
  });

  test("day in a previous year falls back to full date", () => {
    expect(formatDateLabel(new Date(2025, 11, 31, 8, 0), today)).toBe(
      "Dec 31, 2025",
    );
  });
});

describe("formatFullDate", () => {
  test("renders abbreviated month, day, and year", () => {
    expect(formatFullDate(new Date(2026, 8, 28, 6, 30))).toBe(
      "Sep 28, 2026",
    );
  });
});
