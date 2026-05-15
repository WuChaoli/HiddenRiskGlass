---
name: rokid-camera-preview-recovery
description: Use when debugging or fixing Rokid Glass Android camera preview black screen, sleep recovery preview not rendering, page re-entry preview failures, or issues involving RokidCameraPreviewView and InspectionCameraCoordinator where recovery must avoid camera release or restart.
---

# Rokid Camera Preview Recovery

## Overview

Use this skill for Rokid Glass Android pages where the camera frame stream is alive but the on-screen preview is black, especially after auto sleep recovery or page state changes. The default strategy is layered diagnosis followed by soft preview recovery.

## Core Boundary

For normal sleep or preview-render recovery:

- Do not call `releaseAppCamera`.
- Do not call `InspectionCameraCoordinator.restart`.
- Do not add or reuse `restartFrameStreamForPreview`.
- Prefer `pause`, `acquire`, and `updatePreview` so the camera and frame stream stay owned by the coordinator.

Only discuss hard release or restart if logs prove the underlying camera or frame stream is dead, not merely the preview surface or GL view.

## Diagnosis Order

Check the failure layer before proposing code changes:

1. Frame stream layer
   - Look for frame stream open/ready/warm logs.
   - If frame data keeps arriving, avoid camera release/restart.

2. Coordinator binding layer
   - Look for `acquire owner=... needPreview=true`.
   - Look for `previewBind owner=...`.
   - Look for `preview_ready owner=... success=true`.

3. View/GL draw layer
   - Look for `shared surface first frame available`.
   - Look for `shared surface texture id still 0 after frame update`.
   - Look for `first preview draw`.
   - Treat `previewStarted=true` as insufficient; the important signal is an actual drawn frame.

If logs show `preview_ready success=true` and frames are available, but there is no `first preview draw`, the likely fault is the preview View/Surface/GL layer.

## Soft Recovery Pattern

When preview binding succeeds but no drawn frame appears within a short guard window:

```kotlin
InspectionCameraCoordinator.updatePreview(
    owner = owner,
    needPreview = false,
    previewView = null,
) {
    val oldPreview = viewLivePreview
    val oldIndex = previewContainer.indexOfChild(oldPreview).takeIf { it >= 0 }
        ?: previewContainer.childCount
    val oldLayoutParams = oldPreview.layoutParams
    val oldVisibility = oldPreview.visibility

    oldPreview.detachPreview()
    previewContainer.removeView(oldPreview)

    val newPreview = RokidCameraPreviewView(this).apply {
        id = R.id.viewLivePreview
        visibility = oldVisibility
        layoutParams = oldLayoutParams
    }
    previewContainer.addView(newPreview, oldIndex)
    viewLivePreview = newPreview

    InspectionCameraCoordinator.updatePreview(
        owner = owner,
        needPreview = true,
        previewView = viewLivePreview,
    )
}
```

Keep the soft recovery one-shot per recovery round to avoid repeatedly rebuilding the view.

## Auto Sleep Recovery Shape

For sleep warning state:

- Stop page business loops or in-flight requests.
- Set page-side readiness flags such as `frameStreamReady=false` and `frameStreamInitializing=false`.
- Hide or pause preview UI as needed.
- Call `InspectionCameraCoordinator.pause(owner, reason = "...auto_sleep_warning")`.

For wake or waking state:

- Restore visible preview UI.
- Re-enter the normal page initialization path.
- Use `acquire(... needPreview=true, previewView=...)` or `updatePreview(... needPreview=true, previewView=...)`.
- Schedule a short draw check; if `isPreviewStarted()` is true but `isPreviewFrameDrawn()` is false, run the soft recovery pattern.

## Log Checklist

Use a focused logcat filter around these terms:

```text
auto_sleep_warning
resumeFromAutoSleep
acquire owner=
pause owner=
updatePreview owner=
previewBind owner=
preview_ready owner=
shared surface first frame
shared surface texture id
first preview draw
recreate
restartFrameStream
restartFrameStreamForPreview
InspectionCameraCoordinator.restart
releaseAppCamera
```

Success looks like:

- Sleep entry logs `pause owner=... reason=...auto_sleep_warning`.
- Recovery logs `acquire` or `updatePreview needPreview=true`.
- If first bind does not draw, logs show `recreate...PreviewView reason=preview_no_drawn_frame`.
- Final logs include `first preview draw`.
- Logs do not include `restartFrameStream`, `restartFrameStreamForPreview`, `InspectionCameraCoordinator.restart`, or `releaseAppCamera` on the normal recovery path.

## Implementation Notes

- Track actual draw completion in `RokidCameraPreviewView`, not only preview start.
- Set the draw flag only after the GL draw call succeeds.
- Preserve the old preview view id, layout params, visibility, and insertion index when recreating the view.
- Use page-specific owner values such as `AI_INSPECTION` or `DEVICE_GUIDE`; do not broaden the fix to unrelated pages without matching logs.
- Compile the relevant variant after changes, then verify on device with fresh logcat when available.
