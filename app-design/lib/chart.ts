export interface ChartBox {
  width: number;
  height: number;
  padding: number;
}

export interface ChartPoint {
  x: number;
  y: number;
}

export interface ChartSeries {
  points: ChartPoint[];
  minY: number;
  maxY: number;
}

/**
 * Plots weight values into a chart box: points are equally spaced on x,
 * the minimum value maps to the bottom inset, the maximum to the top inset,
 * and all-equal values draw a horizontal mid line. Pure math — ports 1:1
 * to a Compose Canvas draw pass.
 */
export function plotSeries(
  values: readonly number[],
  box: ChartBox,
): ChartSeries {
  if (values.length === 0) {
    return { points: [], minY: 0, maxY: 0 };
  }

  const minY = Math.min(...values);
  const maxY = Math.max(...values);
  const innerWidth = box.width - box.padding * 2;
  const innerHeight = box.height - box.padding * 2;
  const span = maxY - minY;

  const points = values.map((value, i) => {
    const x =
      values.length === 1
        ? box.padding
        : box.padding + (innerWidth * i) / (values.length - 1);
    const y =
      span === 0
        ? box.height / 2
        : box.height - box.padding - ((value - minY) / span) * innerHeight;
    return { x, y };
  });

  return { points, minY, maxY };
}

/** Builds an SVG path string ("M x y L x y ...") through the points. */
export function linePath(points: readonly ChartPoint[]): string {
  if (points.length === 0) return "";
  return points
    .map((p, i) => `${i === 0 ? "M" : "L"} ${p.x} ${p.y}`)
    .join(" ");
}
