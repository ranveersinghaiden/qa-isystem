# QA-ISystem Product Expert Context Files

This directory contains **sample templates** for the product expert context files
that should be created in your **test repository** (not this service).

## Where to put them (in the test repo)

```
your-test-repo/
  productExpert/
    payments/
      PRODUCT.md       ← domain knowledge about the payments module
      PATTERNS.md      ← how to test payments (patterns, pitfalls, known issues)
    authentication/
      PRODUCT.md
      PATTERNS.md
    <any-product-name>/
      PRODUCT.md
      PATTERNS.md
      *.md             ← any additional markdown files
  .aiqa/
    context.md         ← repo-level QA context (environments, conventions, CI notes)
  .github/
    agents/
      api-conventions.md     ← existing agent instruction files (already supported)
```

## How they are used

When `strategy-service` starts, it clones/reads the test repo and:

1. Scans `productExpert/` for subdirectories (one per product)
2. Reads all `.md` files from each product directory
3. Reads `.aiqa/context.md` (if present)
4. Injects all content as the **AI system prompt** when generating BDD scenarios and test code

The AI will generate tests that are grounded in your product's real domain knowledge,
terminology, and testing patterns — rather than generic patterns.

## Feedback loop (automatic updates)

When a QA PR is **rejected** by a reviewer:

1. `PrFeedbackService` fetches all review comments
2. If the feedback reveals a **product knowledge gap**, the service automatically:
   - Updates the relevant `productExpert/{product}/PRODUCT.md`
   - Opens a PR titled `[AI-QA] Product Expert Update: {product}`
3. Re-generates the scenarios/code incorporating the feedback
4. Opens a new review PR

Over time, the product expert files become richer and generation quality improves.

## Sample files (copy to your test repo and customise)

See `PRODUCT.md.template` and `PATTERNS.md.template` in this directory.

