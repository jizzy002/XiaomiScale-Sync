import { WeighInList } from "@/components/WeighInList";
import { weighIns } from "@/data/mock";
import { connection } from "next/server";

// Renders at request time — weigh-in labels are relative to `new Date()`.
export const instant = false;

export default async function HistoryPage() {
  await connection();
  return (
    <div className="space-y-4 pt-2">
      <div className="flex items-center justify-between">
        <h2 className="text-[15px] font-bold text-text-primary">
          Synced Weigh-ins
        </h2>
        <span className="text-[12px] font-semibold text-link">See all</span>
      </div>
      <WeighInList weighIns={weighIns} />
    </div>
  );
}
