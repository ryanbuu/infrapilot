import { test } from "node:test";
import assert from "node:assert/strict";
import {
  MASK,
  resolveSchema,
  schemaType,
  validateScalar,
  validateMasks,
  resourceDefaults,
  safeKey,
} from "../src/schema-model.js";
test("selects upstream node map or array without coercing data", () => {
  const schema = {
    anyOf: [
      { type: "object", additionalProperties: { type: "integer" } },
      { type: "array", items: { type: "object" } },
    ],
  };
  assert.equal(resolveSchema(schema, { "host:80": 0 }).type, "object");
  assert.equal(
    resolveSchema(schema, [{ host: "host", weight: 0 }]).type,
    "array",
  );
  assert.equal(schemaType({ type: "integer" }, 0), "integer");
});
test("numeric limits and false or zero are retained", () => {
  assert.equal(validateScalar(0, { type: "integer", minimum: 0 }, "权重"), 0);
  assert.equal(validateScalar(false, { type: "boolean" }, "验证"), false);
  assert.throws(() => validateScalar(1.5, { type: "integer" }, "权重"));
  assert.throws(() =>
    validateScalar(-1, { type: "number", minimum: 0 }, "权重"),
  );
  assert.throws(() =>
    validateScalar(0, { type: "number", exclusiveMinimum: 0 }, "超时"),
  );
});
test("masked values cannot be copied to a different path", () => {
  validateMasks(
    { plugins: { auth: { key: MASK } } },
    { plugins: { auth: { key: MASK } } },
  );
  assert.throws(() =>
    validateMasks(
      { plugins: { auth: { key: MASK } } },
      { plugins: { other: { key: MASK } } },
    ),
  );
  assert.equal(safeKey("__proto__"), false);
  assert.equal(safeKey("x-header"), true);
});
test("new routes are disabled and use editable node rows", () => {
  assert.equal(resourceDefaults("routes").status, 0);
  assert.deepEqual(resourceDefaults("upstreams").nodes, []);
});
