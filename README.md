# QA-isystem tooling

Standalone Python utilities for creating and validating synthetic QA context. This repository contains no application services, deployment configuration, production product knowledge, or external repository coupling.

## Requirements

- Python 3.12 or later
- `pytest` to run the test suite

## Layout

- `.github/scripts/qa-isystem/` — standard-library Python tools and safe shell helpers
- `.github/scripts/qa-isystem/tests/` — pytest tests and synthetic fixtures
- `.github/workflows/` — read-only validation workflows

## Run tests

```bash
python3 -m pytest .github/scripts/qa-isystem/tests -q
```

## Workflows

The workflows validate local tooling only. They do not create commits or pull requests, dispatch work to other repositories, access private packages, or use repository secrets. `fetch_jira_summary.sh` is not called by any workflow; it is an optional local helper that requires its issue-tracker configuration and token through environment variables.
