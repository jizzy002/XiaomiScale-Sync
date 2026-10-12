const WEEKDAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
const MONTHS = [
  "Jan",
  "Feb",
  "Mar",
  "Apr",
  "May",
  "Jun",
  "Jul",
  "Aug",
  "Sep",
  "Oct",
  "Nov",
  "Dec",
];

/** Formats a weight in kg with one decimal, e.g. "72.4 kg". */
export function formatWeight(kg: number): string {
  return `${kg.toFixed(1)} kg`;
}

/**
 * Formats a delta in kg: "+0.3 kg", "\u22120.2 kg", "0.0 kg",
 * or an em dash when there is no previous reading.
 */
export function formatDelta(deltaKg: number | null): string {
  if (deltaKg === null) return "\u2014";
  if (deltaKg > 0) return `+${deltaKg.toFixed(1)} kg`;
  if (deltaKg < 0) return `\u2212${Math.abs(deltaKg).toFixed(1)} kg`;
  return `${deltaKg.toFixed(1)} kg`;
}

export type DeltaTone = "down" | "up" | "flat" | "none";

/** Classifies a delta for coloring: weight loss is "down", gain "up". */
export function deltaTone(deltaKg: number | null): DeltaTone {
  if (deltaKg === null) return "none";
  if (deltaKg < 0) return "down";
  if (deltaKg > 0) return "up";
  return "flat";
}

function calendarDay(d: Date): number {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
}

/**
 * Short label for a weigh-in date relative to today:
 * "Today", "Yest.", short weekday within the same year,
 * or "Dec 31, 2025" across year boundaries.
 */
export function formatDateLabel(date: Date, today: Date): string {
  const dayMs = 24 * 60 * 60 * 1000;
  const diffDays = Math.round((calendarDay(today) - calendarDay(date)) / dayMs);
  if (diffDays === 0) return "Today";
  if (diffDays === 1) return "Yest.";
  if (date.getFullYear() === today.getFullYear()) return WEEKDAYS[date.getDay()];
  return formatFullDate(date);
}

/** Full date with abbreviated month, e.g. "Sep 28, 2026". */
export function formatFullDate(date: Date): string {
  return `${MONTHS[date.getMonth()]} ${date.getDate()}, ${date.getFullYear()}`;
}
