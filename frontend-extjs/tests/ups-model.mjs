import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import {
  pageFor,
  upsPages,
  numeric,
  batteryCondition,
  powerReading,
  chartRows,
  schemaDefaults,
} from "../src/ups-model.js";
test("every supported operation has a native page and notifications have no operation", () => {
  const c = JSON.parse(
    readFileSync(
      new URL(
        "../../backend/src/main/resources/ups/catalog.json",
        import.meta.url,
      ),
    ),
  );
  for (const op of c.operations) {
    assert(upsPages.some((p) => p[0] === pageFor(op)));
    assert(!/ntfy|telegram|webhook|report\/send|settings\/mail/.test(op.path));
  }
  assert(c.operations.length > 100);
});
test("missing readings remain unknown; zero is valid", () => {
  assert.equal(numeric(null), null);
  assert.equal(numeric(""), null);
  assert.equal(numeric(0), 0);
  assert.equal(batteryCondition({ battery_charge: 100 }), "未提供健康结论");
  assert.equal(
    batteryCondition({ ups_test_result: "Done and passed" }),
    "最近自检通过",
  );
  assert.equal(batteryCondition({ ups_status: "OL RB" }), "需要检查或更换");
  assert.equal(
    powerReading({ ups_load: 20, ups_realpower_nominal: 390 }).value,
    78,
  );
});
test("chart gaps and timestamps are not fabricated", () => {
  const r = chartRows(
    [
      { timestamp_utc: "2026-11-10T00:00:00Z", battery_temperature: null },
      { timestamp_utc: "invalid", battery_temperature: 12 },
    ],
    ["battery_temperature"],
  );
  assert.equal(r.length, 1);
  assert.equal(r[0].battery_temperature, null);
});
test("upstream string defaults are converted only for numeric fields", () => {
  assert.deepEqual(
    schemaDefaults({
      properties: {
        page: { type: "integer", default: "1" },
        rows: { type: "integer", default: "all" },
      },
    }),
    { page: 1 },
  );
});
