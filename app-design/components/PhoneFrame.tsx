export function PhoneFrame({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-screen items-center justify-center p-6">
      <div className="relative flex h-[874px] w-[402px] flex-col overflow-hidden rounded-[40px] bg-bg shadow-2xl ring-1 ring-black/5">
        {children}
      </div>
    </div>
  );
}
