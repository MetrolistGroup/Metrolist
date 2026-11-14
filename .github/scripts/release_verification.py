"""Require completed GitHub Actions verification before release packaging."""

import json
import os
from pathlib import Path
import subprocess
import sys
import time

from ci_paths import changed_paths, classify, metadata_only


CHECKS = {
    "android": ("build-android.yml", "Android / APK"),
    "desktop": ("build-desktop.yml", "Desktop / Linux x64"),
    "ios": ("build-ios.yml", "iOS App & Framework / Validate iOS"),
    "repository": ("verify-repository-checks.yml", "checks"),
}


class ApiError(ValueError):
    """Stop the gate when GitHub cannot supply verification evidence."""


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def source_commit(head):
    # First-parent history follows the release branch, never an unrelated merge parent.
    candidate = head
    for parent in git("rev-list", "--first-parent", head).splitlines()[1:]:
        if not metadata_only(parent, head):
            return candidate
        candidate = parent
    raise ValueError("Cannot find a source commit before the release metadata. Fetch full history.")


def api(path):
    for attempt in range(3):
        result = subprocess.run(["gh", "api", "--paginate", "--slurp", path], capture_output=True, text=True)
        if not result.returncode:
            try:
                return json.loads(result.stdout)
            except json.JSONDecodeError as error:
                raise ApiError("Release blocked before packaging. GitHub returned unreadable verification evidence. Retry after GitHub recovers.") from error
        transient = any(message in result.stderr.lower() for message in ("rate limit", "http 429", "http 502", "http 503", "http 504"))
        if not transient or attempt == 2:
            break
        time.sleep(2 ** (attempt + 1))
    raise ApiError(f"Release blocked before packaging. GitHub API could not read {path}. Check actions:read and checks:read permissions, API availability and rate limits. "
                   "A renamed workflow file needs a CHECKS update in release_verification.py. Retry after recovery; verification stays required.")


def verify_run(repository, sha, workflow, job_name, get=api, required=True):
    pages = get(f"repos/{repository}/actions/workflows/{workflow}/runs?head_sha={sha}&per_page=100")
    runs = [run for page in pages for run in page["workflow_runs"] if
            run["head_sha"] == sha and run["head_branch"] == "main" and
            run["event"] in ("push", "workflow_dispatch") and
            run["path"] == f".github/workflows/{workflow}"]
    if not runs:
        if not required:
            return None
        raise ValueError(f"{workflow}: no trusted main-branch verification for {sha}.")
    run = max(runs, key=lambda item: (item["run_number"], item.get("run_attempt", 1)))
    if run["status"] != "completed" or run["conclusion"] != "success":
        raise ValueError(f"{workflow}: latest run is {run['status']}/{run['conclusion']}: {run['html_url']}")
    jobs = [job for page in get(f"repos/{repository}/actions/runs/{run['id']}/jobs?filter=latest&per_page=100") for job in page["jobs"]]
    matches = [job for job in jobs if job["name"] == job_name]
    if not required and (not matches or matches[0]["conclusion"] == "skipped"):
        return None
    if len(matches) != 1 or matches[0]["conclusion"] != "success":
        raise ValueError(f"{workflow}: required job {job_name!r} did not succeed: {run['html_url']}")
    if workflow == "build-android.yml" and not any(step["name"] == "Test Android string generation" and step["conclusion"] == "success" for step in matches[0].get("steps", [])):
        raise ValueError(f"{workflow}: this run only packaged an APK. Dispatch variant=debug to run Android verification.")
    check_id = matches[0]["check_run_url"].rsplit("/", 1)[1]
    check = get(f"repos/{repository}/check-runs/{check_id}")[0]
    if check["head_sha"] != sha or check["app"]["slug"] != "github-actions" or check["status"] != "completed" or check["conclusion"] != "success":
        raise ValueError(f"{workflow}: GitHub Actions check run did not succeed for {sha}.")
    return run["html_url"]


def main():
    head = git("rev-parse", os.environ.get("GITHUB_SHA", "HEAD"))
    source = source_commit(head)
    parent = git("rev-parse", f"{source}^1")
    needed = classify(changed_paths(parent, source))
    repository = os.environ["GITHUB_REPOSITORY"]
    # A manual retry can verify the release revision itself. Otherwise use its source commit.
    errors = []
    candidates = [*git("rev-list", "--first-parent", f"{source}..{head}").splitlines(), source]
    for candidate in candidates:
        try:
            # A push can include several commits. Reject failed platform runs even when the last commit did not touch that platform.
            links = [verify_run(repository, candidate, *CHECKS[key], required=needed[key]) for key in CHECKS]
        except ApiError:
            raise
        except ValueError as error:
            errors.append(str(error))
            continue
        summary = f"Release verification reused from {candidate}.\n" + "\n".join(link for link in links if link) + "\n"
        print(summary)
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as stream:
                stream.write(summary)
        return
    raise ValueError("Release blocked before packaging. " + " ".join(errors) +
                     " Run the required Verify workflows on main at the release revision, then retry the release. See docs/releasing.md.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, subprocess.CalledProcessError) as error:
        print(f"::error::{error}", file=sys.stderr)
        sys.exit(1)
