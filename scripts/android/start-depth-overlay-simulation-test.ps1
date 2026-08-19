param(
    [string]$Serial
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$launchParameters = @{
    Activity = 'com.rokid.glass.hiddenrisk.DepthOverlaySimulationTestActivity'
}
if ($Serial) {
    $launchParameters.Serial = $Serial
}

& "$PSScriptRoot\start-activity.ps1" @launchParameters
