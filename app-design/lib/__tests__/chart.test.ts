import { describe, expect, test } from "vitest";
import { linePath, plotSeries } from "../chart";

const box = { width: 354, height: 200, padding: 16 };

describe("plotSeries", () => {
  test("returns one point per value, equally spaced on x", () => {
    const { points } = plotSeries([72, 73, 74], box);
    expect(points).toHaveLength(3);
    const [a, b, c] = points;
    expect(a.x).toBe(box.padding);
    expect(c.x).toBe(box.width - box.padding);
    expect(b.x - a.x).toBeCloseTo(c.x - b.x);
  });

  test("maps the minimum value to the bottom inset and max to the top inset", () => {
    const { points, minY, maxY } = plotSeries([70, 80], box);
    expect(minY).toBe(70);
    expect(maxY).toBe(80);
    expect(points[0].y).toBeCloseTo(box.height - box.padding);
    expect(points[1].y).toBeCloseTo(box.padding);
  });

  test("a mid value lands halfway between the insets", () => {
    const { points } = plotSeries([70, 75, 80], box);
    expect(points[1].y).toBeCloseTo(box.height / 2);
  });

  test("all-equal values draw a horizontal mid line", () => {
    const { points, minY, maxY } = plotSeries([72.4, 72.4], box);
    expect(minY).toBe(72.4);
    expect(maxY).toBe(72.4);
    expect(points[0].y).toBeCloseTo(box.height / 2);
    expect(points[1].y).toBeCloseTo(box.height / 2);
  });

  test("single value lands on the mid line at first x", () => {
    const { points } = plotSeries([72.4], box);
    expect(points).toHaveLength(1);
    expect(points[0].x).toBe(box.padding);
    expect(points[0].y).toBeCloseTo(box.height / 2);
  });

  test("empty input yields no points and zero bounds", () => {
    const { points, minY, maxY } = plotSeries([], box);
    expect(points).toEqual([]);
    expect(minY).toBe(0);
    expect(maxY).toBe(0);
  });
});

describe("linePath", () => {
  test("builds an SVG path through all points", () => {
    const { points } = plotSeries([70, 80], box);
    expect(linePath(points)).toBe(
      `M ${points[0].x} ${points[0].y} L ${points[1].x} ${points[1].y}`,
    );
  });

  test("empty points yield an empty path", () => {
    expect(linePath([])).toBe("");
  });
});
