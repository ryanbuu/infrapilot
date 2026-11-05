import { api, encode, pretty } from "./api";

export const mobile = () => Ext.getViewportWidth() <= 760;
export const label = (text, config = {}) => ({
  xtype: "component",
  html: encode(text),
  ...config,
});
export const col = (text, dataIndex, extra = {}) => ({
  text,
  dataIndex,
  flex: 1,
  minWidth: 110,
  renderer: encode,
  cell: { encodeHtml: false, wrap: true },
  ...extra,
});
export const list = (value) =>
  (Array.isArray(value) ? value : String(value || "").split(/[,\n]+/))
    .filter(Boolean)
    .map(encode)
    .join("<br>") || "—";
export const toolbar = (items, config = {}) => ({
  xtype: "toolbar",
  defaultButtonUI: "default",
  items,
  ...config,
});
export const field = ({ kind, section, key, onChange, ...config }) => ({
  xtype:
    kind === "area"
      ? "textareafield"
      : kind === "select"
        ? "selectfield"
        : kind === "display"
          ? "displayfield"
          : "textfield",
  labelAlign: "top",
  cls: "native-field",
  ...config,
  ...(onChange ? { listeners: { change: (_, value) => onChange(value) } } : {}),
});
export function showError(error) {
  Ext.Msg.alert("操作未完成", encode(error?.message || error));
}
export function dialog(title, config = {}) {
  return Ext.create("Ext.Dialog", {
    title,
    cls: "native-dialog",
    width: Math.min(900, Ext.getViewportWidth() - 32),
    height: Math.min(760, Ext.getViewportHeight() - 48),
    maximized: mobile(),
    modal: true,
    centered: true,
    closable: true,
    closeAction: "destroy",
    layout: "vbox",
    ...config,
  });
}
export function showDetail(title, value) {
  const win = dialog(title, {
    items: [
      field({
        kind: "area",
        label: "详细内容",
        readOnly: true,
        value: pretty(value),
        flex: 1,
        margin: 16,
      }),
      toolbar([{ text: "关闭", handler: () => win.close() }], {
        docked: "bottom",
        layout: { type: "hbox", pack: "end" },
      }),
    ],
  });
  win.show();
  return win;
}
export function metricFields(items) {
  const count = mobile() ? 2 : 4;
  const rows = [];
  for (let i = 0; i < items.length; i += count)
    rows.push({
      xtype: "container",
      layout: "hbox",
      items: items.slice(i, i + count).map(([title, value, detail]) =>
        field({
          kind: "display",
          label: title,
          value: String(value ?? "—"),
          flex: 1,
          tooltip: detail ? encode(detail) : undefined,
        }),
      ),
    });
  return {
    xtype: "container",
    cls: "metrics",
    layout: "vbox",
    padding: "8 16",
    items: rows,
  };
}
export function actionItems(actions, busy, onError) {
  const run = (fn) => Promise.resolve().then(fn).catch(onError);
  const buttons = (
    Ext.getViewportWidth() < 1100 ? actions.slice(0, 1) : actions
  ).map(([text, fn, disabled], i) => ({
    xtype: "button",
    text,
    ui: i === 0 && /^添加/.test(text) ? "action" : "default",
    disabled: busy || !!disabled,
    handler: () => run(fn),
  }));
  if (Ext.getViewportWidth() < 1100 && actions.length > 1)
    buttons.push({
      xtype: "button",
      text: "更多操作",
      iconCls: "x-fa fa-ellipsis-h",
      ui: "default",
      menu: {
        cls: "actions-menu",
        items: actions.slice(1).map(([text, fn, disabled]) => ({
          text,
          disabled: busy || !!disabled,
          handler: (item) => {
            item.getParent().hide();
            run(fn);
          },
        })),
      },
    });
  return buttons;
}
export function openEditor(editor) {
  let preview = null,
    busy = false;
  const values = { ...editor.values };
  const sections = [];
  for (const f of editor.fields || []) {
    const title = f.section || "基本设置";
    let section = sections[sections.length - 1];
    if (!section || section.title !== title) {
      section = { title, rows: [] };
      sections.push(section);
    }
    const row = section.rows[section.rows.length - 1];
    if (
      !mobile() &&
      f.kind !== "area" &&
      row?.length === 1 &&
      row[0].kind !== "area"
    )
      row.push(f);
    else section.rows.push([f]);
  }
  const form = Ext.create("Ext.form.Panel", {
    itemId: "edit-form",
    flex: 1,
    scrollable: "vertical",
    padding: 16,
    layout: "vbox",
    items: editor.fields
      ? sections.map((section) => ({
          xtype: "fieldset",
          title: section.title,
          layout: "vbox",
          items: section.rows.map((row) => ({
            xtype: "container",
            layout: "hbox",
            items: row.map((f, i) =>
              field({
                ...f,
                flex: 1,
                margin: i ? "0 0 0 16" : 0,
                height: f.kind === "area" ? f.height || 180 : undefined,
                value: values[f.name] ?? "",
              }),
            ),
          })),
        }))
      : [
          field({
            kind: "area",
            name: "raw",
            label: "配置内容",
            value: pretty(editor.values || {}),
            flex: 1,
            minHeight: 360,
          }),
        ],
  });
  const review = Ext.create("Ext.field.TextArea", {
    label: "变更预览",
    labelAlign: "top",
    cls: "native-field",
    readOnly: true,
    flex: 1,
    margin: 16,
    hidden: true,
  });
  const back = Ext.create("Ext.Button", {
    text: "取消",
    handler: () => {
      if (preview) {
        preview = null;
        form.show();
        review.hide();
        back.setText("取消");
        save.setText("校验并预览");
      } else win.close();
    },
  });
  const save = Ext.create("Ext.Button", {
    text: "校验并预览",
    ui: "action",
    handler: async () => {
      busy = true;
      save.disable();
      back.disable();
      win.setClosable(false);
      try {
        if (!preview) {
          const payload = editor.payload(
            editor.fields
              ? { ...values, ...form.getValues() }
              : JSON.parse(form.down("[name=raw]").getValue()),
          );
          const result = editor.preview
            ? await api(editor.preview, payload)
            : payload;
          preview = { payload, result };
          review.setValue(pretty(result));
          form.hide();
          review.show();
          back.setText("返回编辑");
          save.setText("确认保存");
        } else {
          const result = await api(
            editor.path,
            preview.payload,
            editor.method || "POST",
          );
          busy = false;
          win.close();
          editor.onSaved?.(result);
          return;
        }
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
  const contentHeight = editor.fields
    ? 160 +
      sections.reduce(
        (sum, s) =>
          sum +
          52 +
          s.rows.reduce(
            (n, row) =>
              n + (row[0].kind === "area" ? row[0].height || 180 : 64),
            0,
          ),
        0,
      )
    : 680;
  const win = dialog(editor.title, {
    height: Math.min(contentHeight, Ext.getViewportHeight() - 48),
    items: [
      form,
      review,
      toolbar([back, save], {
        docked: "bottom",
        layout: { type: "hbox", pack: "end" },
      }),
    ],
    listeners: { beforeclose: () => !busy },
  });
  win.show();
  return win;
}
export function download(name, data) {
  const url = URL.createObjectURL(
    new Blob([typeof data === "string" ? data : pretty(data)], {
      type: "text/plain;charset=utf-8",
    }),
  );
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
