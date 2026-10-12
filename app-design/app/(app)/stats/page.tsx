import { TrendSummaryCard } from "@/components/TrendSummaryCard";
import { WeightGraph } from "@/components/stats/WeightGraph";
import { getStatsState } from "@/data/mock";
import { connection } from "next/server";

// Renders at request time — graph labels are relative to `new Date()`.
export const instant = false;

export default async function StatsPage() {
  await connection();
  const state = getStatsState();
  return (
    <div className="space-y-4 pt-2">
      <TrendSummaryCard trend={state.trend} />
      <WeightGraph weighIns={state.weighIns} />
    </div>
  );
}
