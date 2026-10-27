export interface Zone {
  id: string;
  name: string;
  description: string;
  readOnly: boolean;
  primary: string;
  secondaries: string[];
  serial?: number;
  recordCount?: number;
  error?: string;
}
export interface Rrset {
  name: string;
  type: string;
  ttl: number;
  values: string[];
  revision: string;
  editable: boolean;
  protection?: string;
}
export interface ZoneRecords {
  zone: Zone;
  records: Rrset[];
}
export interface User {
  username: string;
  admin: boolean;
}
export interface Meta {
  name: string;
  version: string;
  demo: boolean;
}
export interface AuditEvent {
  id: number;
  occurredAt: string;
  actor: string;
  zoneId: string;
  action: string;
  recordName: string;
  recordType: string;
  beforeJson: string;
  afterJson: string;
  status: string;
  detail: string;
}
export interface ProbeResult {
  server: string;
  reachable: boolean;
  rcode?: string;
  serial?: number;
  answers: string[];
  elapsedMs: number;
  error?: string;
}

export interface DhcpNode {
  id: string;
  label: string;
  online: boolean;
  version?: string;
  mode?: string;
  role?: string;
  state?: string;
  inTouch?: boolean;
  leaseCount?: number;
  error?: string;
}
export interface DhcpOption {
  name?: string;
  code?: number;
  space?: string;
  data: string;
  "csv-format"?: boolean;
  "always-send"?: boolean;
  "never-send"?: boolean;
  [key: string]: unknown;
}
export interface ReservationSettings {
  router: string;
  dnsServers: string;
  domain: string;
  searchDomains: string;
  ntpServers: string;
  mtu: number | string | null;
  nextServer: string;
  serverHostname: string;
  bootFileName: string;
  clientClasses: string[];
  comment: string;
  extraOptions: DhcpOption[];
}
export interface Reservation extends ReservationSettings {
  mac: string;
  address: string;
  hostname: string;
  editable: boolean;
  subnetId?: number;
  inherited: Record<string, string>;
  effective: Record<string, string>;
}
export interface DhcpSubnet {
  id: number;
  prefix: string;
  pools: string[];
  router: string;
  dnsServers: string;
  domain: string;
  searchDomains: string;
  validLifetime: number;
  reservations: Reservation[];
  reservationDefaults: Record<string, string>;
}
export interface DhcpLease {
  address: string;
  mac: string;
  clientId: string;
  hostname: string;
  subnetId: number;
  expiresAt: number;
  state: number;
  active: boolean;
  ddnsForward: boolean;
  ddnsReverse: boolean;
  revision: string;
  seenOn: string[];
}
export interface DhcpState {
  clientClasses: string[];
  enabled: boolean;
  revision: string;
  aligned: boolean;
  writable: boolean;
  nodes: DhcpNode[];
  subnets: DhcpSubnet[];
  leases: DhcpLease[];
}
