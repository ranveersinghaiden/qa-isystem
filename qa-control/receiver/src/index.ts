/**
 * QA-ISystem webhook receiver  (Design doc Decision C4 + Part D)
 *
 * A tiny, stateless serverless function (Cloudflare Worker shown; the same ~100
 * lines port to AWS Lambda / GCP Cloud Function). It is the ONLY routing logic
 * in the system:
 *
 *   1. Verify the GitHub webhook HMAC (X-Hub-Signature-256).
 *   2. Map the GitHub event → a `repository_dispatch` type (Part D table).
 *   3. Fire `repository_dispatch` at the `qa-control` repo with a minimal
 *      `client_payload` (prId, owner, repo, branch, headSha, prNumber, reviewId).
 *
 * Security:
 *   • NO tokens are ever placed in the dispatch payload. Each workflow job mints
 *     its own short-lived, target-scoped token via actions/create-github-app-token.
 *   • The receiver authenticates its OWN call to the dispatch API with
 *     QA_DISPATCH_TOKEN (a fine-grained PAT or GitHub App installation token with
 *     `contents:write` on qa-control only) — stored as a Worker secret, never logged.
 *   • HMAC is compared in constant time.
 *
 * Secrets (wrangler secret put …): QA_WEBHOOK_SECRET, QA_DISPATCH_TOKEN.
 * Vars (wrangler.toml [vars]): QA_CONTROL_OWNER, QA_CONTROL_REPO.
 *
 * OPEN WIRING ITEM (Phase 4 tuning — see receiver/README.md §"Payload seeding"):
 *   The impact one-shot reads the PR payload (changed files / diff) from Neon by
 *   prId. Something must seed pr_history.payload before the impact job runs.
 *   Two supported options are documented in the README; this receiver deliberately
 *   stays out of the DB to remain small + faithful to Decision C4.
 */

export interface Env {
  QA_WEBHOOK_SECRET: string;
  QA_DISPATCH_TOKEN: string;
  QA_CONTROL_OWNER: string;
  QA_CONTROL_REPO: string;
}

const QA_BDD_PREFIX = "qa/bdd/";
const QA_TESTS_PREFIX = "qa/tests/";

interface Dispatch {
  type: string;
  payload: Record<string, unknown>;
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.method !== "POST") {
      return new Response("method not allowed", { status: 405 });
    }

    const event = request.headers.get("x-github-event") ?? "";
    const signature = request.headers.get("x-hub-signature-256") ?? "";
    const raw = await request.text();

    if (!(await verifySignature(raw, signature, env.QA_WEBHOOK_SECRET))) {
      return new Response("invalid signature", { status: 401 });
    }

    let body: any;
    try {
      body = JSON.parse(raw);
    } catch {
      return new Response("bad json", { status: 400 });
    }

    const dispatch = mapEvent(event, body);
    if (!dispatch) {
      // Not an event we act on (e.g. PR closed, a non-QA review). Ack with 204.
      return new Response(null, { status: 204 });
    }

    const res = await fireDispatch(env, dispatch);
    if (!res.ok) {
      // Log status only — never the token or body.
      console.log(`[receiver] dispatch ${dispatch.type} failed: ${res.status}`);
      return new Response("dispatch failed", { status: 502 });
    }
    console.log(`[receiver] dispatched ${dispatch.type} for ${dispatch.payload.prId}`);
    return new Response(null, { status: 202 });
  },
};

/**
 * Part D — the only routing logic.
 *   pull_request  opened|reopened          → pr_opened
 *   pull_request  synchronize              → pr_sync
 *   pull_request_review approved  qa/bdd/*  → bdd_approved
 *   pull_request_review approved  qa/tests/*→ tests_approved
 *   pull_request_review changes_requested  → pr_changes_requested
 */
export function mapEvent(event: string, body: any): Dispatch | null {
  if (event === "pull_request") {
    const action = body.action;
    const pr = body.pull_request;
    if (!pr) return null;
    const base = prPayload(body.repository, pr);
    if (action === "opened" || action === "reopened") {
      return { type: "pr_opened", payload: base };
    }
    if (action === "synchronize") {
      return { type: "pr_sync", payload: base };
    }
    return null;
  }

  if (event === "pull_request_review") {
    const review = body.review;
    const pr = body.pull_request;
    if (!review || !pr) return null;
    const headRef: string = pr.head?.ref ?? "";
    const state: string = (review.state ?? "").toLowerCase();

    if (state === "changes_requested") {
      // prId is encoded in the QA-authored branch name (qa/bdd/PR-… or qa/tests/PR-…).
      const prId = prIdFromBranch(headRef);
      if (!prId) return null;
      return {
        type: "pr_changes_requested",
        payload: {
          ...prPayload(body.repository, pr, prId),
          reviewId: String(review.id ?? ""),
        },
      };
    }

    if (state === "approved") {
      if (headRef.startsWith(QA_BDD_PREFIX)) {
        const prId = prIdFromBranch(headRef);
        if (!prId) return null;
        return { type: "bdd_approved", payload: prPayload(body.repository, pr, prId) };
      }
      if (headRef.startsWith(QA_TESTS_PREFIX)) {
        const prId = prIdFromBranch(headRef);
        if (!prId) return null;
        return { type: "tests_approved", payload: prPayload(body.repository, pr, prId) };
      }
    }
    return null;
  }

  return null;
}

/** Minimal, token-free client_payload. */
function prPayload(repository: any, pr: any, prIdOverride?: string): Record<string, unknown> {
  const owner: string = repository?.owner?.login ?? "";
  const repo: string = repository?.name ?? "";
  const number: number = pr?.number ?? 0;
  return {
    prId: prIdOverride ?? deterministicPrId(owner, repo, number),
    owner,
    repo,
    prNumber: number,
    branch: pr?.head?.ref ?? "",
    headSha: pr?.head?.sha ?? "",
  };
}

/**
 * Deterministic, stable prId for a source PR so all three workflow runs and the
 * Neon pr_history row agree. Format mirrors the existing "PR-XXXXXXXX" style.
 */
export function deterministicPrId(owner: string, repo: string, number: number): string {
  let h = 2166136261 >>> 0; // FNV-1a 32-bit
  const s = `${owner}/${repo}#${number}`;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619) >>> 0;
  }
  return "PR-" + h.toString(16).toUpperCase().padStart(8, "0");
}

/** Extract the source prId encoded in a QA-authored branch (qa/bdd/PR-… , qa/tests/PR-…). */
export function prIdFromBranch(ref: string): string | null {
  const m = ref.match(/^qa\/(?:bdd|tests)\/(PR-[0-9A-Fa-f]+)/);
  return m ? m[1] : null;
}

async function fireDispatch(env: Env, dispatch: Dispatch): Promise<Response> {
  const url = `https://api.github.com/repos/${env.QA_CONTROL_OWNER}/${env.QA_CONTROL_REPO}/dispatches`;
  return fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env.QA_DISPATCH_TOKEN}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "qa-isystem-receiver",
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ event_type: dispatch.type, client_payload: dispatch.payload }),
  });
}

/** Constant-time HMAC-SHA256 verification of the raw request body. */
export async function verifySignature(
  raw: string,
  signatureHeader: string,
  secret: string,
): Promise<boolean> {
  if (!secret || !signatureHeader.startsWith("sha256=")) return false;
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(raw));
  const expected = "sha256=" + toHex(new Uint8Array(mac));
  return timingSafeEqual(expected, signatureHeader);
}

function toHex(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += b.toString(16).padStart(2, "0");
  return s;
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
