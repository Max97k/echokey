import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import urllib.error
import zipfile
from contextlib import redirect_stderr, redirect_stdout

sys.path.insert(0, str(Path(__file__).resolve().parent))
import publish_play_internal as pub


SAMPLE_GRADLE = """
android {
    namespace = "com.akeyboard"
    defaultConfig {
        applicationId = "com.max97k.akeyboard"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
}
"""


class FakeHttpResponse:
    def __init__(self, body: bytes | dict[str, object]):
        if isinstance(body, dict):
            self._raw = json.dumps(body).encode("utf-8")
        else:
            self._raw = body

    def read(self) -> bytes:
        return self._raw

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False


class PublishPlayInternalTests(unittest.TestCase):
    def test_read_and_update_gradle_version(self):
        cfg = pub.read_gradle_app_config(SAMPLE_GRADLE)
        self.assertEqual(cfg.application_id, "com.max97k.akeyboard")
        self.assertEqual(cfg.version_code, 1)
        self.assertEqual(cfg.version_name, "0.1.0")

        bumped = pub.update_gradle_version(SAMPLE_GRADLE, new_version_code=2, new_version_name="0.2.0")
        bumped_cfg = pub.read_gradle_app_config(bumped)
        self.assertEqual(bumped_cfg.version_code, 2)
        self.assertEqual(bumped_cfg.version_name, "0.2.0")
        self.assertEqual(bumped_cfg.application_id, "com.max97k.akeyboard")

        with self.assertRaises(ValueError):
            pub.read_gradle_app_config("android {}")
        with self.assertRaises(ValueError):
            pub.update_gradle_version(SAMPLE_GRADLE, new_version_code=0)

    def test_verify_signed_aab(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            missing = tmp_dir / "missing.aab"
            with self.assertRaises(pub.PlayPublishError):
                pub.verify_signed_aab(missing)

            unsigned = tmp_dir / "unsigned.aab"
            with zipfile.ZipFile(unsigned, "w") as zf:
                zf.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
            with self.assertRaises(pub.PlayPublishError):
                pub.verify_signed_aab(unsigned)

            signed = tmp_dir / "signed.aab"
            with zipfile.ZipFile(signed, "w") as zf:
                zf.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
                zf.writestr("META-INF/AKEYBOAR.RSA", b"fake-sig")
            self.assertGreater(pub.verify_signed_aab(signed), 0)

    def test_get_impersonated_access_token_passes_androidpublisher_scope(self):
        seen_cmds: list[list[str]] = []

        def fake_run(cmd, capture_output=False, text=False, check=False):
            seen_cmds.append(list(cmd))
            return subprocess.CompletedProcess(cmd, 0, stdout="tok-123\n", stderr="")

        tok = pub.get_impersonated_access_token(
            account="4r099015@gmail.com",
            service_account="play-store@my-project-play-store-490116.iam.gserviceaccount.com",
            run_fn=fake_run,
            which_fn=lambda name: "C:\\gcloud.cmd" if name == "gcloud.cmd" else None,
        )
        self.assertEqual(tok, "tok-123")
        self.assertEqual(len(seen_cmds), 1)
        self.assertIn("--scopes=https://www.googleapis.com/auth/androidpublisher", seen_cmds[0])
        self.assertIn("--account=4r099015@gmail.com", seen_cmds[0])

        def fail_run(cmd, **_kwargs):
            return subprocess.CompletedProcess(cmd, 1, stdout="", stderr="auth error")

        with self.assertRaises(pub.PlayPublishError):
            pub.get_impersonated_access_token(
                run_fn=fail_run,
                which_fn=lambda name: "gcloud" if name == "gcloud" else None,
            )

    def test_publish_aab_to_play_happy_path(self):
        calls: list[tuple[str, str, dict[str, str], bytes | None]] = []

        def fake_urlopen(req, timeout=0):
            headers = {k.lower(): v for k, v in req.header_items()}
            calls.append((req.get_method(), req.full_url, headers, req.data))
            if req.full_url.endswith("/edits"):
                return FakeHttpResponse({"id": "edit-42"})
            if "/bundles" in req.full_url:
                return FakeHttpResponse({"versionCode": 2})
            if "/tracks/internal" in req.full_url:
                return FakeHttpResponse({"track": "internal"})
            if req.full_url.endswith(":commit"):
                return FakeHttpResponse({"id": "edit-42"})
            raise AssertionError(f"Unexpected URL: {req.full_url}")

        with tempfile.TemporaryDirectory() as tmp:
            aab = Path(tmp) / "app-release.aab"
            aab.write_bytes(b"aab-binary-bytes")
            result = pub.publish_aab_to_play(
                package_name="com.max97k.akeyboard",
                aab_path=aab,
                token="secret-token",
                track="internal",
                release_status="completed",
                release_name="0.1.0 (2)",
                release_notes="Fix newlines",
                urlopen_fn=fake_urlopen,
            )

        self.assertEqual(
            result,
            {
                "editId": "edit-42",
                "packageName": "com.max97k.akeyboard",
                "track": "internal",
                "status": "completed",
                "versionCode": 2,
            },
        )
        self.assertEqual([c[0] for c in calls], ["POST", "POST", "PUT", "POST"])
        self.assertTrue(calls[1][1].endswith("/edits/edit-42/bundles?uploadType=media"))
        self.assertEqual(calls[1][2]["content-type"], "application/octet-stream")
        self.assertEqual(calls[1][3], b"aab-binary-bytes")
        track_payload = json.loads(calls[2][3].decode("utf-8"))
        self.assertEqual(track_payload["releases"][0]["versionCodes"], ["2"])
        self.assertEqual(track_payload["releases"][0]["status"], "completed")
        self.assertEqual(len(track_payload["releases"][0]["releaseNotes"]), 2)
        self.assertTrue(all(c[2]["authorization"] == "Bearer secret-token" for c in calls))

    def test_publish_aab_to_play_cleans_up_edit_and_explains_draft_app_error(self):
        calls: list[tuple[str, str]] = []

        def fake_urlopen(req, timeout=0):
            calls.append((req.get_method(), req.full_url))
            if req.full_url.endswith("/edits"):
                return FakeHttpResponse({"id": "edit-99"})
            if "/bundles" in req.full_url:
                return FakeHttpResponse({"versionCode": 2})
            if "/tracks/internal" in req.full_url:
                err_json = json.dumps({
                    "error": {
                        "code": 400,
                        "message": "Only releases with status draft may be created on draft app.",
                    }
                }).encode("utf-8")
                raise urllib.error.HTTPError(
                    req.full_url,
                    400,
                    "Bad Request",
                    hdrs=None,
                    fp=io.BytesIO(err_json),
                )
            if req.get_method() == "DELETE" and req.full_url.endswith("/edits/edit-99"):
                return FakeHttpResponse(b"")
            raise AssertionError(f"Unexpected call: {req.get_method()} {req.full_url}")

        with tempfile.TemporaryDirectory() as tmp:
            aab = Path(tmp) / "app-release.aab"
            aab.write_bytes(b"aab")
            with self.assertRaises(pub.PlayPublishError) as ctx:
                pub.publish_aab_to_play(
                    package_name="com.max97k.akeyboard",
                    aab_path=aab,
                    token="secret-token",
                    urlopen_fn=fake_urlopen,
                )

        self.assertIn("--status draft", str(ctx.exception))
        self.assertEqual(calls[-1][0], "DELETE")
        self.assertTrue(calls[-1][1].endswith("/edits/edit-99"))

    def test_publish_aab_to_play_explains_play_signing_enrollment_error(self):
        calls: list[tuple[str, str]] = []

        def fake_urlopen(req, timeout=0):
            calls.append((req.get_method(), req.full_url))
            if req.full_url.endswith("/edits"):
                return FakeHttpResponse({"id": "edit-77"})
            if "/bundles" in req.full_url:
                err_json = json.dumps({
                    "error": {
                        "code": 403,
                        "message": "For uploading an AppBundle you must be enrolled in Play Signing.",
                    }
                }).encode("utf-8")
                raise urllib.error.HTTPError(
                    req.full_url,
                    403,
                    "Forbidden",
                    hdrs=None,
                    fp=io.BytesIO(err_json),
                )
            if req.get_method() == "DELETE" and req.full_url.endswith("/edits/edit-77"):
                return FakeHttpResponse(b"")
            raise AssertionError(f"Unexpected call: {req.get_method()} {req.full_url}")

        with tempfile.TemporaryDirectory() as tmp:
            aab = Path(tmp) / "app-release.aab"
            aab.write_bytes(b"aab")
            with self.assertRaises(pub.PlayPublishError) as ctx:
                pub.publish_aab_to_play(
                    package_name="com.max97k.akeyboard",
                    aab_path=aab,
                    token="secret-token",
                    urlopen_fn=fake_urlopen,
                )

        self.assertIn("Play App Signing", str(ctx.exception))
        self.assertEqual(calls[-1][0], "DELETE")
        self.assertTrue(calls[-1][1].endswith("/edits/edit-77"))

    def test_release_notes_alignment_and_500_char_limit(self):
        self.assertEqual(
            pub.build_github_release_url("0.3.0"),
            "https://github.com/Max97k/echokey/releases/tag/v0.3.0",
        )
        self.assertEqual(
            pub.build_github_release_url("v0.2.0"),
            "https://github.com/Max97k/echokey/releases/tag/v0.2.0",
        )

        default_notes = pub.format_play_release_notes(None, "0.3.0")
        self.assertIn("v0.3.0", default_notes)
        self.assertIn("https://github.com/Max97k/echokey/releases/tag/v0.3.0", default_notes)
        self.assertLessEqual(len(default_notes), 500)

        long_summary = "A" * 700
        truncated = pub.format_play_release_notes(long_summary, "0.3.0")
        self.assertLessEqual(len(truncated), 500)
        self.assertTrue(truncated.endswith("https://github.com/Max97k/echokey/releases/tag/v0.3.0"))
        self.assertIn("…", truncated)

    def test_sync_github_release_creates_or_edits_via_gh_cli(self):
        create_cmds: list[list[str]] = []

        def run_create(cmd, capture_output=False, text=False, check=False):
            create_cmds.append(list(cmd))
            if cmd[1:3] == ["release", "view"]:
                return subprocess.CompletedProcess(cmd, 1, stdout="", stderr="release not found")
            return subprocess.CompletedProcess(cmd, 0, stdout="ok", stderr="")

        url = pub.sync_github_release(
            version_name="0.3.0",
            version_code=2,
            track="internal",
            notes="Changelog body",
            run_fn=run_create,
            which_fn=lambda name: "gh.exe" if name == "gh.exe" else None,
        )
        self.assertEqual(url, "https://github.com/Max97k/echokey/releases/tag/v0.3.0")
        self.assertEqual(create_cmds[0][:4], ["gh.exe", "release", "view", "v0.3.0"])
        self.assertEqual(create_cmds[1][:4], ["gh.exe", "release", "create", "v0.3.0"])
        self.assertIn("v0.3.0 (2)", create_cmds[1][create_cmds[1].index("--notes") + 1])

        edit_cmds: list[list[str]] = []

        def run_edit(cmd, capture_output=False, text=False, check=False):
            edit_cmds.append(list(cmd))
            return subprocess.CompletedProcess(cmd, 0, stdout="ok", stderr="")

        pub.sync_github_release(
            version_name="v0.3.0",
            version_code=2,
            track="internal",
            notes="Updated notes",
            run_fn=run_edit,
            which_fn=lambda name: "gh" if name == "gh" else None,
        )
        self.assertEqual(edit_cmds[1][:4], ["gh", "release", "edit", "v0.3.0"])

    def test_main_dry_run_and_rollback_on_build_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "app").mkdir(parents=True)
            (root / "keystore.properties").write_text("storeFile=x.jks\n", encoding="utf-8")
            gradle_file = root / "app" / "build.gradle.kts"
            gradle_file.write_text(SAMPLE_GRADLE, encoding="utf-8")

            stdout = io.StringIO()
            with redirect_stdout(stdout):
                rc = pub.main(
                    ["--dry-run"],
                    repo_root=root,
                    run_fn=lambda *_a, **_kw: self.fail("dry-run must not run commands"),
                    urlopen_fn=lambda *_a, **_kw: self.fail("dry-run must not call network"),
                )
            self.assertEqual(rc, 0)
            self.assertEqual(gradle_file.read_text(encoding="utf-8"), SAMPLE_GRADLE)
            self.assertIn("versionCode: 1 -> 2", stdout.getvalue())

            # Simulate Gradle failure after bumping: build.gradle.kts must roll back cleanly.
            (root / "gradlew.bat").write_text("@echo off\n", encoding="utf-8")
            (root / "gradlew").write_text("#!/bin/sh\n", encoding="utf-8")
            stderr = io.StringIO()
            with redirect_stderr(stderr):
                rc_fail = pub.main(
                    [],
                    repo_root=root,
                    run_fn=lambda cmd, **_kw: subprocess.CompletedProcess(cmd, 1),
                )
            self.assertEqual(rc_fail, 1)
            self.assertEqual(gradle_file.read_text(encoding="utf-8"), SAMPLE_GRADLE)
            self.assertIn("Gradle build failed", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
