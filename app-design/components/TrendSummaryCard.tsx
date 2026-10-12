import { AlertTriangle } from "lucide-react";
import { DarkCard } from "@/components/DarkCard";
import { deltaTone, formatDelta, formatWeight } from "@/lib/format";
import type { TrendSummary } from "@/lib/types";

const toneClasses: Record<string, string> = {
  down: "text-success",
  up: "text-danger",
  flat: "text-text-on-dark",
  none: "text-text-on-dark",
};

export function TrendSummaryCard({ trend }: { trend: TrendSummary }) {
  return (
    <DarkCard>
      <span className="text-[13px] font-medium text-text-faint">
        Trend summary
      </span>
      <p className="mt-3 text-[36px] font-bold leading-none tracking-tight">
        {formatWeight(trend.currentKg)}
      </p>
      <dl className="mt-4 space-y-2.5 text-[13px]">
        <div className="flex items-center justify-between">
          <dt className="text-text-faint">Readings</dt>
          <dd className="font-semibold">{trend.readingCount} this week</dd>
        </div>
        <div className="flex items-center justify-between">
          <dt className="text-text-faint">Weight change</dt>
          <dd
            className={`font-semibold ${toneClasses[deltaTone(trend.weekDeltaKg)]}`}
          >
            {formatDelta(trend.weekDeltaKg)}
          </dd>
        </div>
        <div className="flex items-center justify-between">
          <dt className="text-text-faint">Unknown readings</dt>
          <dd className="inline-flex items-center gap-1.5 font-semibold text-brand">
            <AlertTriangle className="h-3.5 w-3.5" strokeWidth={2.5} />
            {trend.unknownCount} need review
          </dd>
        </div>
      </dl>
    </DarkCard>
  );
}
