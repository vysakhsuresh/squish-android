param(
  [Parameter(Mandatory=$true)][string]$In,
  [Parameter(Mandatory=$true)][string]$Out,
  [double]$X = 0, [double]$Y = 0, [double]$W = 1, [double]$H = 1,
  [double]$Scale = 1
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$img = [System.Drawing.Image]::FromFile((Resolve-Path $In))
$sx = [int]($img.Width * $X); $sy = [int]($img.Height * $Y)
$sw = [int]($img.Width * $W); $sh = [int]($img.Height * $H)
$dw = [int]($sw * $Scale); $dh = [int]($sh * $Scale)
$bmp = New-Object System.Drawing.Bitmap($dw, $dh)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.DrawImage($img, (New-Object System.Drawing.Rectangle(0, 0, $dw, $dh)), $sx, $sy, $sw, $sh, [System.Drawing.GraphicsUnit]::Pixel)
$g.Dispose(); $img.Dispose()
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
"$Out ${dw}x${dh}"
