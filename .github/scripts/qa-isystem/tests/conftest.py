"""Shared pytest fixtures/setup for .github/scripts/qa-isystem/tests/.

gather_context.py is a plain sibling module in this directory.
Insert that directory onto sys.path so tests can import them regardless of
where pytest is invoked from.
"""

from __future__ import annotations

import sys
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent.parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))
