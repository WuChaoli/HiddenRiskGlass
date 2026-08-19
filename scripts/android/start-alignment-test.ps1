param(
    [string]$Serial,
    [ValidateSet('left', 'right')][string]$Eye = 'right'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\common.ps1"

$activityArguments = Get-AlignmentTestActivityArguments -Eye $Eye
$launchParameters = @{
    Activity = 'com.rokid.glass.hiddenrisk.RawCameraPreviewDebugActivity'
}
if ($Serial) {
    $launchParameters.Serial = $Serial
}

& "$PSScriptRoot\start-activity.ps1" @launchParameters -ActivityArguments $activityArguments
