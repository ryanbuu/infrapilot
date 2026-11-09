import { api, encode } from "./api";
import { createSchemaForm } from "./gateway-form";
import {
  upsPages,
  pageFor,
  title,
  numeric,
  reading,
  timestampMillis,
  normalizeTableResponse,
  batteryCondition,
  powerReading,
  unwrap,
  rowsOf,
  flatten,
  chartRows,
  schemaDefaults,
} from "./ups-model";
import {
  col,
  field,
  toolbar,
  mobile,
  metricFields,
  dialog,
  download,
  showError,
} from "./ui";
const primary = {
  battery: "/api/battery/metrics",
  power: "/api/power/metrics",
  voltage: "/api/voltage/metrics",
  energy: "/api/energy/data",
  history: "/api/table/dynamic",
  events: "/api/table/events",
  devices: "/api/multi-nut/targets",
  commands: "/api/upscmd/list",
  variables: "/api/upsrw/list",
  reports: "/api/multi-nut/report",
  settings: "/api/settings/variables",
  scripts: "/api/options/script-actions",
  nut: "/api/advanced/nut/files",
  users: "/auth/api/admin/users",
  system: "/api/system/info",
};
const cellValue = (v) =>
  v === null || v === undefined
    ? "未上报"
    : typeof v === "boolean"
      ? v
        ? "是"
        : "否"
      : typeof v === "object"
        ? Object.entries(v)
            .map(([k, x]) => `${title(k)}: ${typeof x === "object" ? "…" : x}`)
            .join(" · ")
        : String(v);
