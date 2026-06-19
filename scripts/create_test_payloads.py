#!/usr/bin/env python3
"""Creates 3 distinct PR webhook payloads for concurrent pipeline testing."""
import json

with open("pr-webhook-sample.json") as f:
    base = json.load(f)

raw_diff = base["raw_diff"]

payloads = [
    {
        "title": "VSF-3501: Remove TSFixMe from Fleet domain components",
        "author": "dev@example.com",
        "repositoryName": "myeroad-portal",
        "products": ["myeroad"],
        "sourceBranch": "VSF-3501",
        "raw_diff": raw_diff,
    },
    {
        "title": "VSF-3502: Strict typing for Vehicle management API layer",
        "author": "dev@example.com",
        "repositoryName": "myeroad-portal",
        "products": ["myeroad"],
        "sourceBranch": "VSF-3502",
        "raw_diff": raw_diff,
    },
    {
        "title": "VSF-3503: Replace any types with proper interfaces in Settings module",
        "author": "dev@example.com",
        "repositoryName": "myeroad-portal",
        "products": ["myeroad"],
        "sourceBranch": "VSF-3503",
        "raw_diff": raw_diff,
    },
]

for i, p in enumerate(payloads, 1):
    fname = f"pr-webhook-test-{i}.json"
    with open(fname, "w") as f:
        json.dump(p, f)
    print(f"Created {fname}: {p['title']}")

