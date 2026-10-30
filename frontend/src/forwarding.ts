export interface ForwardingRule {
  domain: string;
  transport: "DNS" | "DOT";
  forward: "only" | "first";
  port: number;
  addresses: string[];
  tlsServerName: string;
  description: string;
}
export interface ForwardingState {
  enabled: boolean;
  consistent: boolean;
  writable: boolean;
  revision: string;
  error?: string;
  rules: ForwardingRule[];
  nodes: {
    id: string;
    label: string;
    online: boolean;
    drift?: boolean;
    pending?: unknown;
    error?: string;
  }[];
}
export interface ForwardingChange {
  operation: "create" | "update" | "delete" | "restore";
  revision: string;
  originalDomain?: string;
  rule?: ForwardingRule;
  backupId?: string;
}
export interface ForwardingBackup {
  id: string;
  createdAt: string;
  domain: string;
  actor: string;
  operation: string;
  phase: string;
  before: ForwardingRule[];
  after: ForwardingRule[];
}