const csv = (rows) => {
  const keys = [...new Set(rows.flatMap(Object.keys))];
  return (
    "\ufeff" +
    [keys, ...rows.map((r) => keys.map((k) => cellValue(r[k])))]
      .map((r) =>
        r.map((v) => '"' + String(v).replaceAll('"', '""') + '"').join(","),
      )
      .join("\r\n")
  );
};
export default function createUpsWorkspace({ route, user }) {
  const section = route.slice(4),
    info = upsPages.find((p) => p[0] === section) || upsPages[0];
  let catalog = [],
    targets = [],
    target = Number(sessionStorage.getItem("ups-target")) || 0,
    selected = null,
    data = null,
    loadSeq = 0,
    loading = false,
    filter = "",
    auto = true,
    metricContainer = null,
    shownMetrics = [],
    displayCharts = [],
    compact = mobile();
  const windows = new Set(),
    track = (win) => {
      windows.add(win);
      win.on("destroy", () => windows.delete(win));
      return win;
    };
  const content = Ext.create("Ext.Container", { flex: 1, layout: "fit" });
  const actions = Ext.create("Ext.Toolbar", {
    cls: "tools-actions",
    defaultButtonUI: "default",
    scrollable: "horizontal",
  });
  const caption = Ext.create("Ext.Component", { html: "正在读取…" });
  const device = Ext.create("Ext.field.Select", {
    label: "设备",
    labelAlign: "left",
    labelWidth: 45,
    width: mobile() ? 200 : 360,
    options: [],
    listeners: {
      change: (_, v) => {
        if (Number(v) !== target) {
          target = Number(v);
          sessionStorage.setItem("ups-target", String(target));
          load();
        }
      },
    },
  });
  const page = Ext.create("Ext.Panel", {
    cls: "workspace ups-workspace",
    title: info[1],
    iconCls: "x-fa fa-" + info[2],
    layout: "vbox",
    tools: [
      { iconCls: "x-fa fa-sync", tooltip: "刷新", handler: () => load() },
      {
        iconCls: "x-fa fa-question-circle",
        tooltip: "数据来自 Nutify；修改需预览确认，操作记录在审计中。",
      },
    ],
    items: [
      toolbar(
        [
          device,
          { xtype: "spacer" },
          {
            xtype: "checkboxfield",
            label: "自动刷新",
            labelAlign: "left",
            labelWidth: 70,
            checked: true,
            hidden: ![
              "overview",
              "battery",
              "power",
              "voltage",
              "energy",
            ].includes(section),
            listeners: { change: (_, v) => (auto = v) },
          },
        ],
        { docked: "top" },
      ),
      toolbar(
        [
          {
            xtype: "searchfield",
            placeholder: "搜索当前列表",
            flex: 1,
            listeners: {
              change: (_, v) => {
                filter = String(v || "").toLowerCase();
                render();
              },
            },
          },
        ],
        { docked: "top" },
      ),
      actions,
      content,
      toolbar([caption], { docked: "bottom" }),
    ],
  });
  function opAt(path, method = "GET") {
    return catalog.find((o) => o.path === path && o.method === method);
  }
  async function query(op, input = {}) {
    if (!op) throw Error("当前版本未提供此接口");
    const response = await api("/ups/query/" + op.id, {
      targetId: target,
      ...input,
    });
    return {
      ...response,
      data: normalizeTableResponse(op.path, response.data),
    };
  }
  async function read(path, queryParams = {}) {
    return (await query(opAt(path), { query: queryParams })).data;
  }
  function grid(rows, options = {}) {
    rows = rows.map((r, i) =>
      r && typeof r === "object"
        ? { ...r, __row: i }
        : { name: String(r), __row: i },
    );
    const preferred = [
      "id",
      "name",
      "username",
      "event_type",
      "event_message",
      "timestamp_utc",
      "timestamp",
      "host",
      "port",
      "value",
      "description",
      "type",
      "enabled",
      "acknowledged",
      "role",
      "is_primary",
    ];
    let keys = [...new Set(rows.flatMap((r) => Object.keys(r)))].filter(
      (k) =>
        ![
          "__row",
          "raw_json",
          "data_json",
          "html",
          "script_body",
          "content",
        ].includes(k),
    );
    keys = [
      ...preferred.filter((k) => keys.includes(k)),
      ...keys.filter((k) => !preferred.includes(k)),
    ];
    const columns = (options.keys || keys.slice(0, 10)).map((k) =>
      col(title(k), k, {
        minWidth: k.includes("message")
          ? 260
          : k.includes("timestamp")
            ? 185
            : 130,
        renderer: (v) =>
          encode(
            k.endsWith("_utc") && v
              ? new Date(timestampMillis(v, true)).toLocaleString()
              : cellValue(v),
          ),
      }),
    );
    if (!columns.length) columns.push(col("内容", "name"));
    return {
      xtype: "grid",
      cls: "native-grid ups-grid",
      store: {
        data: rows.filter((r) =>
          JSON.stringify(r).toLowerCase().includes(filter),
        ),
      },
      columns,
      selectable: { mode: "single" },
      emptyText: "暂无数据",
      columnLines: true,
      striped: true,
      variableHeights: true,
      scrollable: true,
      listeners: {
        select: (_, record) => {
          selected = record?.data || null;
        },
        childdoubletap: (_, location) => {
          if (location.record) showResult("详细信息", location.record.data);
        },
      },
    };
  }
  function details(value) {
    return grid(
      flatten(value).filter(
        (r) => !filter || `${r.name} ${r.value}`.toLowerCase().includes(filter),
      ),
      { keys: ["name", "value"] },
    );
  }
  function resultComponent(value) {
    const sections = [];
    const walk = (v, path = "结果", depth = 0) => {
      if (depth > 3) return;
      if (Array.isArray(v)) {
        if (
          v.some(
            (r) =>
              r &&
              typeof r === "object" &&
              (r.timestamp !== undefined || r.x !== undefined) &&
              (r.value !== undefined || r.y !== undefined),
          )
        ) {
          const points = v.map((r) => ({
            timestamp: r.timestamp ?? r.x,
            value: r.value ?? r.y,
          }));
          sections.push({
            title: path + " · 曲线",
            layout: "fit",
            items: [plot(points, ["value"], path)],
          });
        }
        sections.push({ title: path, layout: "fit", items: [grid(v)] });
        return;
      }
      if (!v || typeof v !== "object") return;
      for (const [k, x] of Object.entries(v))
        if (Array.isArray(x)) walk(x, title(k), depth + 1);
        else if (x && typeof x === "object")
          walk(
            x,
            path === "结果" ? title(k) : path + " / " + title(k),
            depth + 1,
          );
    };
    if (flatten(value).length)
      sections.push({ title: "概要", layout: "fit", items: [details(value)] });
    walk(value);
    if (sections.length > 1)
      return { xtype: "tabpanel", flex: 1, items: sections };
    return sections[0]
      ? { xtype: "container", layout: "fit", flex: 1, items: sections[0].items }
      : { xtype: "component", html: "暂无数据", padding: 16 };
  }
  function showResult(heading, response) {
    if (response?.download) {
      const bytes = Uint8Array.from(atob(response.download), (c) =>
        c.charCodeAt(0),
      );
      download(
        response.filename,
        new Blob([bytes], { type: response.contentType }),
      );
      return;
    }
    const value = response?.data ?? response;
    const win = track(
      dialog(heading, {
        items: [
          resultComponent(value),
          toolbar(
            [
              {
                text: "导出 JSON",
                handler: () =>
                  download("ups-export.json", JSON.stringify(value, null, 2)),
              },
              ...(rowsOf(value).length
                ? [
                    {
                      text: "导出 CSV",
                      handler: () =>
                        download("ups-export.csv", csv(rowsOf(value))),
                    },
                  ]
                : []),
              ...(response?.data?.html
                ? [
                    {
                      text: "下载 HTML 报告",
                      handler: () =>
                        download("ups-report.html", response.data.html),
                    },
                  ]
                : []),
              { xtype: "spacer" },
              { text: "关闭", handler: () => win.close() },
            ],
            { docked: "bottom" },
          ),
        ],
      }),
    );
    win.show();
    return win;
  }
  function plot(rows, keys, heading) {
    const records = chartRows(rows, keys),
      used = keys.filter((k) => records.some((r) => r[k] !== null));
    if (!used.length)
      return {
        xtype: "panel",
        title: heading,
        items: [
          { xtype: "component", html: "此时间段暂无可用采样", padding: 16 },
        ],
      };
    return {
      xtype: "panel",
      title: heading,
      height: mobile() ? 270 : 300,
      layout: "fit",
      items: [
        {
          xtype: "cartesian",
          animation: false,
          store: { data: records },
          legend: { docked: "bottom" },
          interactions: ["panzoom"],
          axes: [
            {
              type: "numeric",
              position: "left",
              fields: used,
              grid: true,
              title: heading,
            },
            {
              type: "time",
              position: "bottom",
              fields: ["time"],
              dateFormat: "H:i",
              title: "采样时间",
            },
          ],
          series: used.map((k) => ({
            type: "line",
            xField: "time",
            yField: k,
            title: title(k),
            marker: false,
            tooltip: {
              trackMouse: true,
              renderer(tip, item) {
                tip.setHtml(encode(title(k) + ": " + item.record.get(k)));
              },
            },
          })),
        },
      ],
    };
  }
  function render() {
    if (page.destroyed || !data) return;
    content.removeAll(true);
    metricContainer = null;
    displayCharts = [];
    selected = null;
    if (["overview", "battery", "power", "voltage"].includes(section)) {
      const d = unwrap(data.snapshot),
        p = powerReading(d);
      let metrics;
      if (section === "battery")
        metrics = [
          ["电量", `${reading(d.battery_charge)} %`],
          [
            "预计续航",
            numeric(d.battery_runtime) !== null
              ? Math.round(d.battery_runtime / 60) + " 分钟"
              : "未上报",
            "按当前负载估计",
          ],
          [
            "电池状态",
            batteryCondition(d),
            "设备状态与最近自检结论；不代表剩余容量百分比",
          ],
          [
            "电池温度",
            numeric(d.battery_temperature) !== null
              ? d.battery_temperature + " °C"
              : "未上报",
          ],
        ];
      else if (section === "voltage")
        metrics = [
          "input_voltage",
          "output_voltage",
          "input_voltage_nominal",
          "output_voltage_nominal",
        ].map((k) => [title(k), d[k] ?? "未上报"]);
      else
        metrics = [
          ["供电状态", d.ups_status || "未知"],
          [
            "有功功率",
            p.value !== null ? p.value.toFixed(1) + " W" : "未上报",
            p.explanation,
          ],
          ["负载", `${reading(d.ups_load)} %`],
          ["电量", `${reading(d.battery_charge)} %`],
        ];
      const charts =
        section === "battery"
          ? [
              [["battery_charge"], "电量（%）"],
              [["battery_runtime"], "续航（秒）"],
              [["battery_voltage"], "电池电压（V）"],
            ]
          : section === "voltage"
            ? [[["input_voltage", "output_voltage"], "电压（V）"]]
            : [
                [["ups_realpower"], "有功功率（W）"],
                [["ups_load"], "负载（%）"],
              ];
      shownMetrics = metrics;
      compact = mobile();
      metricContainer = Ext.create("Ext.Container", metricFields(metrics));
      displayCharts = charts.map(([keys, heading]) =>
        Ext.create("Ext.Panel", plot(data.history, keys, heading)),
      );
      content.add({
        xtype: "container",
        layout: "vbox",
        scrollable: "vertical",
        items: [
          metricContainer,
          ...displayCharts,
          {
            xtype: "panel",
            title: "当前读数",
            height: 360,
            layout: "fit",
            items: [details(d)],
          },
        ],
      });
    } else {
      const rows = rowsOf(data);
      content.add(rows.length ? grid(rows) : resultComponent(data));
    }
    caption.setHtml(
      encode(
        `更新于 ${new Date().toLocaleTimeString()}${rowsOf(data).length ? " · " + rowsOf(data).length + " 条记录" : ""}`,
      ),
    );
  }
  function seedParams(op) {
    const params = {};
    for (const key of Object.keys(op.params.properties || {})) {
      if (key === "target_id") params[key] = selected?.id || target;
      else if (
        ["user_id", "action_id", "event_id"].includes(key) &&
        selected?.id
      )
        params[key] = selected.id;
      else if (key === "filename") params[key] = selected?.name || "nut.conf";
      else if (key === "variable") params[key] = selected?.name;
    }
    return params;
  }
  function bodyFromResponse(value, op) {
    const v = unwrap(value) || {},
      root = {
        ...v,
        ...v.policy,
        ...v.target,
        ...v.config,
        ...v.target?.policy,
      },
      fields = op.body.properties || {};
    return Object.fromEntries(
      Object.entries(root).filter(([k]) => k in fields),
    );
  }
  async function openOperation(op) {
    op = structuredClone(op);
    if (op.path === "/api/upscmd/execute") {
      const values = rowsOf(await read("/api/upscmd/list"));
      op.body.properties.command.enum = values.map((x) => x.name || x.command);
    }
    if (op.path === "/api/upsrw/set") {
      const values = rowsOf(await read("/api/upsrw/list"));
      op.body.properties.name.enum = values.map((x) => x.name);
    }

    const frozenTarget = target,
      params = seedParams(op),
      input = {
        targetId: frozenTarget,
        params,
        query: schemaDefaults(op.query),
        body: schemaDefaults(op.body),
      };
    if (op.path === "/api/energy/detailed")
      input.query = {
        from_time: new Date(Date.now() - 86400000).toISOString(),
        to_time: new Date().toISOString(),
        detail_type: "day",
      };
    if (op.path === "/api/report/generate")
      input.body = {
        from_date: new Date(Date.now() - 86400000).toISOString(),
        to_date: new Date().toISOString(),
        report_type: "custom",
      };
    if (op.path === "/api/upscmd/execute" && selected)
      input.body.command = selected.command || selected.name;
    if (op.path === "/api/upsrw/set" && selected)
      input.body = { name: selected.name, value: selected.value };
    if (
      op.path.includes("/multi-nut/targets") &&
      (op.method === "PUT" || op.path.endsWith("/test"))
    )
      input.body = bodyFromResponse(
        selected || targets.find((t) => t.id === params.target_id) || {},
        op,
      );
    if (op.path.includes("script-actions") && op.method === "PUT")
      input.body = bodyFromResponse(selected || {}, op);
    if (op.path.endsWith("/renamer/mappings")) {
      const live = unwrap(
        (
          await query(opAt("/api/multi-nut/renamer/catalog"), {
            targetId: frozenTarget,
          })
        ).data,
      );
      input.body = {
        mappings: Object.fromEntries(
          (live.rows || [])
            .filter((r) => r.selected_source || r.current_source)
            .map((r) => [
              r.canonical_key,
              r.selected_source || r.current_source,
            ]),
        ),
        replace: true,
      };
    }
    const get = opAt(op.path);
    if (
      !op.read &&
      get &&
      !op.path.endsWith("/targets") &&
      !op.path.endsWith("/users") &&
      !op.path.endsWith("/script-actions")
    )
      try {
        const current = await query(get, { params, targetId: frozenTarget });
        input.body = { ...input.body, ...bodyFromResponse(current.data, op) };
      } catch (e) {
        showError(e);
        return;
      }
    const parts = {};
    for (const [part, heading] of [
      ["params", "操作对象"],
      ["query", "查询条件"],
      ["body", "配置"],
    ])
      if (Object.keys(op[part].properties || {}).length)
        parts[part] = {
          ...op[part],
          title: heading,
          additionalProperties: false,
        };
    const form = createSchemaForm(
      {
        type: "object",
        properties: parts,
        required: Object.keys(parts),
        additionalProperties: false,
      },
      Object.fromEntries(Object.keys(parts).map((k) => [k, input[k]])),
    );
    let preview = null,
      busy = false;
    const view = Ext.create("Ext.Container", {
      flex: 1,
      layout: "fit",
      hidden: true,
    });
    const selectedDevice = targets.find((t) => Number(t.id) === frozenTarget);
    const back = Ext.create("Ext.Button", {
      text: "取消",
      handler: () => {
        if (preview) {
          preview = null;
          view.hide();
          form.component.show();
          save.setText(op.read ? "查询" : "校验并预览");
          back.setText("取消");
        } else win.close();
      },
    });
    const save = Ext.create("Ext.Button", {
      text: op.read ? "查询" : "校验并预览",
      ui: "action",
      handler: async () => {
        busy = true;
        save.disable();
        back.disable();
        win.setClosable(false);
        try {
          if (preview) {
            const result = await api("/ups/execute", { token: preview.token });
            busy = false;
            win.close();
            await load();
            showResult(op.title, result);
            return;
          }
          const payload = { ...input, ...form.read() };
          if (op.read) {
            const result = await query(op, payload);
            busy = false;
            win.close();
            showResult(op.title, result);
            return;
          }
          preview = await api("/ups/preview/" + op.id, payload);
          view.removeAll(true);
          view.add({
            xtype: "container",
            layout: "vbox",
            items: [
              field({
                kind: "display",
                label: "影响",
                value: preview.effect,
                margin: 16,
              }),
              {
                xtype: "tabpanel",
                flex: 1,
                items: [
                  {
                    title: "变更后",
                    layout: "fit",
                    items: [resultComponent(preview.after)],
                  },
                  {
                    title: "变更前",
                    layout: "fit",
                    items: [resultComponent(preview.before)],
                  },
                ],
              },
            ],
          });
          form.component.hide();
          view.show();
          save.setText("确认执行");
          back.setText("返回编辑");
        } catch (e) {
          showError(e);
        } finally {
          busy = false;
          if (!win.destroyed) {
            save.enable();
            back.enable();
            win.setClosable(true);
          }
        }
      },
    });
    const win = track(
      dialog(op.title, {
        items: [
          ...(op.scope
            ? [
                field({
                  kind: "display",
                  label: "目标设备",
                  value: selectedDevice?.name || String(frozenTarget),
                  margin: "0 16",
                }),
              ]
            : []),
          form.component,
          view,
          toolbar([back, save], {
            docked: "bottom",
            layout: { type: "hbox", pack: "end" },
          }),
        ],
        listeners: { beforeclose: () => !busy },
      }),
    );
    win.show();
  }
  function actionsFor() {
    const ops = catalog.filter((o) => pageFor(o) === section),
      reads = ops.filter((o) => o.read),
      writes = ops.filter((o) => !o.read);
    const menu = (text, entries) => ({
      text,
      menu: {
        items: entries.map((op) => ({
          text: op.title,
          handler: (item) => {
            item.getParent().hide();
            openOperation(op).catch(showError);
          },
        })),
      },
    });
    actions.removeAll(true);
    if (writes.length) {
      const first =
        writes.find(
          (o) =>
            o.method === "POST" &&
            /\/(targets|script-actions|users)$/.test(o.path),
        ) || writes.find((o) => /\/execute$|\/set$/.test(o.path));
      if (first)
        actions.add({
          text: first.title,
          ui: "action",
          handler: () => openOperation(first).catch(showError),
        });
      actions.add(menu("管理操作", writes));
    }
    if (reads.length)
      actions.add(
        menu(section === "reports" ? "生成报告" : "查询与导出", reads),
      );
    actions.add({
      text: "导出当前数据",
      iconCls: "x-fa fa-download",
      handler: () =>
        download("ups-" + section + ".json", JSON.stringify(data, null, 2)),
      disabled: !data,
    });
  }
  async function load(initial = false) {
    if (page.destroyed || loading) return;
    loading = true;
    device.disable();
    const seq = ++loadSeq;
    caption.setHtml("正在读取…");
    try {
      if (initial) {
        const result = await api("/ups/catalog");
        catalog = result.operations;
        if (!result.enabled) throw Error("UPS 集成尚未启用");
      }
      const targetData = await read("/api/multi-nut/targets", {
        include_disabled: true,
      });
      targets = rowsOf(targetData);
      if (!targets.some((t) => Number(t.id) === target))
        target = Number(
          targets.find((t) => t.is_primary)?.id || targets[0]?.id || 0,
        );
      device.suspendEvents();
      device.setOptions(
        targets.map((t) => ({ text: t.name, value: Number(t.id) })),
      );
      device.setValue(target);
      device.resumeEvents();
      if (["overview", "battery", "power", "voltage"].includes(section)) {
        const [snapshot, history] = await Promise.all([
          read("/api/data/all"),
          read("/api/table/dynamic", { rows: 240 }),
        ]);
        if (seq !== loadSeq || page.destroyed) return;
        data = { snapshot, history: rowsOf(history) };
      } else
        data = await read(
          primary[section] || "/api/system/info",
          section === "history" || section === "events" ? { rows: 250 } : {},
        );
      if (seq !== loadSeq || page.destroyed) return;
      render();
      actionsFor();
    } catch (e) {
      if (!page.destroyed) {
        caption.setHtml("读取失败");
        showError(e);
      }
    } finally {
      loading = false;
      if (!device.destroyed) device.enable();
    }
  }
  const timer = setInterval(() => {
    if (
      auto &&
      !windows.size &&
      ["overview", "battery", "power", "voltage", "energy"].includes(section)
    )
      load();
  }, 30000);
  const resize = Ext.Function.createBuffered(() => {
    if (page.destroyed) return;
    const nextCompact = mobile();
    device.setWidth(nextCompact ? 200 : 360);
    if (
      compact !== nextCompact &&
      metricContainer &&
      !metricContainer.destroyed
    ) {
      metricContainer.removeAll(true);
      metricContainer.add(metricFields(shownMetrics).items);
      for (const chart of displayCharts)
        if (!chart.destroyed) chart.setHeight(nextCompact ? 270 : 300);
    }
    compact = nextCompact;
  }, 120);
  Ext.Viewport.on("resize", resize);
  page.on("destroy", () => {
    Ext.Viewport.un("resize", resize);
    clearInterval(timer);
    loadSeq++;
    for (const w of windows) if (!w.destroyed) w.destroy();
  });
  if (user.admin) load(true);
  else caption.setHtml("需要管理员权限");
  return page;
}
