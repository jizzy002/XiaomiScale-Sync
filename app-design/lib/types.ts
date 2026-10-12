/*
 * Domain model — mirrors the future Compose UI-state classes:
 *   WeighIn        -> data model / entity
 *   HomeUiState    -> HomeScreen(state: HomeUiState)
 *   StatsUiState   -> StatsScreen(state: StatsUiState)
 *   ProfileUiState -> ProfileScreen(state: ProfileUiState)
 */

export interface WeighIn {
  id: string;
  timestamp: Date;
  weightKg: number;
  /** Delta vs the previous reading; null when unknown/first reading. */
  deltaKg: number | null;
  /** Excluded readings are shown gray on the graph and need review. */
  excluded: boolean;
  source: string;
}

export interface TrendSummary {
  currentKg: number;
  readingCount: number;
  unknownCount: number;
  weekDeltaKg: number;
}

export interface SyncStatus {
  lastSync: Date;
  healthy: boolean;
  message: string;
}

export interface ConnectedAccount {
  id: string;
  name: string;
  description: string;
  connected: boolean;
}

export interface UserProfile {
  name: string;
  accountId: string;
  plan: string;
  region: string;
  memberSince: string;
  readingCount: number;
}

export interface HomeUiState {
  currentKg: number;
  synced: boolean;
  weekDeltaKg: number;
  recentWeighIns: WeighIn[];
}

export interface StatsUiState {
  trend: TrendSummary;
  weighIns: WeighIn[];
}

export interface ProfileUiState {
  profile: UserProfile;
  sync: SyncStatus;
  accounts: ConnectedAccount[];
}
