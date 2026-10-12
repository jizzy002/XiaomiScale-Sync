import { ArrowDown, ArrowUp } from "lucide-react";
import { deltaTone, formatDelta } from "@/lib/format";

const toneClasses: Record<string, string> = {
  down: "bg-success/10 text-success",
  up: "bg-danger/10 text-danger",
  flat: "bg-bg text-text-muted",
  none: "bg-bg text-text-faint",
};

export function DeltaPill({ delta }: { delta: number | null }) {
  const tone = deltaTone(delta);
  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-[12px] font-semibold ${toneClasses[tone]}`}
    >
      {tone === "down" && <ArrowDown className="h-3 w-3" strokeWidth={2.5} />}
      {tone === "up" && <ArrowUp className="h-3 w-3" strokeWidth={2.5} />}
      {formatDelta(delta)}
    </span>
  );
}
