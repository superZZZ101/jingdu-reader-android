param(
  [Parameter(Mandatory = $true)][string]$Label,
  [int]$WaitSeconds = 8,
  [switch]$DumpTexts,
  [string]$Device = ''
)

$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\mingy\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$package = 'com.example.jingdu'
$work = 'C:\Users\mingy\Documents\ds_harness\novel-reading-android\.device-check'
New-Item -ItemType Directory -Force -Path $work | Out-Null

& $adb -s $Device shell uiautomator dump /sdcard/ui-tap.xml | Out-Null
& $adb -s $Device exec-out cat /sdcard/ui-tap.xml > "$work\ui-tap.xml"
$ui = [System.IO.File]::ReadAllText("$work\ui-tap.xml", [System.Text.Encoding]::UTF8)

$pattern = 'text="' + [regex]::Escape($Label) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
$match = [regex]::Match($ui, $pattern)
if (-not $match.Success) {
  Write-Output ("NOT FOUND: " + $Label)
  exit 1
}
$x = [int](([int]$match.Groups[1].Value + [int]$match.Groups[3].Value) / 2)
$y = [int](([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2)
& $adb -s $Device shell input tap $x $y | Out-Null
Write-Output ("tapped " + $Label + " at " + $x + "," + $y)

Start-Sleep -Seconds $WaitSeconds

if ($DumpTexts) {
  & $adb -s $Device shell uiautomator dump /sdcard/ui-after.xml | Out-Null
  & $adb -s $Device exec-out cat /sdcard/ui-after.xml > "$work\ui-after.xml"
  $after = [System.IO.File]::ReadAllText("$work\ui-after.xml", [System.Text.Encoding]::UTF8)
  Write-Output '--- screen texts ---'
  [regex]::Matches($after, 'text="([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Select-Object -First 40 | ForEach-Object { Write-Output $_ }
}

& $adb -s $Device exec-out run-as $package cat /data/data/$package/shared_prefs/jingdu.xml > "$work\prefs-after.xml" 2>$null
$prefs = [System.IO.File]::ReadAllText("$work\prefs-after.xml", [System.Text.Encoding]::UTF8)
function Get-PrefValue([string]$name) {
  $p = '<string name="' + [regex]::Escape($name) + '">(.*?)</string>'
  $m = [regex]::Match($prefs, $p, 'Singleline')
  if (-not $m.Success) { return $null }
  return [System.Net.WebUtility]::HtmlDecode($m.Groups[1].Value)
}
$cacheRaw = Get-PrefValue 'reader_current_document_cache'
if ($cacheRaw) {
  $doc = $cacheRaw | ConvertFrom-Json
  Write-Output ("cache.title     = " + $doc.title)
  Write-Output ("cache.paragraphs= " + $doc.paragraphs.Count)
  Write-Output ("cache.catalog   = " + $doc.catalogItems.Count)
  if ($doc.catalogItems.Count -gt 0) {
    Write-Output ("catalogFirst    = " + $doc.catalogItems[0].label + ' -> ' + $doc.catalogItems[0].href)
    Write-Output ("catalogLast     = " + $doc.catalogItems[-1].label + ' -> ' + $doc.catalogItems[-1].href)
  }
  Write-Output ("nav.next        = " + $doc.navigation.next.href)
  Write-Output ("nav.catalog     = " + $doc.navigation.catalog.href)
}
Write-Output ("last_url        = " + (Get-PrefValue 'last_url'))
Write-Output ("catalogRoot     = " + (Get-PrefValue 'catalog_state_root'))
Write-Output ("catalogCount    = " + (Get-PrefValue 'catalog_state_count'))
Write-Output ("catalogComplete = " + (Get-PrefValue 'catalog_state_complete'))
Write-Output ("catalogLoadUrl  = " + (Get-PrefValue 'catalog_state_load_url'))
$logs = Get-PrefValue 'diagnostic_log'
if ($logs) {
  Write-Output '--- diagnostics (tail) ---'
  ($logs -split "`n" | Select-Object -Last 8) | ForEach-Object { Write-Output $_ }
}
