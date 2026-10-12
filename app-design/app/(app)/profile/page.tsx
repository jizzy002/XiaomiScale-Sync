import { AccountCard } from "@/components/profile/AccountCard";
import { ConnectedAccountRow } from "@/components/profile/ConnectedAccountRow";
import { SyncStatusCard } from "@/components/profile/SyncStatusCard";
import { getProfileState } from "@/data/mock";
import { connection } from "next/server";

// Renders at request time — sync status is relative to `new Date()`.
export const instant = false;

export default async function ProfilePage() {
  await connection();
  const { profile, sync, accounts } = getProfileState();
  return (
    <div className="space-y-4 pt-2">
      <div>
        <h2 className="mb-3 text-[15px] font-bold text-text-primary">
          Account details
        </h2>
        <AccountCard profile={profile} />
      </div>

      <SyncStatusCard sync={sync} />

      <div>
        <h2 className="mb-3 text-[15px] font-bold text-text-primary">
          Connected accounts
        </h2>
        <ul className="space-y-2.5">
          {accounts.map((a) => (
            <ConnectedAccountRow key={a.id} account={a} />
          ))}
        </ul>
      </div>

      <section className="flex items-center justify-between rounded-xl bg-white p-5 ring-1 ring-border-soft">
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
    </div>
  );
}
