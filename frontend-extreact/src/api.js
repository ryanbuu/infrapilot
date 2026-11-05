let token, header;
export async function csrf() {
  const r = await fetch("/api/csrf");
  if (!r.ok) throw Error("无法建立安全会话");
  const d = await r.json();
  token = d.token;
  header = d.headerName;
}
export async function api(
  path,
  body,
  method = body === undefined ? "GET" : "POST",
) {
  if (method !== "GET" && !token) await csrf();
  const headers = {};
  if (body !== undefined && !(body instanceof URLSearchParams))
    headers["Content-Type"] = "application/json";
  if (method !== "GET") headers[header] = token;
  const r = await fetch("/api" + path, {
    method,
    headers,
    credentials: "same-origin",
    body:
      body === undefined
        ? undefined
        : body instanceof URLSearchParams
          ? body
          : JSON.stringify(body),
  });
  if (!r.ok) {
    if (r.status === 401 && path !== "/login" && path !== "/session")
      window.dispatchEvent(new Event("session-expired"));
    throw Error(
      (await r.json().catch(() => ({}))).message || `请求失败 (${r.status})`,
    );
  }
  return r.status === 204 ? null : r.json();
}
export async function login(username, password) {
  await csrf();
  await api("/login", new URLSearchParams({ username, password }));
  await csrf();
}
export const encode = (v) => Ext.String.htmlEncode(String(v ?? "—"));
export const pretty = (v) => JSON.stringify(v, null, 2);
