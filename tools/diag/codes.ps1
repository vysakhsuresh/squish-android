param([string]$Dir)
Add-Type -AssemblyName System.Drawing
function Read-Code($b, [double]$fx) {
  $x = [int]($b.Width * $fx); $v = 0; $sure = $true
  for ($bit = 0; $bit -lt 12; $bit++) {
    $y = [int]($b.Height * (1 - ($bit + 0.5) / 12))
    $c = $b.GetPixel($x, $y); $l = ($c.R + $c.G + $c.B) / 3
    if ($l -gt 160) { $v += [math]::Pow(2, $bit) } elseif ($l -gt 90) { $sure = $false }
    if (($c.R - $c.G) -gt 40 -or ($c.G - $c.B) -gt 60 -or ($c.B - $c.R) -gt 60) { $sure = $false }
  }
  if ($sure) { [int]$v } else { '?' }
}
Get-ChildItem $Dir -Filter *.jpg | Sort-Object Name | ForEach-Object {
  $b = [System.Drawing.Bitmap]::FromFile($_.FullName)
  "{0} left={1} right={2}" -f $_.Name, (Read-Code $b 0.03), (Read-Code $b 0.97)
  $b.Dispose()
}
