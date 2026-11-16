<#
 AeroGrid → DJI Fly placeholder replacement over USB (adb). No root.
 Usage:  .\djifly_transfer.ps1 -Kmz .\Mission_Block_01.kmz [-Id <placeholder-id>]
 Requires Android platform-tools (adb) and USB debugging enabled on the phone.
#>
param([Parameter(Mandatory=$true)][string]$Kmz, [string]$Id)
$ErrorActionPreference = "Stop"
$root = "/sdcard/Android/data/dji.go.v5/files/waypoint"
if (-not (Test-Path $Kmz)) { throw "KMZ not found: $Kmz" }
adb get-state | Out-Null
$list = adb shell "for f in $root/*/*.kmz; do [ -f `"`$f`" ] && stat -c '%Y|%s|%n' `"`$f`"; done" | Sort-Object -Descending
if (-not $list) { throw "No DJI Fly missions found in $root. Save a 2-waypoint placeholder in DJI Fly first." }
Write-Host "DJI Fly missions (newest first):"; $list | ForEach-Object { $p = $_.Split('|'); Write-Host ("  {0}  {1} bytes  {2}" -f ([DateTimeOffset]::FromUnixTimeSeconds([int64]$p[0]).LocalDateTime), $p[1], $p[2]) }
if (-not $Id) { $Id = ($list[0].Split('|')[2] -split '/')[-2]; Write-Host "Using newest placeholder: $Id" }
$target = "$root/$Id/$Id.kmz"
adb shell "am force-stop dji.go.v5" | Out-Null
$backup = "backup_${Id}_$(Get-Date -Format yyyyMMdd_HHmmss).kmz"
adb pull $target $backup | Out-Null
if (-not (Test-Path $backup)) { throw "Backup failed — nothing changed." }
Write-Host "Backup saved: $backup"
adb push $Kmz $target | Out-Null
$local = (Get-FileHash $Kmz -Algorithm SHA256).Hash.ToLower()
$remote = ((adb shell "sha256sum $target") -split '\s+')[0]
if ($local -ne $remote) {
  Write-Warning "Verification failed — restoring backup."
  adb push $backup $target | Out-Null; throw "Transfer failed; original restored."
}
Write-Host "Verified ($local). Open DJI Fly, open the placeholder mission and inspect every waypoint before flight."
