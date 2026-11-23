# Copyright 2026 Max97k
# SPDX-License-Identifier: Apache-2.0

"""Build, verify, and publish a signed AAB to Google Play Internal Testing.

Uses only Python 3.10+ standard library and gcloud service-account impersonation
(no local service-account JSON key file required).
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
import zipfile

DEFAULT_ACCOUNT = "4r099015@gmail.com"
DEFAULT_SERVICE_ACCOUNT = "play-store@my-project-play-store-490116.iam.gserviceaccount.com"
DEFAULT_GITHUB_REPO = "Max97k/voice-keyboard-pc-bridge"
PLAY_RELEASE_NOTES_MAX_CHARS = 500
ANDROID_PUBLISHER_SCOPE = "https://www.googleapis.com/auth/androidpublisher"
API_BASE = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
UPLOAD_BASE = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications"

_APP_ID_RE = re.compile(r'applicationId\s*=\s*"([^"]+)"')
_VERSION_CODE_RE = re.compile(r"(versionCode\s*=\s*)(\d+)")
_VERSION_NAME_RE = re.compile(r'(versionName\s*=\s*)"([^"]+)"')
_SIGNATURE_ENTRY_RE = re.compile(r"^META-INF/[^/]+\.(RSA|DSA|EC)$", re.IGNORECASE)


class PlayPublishError(RuntimeError):
    """Raised when building, verifying, or publishing to Play Console fails."""


@dataclass(frozen=True)
class GradleAppConfig:
    application_id: str
    version_code: int
    version_name: str


def read_gradle_app_config(gradle_text: str) -> GradleAppConfig:
    app_ids = _APP_ID_RE.findall(gradle_text)
    version_codes = _VERSION_CODE_RE.findall(gradle_text)
    version_names = _VERSION_NAME_RE.findall(gradle_text)
    if len(app_ids) != 1:
        raise ValueError(f"Expected 1 applicationId in build.gradle.kts, found {len(app_ids)}")
    if len(version_codes) != 1:
        raise ValueError(f"Expected 1 versionCode in build.gradle.kts, found {len(version_codes)}")
    if len(version_names) != 1:
        raise ValueError(f"Expected 1 versionName in build.gradle.kts, found {len(version_names)}")
    return GradleAppConfig(
        application_id=app_ids[0],
        version_code=int(version_codes[0][1]),
        version_name=version_names[0][1],
    )


def update_gradle_version(
    gradle_text: str,
    new_version_code: int,
    new_version_name: str | None = None,
) -> str:
    if new_version_code <= 0:
        raise ValueError(f"new_version_code must be positive, got {new_version_code}")
    updated, code_count = _VERSION_CODE_RE.subn(rf"\g<1>{new_version_code}", gradle_text, count=2)
    if code_count != 1:
        raise ValueError(f"Expected 1 versionCode replacement, got {code_count}")
    if new_version_name is not None:
        if not new_version_name.strip() or '"' in new_version_name:
            raise ValueError(f"Invalid versionName: {new_version_name!r}")
        updated, name_count = _VERSION_NAME_RE.subn(rf'\g<1>"{new_version_name}"', updated, count=2)
        if name_count != 1:
            raise ValueError(f"Expected 1 versionName replacement, got {name_count}")
    return updated


def verify_signed_aab(aab_path: Path) -> int:
    if not aab_path.is_file():
        raise PlayPublishError(f"AAB not found: {aab_path}")
    size = aab_path.stat().st_size
    if size <= 0:
        raise PlayPublishError(f"AAB is empty: {aab_path}")
    try:
        with zipfile.ZipFile(aab_path, "r") as zf:
            names = zf.namelist()
    except zipfile.BadZipFile as exc:
        raise PlayPublishError(f"AAB is not a valid ZIP archive: {aab_path}") from exc
    if not any(_SIGNATURE_ENTRY_RE.match(name) for name in names):
        raise PlayPublishError(f"AAB is missing META-INF signature (.RSA/.DSA/.EC): {aab_path}")
    return size


def _resolve_gcloud(which_fn=shutil.which) -> str:
    for candidate in ("gcloud.cmd", "gcloud"):
        resolved = which_fn(candidate)
        if resolved:
            return resolved
    raise PlayPublishError("gcloud CLI not found on PATH.")


def get_impersonated_access_token(
    account: str = DEFAULT_ACCOUNT,
    service_account: str = DEFAULT_SERVICE_ACCOUNT,
    run_fn=subprocess.run,
    which_fn=shutil.which,
) -> str:
    gcloud_exe = _resolve_gcloud(which_fn=which_fn)
    cmd = [
        gcloud_exe,
        "auth",
        "print-access-token",
        f"--account={account}",
        f"--impersonate-service-account={service_account}",
        f"--scopes={ANDROID_PUBLISHER_SCOPE}",
    ]
    proc = run_fn(cmd, capture_output=True, text=True, check=False)
    if proc.returncode != 0:
        stderr = (proc.stderr or "").strip()
        raise PlayPublishError(f"Failed to obtain impersonated access token via gcloud: {stderr}")
    token = (proc.stdout or "").strip()
    if not token:
        raise PlayPublishError("gcloud returned an empty access token.")
    return token


def _format_http_error(exc: urllib.error.HTTPError, body_text: str) -> PlayPublishError:
    lower = body_text.lower()
    if exc.code == 400 and ("draft app" in lower or "status draft" in lower):
        return PlayPublishError(
            "Google Play rejected a completed release because this app is still in Draft state. "
            "Either re-run with `--status draft` to stage a draft release, or finish the initial "
            f"Publish/Rollout once in Play Console web UI. Raw response: {body_text}"
        )
    if exc.code == 403 and "play signing" in lower:
        return PlayPublishError(
            "Google Play rejected the App Bundle upload because Play App Signing is not enrolled yet. "
            "Open Play Console -> Internal testing -> Create new release (or App integrity -> Play App Signing) "
            "and upload the first .aab manually once to enroll Play App Signing. "
            f"Raw response: {body_text}"
        )
    return PlayPublishError(f"Google Play API error HTTP {exc.code}: {body_text}")


def _api_request(
    url: str,
    method: str,
    token: str,
    body_bytes: bytes | None = None,
    content_type: str = "application/json",
    timeout: int = 60,
    urlopen_fn=urllib.request.urlopen,
) -> dict[str, object]:
    headers = {"Authorization": f"Bearer {token}"}
    if body_bytes is not None:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(url, data=body_bytes, headers=headers, method=method)
    try:
        with urlopen_fn(req, timeout=timeout) as resp:
            raw = resp.read()
    except urllib.error.HTTPError as exc:
        err_body = exc.read().decode("utf-8", errors="replace")
        raise _format_http_error(exc, err_body) from exc
    except urllib.error.URLError as exc:
        raise PlayPublishError(f"Network error calling Google Play API ({url}): {exc.reason}") from exc

    if not raw:
        return {}
    try:
        parsed = json.loads(raw.decode("utf-8"))
    except json.JSONDecodeError as exc:
        raise PlayPublishError(f"Invalid JSON response from Google Play API ({url})") from exc
    if not isinstance(parsed, dict):
        raise PlayPublishError(f"Unexpected non-object JSON response from Google Play API ({url})")
    return parsed


def publish_aab_to_play(
    package_name: str,
    aab_path: Path,
    token: str,
    track: str = "internal",
    release_status: str = "completed",
    release_name: str | None = None,
    release_notes: str | None = None,
    urlopen_fn=urllib.request.urlopen,
) -> dict[str, object]:
    edit_resp = _api_request(
        url=f"{API_BASE}/{package_name}/edits",
        method="POST",
        token=token,
        body_bytes=b"{}",
        urlopen_fn=urlopen_fn,
    )
    edit_id = edit_resp.get("id")
    if not isinstance(edit_id, str) or not edit_id:
        raise PlayPublishError(f"Missing edit id in Play API response: {edit_resp}")

    try:
        aab_bytes = Path(aab_path).read_bytes()
        upload_resp = _api_request(
            url=f"{UPLOAD_BASE}/{package_name}/edits/{edit_id}/bundles?uploadType=media",
            method="POST",
            token=token,
            body_bytes=aab_bytes,
            content_type="application/octet-stream",
            timeout=180,
            urlopen_fn=urlopen_fn,
        )
        raw_vc = upload_resp.get("versionCode")
        if raw_vc is None:
            raise PlayPublishError(f"Missing versionCode in bundle upload response: {upload_resp}")
        uploaded_version_code = int(raw_vc)  # type: ignore[arg-type]

        release: dict[str, object] = {
            "versionCodes": [str(uploaded_version_code)],
            "status": release_status,
        }
        if release_name:
            release["name"] = release_name
        if release_notes:
            release["releaseNotes"] = [
                {"language": "en-US", "text": release_notes},
                {"language": "zh-TW", "text": release_notes},
            ]

        track_body = json.dumps({"track": track, "releases": [release]}).encode("utf-8")
        _api_request(
            url=f"{API_BASE}/{package_name}/edits/{edit_id}/tracks/{track}",
            method="PUT",
            token=token,
            body_bytes=track_body,
            urlopen_fn=urlopen_fn,
        )

        _api_request(
            url=f"{API_BASE}/{package_name}/edits/{edit_id}:commit",
            method="POST",
            token=token,
            body_bytes=b"",
            urlopen_fn=urlopen_fn,
        )
        return {
            "editId": edit_id,
            "packageName": package_name,
            "track": track,
            "status": release_status,
            "versionCode": uploaded_version_code,
        }
    except Exception:
        try:
            _api_request(
                url=f"{API_BASE}/{package_name}/edits/{edit_id}",
                method="DELETE",
                token=token,
                urlopen_fn=urlopen_fn,
            )
        except Exception:
            pass
        raise


def _normalize_tag(version_name: str) -> str:
    clean = version_name.strip().lstrip("vV")
    if not clean:
        raise ValueError(f"Invalid version_name: {version_name!r}")
    return f"v{clean}"


def build_github_release_url(version_name: str, repo: str = DEFAULT_GITHUB_REPO) -> str:
    tag = _normalize_tag(version_name)
    return f"https://github.com/{repo}/releases/tag/{tag}"


def format_play_release_notes(
    notes: str | None,
    version_name: str,
    repo: str = DEFAULT_GITHUB_REPO,
    max_chars: int = PLAY_RELEASE_NOTES_MAX_CHARS,
) -> str:
    tag = _normalize_tag(version_name)
    url = build_github_release_url(version_name, repo=repo)
    footer = f"Release notes: {url}"
    summary = (notes or "").strip()
    if not summary:
        return f"{tag} - {url}"[:max_chars]

    sep = "\n\n"
    budget = max_chars - len(sep) - len(footer)
    if budget <= 0:
        return footer[:max_chars]
    if len(summary) > budget:
        summary = summary[: max(0, budget - 1)].rstrip() + "…"
    return f"{summary}{sep}{footer}"


def _resolve_gh(which_fn=shutil.which) -> str:
    for candidate in ("gh.exe", "gh"):
        resolved = which_fn(candidate)
        if resolved:
            return resolved
    raise PlayPublishError("GitHub CLI (gh) not found on PATH.")


def _verify_git_ready_for_release(repo_root: Path, run_fn=subprocess.run) -> None:
    status_proc = run_fn(
        ["git", "-C", str(repo_root), "status", "--porcelain"],
        capture_output=True,
        text=True,
        check=False,
    )
    if status_proc.returncode != 0:
        raise PlayPublishError(f"git status failed: {(status_proc.stderr or '').strip()}")
    if (status_proc.stdout or "").strip():
        raise PlayPublishError(
            "Working tree has uncommitted changes; commit and push before running with --github-release."
        )
    ahead_proc = run_fn(
        ["git", "-C", str(repo_root), "rev-list", "--count", "@{u}..HEAD"],
        capture_output=True,
        text=True,
        check=False,
    )
    if ahead_proc.returncode != 0:
        raise PlayPublishError(
            "Current branch has no upstream tracking branch; push to GitHub before --github-release."
        )
    ahead_count = int((ahead_proc.stdout or "0").strip() or "0")
    if ahead_count > 0:
        raise PlayPublishError(
            f"Local branch is ahead of upstream by {ahead_count} commit(s); push before --github-release."
        )


def sync_github_release(
    version_name: str,
    version_code: int,
    track: str,
    notes: str,
    repo: str = DEFAULT_GITHUB_REPO,
    run_fn=subprocess.run,
    which_fn=shutil.which,
) -> str:
    gh_exe = _resolve_gh(which_fn=which_fn)
    tag = _normalize_tag(version_name)
    url = build_github_release_url(version_name, repo=repo)
    header = f"Google Play `{track}` track: `{tag} ({version_code})`"
    body = f"{header}\n\n{notes.strip()}" if notes.strip() else header

    view_proc = run_fn(
        [gh_exe, "release", "view", tag, "--repo", repo],
        capture_output=True,
        text=True,
        check=False,
    )
    action = "edit" if view_proc.returncode == 0 else "create"
    proc = run_fn(
        [gh_exe, "release", action, tag, "--repo", repo, "--title", tag, "--notes", body],
        capture_output=True,
        text=True,
        check=False,
    )
    if proc.returncode != 0:
        detail = (proc.stderr or proc.stdout or "").strip()
        raise PlayPublishError(f"gh release {action} failed for {tag}: {detail}")
    return url


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Bump versionCode, build signed release AAB, and publish to Google Play."
    )
    parser.add_argument("--package", help="Override Android applicationId from app/build.gradle.kts")
    parser.add_argument("--track", default="internal", help="Google Play release track (default: internal)")
    parser.add_argument(
        "--status",
        choices=("completed", "draft"),
        default="completed",
        help="Track release status (default: completed)",
    )
    parser.add_argument("--no-bump", action="store_true", help="Do not increment versionCode before building")
    parser.add_argument("--version-name", help="Optional new versionName to write into app/build.gradle.kts")
    parser.add_argument("--release-notes", help="Optional release notes summary (applied to Play en-US/zh-TW)")
    parser.add_argument("--notes-file", help="Optional UTF-8 Markdown file containing full release notes")
    parser.add_argument(
        "--github-repo",
        default=DEFAULT_GITHUB_REPO,
        help=f"GitHub repository slug for release notes links (default: {DEFAULT_GITHUB_REPO})",
    )
    parser.add_argument(
        "--github-release",
        action="store_true",
        help="Create or update the matching GitHub Release v<versionName> via gh CLI after Play commit",
    )
    parser.add_argument("--skip-build", action="store_true", help="Skip Gradle build and publish existing AAB")
    parser.add_argument(
        "--skip-checks",
        action="store_true",
        help="Skip Spotless, Licensee, CycloneDX SBOM, unit tests, and lint before bundleRelease",
    )
    parser.add_argument("--aab", help="Path to signed .aab file (defaults to release bundle output)")
    parser.add_argument("--account", default=DEFAULT_ACCOUNT, help=f"gcloud user account (default: {DEFAULT_ACCOUNT})")
    parser.add_argument(
        "--service-account",
        default=DEFAULT_SERVICE_ACCOUNT,
        help=f"Play Console service account to impersonate (default: {DEFAULT_SERVICE_ACCOUNT})",
    )
    parser.add_argument("--dry-run", action="store_true", help="Print resolved actions without modifying files or calling Play API")
    return parser


def _run_gradle(repo_root: Path, tasks: list[str], run_fn=subprocess.run) -> None:
    gradlew = repo_root / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
    if not gradlew.is_file():
        raise PlayPublishError(f"Gradle wrapper not found: {gradlew}")
    cmd = [str(gradlew), *tasks, "--console=plain"]
    proc = run_fn(cmd, cwd=str(repo_root), check=False)
    if proc.returncode != 0:
        raise PlayPublishError(f"Gradle build failed with exit code {proc.returncode}: {' '.join(tasks)}")


def main(
    argv: list[str] | None = None,
    *,
    repo_root: Path | None = None,
    run_fn=subprocess.run,
    which_fn=shutil.which,
    urlopen_fn=urllib.request.urlopen,
) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)

    root = repo_root or Path(__file__).resolve().parent.parent
    gradle_path = root / "app" / "build.gradle.kts"
    keystore_props = root / "keystore.properties"
    aab_path = (
        Path(args.aab)
        if args.aab
        else (root / "app" / "build" / "outputs" / "bundle" / "release" / "app-release.aab")
    )

    try:
        original_gradle = gradle_path.read_text(encoding="utf-8")
        cfg = read_gradle_app_config(original_gradle)
    except (OSError, ValueError) as exc:
        print(f"[error] Failed to read {gradle_path}: {exc}", file=sys.stderr, flush=True)
        return 1

    notes_from_file = ""
    if args.notes_file:
        try:
            notes_from_file = Path(args.notes_file).read_text(encoding="utf-8").strip()
        except OSError as exc:
            print(f"[error] Failed to read --notes-file {args.notes_file}: {exc}", file=sys.stderr, flush=True)
            return 1

    package_name = args.package or cfg.application_id
    should_bump = (not args.no_bump) and (not args.skip_build)
    target_version_code = cfg.version_code + 1 if should_bump else cfg.version_code
    target_version_name = args.version_name or cfg.version_name
    tag = _normalize_tag(target_version_name)
    play_release_name = f"{tag} ({target_version_code})"
    github_release_url = build_github_release_url(target_version_name, repo=args.github_repo)
    github_notes = notes_from_file or (args.release_notes or "")
    play_notes = format_play_release_notes(
        notes=args.release_notes or notes_from_file,
        version_name=target_version_name,
        repo=args.github_repo,
    )
    pre_build_checks = (
        []
        if args.skip_checks
        else [
            ":app:spotlessCheck",
            ":app:licenseeAndroidRelease",
            ":app:cyclonedxDirectBom",
            ":app:testDebugUnitTest",
            ":app:lintDebug",
        ]
    )
    gradle_tasks = [] if args.skip_build else pre_build_checks + [":app:bundleRelease"]

    if not args.skip_build and not keystore_props.is_file():
        print(f"[error] Missing {keystore_props}; cannot build signed release AAB.", file=sys.stderr, flush=True)
        return 1

    if args.dry_run:
        print(f"[dry-run] package: {package_name}", flush=True)
        print(
            f"[dry-run] versionCode: {cfg.version_code} -> {target_version_code} "
            f"(versionName: {cfg.version_name} -> {target_version_name})",
            flush=True,
        )
        print(f"[dry-run] play release name: {play_release_name}", flush=True)
        print(f"[dry-run] github release url: {github_release_url}", flush=True)
        print(f"[dry-run] play notes length: {len(play_notes)}/{PLAY_RELEASE_NOTES_MAX_CHARS}", flush=True)
        print(f"[dry-run] sync github release: {bool(args.github_release)}", flush=True)
        print(f"[dry-run] gradle tasks: {gradle_tasks or 'skipped'}", flush=True)
        print(f"[dry-run] aab: {aab_path}", flush=True)
        print(f"[dry-run] track: {args.track} (status={args.status})", flush=True)
        print(f"[dry-run] account: {args.account} -> {args.service_account}", flush=True)
        return 0

    if args.github_release:
        try:
            _resolve_gh(which_fn=which_fn)
            _verify_git_ready_for_release(root, run_fn=run_fn)
        except PlayPublishError as exc:
            print(f"[error] {exc}", file=sys.stderr, flush=True)
            return 1

    gradle_modified = False
    try:
        if should_bump or args.version_name is not None:
            updated_gradle = update_gradle_version(
                original_gradle,
                new_version_code=target_version_code,
                new_version_name=args.version_name,
            )
            gradle_path.write_text(updated_gradle, encoding="utf-8")
            gradle_modified = updated_gradle != original_gradle

        if gradle_tasks:
            print(f"[build] Running {' '.join(gradle_tasks)}...", flush=True)
            _run_gradle(root, gradle_tasks, run_fn=run_fn)

        aab_size = verify_signed_aab(aab_path)
        print(f"[verify] Signed AAB OK ({aab_size} bytes): {aab_path}", flush=True)

        token = get_impersonated_access_token(
            account=args.account,
            service_account=args.service_account,
            run_fn=run_fn,
            which_fn=which_fn,
        )
        result = publish_aab_to_play(
            package_name=package_name,
            aab_path=aab_path,
            token=token,
            track=args.track,
            release_status=args.status,
            release_name=play_release_name,
            release_notes=play_notes,
            urlopen_fn=urlopen_fn,
        )
        print(
            f"[published] package={result['packageName']} track={result['track']} "
            f"status={result['status']} versionCode={result['versionCode']} editId={result['editId']}",
            flush=True,
        )
        if args.github_release:
            gh_url = sync_github_release(
                version_name=target_version_name,
                version_code=int(result["versionCode"]),  # type: ignore[arg-type]
                track=args.track,
                notes=github_notes,
                repo=args.github_repo,
                run_fn=run_fn,
                which_fn=which_fn,
            )
            print(f"[github-release] synced {gh_url}", flush=True)
        return 0
    except Exception as exc:
        if gradle_modified:
            gradle_path.write_text(original_gradle, encoding="utf-8")
        print(f"[error] {exc}", file=sys.stderr, flush=True)
        return 1


if __name__ == "__main__":
    sys.exit(main())
