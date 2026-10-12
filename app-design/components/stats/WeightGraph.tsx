import { linePath, plotSeries } from "@/lib/chart";
import type { WeighIn } from "@/lib/types";

const BOX = { width: 322, height: 170, padding: 14 };

/**
 * Weight-history graph: your readings form an orange line, excluded
 * readings render as gray dots. Pure SVG from `lib/chart` math —
 * ports to a Compose Canvas by reusing the same plotSeries function.
 */
export function WeightGraph({ weighIns }: { weighIns: WeighIn[] }) {
  const chronological = [...weighIns].reverse();
  const { points } = plotSeries(
    chronological.map((w) => w.weightKg),
    BOX,
  );

  const included = points.filter((_, i) => !chronological[i].excluded);
  const excluded = points.filter((_, i) => chronological[i].excluded);

  return (
    <div className="rounded-xl bg-white p-5 ring-1 ring-border-soft">
      <div className="flex items-center justify-between">
        <h3 className="text-[14px] font-bold text-text-primary">
          Weight history
        </h3>
        <span className="rounded-full bg-bg px-2.5 py-1 text-[11px] font-semibold text-text-muted">
          7 days
        </span>
      </div>

      <svg
        viewBox={`0 0 ${BOX.width} ${BOX.height}`}
        className="mt-4 w-full"
        role="img"
        aria-label="Weight over the last 7 days"
      >
        {[0.25, 0.5, 0.75].map((f) => (
          <line
            key={f}
            x1={BOX.padding}
            x2={BOX.width - BOX.padding}
            y1={BOX.padding + (BOX.height - BOX.padding * 2) * f}
            y2={BOX.padding + (BOX.height - BOX.padding * 2) * f}
            stroke="#E5E7EB"
            strokeWidth={1}
            strokeDasharray="3 4"
          />
        ))}

        <path d={linePath(included)} fill="none" stroke="#FF6B35" strokeWidth={2.5} strokeLinecap="round" strokeLinejoin="round" />

        {included.map((p, i) => (
          <circle key={`m${i}`} cx={p.x} cy={p.y} r={4} fill="#FF6B35" stroke="#fff" strokeWidth={1.5} />
        ))}
        {excluded.map((p, i) => (
          <circle key={`e${i}`} cx={p.x} cy={p.y} r={4} fill="#D1D5DB" stroke="#fff" strokeWidth={1.5} />
        ))}
      </svg>

      <div className="mt-3 flex items-center gap-4">
        <span className="inline-flex items-center gap-1.5 text-[12px] font-medium text-text-muted">
          <span className="h-2.5 w-2.5 rounded-full bg-brand" />
          Mine
        </span>
        <span className="inline-flex items-center gap-1.5 text-[12px] font-medium text-text-muted">
          <span className="h-2.5 w-2.5 rounded-full bg-border-lighter" />
          Excluded
        </span>
      </div>

      <p className="mt-3 text-[12px] leading-relaxed text-text-faint">
        Readings without a known source are excluded from your trend.
        Review them in History to include them.
      </p>
    </div>
  );
}
