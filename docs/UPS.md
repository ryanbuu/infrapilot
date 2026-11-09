# UPS / Nutify integration

InfraPilot adds 16 native Ext JS Modern pages for the deployed Nutify 0.3.0 API. The frontend uses the official Material light theme and official Sencha Charts package, with no custom CSS, iframe, React, or embedded Nutify frontend.

## Features

| Page | Functions |
| --- | --- |
| 电源概览 | Current supply state, power, load, charge, automatically refreshed curves |
| 电池 | Charge, current-load runtime estimate, voltage, actual reported temperature, recent test/status interpretation, history and statistics |
| 功率与负载 | Current readings, curves, period statistics, outlet groups |
| 电压 | Input/output readings, history, thresholds and period statistics |
| 能耗与费用 | Energy, cost, carbon emissions, distribution, trend and detailed queries |
| 历史与设备信息 | Sampling table, static information, per-variable queries, JSON/CSV exports |
| 事件 | NUT/UPS history, acknowledge, bulk acknowledge, delete and bulk delete |
| 设备管理 | Multiple targets, connection tests, polling, primary/enable controls, location and retention/storage policy, fleet report |
| 设备控制 | Supported commands, explicit execution preview, command history and statistics |
| 可写变量 | Variable catalogue, changes, per-variable history and history cleanup |
| 报告导出 | Local report generation by time interval, downloadable report data/HTML; no sending |
| 采样与计算 | Sampling frequency, instance settings, currency/cost/carbon factors, power/energy formulas, canonical variable mapping |
| 自动化脚本 | Script creation, editing, trigger/threshold/cooldown settings, deletion and explicit test execution |
| NUT 接入与配置 | NUT file editor/documentation, driver discovery, USB/serial/network setup forms, connection/configuration checks and previews, service status/logs/control |
| 用户与单点登录 | User creation/deletion, roles/passwords, page/settings permissions, OIDC provider configuration/discovery/registration |
| 系统维护 | Runtime information, resource statistics, log settings/filter/download/cleanup, database statistics/backup/maintenance and application restart |

The operation catalog is pinned to Nutify source commit `943c5794773c4a39fb17462a0e4f4014a9c86e73`, and the actual deployed API catalog is compared during integration. Capabilities depend on the UPS driver and the NUT account's existing permissions; unsupported commands are not granted additional privileges.

Excluded at the user's request: email, Telegram, Ntfy, Webhook, sending tests, report delivery/scheduling and notification integration scripts. No notification API can be invoked through the InfraPilot operation broker. Existing independent Nutify notification settings are not modified.

## Connection

Configure the backend only:

```
INFRAPILOT_UPS_ENABLED=true
INFRAPILOT_UPS_URL=http://nutify-host:8099
INFRAPILOT_UPS_USERNAME=<existing Nutify administrator>
INFRAPILOT_UPS_PASSWORD=<secret>
INFRAPILOT_UPS_BACKUP_DIRECTORY=/app/data/ups
```

Use the private deployment `.env`; never commit credentials. The URL is a fixed HTTP(S) origin. Nutify's session cookie stays on the Java backend. The browser uses InfraPilot authentication/CSRF and never receives the broker credentials. UPS endpoints require InfraPilot administrator access.

Changing/deleting the account used by the broker is blocked in this UI to prevent breaking the connection. Rotate this account and its deployment credential together. OIDC/user permissions here apply to Nutify, while InfraPilot authentication remains independent.

Each request carries an explicit device scope, including both query and body where needed. No shared active-target session state is changed. Configuration writes require a five-minute, single-use, actor-bound preview token. Where an existing configuration is readable, it is rechecked before execution. Ambiguous write failures are not automatically retried.

## Data interpretation

- Charge is not battery capacity health. The UI reports actual status/test evidence; absence of evidence is unknown.
- Missing temperature remains “未上报”. A real zero reading remains zero.
- Power may be a Nutify-calculated value; the tooltip states this. Missing real power can be estimated only from available load and nominal wattage.
- Missing chart readings and timestamps are never invented. Nutify 0.3.0 static/dynamic table serialization incorrectly attaches the configured local zone to UTC database wall-clock values. The adapter normalizes these two table responses to UTC, verified against the authoritative history endpoint epochs; all other offset-bearing timestamps retain their original offset. Charts and tables display the browser’s local timezone.
- Configuration/script file contents are visible only in administrator editing contexts; audit previews redact them and JSON secret fields.

## Backups and rollback

Every executed write creates a private JSON snapshot in `/app/data/ups` (directory 0700, files 0600) and a redacted audit entry in the existing InfraPilot PostgreSQL database. Restore configuration using the corresponding form and the saved `before` values. Device commands, script executions, restarts and deleted history are not undone by restoring configuration. Download a Nutify database backup before history/database maintenance.

Application deployment separately saves the previous frontend/backend images, files, Compose environment and shared database backup, and creates `rollback.sh`. This integration does not replace the Nutify/NUT containers or alter PVE/DSM shutdown policies. The NUT service controls refer to the Nutify instance; remotely managed NUT services and OS shutdown policies are separate.

## Validation

- `mvn -f backend/pom.xml verify`
- `cd frontend-extjs && npm test && npm run test:ups`
- `BROWSER=webkit npm run test:ups`
- Existing full browser and Application Gateway form regression tests remain required.
- UI mutations are tested only with the isolated demo backend. Live validation uses read-only endpoints and report generation; it never executes UPS commands, scripts, restarts, shutdowns or sends notifications.

To regenerate the operation/form catalog, obtain the pinned Nutify source and a matching `/api/frontend/catalog` response, then run `deploy/nutify/build-catalog.py --source <nutify Python root> --catalog <catalog.json> --output backend/src/main/resources/ups/catalog.json`. Review new routes and payloads before enabling them. Notifications stay excluded.

Nutify source is MIT licensed; see `docs/licenses/Nutify-LICENSE`. Ext JS and Charts use the existing project's official GPL SDK license and checksum-pinned installation script.
