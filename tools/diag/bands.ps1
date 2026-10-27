param([string]$Dir, [int]$Row = -1, [switch]$Vertical)
Add-Type -AssemblyName System.Drawing
Get-ChildItem $Dir -Filter *.jpg | Sort-Object Name | ForEach-Object {
  $b = [System.Drawing.Bitmap]::FromFile($_.FullName)
  $n = if ($Vertical) { $b.Height } else { $b.Width }
  $fixed = if ($Row -ge 0) { $Row } elseif ($Vertical) { [int]($b.Width / 2) } else { [int]($b.Height / 2) }
  $runs = @(); $start = -1
  for ($i = 0; $i -lt $n; $i++) {
    $c = if ($Vertical) { $b.GetPixel($fixed, $i) } else { $b.GetPixel($i, $fixed) }
    $dark = ($c.R -lt 24 -and $c.G -lt 24 -and $c.B -lt 24)
    if ($dark -and $start -lt 0) { $start = $i }
    if (-not $dark -and $start -ge 0) { if ($i - $start -ge 2) { $runs += "$start-$($i-1)" }; $start = -1 }
  }
  if ($start -ge 0) { $runs += "$start-$($n-1)" }
  "{0} {1}" -f $_.Name, ($runs -join ' ')
  $b.Dispose()
}
