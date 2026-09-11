param(
  [Parameter(Mandatory = $true)][string]$Url,
  [int]$WaitSeconds = 25,
  [switch]$ClearFirst,
  [switch]$StartReading,
  [switch]$OpenMenu
)

$ErrorActionPreference = 'Stop'
$adb = 'C:\Users\mingy\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$package = 'com.example.jingdu'
$activity = "$package/.MainActivity"
$work = 'C:\Users\mingy\Documents\ds_harness\novel-reading-android\.device-check'
New-Item -ItemType Directory -Force -Path $work | Out-Null

function Get-UiTexts {
  & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
  & $adb exec-out cat /sdcard/ui.xml > "$work\ui.xml"
  $ui = [System.IO.File]::ReadAllText("$work\ui.xml", [System.Text.Encoding]::UTF8)
  return $ui
}

function Invoke-TextTap([string]$label) {
  $ui = Get-UiTexts
  $match = [regex]::Match($ui, 'text="' + [regex]::Escape($label) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
  if (-not $match.Success) { return $false }
  $x = [int](([int]$match.Groups[1].Value + [int]$match.Groups[3].Value) / 2)
  $y = [int](([int]$match.Groups[2].Value + [int]$match.Groups[4].Value) / 2)
  & $adb shell input tap $x $y | Out-Null
  return $true
}

if ($ClearFirst) {
  & $adb shell pm clear $package | Out-Null
  Start-Sleep -Seconds 2
}

& $adb shell am force-stop $package | Out-Null
Start-Sleep -Seconds 1
& $adb shell am start -n $activity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT $Url | Out-Null
Start-Sleep -Seconds 4

if ($StartReading) {
  $tapped = Invoke-TextTap '打开并整理'
  if ($tapped) {
    Write-Output 'tapped 打开并整理'
  } else {
    Write-Output 'WARN: 打开并整理 button not found'
  }
}

$elapsed = 0
while ($elapsed -lt $WaitSeconds) {
  Start-Sleep -Seconds 5
  $elapsed += 5
}

function Get-ScreenTexts {
  $ui = Get-UiTexts
  $matches = [regex]::Matches($ui, 'text="([^"]+)"')
  return ($matches | ForEach-Object { $_.Groups[1].Value })
}

if ($OpenMenu) {
  & $adb shell input tap 540 1200 | Out-Null
  Start-Sleep -Seconds 3
  Write-Output '--- screen texts (menu) ---'
  $texts = Get-ScreenTexts
  $texts | ForEach-Object { Write-Output $_ }
  if (Invoke-TextTap '目录') {
    Start-Sleep -Seconds 8
    Write-Output '--- screen texts (catalog) ---'
    Get-ScreenTexts | ForEach-Object { Write-Output $_ }
  }
}

& $adb exec-out run-as $package cat /data/data/$package/shared_prefs/jingdu.xml > "$work\prefs.xml" 2>$null
$prefs = [System.IO.File]::ReadAllText("$work\prefs.xml", [System.Text.Encoding]::UTF8)

if (-not $prefs) {
  Write-Output 'prefs: EMPTY (run-as unavailable or app not started)'
  exit 1
}

function Get-PrefValue([string]$name) {
  $pattern = '<string name="' + [regex]::Escape($name) + '">(.*?)</string>'
  $match = [regex]::Match($prefs, $pattern, 'Singleline')
  if (-not $match.Success) { return $null }
  return $match.Groups[1].Value
}

$cacheRaw = Get-PrefValue 'reader_current_document_cache'
if (-not $cacheRaw) {
  Write-Output 'reader cache: MISSING'
} else {
  $htmlDecoded = [System.Net.WebUtility]::HtmlDecode($cacheRaw)
  $doc = $htmlDecoded | ConvertFrom-Json
  Write-Output ("cache.sourceUrl = " + $doc.sourceUrl)
  Write-Output ("cache.title     = " + $doc.title)
  Write-Output ("cache.paragraphs= " + $doc.paragraphs.Count)
  Write-Output ("cache.first     = " + ($doc.paragraphs | Select-Object -First 1))
  Write-Output ("cache.catalog   = " + $doc.catalogItems.Count + " items")
  if ($doc.catalogItems.Count -gt 0) {
    Write-Output ("cache.catalogFirst = " + $doc.catalogItems[0].label + ' -> ' + $doc.catalogItems[0].href)
    Write-Output ("cache.catalogLast  = " + $doc.catalogItems[-1].label + ' -> ' + $doc.catalogItems[-1].href)
  }
  Write-Output ("cache.catalogPages = " + $doc.catalogPages.Count)
  if ($doc.catalogPages.Count -gt 0) {
    Write-Output ("cache.catalogPagesFirst = " + $doc.catalogPages[0].label + ' -> ' + $doc.catalogPages[0].href)
  }
  Write-Output ("nav.previous    = " + $doc.navigation.previous.href)
  Write-Output ("nav.next        = " + $doc.navigation.next.href)
  Write-Output ("nav.catalog     = " + $doc.navigation.catalog.href)
  Write-Output ("nav.previousPage= " + $doc.navigation.previousPage.href)
  Write-Output ("nav.nextPage    = " + $doc.navigation.nextPage.href)
}

Write-Output ("last_url        = " + (Get-PrefValue 'last_url'))
Write-Output ("catalogRoot     = " + (Get-PrefValue 'catalog_state_root'))
Write-Output ("catalogCount    = " + (Get-PrefValue 'catalog_state_count'))
Write-Output ("catalogComplete = " + (Get-PrefValue 'catalog_state_complete'))
$logs = Get-PrefValue 'diagnostic_log'
if ($logs) {
  $decoded = [System.Net.WebUtility]::HtmlDecode($logs)
  Write-Output '--- diagnostics (tail) ---'
  ($decoded -split "`n" | Select-Object -Last 12) | ForEach-Object { Write-Output $_ }
}
