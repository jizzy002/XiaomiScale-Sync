import { CheckCircle2 } from "lucide-react";
import { formatDateLabel } from "@/lib/format";
import type { SyncStatus } from "@/lib/types";

export function SyncStatusCard({ sync }: { sync: SyncStatus }) {
  const time = sync.lastSync.toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit",
  });
  return (
    <section className="rounded-xl bg-surface-card p-5 ring-1 ring-border-soft">
      <div className="flex items-center justify-between">
        <h3 className="text-[14px] font-bold text-text-primary">
          Sync status
        </h3>
        <span
          className={`inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-[11px] font-semibold ${
            sync.healthy ? "bg-success/10 text-success" : "bg-danger/10 text-danger"
          }`}
        >
          <CheckCircle2 className="h-3 w-3" strokeWidth={2.5} />
          {sync.healthy ? "Healthy" : "Needs attention"}
        </span>
      </div>
      <p className="mt-3 text-[13px] text-text-muted">
        <span
          className={`mr-1.5 inline-block h-2 w-2 align-middle rounded-full ${
            sync.healthy ? "bg-success" : "bg-danger"
          }`}
        />
        {sync.message} · {formatDateLabel(sync.lastSync, new Date())} {time}
      </p>
    </section>
  );
}
