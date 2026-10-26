# Reads `dumpsys gfxinfo <pkg> framestats` and prints, over every frame in its
# PROFILEDATA blocks: count, median/p90/p95 of the whole frame
# (FrameCompleted - IntendedVsync), the app's own part (SyncQueued -
# IntendedVsync: input, animation, layout, record) and the GPU's
# (GpuCompleted - SyncQueued, else FrameCompleted - SyncQueued), in ms, and
# how many frames missed BUDGET ms (default 11.1, the moto's 90 Hz).
#
# And what the eye sees: the gap from one frame's vsync to the next. A frame
# that takes long but lands on the next vsync is latency; a gap of two vsyncs
# or more is a frame the panel showed twice - a skip. Gaps over 100 ms are
# the finger resting between swipes, not frames, and are left out.
#   awk -v BUDGET=11.1 -f tools/bench/framestats.awk gfx.txt
BEGIN { FS=","; if (BUDGET == "") BUDGET = 11.1 }
/^---PROFILEDATA---/ { inblock = !inblock; header = 0; prev = 0; next }
inblock && /^Flags/ { for (i = 1; i <= NF; i++) col[$i] = i; header = 1; next }
inblock && header && NF > 10 {
  if ($col["Flags"] != 0) next
  iv = $col["IntendedVsync"]; sq = $col["SyncQueued"]; fc = $col["FrameCompleted"]
  gc = ("GpuCompleted" in col) ? $col["GpuCompleted"] : 0
  if (iv <= 0 || fc <= 0) next
  n++; tot[n] = (fc - iv) / 1e6; app[n] = (sq - iv) / 1e6
  end_ = (gc > 0 && gc > sq) ? gc : fc; gpu[n] = (end_ - sq) / 1e6
  if (tot[n] > BUDGET) miss++
  if (prev > 0) { gap = (iv - prev) / 1e6; if (gap > 0 && gap < 100) { g++; if (gap > BUDGET * 1.5) { skips++; lost += int(gap / BUDGET + 0.5) - 1 } } }
  prev = iv
}
function pct(a, p,   m, i, j, t, s) { m = n; for (i = 1; i <= m; i++) s[i] = a[i]
  for (i = 2; i <= m; i++) { t = s[i]; j = i - 1; while (j > 0 && s[j] > t) { s[j+1] = s[j]; j-- } s[j+1] = t }
  return s[int((m - 1) * p) + 1] }
END {
  if (n == 0) { print "no frames"; exit }
  printf "frames %d; over %.1f ms: %d (%.1f%%)\n", n, BUDGET, miss, 100 * miss / n
  printf "whole frame  median %.1f  p90 %.1f  p95 %.1f\n", pct(tot, .5), pct(tot, .9), pct(tot, .95)
  printf "app (to sync) median %.1f  p90 %.1f\n", pct(app, .5), pct(app, .9)
  printf "gpu+render   median %.1f  p90 %.1f\n", pct(gpu, .5), pct(gpu, .9)
  printf "skips: %d of %d frame gaps (%.1f%%), %d vsyncs shown twice\n", skips, g, (g ? 100 * skips / g : 0), lost
}
