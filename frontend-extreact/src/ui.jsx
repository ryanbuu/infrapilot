import React, {
  useRef,
  useCallback,
  useState,
  useMemo,
  useEffect,
} from "react";
import ExtButton from "@sencha/ext-react-modern/dist/ExtButton";
import ExtGrid from "@sencha/ext-react-modern/dist/ExtGrid";
import ExtSearch from "@sencha/ext-react-modern/dist/ExtSearchfield";
import ExtText from "@sencha/ext-react-modern/dist/ExtTextfield";
import ExtArea from "@sencha/ext-react-modern/dist/ExtTextareafield";
import ExtSelect from "@sencha/ext-react-modern/dist/ExtSelectfield";
import ExtDialog from "@sencha/ext-react-modern/dist/ExtDialog";
export { default as Container } from "@sencha/ext-react-modern/dist/ExtContainer";
export { default as Panel } from "@sencha/ext-react-modern/dist/ExtPanel";
export { default as Form } from "@sencha/ext-react-modern/dist/ExtFormpanel";
export { default as Tree } from "@sencha/ext-react-modern/dist/ExtTreelist";
import Container from "@sencha/ext-react-modern/dist/ExtContainer";
import Panel from "@sencha/ext-react-modern/dist/ExtPanel";
import ExtToolbar from "@sencha/ext-react-modern/dist/ExtToolbar";
import Form from "@sencha/ext-react-modern/dist/ExtFormpanel";
import Component from "@sencha/ext-react-modern/dist/ExtComponent";
import { pretty, encode, api } from "./api";

