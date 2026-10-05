param(
  [Parameter(Mandatory=$true)][string]$Dir,
  [Parameter(Mandatory=$true)][string]$Out,
  [int]$Cols = 7,
  [int]$CellW = 200,
  [int]$Skip = 0,
  [int]$Take = 0
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$files = Get-ChildItem "$Dir\*.jpg" | Sort-Object Name
if ($Skip -gt 0) { $files = $files | Select-Object -Skip $Skip }
if ($Take -gt 0) { $files = $files | Select-Object -First $Take }
if ($files.Count -eq 0) { throw "no frames in $Dir" }

$first = [System.Drawing.Image]::FromFile($files[0].FullName)
$ratio = $first.Height / [double]$first.Width
$first.Dispose()
$cellH = [int][math]::Round($CellW * $ratio)
$rows = [int][math]::Ceiling($files.Count / [double]$Cols)
$label = 14

$bmp = New-Object System.Drawing.Bitmap(($Cols * $CellW), ($rows * ($cellH + $label)))
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.Clear([System.Drawing.Color]::Black)
$font = New-Object System.Drawing.Font("Consolas", 9)
$brush = [System.Drawing.Brushes]::White

for ($i = 0; $i -lt $files.Count; $i++) {
  $c = $i % $Cols
  $r = [int][math]::Floor($i / $Cols)
  $img = [System.Drawing.Image]::FromFile($files[$i].FullName)
  $g.DrawImage($img, ($c * $CellW), ($r * ($cellH + $label)), $CellW, $cellH)
  $img.Dispose()
  $ms = ($files[$i].BaseName -replace '^f\d+_', '') -replace 'ms$', ''
  $g.DrawString(("{0}  {1:n1}s" -f $i, ([double]$ms / 1000)), $font, $brush, ($c * $CellW + 2), ($r * ($cellH + $label) + $cellH))
}
$g.Dispose()
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
"$Out $($files.Count) frames, ${Cols}x${rows}, cell ${CellW}x${cellH}"
