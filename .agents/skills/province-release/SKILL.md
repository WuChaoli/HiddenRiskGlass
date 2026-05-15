---
name: province-release
description: Publish the glassdemo 全省版 Android release. Use when Codex needs to bump the app version, generate release/changelog.md, build and debug-sign the standardRelease APK, stage release/全省版-vX.Y.Z.apk, move a version tag to the release commit, and push the 全省版 branch/tag to Gitee and GitHub while handling known large-file failure modes.
---

# Province Release

Use this skill for the glassdemo 全省版 release flow. Keep changes surgical: version metadata, changelog, release APK artifacts, commit/tag, and remote push only.

## Release Workflow

1. Inspect facts before mutating.

- Check `git status --short --branch`, current branch, remotes, current tag position, and recent commits.
- Confirm the target branch is `全省版` unless the user explicitly says otherwise.
- Read `app/build.gradle` and `release/changelog.md`.
- If unrelated local changes exist, pause before editing.

2. Update release metadata.

- Set `defaultConfig.versionName` in `app/build.gradle` to the requested version.
- Keep `versionCode` unchanged unless the user explicitly asks or the repo shows an established bump rule.
- Update the top section of `release/changelog.md`; summarize commits since the previous tag or current same-version tag.
- For retagging an existing version, include commits from `<tag>..HEAD` that are not already covered.

3. Build the APK.

- Use the actual project variant: `.\gradlew.bat :app:assembleStandardRelease`.
- Treat Gradle manifest/SDK XML warnings as non-blocking if the build succeeds.
- Locate the real output under `app/build/outputs/apk/standard/release`; do not assume the filename without enumerating it.

4. Stage and sign the release artifact.

- Expected unsigned output is usually `app-standard-release-unsigned.apk`.
- Copy/sign into `release/全省版-v<version>.apk`; do not rename the Gradle output in place.
- If the release APK is unsigned and the user allows local/demo signing, use the latest Android build-tools with `zipalign` and `apksigner` plus `~/.android/debug.keystore`.
- Preserve or replace the generated `.idsig` next to the staged APK when `apksigner` creates it.
- Verify signing with `apksigner verify --verbose`.
- Verify app metadata with `aapt dump badging`; if Windows tools fail on the Chinese path, copy the APK to `.tmp/verify-v<version>.apk`, inspect that temp path, then delete it.

5. Commit and retag.

- Force-add ignored release artifacts when the user asked to include APKs in the release commit: `git add -f release/全省版-v<version>.apk release/全省版-v<version>.apk.idsig`.
- Use commit message `发布：全省版 <version>` unless the user gives another message.
- Move the tag after the release commit: `git tag -f <version> HEAD`.
- Confirm `git log --oneline --decorate -1` shows `tag: <version>` on the release commit.

6. Push and verify remotes.

- Push `全省版` and the moved tag to Gitee and GitHub when requested:
  - `git push gitee 全省版`
  - `git push origin 全省版`
  - `git push --force gitee <version>`
  - `git push --force origin <version>`
- Verify with `git ls-remote <remote> refs/heads/全省版 refs/tags/<version>`.

## Known Failure Modes

- `release/` is ignored by `.gitignore`; force-add requested APK artifacts deliberately and mention it.
- Gitee may warn about files larger than 50MB but still accept the push.
- GitHub rejects any blob over 100MB anywhere in the pushed history; if an older APK already exceeds 100MB, branch/tag push will fail even if the new APK is below 100MB.
- GitHub may also reject non-fast-forward branch pushes; do not force-push the branch unless the user explicitly approves after seeing the remote divergence.
- If GitHub blocks on large files, report the exact file and recommend Git LFS, GitHub Releases assets, or a rewritten GitHub-specific history that removes oversized APK blobs.

## Output Discipline

- Report the release commit, tag target, staged APK path, APK size, signing type, and versionName verification.
- Separate completed remotes from blocked remotes.
- Include exact blocker text for failed pushes and do not claim GitHub success unless `ls-remote` confirms it.
