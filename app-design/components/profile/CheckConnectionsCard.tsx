export function CheckConnectionsCard() {
  return (
    <section className="flex items-center justify-between rounded-xl bg-surface-card p-5 ring-1 ring-border-soft">
      <div>
        <h3 className="text-[14px] font-bold text-text-primary">
          Check connections
        </h3>
        <p className="mt-1 text-[12px] text-text-faint">
          Verify all integrations are in sync
        </p>
      </div>
      <span className="rounded-full bg-success/10 px-2.5 py-1 text-[11px] font-semibold text-success">
        Healthy
      </span>
    </section>
  );
}
