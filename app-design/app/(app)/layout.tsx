import { AppHeader } from "@/components/AppHeader";
import { BottomNav } from "@/components/BottomNav";
import { PhoneFrame } from "@/components/PhoneFrame";

export default function AppLayout({ children }: { children: React.ReactNode }) {
  return (
    <PhoneFrame>
      <AppHeader />
      <main className="flex-1 overflow-y-auto px-5 pb-32">{children}</main>
      <BottomNav />
    </PhoneFrame>
  );
}
