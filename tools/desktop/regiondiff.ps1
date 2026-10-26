# The mean absolute difference, 0..255, between two screenshots over a region
# (EDGE_PLAN D6: did a slider held mid-drag change the paused picture?).
# Windows' own System.Drawing; nothing installed, nothing compiled.
#
#   powershell -File regiondiff.ps1 -A before.png -B during.png -Left 30 -Top 268 -Right 1050 -Bottom 1190 [-Step 4]
param(
    [Parameter(Mandatory = $true)][string]$A,
    [Parameter(Mandatory = $true)][string]$B,
    [int]$Left = 0, [int]$Top = 0, [int]$Right = 0, [int]$Bottom = 0,
    [int]$Step = 4
)
Add-Type -AssemblyName System.Drawing
$ia = [System.Drawing.Bitmap]::FromFile((Resolve-Path $A))
$ib = [System.Drawing.Bitmap]::FromFile((Resolve-Path $B))
try {
    if ($Right -le 0) { $Right = $ia.Width }
    if ($Bottom -le 0) { $Bottom = $ia.Height }
    $Right = [Math]::Min($Right, [Math]::Min($ia.Width, $ib.Width))
    $Bottom = [Math]::Min($Bottom, [Math]::Min($ia.Height, $ib.Height))
    $sum = 0.0; $n = 0; $changed = 0
    for ($y = $Top; $y -lt $Bottom; $y += $Step) {
        for ($x = $Left; $x -lt $Right; $x += $Step) {
            $p = $ia.GetPixel($x, $y); $q = $ib.GetPixel($x, $y)
            $d = ([Math]::Abs($p.R - $q.R) + [Math]::Abs($p.G - $q.G) + [Math]::Abs($p.B - $q.B)) / 3.0
            $sum += $d; $n++
            if ($d -gt 12) { $changed++ }
        }
    }
    $mean = if ($n -gt 0) { $sum / $n } else { 0 }
    "{0:F2} {1:F1}" -f $mean, (100.0 * $changed / [Math]::Max(1, $n))
} finally {
    $ia.Dispose(); $ib.Dispose()
}
