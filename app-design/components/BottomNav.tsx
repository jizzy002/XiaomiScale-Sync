"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { BarChart3, History, House, User } from "lucide-react";

const items = [
  { href: "/home", label: "Home", icon: House },
  { href: "/history", label: "History", icon: History },
  { href: "/stats", label: "Stats", icon: BarChart3 },
  { href: "/profile", label: "Profile", icon: User },
];

export function BottomNav() {
  const pathname = usePathname();

  return (
    <nav className="absolute bottom-6 left-1/2 flex -translate-x-1/2 items-center gap-1 rounded-full bg-white px-2 py-1.5 shadow-[0_8px_24px_rgba(17,24,39,0.12)] ring-1 ring-black/5">
      {items.map(({ href, label, icon: Icon }) => {
        const active = pathname === href;
        return (
          <Link
            key={href}
            href={href}
            aria-current={active ? "page" : undefined}
            className={
              active
                ? "flex flex-col items-center gap-0.5 rounded-full bg-bg px-4 py-1.5 text-brand"
                : "flex flex-col items-center gap-0.5 rounded-full px-4 py-1.5 text-text-faint transition-colors hover:text-text-muted"
            }
          >
            <Icon className="h-[22px] w-[22px]" strokeWidth={active ? 2.4 : 2} />
            <span className="text-[10px] font-semibold">{label}</span>
          </Link>
        );
      })}
    </nav>
  );
}
