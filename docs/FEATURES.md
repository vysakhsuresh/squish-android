# Squish - everything it does

A video editor and compressor for Android. No account, no watermark, no
paywalled resolution. Your videos, photos and projects never leave the phone;
the optional online features (off until you turn them on) only fetch free
music, footage and fonts, and translate caption words.

## Projects and home

- Projects grid with covers, length, "Exported / Exported · edited" badges;
  made-up file names shown as "Edit · 29 Sep"; rename, duplicate, preview,
  earlier version (10-minute snapshot), multi-select delete with undo.
- New project from any mix of videos and photos, laid end to end with your
  default ratio, photo length and transition. Browse files, record with the
  camera, or "Open with" / "Share" from another app.
- Autosave every 1.5 s and on leaving; survives the app being killed.
- Recently deleted bin (30 days), Unfinished tool sessions, Library of every
  export with search, preview, share, delete.
- Settings: default ratio, photo length, transition, snapping ticks, keep
  screen on, read-aloud voice, export defaults, online features switch,
  storage use and clean-up.

## Timeline

- Magnetic main track; rows for overlays, sounds and words; fixed playhead,
  pinch zoom, fling scrub, snapping to cuts, beats, markers and the playhead
  (with a tick you can feel).
- Split, trim by either end, long-press to carry, multi-select (Select more),
  duplicate, delete, copy/paste attributes, undo/redo for everything.
- Filmstrips and waveforms on clips; duration chips; glyphs on short clips;
  transitions shown on joins; frame-step buttons; full-screen preview.

## Picture

- Speed: constant 0.1x-100x, speed curves (ramps) with presets, pitch
  follows speed or not, frame blending for smooth slow motion.
- Placement with keyframes (position, scale, rotation) and easing; opacity
  keyframes; 16 arrivals (Pop, Bounce, Drop, Whip, Swing, Twirl, Blink…), 15
  leavings and 11 loops (Pulse, Swing, Bob, Shake, Heartbeat, Sway, Orbit,
  Rotate…).
- Crop per clip (any window, straighten, flip), rotate, mirror.
- Frame: ratios Original, 9:16, 1:1, 16:9, 3:4, 4:3, 4:5, 2:1, 2.35:1;
  auto-reframe that follows the subject; padded canvas with colour, picture
  or blurred background.
- Looks: 50 filters in six families (Essentials, Film, Mood, Cinema, Social,
  Retro) with strength; Adjust: brightness, contrast, saturation, exposure,
  temperature, tint, highlights, shadows, sharpen, vignette, hue, fade,
  grain, plus an 8-colour HSL wheel, and **Auto adjust** (exposure, white
  balance, contrast and colour measured off the shot). Apply to all.
- Effects library (21): Shake, Zoom punch, Slow zoom, Slow zoom out, Glitch, Flash,
  VHS, B&W, Invert, Blur, Rainbow, RGB split, Strobe, Earthquake, Heartbeat,
  TV static, Old film, Dream, Negative pulse, Trippy, Sway -
  timed on the strip, previewed on your own shot.
- Transitions (26): Dissolve, Dip to black/white, Blackout, Slide 4 ways, Push 4
  ways, Whip, Wipe 4 ways, Zoom in, Zoom out, Pop in, Jitter, Flicker, Flash,
  Glow, Blur (a focus pull through the cut) and Burn out (the old shot turning
  white and thinning away over the new one) - previewed on your shots.
- Masks: rectangle, ellipse, linear, mirror, heart, star - cut out,
  pixelate or blur (face privacy), feather, invert, motion-tracked.
- Chroma key (green screen) with eyedropper; background removal (Cutout)
  with blur, colour or cut out, fully on the phone.
- Stabilize with strength; object tracking for masks and words.
- Freeze frame, reverse, replace, extract audio.
- 11 camera moves (push in, push close, pull out, pull wide, pans, diagonal,
  rise, sink, settle, tilt in) and **Animate every
  photo** for a moving slideshow in one tap.
