// Zero-dependency tests for the receiver's pure routing logic (Part D mapping).
// Run: node --test --experimental-strip-types   (Node 20+/22+; no network, no secrets).
import { test } from "node:test";
import assert from "node:assert/strict";
import { mapEvent, deterministicPrId, prIdFromBranch } from "../src/index.ts";

const repo = { name: "playwrightFramework", owner: { login: "ranveersinghaiden" } };
const sourcePr = { number: 42, head: { ref: "feature/x", sha: "abc123" } };

test("pull_request opened → pr_opened with deterministic prId", () => {
  const d = mapEvent("pull_request", { action: "opened", repository: repo, pull_request: sourcePr });
  assert.equal(d?.type, "pr_opened");
  assert.equal(d?.payload.owner, "ranveersinghaiden");
  assert.equal(d?.payload.repo, "playwrightFramework");
  assert.equal(d?.payload.prNumber, 42);
  assert.equal(d?.payload.headSha, "abc123");
  assert.equal(d?.payload.prId, deterministicPrId("ranveersinghaiden", "playwrightFramework", 42));
});

test("pull_request synchronize → pr_sync", () => {
  const d = mapEvent("pull_request", { action: "synchronize", repository: repo, pull_request: sourcePr });
  assert.equal(d?.type, "pr_sync");
});

test("pull_request closed → no dispatch", () => {
  assert.equal(mapEvent("pull_request", { action: "closed", repository: repo, pull_request: sourcePr }), null);
});

test("review approved on qa/bdd/* → bdd_approved, prId from branch", () => {
  const pr = { number: 7, head: { ref: "qa/bdd/PR-D6D336C1", sha: "deadbeef" } };
  const d = mapEvent("pull_request_review", { review: { state: "approved" }, repository: repo, pull_request: pr });
  assert.equal(d?.type, "bdd_approved");
  assert.equal(d?.payload.prId, "PR-D6D336C1");
});

test("review approved on qa/tests/* → tests_approved", () => {
  const pr = { number: 8, head: { ref: "qa/tests/PR-D6D336C1", sha: "f00" } };
  const d = mapEvent("pull_request_review", { review: { state: "approved" }, repository: repo, pull_request: pr });
  assert.equal(d?.type, "tests_approved");
  assert.equal(d?.payload.prId, "PR-D6D336C1");
});

test("review approved on a non-QA branch → no dispatch", () => {
  const pr = { number: 9, head: { ref: "feature/human", sha: "f00" } };
  assert.equal(mapEvent("pull_request_review", { review: { state: "approved" }, repository: repo, pull_request: pr }), null);
});

test("review changes_requested → pr_changes_requested with reviewId", () => {
  const pr = { number: 10, head: { ref: "qa/tests/PR-D6D336C1", sha: "f00" } };
  const d = mapEvent("pull_request_review", {
    review: { state: "changes_requested", id: 555 },
    repository: repo,
    pull_request: pr,
  });
  assert.equal(d?.type, "pr_changes_requested");
  assert.equal(d?.payload.reviewId, "555");
  assert.equal(d?.payload.prId, "PR-D6D336C1");
});

test("deterministicPrId is stable + format PR-XXXXXXXX", () => {
  const a = deterministicPrId("o", "r", 1);
  const b = deterministicPrId("o", "r", 1);
  assert.equal(a, b);
  assert.match(a, /^PR-[0-9A-F]{8}$/);
  assert.notEqual(a, deterministicPrId("o", "r", 2));
});

test("prIdFromBranch parses both prefixes and rejects others", () => {
  assert.equal(prIdFromBranch("qa/bdd/PR-D6D336C1"), "PR-D6D336C1");
  assert.equal(prIdFromBranch("qa/tests/PR-ABC12300"), "PR-ABC12300");
  assert.equal(prIdFromBranch("feature/whatever"), null);
});
