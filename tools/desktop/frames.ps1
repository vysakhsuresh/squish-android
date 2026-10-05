param(
  [Parameter(Mandatory=$true)][string]$Video,
  [Parameter(Mandatory=$true)][string]$OutDir,
  [int]$Count = 24,
  [int]$Width = 480,
  [double]$FromSec = -1,
  [double]$ToSec = -1
)
$ErrorActionPreference = 'Stop'

# Frames out of a video on this machine, the long way round.
#
# No ffmpeg here, and Application Control refuses to run any binary built on
# the spot, so it is Windows' own MediaComposition.GetThumbnailsAsync through
# PowerShell. The WinRT collections come back as bare __ComObject because the
# .NET Framework projection wants a union Windows.winmd that only the Windows
# SDK ships - so every collection call goes through the interface's MethodInfo.

[Windows.Storage.StorageFile, Windows.Storage, ContentType=WindowsRuntime] | Out-Null
[Windows.Media.Editing.MediaClip, Windows.Media, ContentType=WindowsRuntime] | Out-Null
[Windows.Media.Editing.MediaComposition, Windows.Media, ContentType=WindowsRuntime] | Out-Null
[Windows.Storage.Streams.DataReader, Windows.Storage.Streams, ContentType=WindowsRuntime] | Out-Null
[Windows.Storage.Streams.IRandomAccessStream, Windows.Storage.Streams, ContentType=WindowsRuntime] | Out-Null

Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
  $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
function Await($op, $type) {
  $t = $asTaskGeneric.MakeGenericMethod($type).Invoke($null, @($op))
  $t.Wait(-1) | Out-Null
  $t.Result
}

if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Force $OutDir | Out-Null }

$file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($Video)) ([Windows.Storage.StorageFile])
$clip = Await ([Windows.Media.Editing.MediaClip]::CreateFromFileAsync($file)) ([Windows.Media.Editing.MediaClip])
$dur = $clip.OriginalDuration
"duration_ms=$([int]$dur.TotalMilliseconds)"

$comp = New-Object Windows.Media.Editing.MediaComposition
$clips = [Windows.Media.Editing.MediaComposition].GetProperty('Clips').GetValue($comp)
$coll = [System.Collections.Generic.ICollection`1].MakeGenericType([Windows.Media.Editing.MediaClip])
$coll.GetMethod('Add').Invoke($clips, @($clip)) | Out-Null

$lo = if ($FromSec -ge 0) { $FromSec } else { 0.0 }
$hi = if ($ToSec -ge 0) { $ToSec } else { $dur.TotalSeconds }
if ($hi -le $lo) { $hi = $dur.TotalSeconds }

$times = New-Object 'System.Collections.Generic.List[TimeSpan]'
for ($i = 0; $i -lt $Count; $i++) {
  $f = if ($Count -eq 1) { 0.0 } else { $i / [double]($Count - 1) }
  $sec = $lo + ($hi - $lo) * $f
  if ($sec -ge $dur.TotalSeconds) { $sec = $dur.TotalSeconds - 0.08 }
  if ($sec -lt 0) { $sec = 0 }
  $times.Add([TimeSpan]::FromSeconds($sec))
}

$m = [Windows.Media.Editing.MediaComposition].GetMethod('GetThumbnailsAsync')
$argv = New-Object object[] 4
$argv[0] = $times.psobject.BaseObject
$argv[1] = [int]$Width
$argv[2] = [int]0
$argv[3] = [Windows.Media.Editing.VideoFramePrecision]::NearestFrame
$op = $m.Invoke($comp, $argv)
$listType = $m.ReturnType.GetGenericArguments()[0]   # IReadOnlyList<ImageStream>
$imageType = $listType.GetGenericArguments()[0]
$streams = Await $op $listType

# PowerShell flattens the returned IReadOnlyList into an Object[] on its way
# out of the task, so it indexes like any array from here.
$n = $streams.Count

for ($i = 0; $i -lt $n; $i++) {
  $s = $streams[$i]
  $size = [int]$s.Size
  $reader = New-Object Windows.Storage.Streams.DataReader($s.GetInputStreamAt(0))
  Await ($reader.LoadAsync($size)) ([uint32]) | Out-Null
  $bytes = New-Object byte[] $size
  $reader.ReadBytes($bytes)
  $name = Join-Path $OutDir ("f{0:d3}_{1}ms.jpg" -f $i, [int]$times[$i].TotalMilliseconds)
  [System.IO.File]::WriteAllBytes($name, $bytes)
  $i.ToString() + " " + ([int]$times[$i].TotalMilliseconds) + "ms " + $size
}
"wrote=$n"
