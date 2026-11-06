export const MASK = "__INFRAPILOT_REDACTED__";
export const clone = (value) =>
  value === undefined ? undefined : JSON.parse(JSON.stringify(value));
export const typeOf = (value) =>
  value === null ? "null" : Array.isArray(value) ? "array" : typeof value;
export const hasMask = (value) =>
  value === MASK ||
  (value && typeof value === "object" && Object.values(value).some(hasMask));
export const safeKey = (key) =>
  !!key && !["__proto__", "prototype", "constructor"].includes(key);

export function variants(schema = {}) {
  const options = [...(schema.oneOf || []), ...(schema.anyOf || [])];
  if (Array.isArray(schema.type))
    options.push(...schema.type.map((type) => ({ type })));
  return options.filter((s) => s.type || s.properties || s.items || s.enum);
}
export function schemaType(schema = {}, value) {
  if (schema.type && !Array.isArray(schema.type)) return schema.type;
  if (
    schema.properties ||
    schema.patternProperties ||
    schema.additionalProperties?.type
  )
    return "object";
  if (schema.items) return "array";
  if (value !== undefined) return typeOf(value);
  if (schema.enum?.length) return typeOf(schema.enum[0]);
  return "string";
}
export function resolveSchema(schema = {}, value) {
  let result = { ...schema };
  for (const part of schema.allOf || []) {
    result = {
      ...result,
      ...part,
      properties: { ...result.properties, ...part.properties },
      required: [...(result.required || []), ...(part.required || [])],
    };
  }
  const choices = variants(result);
  if (choices.length) {
    const actual = typeOf(value);
    const matching = choices.filter(
      (s) =>
        schemaType(s, value) === actual ||
        (actual === "number" && s.type === "integer"),
    );
    const branch =
      matching.find(
        (s) =>
          s.properties &&
          value &&
          Object.keys(s.properties).some((k) => k in value),
      ) ||
      matching[0] ||
      choices[0];
    result = { ...result, ...branch };
    delete result.oneOf;
    delete result.anyOf;
  }
  return result;
}
export function emptyValue(schema = {}) {
  if (schema.default !== undefined) return clone(schema.default);
  const type = schemaType(resolveSchema(schema));
  if (type === "object") return {};
  if (type === "array") return [];
  if (type === "boolean") return false;
  if (type === "null") return null;
  return undefined;
}
export function validateScalar(value, schema, label) {
  if (value === undefined || value === MASK) return value;
  const type = schemaType(schema, value);
  if (["integer", "number"].includes(type)) {
    if (typeof value !== "number" || !Number.isFinite(value))
      throw Error(label + "必须是数字");
    if (type === "integer" && !Number.isSafeInteger(value))
      throw Error(label + "必须是安全范围内的整数");
    if (schema.minimum !== undefined && value < schema.minimum)
      throw Error(label + "不能小于 " + schema.minimum);
    if (schema.maximum !== undefined && value > schema.maximum)
      throw Error(label + "不能大于 " + schema.maximum);
    if (
      typeof schema.exclusiveMinimum === "number" &&
      value <= schema.exclusiveMinimum
    )
      throw Error(label + "必须大于 " + schema.exclusiveMinimum);
  }
  if (typeof value === "string") {
    if (schema.minLength && value.length < schema.minLength)
      throw Error(label + "至少需要 " + schema.minLength + " 个字符");
    if (schema.maxLength && value.length > schema.maxLength)
      throw Error(label + "最多允许 " + schema.maxLength + " 个字符");
  }
  return value;
}
export function validateMasks(value, original, path = "配置") {
  if (value === MASK) {
    if (original !== MASK)
      throw Error(path + "需要重新填写，不能使用其他位置的隐藏值");
  } else if (value && typeof value === "object") {
    for (const [key, child] of Object.entries(value))
      validateMasks(child, original?.[key], path + "." + key);
  }
}
export function resourceDefaults(kind) {
  if (kind === "upstreams")
    return { type: "roundrobin", scheme: "http", nodes: [] };
  if (kind === "routes")
    return {
      uri: "/*",
      status: 0,
      upstream: { type: "roundrobin", scheme: "http", nodes: [] },
    };
  if (kind === "services" || kind === "stream_routes")
    return { upstream: { type: "roundrobin", nodes: [] } };
  if (kind === "ssls") return { snis: [], status: 1 };
  if (kind === "protos") return { content: 'syntax = "proto3";\n' };
  if (
    [
      "consumers",
      "credentials",
      "consumer_groups",
      "global_rules",
      "plugin_configs",
    ].includes(kind)
  )
    return { plugins: {} };
  return {};
}
