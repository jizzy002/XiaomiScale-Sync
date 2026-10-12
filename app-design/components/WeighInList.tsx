import { WeighInRow } from "@/components/WeighInRow";
import type { WeighIn } from "@/lib/types";

export function WeighInList({ weighIns }: { weighIns: WeighIn[] }) {
  return (
    <ul className="space-y-2.5">
      {weighIns.map((w) => (
        <WeighInRow key={w.id} weighIn={w} />
      ))}
    </ul>
  );
}
