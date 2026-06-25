"""
Test configuration for mock-agent integration tests.

Both wealth/ and servicing/ have a `shared/` package.  When both paths are in
sys.path, whichever is imported first wins and gets cached in sys.modules — the
second package's `shared/` is then invisible.

This conftest provides a pytest fixture that servicing test classes use to
temporarily make servicing's `shared/` take precedence, then restore state.
"""
import os
import sys
import pytest

_SERVICING_PATH = os.path.join(os.path.dirname(__file__), "../servicing")
_WEALTH_PATH    = os.path.join(os.path.dirname(__file__), "../wealth")

_SHARED_PREFIXES = ("shared", "custody", "settlements", "cash", "nav",
                    "corporate_actions")


def _clear_servicing_cache():
    """Remove servicing-owned modules from sys.modules so they re-import cleanly."""
    for key in list(sys.modules.keys()):
        if any(key == p or key.startswith(p + ".") for p in _SHARED_PREFIXES):
            del sys.modules[key]


@pytest.fixture(scope="function")
def servicing_imports():
    """
    Ensure servicing package takes priority over wealth for the duration of a test.

    Usage — add to any test class whose methods import from the servicing package:
        @pytest.fixture(autouse=True)
        def _fix(self, servicing_imports): pass
    """
    # Put servicing first so its shared/ beats wealth's shared/
    if _SERVICING_PATH in sys.path:
        sys.path.remove(_SERVICING_PATH)
    sys.path.insert(0, _SERVICING_PATH)
    _clear_servicing_cache()

    yield

    # Restore: put servicing AFTER wealth so wealth tests can run cleanly next
    if _SERVICING_PATH in sys.path:
        sys.path.remove(_SERVICING_PATH)
    wealth_idx = sys.path.index(_WEALTH_PATH) if _WEALTH_PATH in sys.path else 0
    sys.path.insert(wealth_idx + 1, _SERVICING_PATH)
    _clear_servicing_cache()
