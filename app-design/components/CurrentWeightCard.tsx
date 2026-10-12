import { Check } from "lucide-react";
import { DarkCard } from "@/components/DarkCard";
import { DeltaPill } from "@/components/DeltaPill";
import type { HomeUiState } from "@/lib/types";

export function CurrentWeightCard({ state }: { state: HomeUiState }) {
  return (
    <DarkCard>
      <div className="flex items-start justify-between">
        <span className="text-[13px] font-medium text-text-faint">
          Current Weight
        </span>
        {state.synced && (
          <span className="inline-flex items-center gap-1 rounded-full bg-success/15 px-2.5 py-1 text-[11px] font-semibold text-success">
            <Check className="h-3 w-3" strokeWidth={3} />
            Synced
          </span>
        )}
      </div>
      <p className="mt-3 text-[40px] font-bold leading-none tracking-tight">
        {state.currentKg.toFixed(1)}
        <span className="ml-1.5 text-[18px] font-medium text-text-faint">
          kg
        </span>
      </p>
      <div className="mt-4 flex items-center gap-2.5">
        <span className="text-[12px] font-medium text-text-faint">Trend</span>
        <DeltaPill delta={state.weekDeltaKg} />
      </div>
    </DarkCard>
  );
}
