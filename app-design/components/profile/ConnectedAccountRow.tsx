import { Activity, Check, Smartphone } from "lucide-react";
import type { ConnectedAccount } from "@/lib/types";

const icons: Record<string, React.ElementType> = {
  garmin: Activity,
  mifit: Smartphone,
};

export function ConnectedAccountRow({ account }: { account: ConnectedAccount }) {
  const Icon = icons[account.id] ?? Smartphone;
  return (
    <li className="flex items-center gap-3 rounded-md bg-surface-card px-4 py-3 ring-1 ring-border-soft">
      <div className="flex h-10 w-10 items-center justify-center rounded-md bg-bg">
        <Icon className="h-5 w-5 text-text-muted" />
      </div>
      <div className="min-w-0 flex-1">
        <p className="truncate text-[13px] font-bold text-text-primary">
          {account.name}
        </p>
        <p className="truncate text-[11px] text-text-faint">
          {account.description}
        </p>
      </div>
      {account.connected ? (
        <span className="inline-flex items-center gap-1 text-[11px] font-semibold text-success">
          <Check className="h-3 w-3" strokeWidth={3} />
          Connected
        </span>
      ) : (
        <span className="text-[11px] font-semibold text-text-faint">
          Not connected
        </span>
      )}
    </li>
  );
}
