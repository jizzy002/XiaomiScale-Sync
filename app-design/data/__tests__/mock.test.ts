import { describe, expect, test } from "vitest";
import {
  connectedAccounts,
  getHomeState,
  getStatsState,
  profile,
  syncStatus,
  weighIns,
} from "../mock";

describe("mock repository consistency", () => {
  test("every non-null delta equals weight minus the next older weight", () => {
    for (let i = 0; i < weighIns.length - 1; i++) {
      const w = weighIns[i];
      const older = weighIns[i + 1];
      if (w.deltaKg !== null) {
        expect(w.deltaKg, `delta of ${w.id}`).toBeCloseTo(
          w.weightKg - older.weightKg,
          5,
        );
      }
    }
  });

  test("week delta equals current minus oldest reading", () => {
    const expected =
      weighIns[0].weightKg - weighIns[weighIns.length - 1].weightKg;
    expect(getHomeState().weekDeltaKg).toBeCloseTo(expected, 5);
    expect(getStatsState().trend.weekDeltaKg).toBeCloseTo(expected, 5);
  });

  test("trend counts match the dataset", () => {
    const { trend } = getStatsState();
    expect(trend.readingCount).toBe(weighIns.length);
    expect(trend.unknownCount).toBe(weighIns.filter((w) => w.excluded).length);
    expect(profile.readingCount).toBe(weighIns.length);
  });

  test("every weigh-in carries a source and a positive weight", () => {
    for (const w of weighIns) {
      expect(w.source.length).toBeGreaterThan(0);
      expect(w.weightKg).toBeGreaterThan(0);
    }
    expect(syncStatus.healthy).toBe(true);
    expect(connectedAccounts.every((a) => a.connected)).toBe(true);
  });
});
