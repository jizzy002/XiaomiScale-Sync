import { CurrentWeightCard } from "@/components/CurrentWeightCard";
import { WeighInList } from "@/components/WeighInList";
import { getHomeState } from "@/data/mock";
import { connection } from "next/server";

// This route renders at request time (mock dates via `new Date()`),
// so navigations into it block on the server rather than serving an
// instant static shell.
export const instant = false;

export default async function HomePage() {
  // Mock data derives Today/Yest. labels from `new Date()` — opt out of
  // prerendering so dates reflect the day the page is viewed.
  await connection();
  const state = getHomeState();
  return (
    <div className="space-y-6 pt-2">
      <CurrentWeightCard state={state} />
      <section>
        <h2 className="mb-3 text-[15px] font-bold text-text-primary">
          Synced Weigh-ins
        </h2>
        <WeighInList weighIns={state.recentWeighIns} />
      </section>
    </div>
  );
}
