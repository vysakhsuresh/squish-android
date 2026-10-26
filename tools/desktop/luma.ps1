# Measures the frames frames.ps1 pulled: each frame's mean luma (0-255, Rec.601
# weights) over a region, and across the frames the swing of that mean (flicker)
# and the per-pixel spread over time in the region (noise, for a still stretch).
# Nothing installed or compiled: System.Drawing over the JPEGs.
# PowerShell names ignore case: the region is $rw x $rh, never $w/$h, which are -W/-H.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/desktop/luma.ps1 `
#     -Dir out/ [-X 0 -Y 0 -W 1 -H 1]      # the region, as fractions of the frame
param(
  [Parameter(Mandatory=$true)][string]$Dir,
  [double]$X = 0, [double]$Y = 0, [double]$W = 1, [double]$H = 1
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$files = Get-ChildItem -Path $Dir -Filter *.jpg | Sort-Object Name
if ($files.Count -eq 0) { throw "no .jpg in $Dir" }
$means = @(); $sum = $null; $sum2 = $null; $n = 0; $cells = 0
foreach ($f in $files) {
  $bmp = New-Object System.Drawing.Bitmap $f.FullName
  $x0 = [int]($bmp.Width * $X); $y0 = [int]($bmp.Height * $Y)
  $rw = [Math]::Max(1, [int]($bmp.Width * $W)); $rh = [Math]::Max(1, [int]($bmp.Height * $H))
  $rect = New-Object System.Drawing.Rectangle $x0, $y0, $rw, $rh
  $data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
  $stride = $data.Stride; $bytes = New-Object byte[] ($stride * $rh)
  [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
  $bmp.UnlockBits($data); $bmp.Dispose()
  if ($null -eq $sum) { $cells = $rw * $rh; $sum = New-Object double[] $cells; $sum2 = New-Object double[] $cells }
  $total = 0.0; $k = 0
  for ($yy = 0; $yy -lt $rh; $yy++) {
    $row = $yy * $stride
    for ($xx = 0; $xx -lt $rw; $xx++) {
      $i = $row + 3 * $xx
      $l = 0.114 * $bytes[$i] + 0.587 * $bytes[$i + 1] + 0.299 * $bytes[$i + 2]
      $total += $l
      if ($k -lt $cells) { $sum[$k] += $l; $sum2[$k] += $l * $l }
      $k++
    }
  }
  $m = $total / ($rw * $rh); $means += $m; $n++
  "{0}  mean {1:N2}" -f $f.Name, $m
}
$avg = ($means | Measure-Object -Average).Average
$sd = [Math]::Sqrt((($means | ForEach-Object { ($_ - $avg) * ($_ - $avg) }) | Measure-Object -Sum).Sum / $means.Count)
$steps = @(); for ($i = 1; $i -lt $means.Count; $i++) { $steps += [Math]::Abs($means[$i] - $means[$i - 1]) }
$stepAvg = if ($steps.Count) { ($steps | Measure-Object -Average).Average } else { 0 }
$noise = 0.0
for ($k = 0; $k -lt $cells; $k++) { $mu = $sum[$k] / $n; $noise += [Math]::Sqrt([Math]::Max(0, $sum2[$k] / $n - $mu * $mu)) }
$noise /= [Math]::Max(1, $cells)
"frames {0}  mean {1:N2}  swing (sd of means) {2:N2}  frame-to-frame {3:N2}  noise (per-pixel sd over time) {4:N2}" -f $n, $avg, $sd, $stepAvg, $noise
