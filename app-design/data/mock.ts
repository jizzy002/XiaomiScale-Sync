import type {
  ConnectedAccount,
  HomeUiState,
  ProfileUiState,
  StatsUiState,
  SyncStatus,
  UserProfile,
  WeighIn,
} from "@/lib/types";

/*
 * Mock repository — stands in for the future data layer. Dates are
 * generated relative to "now" so the Home/History lists always read
 * "Today" / "Yest." like the design. Selectors return plain UI state,
 * the same shape a Compose ViewModel would expose.
 */

function daysAgo(days: number, hour: number, minute: number): Date {
  const d = new Date();
  d.setDate(d.getDate() - days);
  d.setHours(hour, minute, 0, 0);
  return d;
}

export const weighIns: WeighIn[] = [
  {
    id: "w7",
    timestamp: daysAgo(0, 7, 15),
    weightKg: 72.4,
    deltaKg: -0.2,
    excluded: false,
    source: "Xiaomi Mi Fit",
  },
  {
    id: "w6",
    timestamp: daysAgo(1, 7, 2),
    weightKg: 72.6,
    deltaKg: 0.2,
    excluded: false,
    source: "Xiaomi Mi Fit",
  },
  {
    id: "w5",
    timestamp: daysAgo(2, 7, 20),
    weightKg: 72.4,
    deltaKg: -0.3,
    excluded: false,
    source: "Xiaomi Mi Fit",
  },
  {
    id: "w4",
    timestamp: daysAgo(3, 7, 8),
    weightKg: 72.7,
    deltaKg: 0.3,
    excluded: false,
    source: "Xiaomi Mi Fit",
  },
  {
    id: "w3",
    timestamp: daysAgo(4, 7, 12),
    weightKg: 72.4,
    deltaKg: null,
    excluded: true,
    source: "Unknown",
  },
  {
    id: "w2",
    timestamp: daysAgo(5, 7, 5),
    weightKg: 72.5,
    deltaKg: null,
    excluded: true,
    source: "Unknown",
  },
  {
    id: "w1",
    timestamp: daysAgo(6, 7, 30),
    weightKg: 72.4,
    deltaKg: null,
    excluded: true,
    source: "Unknown",
  },
];

export const profile: UserProfile = {
  name: "Alex Morgan",
  accountId: "XM-4821",
  plan: "Premium",
  region: "Europe",
  memberSince: "Mar 2025",
  readingCount: 7,
};

export const syncStatus: SyncStatus = {
  lastSync: daysAgo(0, 7, 16),
  healthy: true,
  message: "Sync successful",
};

export const connectedAccounts: ConnectedAccount[] = [
  {
    id: "garmin",
    name: "Garmin Connect",
    description: "Workouts & activities",
    connected: true,
  },
  {
    id: "mifit",
    name: "Xiaomi Mi Fit",
    description: "Scale weigh-ins",
    connected: true,
  },
];

export function getHomeState(): HomeUiState {
  return {
    currentKg: weighIns[0].weightKg,
    synced: true,
    weekDeltaKg: -0.1,
    recentWeighIns: weighIns.slice(0, 4),
  };
}

export function getStatsState(): StatsUiState {
  return {
    trend: {
      currentKg: weighIns[0].weightKg,
      readingCount: weighIns.length,
      unknownCount: weighIns.filter((w) => w.excluded).length,
      weekDeltaKg: -0.1,
    },
    weighIns,
  };
}

export function getProfileState(): ProfileUiState {
  return {
    profile,
    sync: syncStatus,
    accounts: connectedAccounts,
  };
}
