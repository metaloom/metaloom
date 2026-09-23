"""
Packaging tests: does the Dockerfile actually ship what the server imports?

This file exists because of a real failure. `mask_ops.py` was extracted from `server.py`
so the mask arithmetic could be unit-tested without fastapi, and the Dockerfile's `COPY`
line was not updated. The image built fine, pushed fine, and died on startup with
`ModuleNotFoundError: No module named 'mask_ops'` — a deploy-time failure for a mistake
that is visible in the source.

Nothing else catches it: the route and mask tests import from the working directory, where
every file is present by definition, so they pass on a tree that cannot be containerised.

Keep this cheap and static. It parses files; it does not build an image.
"""

import ast
import re
import sys
from pathlib import Path

HERE = Path(__file__).parent

# Modules that ship in the image. Test files and the venv deliberately do not.
ENTRYPOINTS = ["server.py"]


def _local_module_names() -> set:
    """Every .py file in this directory that another module could import."""
    return {p.stem for p in HERE.glob("*.py")}


def _imports_of(path: Path) -> set:
    """Top-level module names imported by `path`, including inside functions."""
    tree = ast.parse(path.read_text())
    names = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            names.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom):
            # `from . import x` has no module; relative imports are not used here.
            if node.module and node.level == 0:
                names.add(node.module.split(".")[0])
    return names


def _copied_files() -> set:
    """Filenames the Dockerfile COPYs into the image."""
    dockerfile = (HERE / "Dockerfile").read_text()
    copied = set()
    for line in dockerfile.splitlines():
        line = line.strip()
        if not line.startswith("COPY "):
            continue
        # COPY a.py b.py ./   ->  everything between the verb and the destination
        parts = re.split(r"\s+", line)[1:-1]
        copied.update(parts)
    return copied


def _transitive_local_imports(entrypoint: str) -> set:
    """Local modules reachable from `entrypoint`, following local imports."""
    local = _local_module_names()
    seen = set()
    queue = [entrypoint]
    while queue:
        current = queue.pop()
        if current in seen:
            continue
        seen.add(current)
        for name in _imports_of(HERE / current):
            if name in local and f"{name}.py" not in seen:
                queue.append(f"{name}.py")
    return seen


def test_the_dockerfile_copies_every_module_the_server_imports():
    """The one that would have caught the mask_ops omission."""
    copied = _copied_files()
    for entrypoint in ENTRYPOINTS:
        for module in _transitive_local_imports(entrypoint):
            assert module in copied, (
                f"{module} is imported (transitively) by {entrypoint} but is not COPYed "
                f"in the Dockerfile. The image would build and then die on startup with "
                f"ModuleNotFoundError. COPY line holds: {sorted(copied)}"
            )


def test_the_dockerfile_does_not_copy_files_that_do_not_exist():
    """A stale COPY is a build failure rather than a runtime one, but still worth naming."""
    for name in _copied_files():
        assert (HERE / name).exists(), f"the Dockerfile COPYs {name}, which is not in {HERE}"


def test_the_diffusers_pin_agrees_across_all_three_places():
    """
    The commit is written down in the loader, setup.sh and the Dockerfile. They drift
    silently: a mismatch means the venv and the image run different pipeline code, and the
    symptom is a behaviour difference between a local run and a deployed one.
    """
    import qwen_loader

    dockerfile = (HERE / "Dockerfile").read_text()
    setup = (HERE / "setup.sh").read_text()
    pin = qwen_loader.DIFFUSERS_GIT_REF

    assert pin in dockerfile, f"Dockerfile does not pin {pin}"
    assert pin in setup, f"setup.sh does not pin {pin}"


def test_torch_and_torchvision_are_installed_together():
    """
    torchvision is not optional and is easy to leave out, because nothing in this
    sidecar's own code imports it. The Qwen3-VL processor the pipeline loads pulls in
    Qwen3VLVideoProcessor, which hard-requires it; without it the model load dies at
    runtime with "requires the Torchvision library but it was not found" - the second
    deploy-time-only failure this file was written for.

    They must also come from the SAME install line, so the compiled ABIs match: torch
    from the CUDA index and torchvision from PyPI is a subtler version of the same bug.
    """
    for name in ("Dockerfile", "setup.sh"):
        # Backslash continuations first: the Dockerfile splits `pip install` from its
        # package list across lines, so a naive per-line scan finds neither.
        text = (HERE / name).read_text().replace("\\\n", " ")
        install_lines = [
            line for line in text.splitlines()
            if "pip" in line and "install" in line and "torch" in line and not line.strip().startswith("#")
        ]
        assert install_lines, f"{name} has no torch install line"
        assert any("torchvision" in line for line in install_lines), (
            f"{name} installs torch but not torchvision. The model load will fail with "
            f"'Qwen3VLVideoProcessor requires the Torchvision library'. Lines: {install_lines}"
        )
        assert any("torch" in line and "torchvision" in line for line in install_lines), (
            f"{name} installs torch and torchvision on separate lines; install them together "
            f"from one index so their compiled ABIs match"
        )


def test_the_exposed_port_matches_the_default():
    """9230 is written in the Dockerfile's EXPOSE, its CMD, and run.sh."""
    dockerfile = (HERE / "Dockerfile").read_text()
    run_sh = (HERE / "run.sh").read_text()
    assert "EXPOSE 9230" in dockerfile
    assert '"--port", "9230"' in dockerfile
    assert "9230" in run_sh


if __name__ == "__main__":
    failures = []
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print(f"PASS {name}")
            except AssertionError as exc:
                failures.append(name)
                print(f"FAIL {name}: {exc}")
    print(f"\n{len(failures)} failure(s)" + (": " + ", ".join(failures) if failures else ""))
    sys.exit(1 if failures else 0)