- Overlays (picture-in-picture) with a drag/pinch/turn box, snapping guides,
  layer order, blend with overlay transitions.
- **Split screen** in one tap (left/right/top/bottom) and a **2x2 grid**
  collage (any shot or overlay into a quarter).

## Sound

- 22 Squish Originals (music composed on the phone) by mood, 24 sound effects,
  songs on
  the phone, starred and recent; audition before adding.
- **Free music online**: 15 genres, thousands of tracks each, Load more
  (Internet Archive, Creative Commons, video-safe licences only - attribution
  and share-alike, never non-commercial or no-derivatives, decided by an
  allow-list rather than by the search's filter).
- **Free stock footage online**: the Archive's stock_footage collection,
  searched or browsed by subject, under the same licence rule, downloaded as
  the smallest usable copy rather than the uploader's master.
- Voiceover recording with count-in, silent picture, live meter, retakes.
- Per-clip volume up to 400%, volume keyframes, fades in/out, mute.
- 14 voice effects: **Enhance** (rumble out, hiss down, voice forward),
  Chipmunk, Helium, Deep, Giant, Robot, Alien, Echo, Cave, Radio, Telephone,
  Megaphone, Wobble.
- **Duck under speech**: music dips automatically wherever someone talks.
- **Remove silences**: pauses cut out of a talking shot as jump cuts, with
  captions and sounds moving along.
- **Even out volume** across shots.
- Beat detection with density (every beat, half, bar), mark beats, cut on
  the beat, **fit shots to the beat** (AutoCut-style), auto-sync a sound to
  the picture, loop to fit.

## Words

- 45 one-tap templates (Reel, Vlog, Cinematic, Retro, Party, Memories, Travel,
  Birthday, Food, Fitness, Gaming, Love, News, Sale and thirty-one more), each
  one a crop, a look, a title and a set of effects - and
  `tools/jvm/TemplateChecks.kt` holds that every one of them names a look, a
  title and effects that exist.
- Add text, 17 title styles (Headline, Subtitle, Name tag, Neon, Breaking,
  Quote, Sale, Big number, Chapter, Vlog, Gaming, Love, Follow, Cinema, Spin,
  Typewriter, Bounce), 12 sticker sets, templates (Reel, Vlog, Cinematic,
  Retro, Party, Memories).
- Style: 12 built-in fonts, imported .ttf/.otf, **free Google Fonts online**,
  bold/italic/underline, alignment, colour with eyedropper, size, letter and
  line spacing, outline, shadow, bubbles (Box, Pill, Band, Speech), saved
  styles, apply to all.
- Animation: 11 arrivals, 10 leavings, 8 loops, word-by-word and letter reveals.
- Auto-captions (on-device speech recognition where the phone has it; the
  timing always), SRT import/export, **translate captions** online into 18
  languages, read aloud with 5 voices.

## Export

- One-tap presets: WhatsApp (under 16 MB), Email (under 10 MB), Reels ·
  TikTok (1080p), YouTube (full size, higher quality).
- 360p to 4K (greyed above what the phone's encoder can do), custom size,
  24/25/30/50/60 fps or Auto, quality Lower/Standard/Higher, HEVC, keep HDR,
  fit to a file size (e.g. 16 MB) with a retry if missed, sound only (.m4a).
- Size estimate before rendering; runs in the background with a
  notification; Stop asks first and leaves nothing behind.
- Done screen: plays the file, measured frame/length/rate/size, copy to
  Files, **Save as GIF**, share to WhatsApp, Instagram, mail and more.

## Quick tools (Fast lane)

- Squeeze (compress, never bigger than the source), Snip (cut without
  re-encoding), Extract audio, Stitch (join clips in order); sessions are
  saved and resumable.

## Privacy

- No account, no upload, no watermark. Online features are off by default,
  asked for the first time a tool needs them, and switchable in Settings;
  only a search, a caption's words, or the name of what you pick is sent.
