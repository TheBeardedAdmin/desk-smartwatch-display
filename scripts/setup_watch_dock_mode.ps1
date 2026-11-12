[CmdletBinding()]
param(
    [Parameter(Mandatory=$true, HelpMessage="Enter your smartwatch IP address (e.g. <WATCH_IP>)")]
    [string]$WatchIP,
    [int]$AdbPort = 5555
)

$target = "${WatchIP}:${AdbPort}"
Write-Host "Connecting to watch at $target..."
adb connect $target

Write-Host "1. Disabling Fossil OEM charging screen overlay (prevents takeover while docked)..."
adb -s $target shell pm disable-user --user 0 com.fossil.charge.firefish
adb -s $target shell am force-stop com.fossil.charge.firefish

Write-Host "2. Granting battery optimization exemption..."
adb -s $target shell dumpsys deviceidle whitelist +com.fossil.deskalert

Write-Host "3. Setting screen keep-awake flags..."
adb -s $target shell svc power stayon true
adb -s $target shell settings put system screen_off_timeout 2147483647

Write-Host "4. Setting volume and notification audio..."
adb -s $target shell settings put system notification_sound ""

Write-Host "Watch configured successfully for desk dock mode!"
