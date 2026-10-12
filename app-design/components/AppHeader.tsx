import { Scale, Settings } from "lucide-react";

export function AppHeader() {
  return (
    <header className="flex items-center justify-between px-5 pb-2 pt-6">
      <div className="flex items-center gap-2.5">
        <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-brand">
          <Scale className="h-5 w-5 text-text-on-dark" strokeWidth={2.25} />
        </div>
        <span className="text-[17px] font-bold tracking-tight text-text-primary">
          Scale Sync
        </span>
      </div>
      <button
        type="button"
        aria-label="Settings"
        className="flex h-9 w-9 items-center justify-center rounded-full bg-surface-card ring-1 ring-border-soft transition-colors hover:bg-bg"
      >
        <Settings className="h-[18px] w-[18px] text-text-muted" />
      </button>
    </header>
  );
}
