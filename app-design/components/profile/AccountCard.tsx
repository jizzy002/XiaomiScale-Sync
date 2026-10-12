import { DarkCard } from "@/components/DarkCard";
import type { UserProfile } from "@/lib/types";

function initials(name: string): string {
  return name
    .split(" ")
    .map((part) => part[0])
    .join("")
    .slice(0, 2)
    .toUpperCase();
}

export function AccountCard({ profile }: { profile: UserProfile }) {
  return (
    <DarkCard>
      <div className="flex items-center gap-3">
        <div className="flex h-12 w-12 items-center justify-center rounded-full bg-brand/15 text-[15px] font-bold text-brand">
          {initials(profile.name)}
        </div>
        <div>
          <p className="text-[16px] font-bold">{profile.name}</p>
          <p className="text-[12px] text-gray-400">{profile.plan} member</p>
        </div>
      </div>
      <dl className="mt-5 grid grid-cols-2 gap-4 text-[13px]">
        <div>
          <dt className="text-gray-400">Account ID</dt>
          <dd className="mt-0.5 font-semibold">{profile.accountId}</dd>
        </div>
        <div>
          <dt className="text-gray-400">Plan</dt>
          <dd className="mt-0.5 font-semibold">{profile.plan}</dd>
        </div>
        <div>
          <dt className="text-gray-400">Region</dt>
          <dd className="mt-0.5 font-semibold">{profile.region}</dd>
        </div>
        <div>
          <dt className="text-gray-400">Member since</dt>
          <dd className="mt-0.5 font-semibold">{profile.memberSince}</dd>
        </div>
      </dl>
    </DarkCard>
  );
}
