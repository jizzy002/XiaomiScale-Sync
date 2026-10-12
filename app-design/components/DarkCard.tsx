export function DarkCard({ children }: { children: React.ReactNode }) {
  return (
    <div className="rounded-xl bg-surface-dark p-5 text-white">{children}</div>
  );
}