export function useEvent(fn) {
  const r = useRef(fn);
  r.current = fn;
  return useCallback((...args) => r.current?.(...args), []);
}
export function useSize() {
  const [size, setSize] = useState({ width: innerWidth, height: innerHeight });
  useEffect(() => {
    const update = () => setSize({ width: innerWidth, height: innerHeight });
    addEventListener("resize", update);
    return () => removeEventListener("resize", update);
  }, []);
  return { ...size, mobile: size.width <= 760 };
}
export function Button({ onTap, ...props }) {
  return <ExtButton {...props} onTap={useEvent(onTap)} />;
}
export function Toolbar(props) {
  return <ExtToolbar shadow={false} {...props} />;
}
export function Label({ text, html, ...props }) {
  const value = html ?? encode(text),
    initial = useRef(value),
    cmp = useRef(null);
  const sync = useEvent(() => {
    if (cmp.current && !cmp.current.destroyed) cmp.current.setHtml(value);
  });
  useEffect(sync, [value]);
  return (
    <Component
      {...props}
      html={initial.current}
      onReady={useEvent(({ cmp: c }) => {
        cmp.current = c;
        sync();
      })}
    />
  );
}
export function Field({ onChange, kind, value, ...props }) {
  const C = kind === "area" ? ExtArea : kind === "select" ? ExtSelect : ExtText;
  const options = useMemo(() => props.options, [JSON.stringify(props.options)]);
  const initial = useRef(value),
    cmp = useRef(null),
    syncing = useRef(false);
  // ExtReact 7.5's attribute adapter parses JSON-looking text as objects.
  // Keep the initial prop stable; use the native field API for value updates.
  const sync = useEvent(() => {
    if (
      cmp.current &&
      !cmp.current.destroyed &&
      cmp.current.getValue() !== value
    ) {
      syncing.current = true;
      try {
        cmp.current.setValue(value ?? "");
      } finally {
        syncing.current = false;
      }
    }
  });
  useEffect(sync, [value]);
  return (
    <C
      labelAlign="top"
      ariaLabel={props.label || props.ariaLabel}
      {...props}
      options={options}
      cls={
        "er-field " + (kind === "area" ? "er-area " : "") + (props.cls || "")
      }
      value={initial.current}
      onReady={useEvent(({ cmp: c }) => {
        cmp.current = c;
        sync();
      })}
      onChange={useEvent(({ newValue }) => {
        if (!syncing.current) onChange?.(newValue);
      })}
    />
  );
}
export function Search({ onChange, ...props }) {
  return (
    <ExtSearch
      {...props}
      cls="er-search"
      placeholder="搜索名称、地址或配置"
      ariaLabel="搜索"
      onChange={useEvent(({ newValue }) => onChange(newValue || ""))}
    />
  );
}
export function Grid({ rows, columns, onSelect, height = 540 }) {
  const store = useMemo(() => ({ data: rows }), [JSON.stringify(rows)]);
  const nativeColumns = useMemo(
    () =>
      columns.map((c) => ({
        flex: 1,
        minWidth: 110,
        renderer: encode,
        cell: { encodeHtml: false },
        ...c,
      })),
    [
      JSON.stringify(columns, (_, v) =>
        typeof v === "function" ? v.toString() : v,
      ),
    ],
  );
  return (
    <ExtGrid
      cls="er-grid"
      height={height}
      store={store}
      columns={nativeColumns}
      emptyText="暂无记录"
      itemConfig={{ height: 64 }}
      striped
      selectable={{ mode: "single" }}
      onSelect={useEvent(({ selected }) =>
        onSelect?.((Array.isArray(selected) ? selected[0] : selected)?.data),
      )}
    />
  );
}
export function Metrics({ items }) {
  const { mobile } = useSize();
  const count = mobile ? 2 : 4,
    groups = [];
  for (let i = 0; i < items.length; i += count)
    groups.push(items.slice(i, i + count));
  return (
    <Container cls="metrics" layout="vbox">
      {groups.map((group, i) => (
        <Container key={i} layout="hbox" margin="0 0 12 0">
          {group.map(([title, value, detail], j) => (
            <Panel
              key={title}
              cls="metric"
              flex={1}
              margin={j ? "0 0 0 12" : 0}
              padding={18}
              layout="vbox"
            >
              <Label cls="metric-label" text={title} />
              <Label cls="metric-value" text={value ?? "—"} />
              {detail && <Label cls="metric-detail" text={detail} />}
            </Panel>
          ))}
        </Container>
      ))}
    </Container>
  );
}
export function Dialog({ title, children, onClose, busy = false, ...props }) {
  const { width, height } = useSize();
  return (
    <ExtDialog
      title={title}
      cls="er-dialog"
      width={Math.min(900, width - 24)}
      height={Math.min(800, height - 40)}
      layout="vbox"
      modal
      centered
      closable={!busy}
      closeAction="hide"
      tabGuard
      draggable={false}
      showAnimation={false}
      hideAnimation={false}
      {...props}
      onHide={useEvent(() => {
        if (!busy) onClose?.();
      })}
      onReady={useEvent(({ cmp }) => cmp.show())}
    >
      {children}
    </ExtDialog>
  );
}
export function Help({ text }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Button
        cls="help"
        ui="flat"
        iconCls="x-fa fa-question-circle"
        ariaLabel="查看说明"
        tooltip={encode(text)}
        onTap={() => setOpen(true)}
      />
      {open && (
        <Dialog title="说明" height={260} onClose={() => setOpen(false)}>
          <Label padding={24} flex={1} text={text} />
          <Toolbar docked="bottom" layout={{ type: "hbox", pack: "end" }}>
            <Button text="知道了" ui="action" onTap={() => setOpen(false)} />
          </Toolbar>
        </Dialog>
      )}
    </>
  );
}
export function Alert({ text }) {
  return (
    <Label
      cls="error"
      ariaRole="alert"
      margin="0 0 16 0"
      padding={12}
      text={text}
    />
  );
}
export function Editor({ editor, onClose, onSaved }) {
  const [values, setValues] = useState(editor.values || {}),
    [raw, setRaw] = useState(pretty(editor.values || {})),
    [preview, setPreview] = useState(null),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  const { mobile } = useSize();
  const build = () => editor.payload(editor.fields ? values : JSON.parse(raw));
  async function review() {
    setBusy(true);
    setError("");
    try {
      const payload = build();
      const result = editor.preview
        ? await api(editor.preview, payload)
        : payload;
      setPreview({ payload, result });
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }
  async function save() {
    setBusy(true);
    setError("");
    try {
      const result = await api(
        editor.path,
        preview.payload,
        editor.method || "POST",
      );
      onSaved(result);
      onClose();
    } catch (e) {
      setError(e.message);
      setPreview(null);
    } finally {
      setBusy(false);
    }
  }
  const groups = [];
  for (const field of editor.fields || []) {
    const previous = groups[groups.length - 1];
    if (
      !mobile &&
      field.kind !== "area" &&
      previous?.length === 1 &&
      previous[0].kind !== "area"
    )
      previous.push(field);
    else groups.push([field]);
  }
  return (
    <Dialog title={editor.title} onClose={onClose} busy={busy}>
      <Form
        flex={1}
        scrollable="vertical"
        padding={20}
        cls="editor-body"
        layout="vbox"
      >
        {error && <Alert text={error} />}
        {preview ? (
          <Field
            key="preview"
            kind="area"
            label="变更预览"
            cls="code-field"
            readOnly
            flex={1}
            minHeight={320}
            value={pretty(preview.result)}
          />
        ) : editor.fields ? (
          groups.map((fields, i) => (
            <Container key={i} layout="hbox" margin="0 0 16 0">
              {fields.map((f, j) => (
                <Field
                  key={f.name}
                  {...f}
                  flex={1}
                  margin={j ? "0 0 0 20" : 0}
                  value={values[f.name] ?? ""}
                  onChange={(v) =>
                    setValues((old) => ({ ...old, [f.name]: v }))
                  }
                />
              ))}
            </Container>
          ))
        ) : (
          <Field
            key="raw"
            kind="area"
            label="配置内容"
            cls="code-field"
            flex={1}
            minHeight={400}
            value={raw}
            onChange={setRaw}
          />
        )}
      </Form>
      <Toolbar
        docked="bottom"
        padding="12 20"
        layout={{ type: "hbox", pack: "end" }}
      >
        <Button
          text={preview ? "返回编辑" : "取消"}
          disabled={busy}
          onTap={() => (preview ? setPreview(null) : onClose())}
        />
        <Button
          text={busy ? "处理中…" : preview ? "确认保存" : "校验并预览"}
          ui="action"
          disabled={busy}
          onTap={preview ? save : review}
        />
      </Toolbar>
    </Dialog>
  );
}
export const column = (text, dataIndex, extra = {}) => ({
  text,
  dataIndex,
  ...extra,
});
export const list = (v) =>
  (Array.isArray(v) ? v : String(v || "").split(/[,\n]+/))
    .filter(Boolean)
    .map((x) => `<div class="address">${encode(x)}</div>`)
    .join("") || "—";
export function download(name, data) {
  const u = URL.createObjectURL(
    new Blob([typeof data === "string" ? data : pretty(data)], {
      type: "text/plain;charset=utf-8",
    }),
  );
  const a = document.createElement("a");
  a.href = u;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(u), 1000);
}
