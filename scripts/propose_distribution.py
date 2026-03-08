#!/usr/bin/env python3
"""Propose generated recipes in a configured GitHub repository from release CI."""

import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

from generate_distribution import REPOSITORY


def run(*args, cwd=None):
    return subprocess.check_output(args, cwd=cwd, text=True).strip()


def propose(kind, generated, repository, version):
    if not os.environ.get("GH_TOKEN") or not repository:
        print(f"Skipping {kind}: publishing token or target repository is missing")
        return
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid target repository")
    if repository.lower() == REPOSITORY.lower():
        raise ValueError("Distribution proposals must target a separate repository")
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", version):
        raise ValueError("Invalid stable version")
    if run("gh", "repo", "view", REPOSITORY, "--json", "isPrivate", "--jq", ".isPrivate") != "false":
        raise ValueError("Do not distribute recipes while the release repository is private")
    if kind == "flatpak":
        if os.environ.get("FLATPAK_BINARY_EXCEPTION") != "true":
            print("Skipping Flatpak: no confirmed Flathub binary-build exception")
            return
        tree = ET.parse(generated / "flatpak/com.metrolist.desktop.metainfo.xml")
        if tree.find("./screenshots/screenshot/image") is None:
            raise ValueError("Flathub proposals require desktop screenshots")
    source = generated / kind
    # Restrict the copied files to the generated recipe tree, not arbitrary checkout files.
    paths = sorted(path.relative_to(source) for path in source.rglob("*") if path.is_file())
    if not paths or any((source / path).is_symlink() for path in paths):
        raise ValueError("Generated recipe tree is missing or contains symlinks")
    paths = [path for path in paths if path.name != "SUBMISSION-NOTES.txt"]
    branch = f"build/metrolist-{version}"
    proposal_state = run("gh", "pr", "list", "--repo", repository, "--head", branch,
                         "--state", "all", "--json", "state", "--jq", '.[0].state // ""')
    if proposal_state in ("CLOSED", "MERGED"):
        print(f"Proposal already completed for {repository}:{branch}")
        return
    with tempfile.TemporaryDirectory() as temporary:
        checkout = Path(temporary) / "target"
        git = ("git", "-c", "credential.helper=", "-c", "credential.helper=!gh auth git-credential")
        run(*git, "clone", "--depth=1", "--filter=blob:none", "--sparse", "--no-tags",
            "--", f"https://github.com/{repository}.git", str(checkout))
        run(*git, "sparse-checkout", "set", "--no-cone",
            *(f"/{path}" for path in paths), cwd=checkout)
        base = run("git", "symbolic-ref", "--short", "refs/remotes/origin/HEAD", cwd=checkout).removeprefix("origin/")
        remote = run(*git, "ls-remote", "--heads", "origin", f"refs/heads/{branch}", cwd=checkout)
        if remote:
            run(*git, "fetch", "--depth=1", "origin",
                f"refs/heads/{branch}:refs/remotes/origin/{branch}", cwd=checkout)
            run(*git, "checkout", "-b", branch, f"origin/{branch}", cwd=checkout)
        else:
            run(*git, "checkout", "-b", branch, cwd=checkout)
        for path in paths:
            destination = checkout / path
            if any(parent.is_symlink() for parent in [destination, *destination.parents]):
                raise ValueError(f"Refusing to overwrite a symlink: {path}")
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source / path, destination)
        run("git", "add", "--", *(str(path) for path in paths), cwd=checkout)
        if run("git", "diff", "--cached", "--name-only", cwd=checkout):
            run("git", "-c", "user.name=github-actions[bot]", "-c",
                "user.email=41898282+github-actions[bot]@users.noreply.github.com",
                "commit", "-m", f"build(packaging): update Metrolist to {version}", cwd=checkout)
            run(*git, "push", "origin", branch, cwd=checkout)
        elif not remote:
            print(f"Recipes already match {repository}:{base}")
            return
        if proposal_state == "OPEN":
            print(f"Proposal already exists for {repository}:{branch}")
            return
        body = Path(temporary) / "body.md"
        body.write_text(f"Update Metrolist to {version} using checksums verified against the published release assets.\n")
        run("gh", "pr", "create", "--repo", repository, "--base", base, "--head", branch,
            "--draft", "--title", f"build(packaging): update Metrolist to {version}",
            "--body-file", str(body))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("winget", "homebrew", "nix", "flatpak"))
    parser.add_argument("--generated", type=Path, required=True)
    parser.add_argument("--repository", default="")
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    propose(args.kind, args.generated, args.repository, args.version)


if __name__ == "__main__":
    main()
