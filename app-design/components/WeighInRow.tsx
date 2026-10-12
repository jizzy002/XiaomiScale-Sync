import { DeltaPill } from "@/components/DeltaPill";
import { formatDateLabel, formatWeight } from "@/lib/format";
import type { WeighIn } from "@/lib/types";

export function WeighInRow({ weighIn }: { weighIn: WeighIn }) {
  const today = new Date();
  return (
    <li className="flex items-center justify-between rounded-md bg-white px-4 py-3 ring-1 ring-border-soft">
      <div className="flex h-10 items-center rounded-md bg-bg px-3">
        <span className="text-[12px] font-semibold text-text-muted">
          {formatDateLabel(weighIn.timestamp, today)}
        </span>
      </div>
      <span className="text-[15px] font-bold text-text-primary">
        {formatWeight(weighIn.weightKg)}
      </span>
      <DeltaPill delta={weighIn.deltaKg} />
    </li>
  );
}
