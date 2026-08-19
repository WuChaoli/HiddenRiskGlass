param(
    [string]$Serial,
    [ValidateSet('left', 'right')][string]$Eye = 'right'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$launchParameters = @{
    Activity = 'com.rokid.glass.hiddenrisk.DistanceAlignmentTestActivity'
    ActivityArguments = @('--es', 'dominantEye', $Eye)
}
if ($Serial) {
    $launchParameters.Serial = $Serial
}

& "$PSScriptRoot\start-activity.ps1" @launchParameters
