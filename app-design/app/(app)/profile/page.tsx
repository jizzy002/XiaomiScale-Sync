import { AccountCard } from "@/components/profile/AccountCard";
import { CheckConnectionsCard } from "@/components/profile/CheckConnectionsCard";
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

      <CheckConnectionsCard />
    </div>
  );
}
