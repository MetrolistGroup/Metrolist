#!/usr/bin/env python3
"""Exercise read-only Git hooks in a temporary repository without committing."""
import os
from pathlib import Path
import subprocess
import tempfile


hooks = Path(__file__).resolve().parents[1] / ".githooks"
environment = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
environment.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull)

with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)

    def git(*args, env=environment):
        return subprocess.run(["git", *args], cwd=root, env=env, check=True, capture_output=True).stdout

    def working_files():
        return {
            path.relative_to(root): path.read_bytes()
            for path in root.rglob("*")
            if ".git" not in path.relative_to(root).parts and path.is_file()
        }

    def run_hook(name, *args, succeeds=True, diagnostic=None, env=environment, cwd=None):
        indices = (git("ls-files", "--stage", "-z"), git("ls-files", "--stage", "-z", env=env))
        files = working_files()
        result = subprocess.run([str(hooks / name), *map(str, args)], cwd=cwd or root, env=env, capture_output=True)
        assert (result.returncode == 0) == succeeds, (name, args, result.returncode, result.stderr)
        if diagnostic:
            assert diagnostic in result.stdout + result.stderr, (result.stdout, result.stderr)
        assert b"Traceback" not in result.stderr, result.stderr
        assert indices == (git("ls-files", "--stage", "-z"), git("ls-files", "--stage", "-z", env=env))
        assert working_files() == files, f"{name} changed the working tree or message"

    def stage(path, content, env=environment):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
        git("add", "--", str(path.relative_to(root)), env=env)

    git("init", "--quiet")
    staged = root / "staged.txt"
    unstaged = root / "unstaged.txt"
    stage(staged, b"staged\n")
    stage(unstaged, b"saved\n")
    staged.write_bytes(b"staged\nworking copy \n")
    unstaged.write_bytes(b"unsaved\n")
    run_hook("pre-commit")

    stage(staged, b"trailing whitespace \n")
    staged.write_bytes(b"valid working copy\n")
    run_hook("pre-commit", succeeds=False, diagnostic=b"trailing whitespace")
    stage(staged, b"staged\n")

    resource = root / "shared/src/commonMain/composeResources/values/strings.xml"
    valid = b'<resources><string name="label">50% &amp; valid</string></resources>\n'
    invalid_escape = b'<resources><string name="label">50%%</string></resources>\n'
    invalid_xml = b"<resources><string>\n"
    stage(resource, valid)
    resource.write_bytes(invalid_xml)
    run_hook("pre-commit")

    for content, diagnostic in ((invalid_escape, b"Android-only escape"), (invalid_xml, b"invalid XML")):
        stage(resource, content)
        resource.write_bytes(valid)
        run_hook("pre-commit", succeeds=False, diagnostic=diagnostic)
    stage(resource, valid)
    resource.unlink()
    run_hook("pre-commit")

    git("rm", "--cached", "--", str(resource.relative_to(root)))
    resource.write_bytes(invalid_xml)
    for name in (
        "unrelated/values-test/strings.xml",
        "shared/src/commonMain/composeResources/values-test/other.xml",
        "shared/src/commonMain/composeResources/values-test/nested/strings.xml",
    ):
        stage(root / name, invalid_xml)
    run_hook("pre-commit")

    resource = resource.parent.parent / "values-test locale-é\nextra" / "strings.xml"
    stage(resource, invalid_escape)
    resource.write_bytes(valid)
    run_hook("pre-commit", succeeds=False, diagnostic=b"Android-only escape")
    stage(resource, valid)
    renamed = resource.parent.parent / "values-renamed" / "strings.xml"
    renamed.parent.mkdir()
    git("mv", "--", str(resource.relative_to(root)), str(renamed.relative_to(root)))
    run_hook("pre-commit")

    nested = root / "nested"
    nested.mkdir()
    run_hook("pre-commit", cwd=nested)
    alternate = {**environment, "GIT_INDEX_FILE": str(root / ".git/alternate-index")}
    git("read-tree", "--empty", env=alternate)
    stage(renamed, invalid_escape, env=alternate)
    run_hook("pre-commit", succeeds=False, diagnostic=b"Android-only escape", env=alternate)
    run_hook("pre-commit")

    message = root / "commit message.txt"
    allowed_types = "feat fix docs style refactor perf test build ci chore revert".split()
    subjects = [f"{kind}: preserve queue position" for kind in allowed_types]
    subjects += [
        "fix(player): preserve queue position",
        "feat!: change playback API",
        "feat(player)!: change playback API",
        "docs(contributor guide): explain setup",
        "fix: x",
        "fix(player): retain café artwork",
    ]
    for subject in subjects:
        message.write_text(subject + "\n\nA detailed body.\n\nBREAKING CHANGE: a footer is allowed\n", encoding="utf-8")
        run_hook("commit-msg", message)
    message.write_bytes(b"fix(player): preserve queue position\r\n\r\nBody\r\n")
    run_hook("commit-msg", message)

    for subject in (
        "", "\nfix: preserve position", "fix:", "fix: ", "fix:preserve position", "Fix: preserve position",
        "feature: add playback", "fix(): preserve position", "fix( ): preserve position",
        "fix(player: preserve position", "fix((player)): preserve position", "fix!!: preserve position",
        "fix: preserve position.", "fix: preserve position ", " fix: preserve position",
        "fixup! fix: preserve position", "squash! fix: preserve position", "amend! fix: preserve position",
        "Merge branch 'translations'", 'Revert "fix: preserve position"',
    ):
        message.write_text(subject + "\n", encoding="utf-8")
        run_hook("commit-msg", message, succeeds=False, diagnostic=b"Use <type>")
    message.write_bytes(b"fix: \xff\n")
    run_hook("commit-msg", message, succeeds=False, diagnostic=b"Cannot read commit message")
    run_hook("commit-msg", root / "missing.txt", succeeds=False, diagnostic=b"Cannot read commit message")
    run_hook("commit-msg", succeeds=False, diagnostic=b"Usage:")

assert not (hooks / "pre-push").exists()
assert not (hooks / "git").exists()
print("Git hook validation and read-only safety checks passed")
