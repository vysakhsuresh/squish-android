# Spelling

`squish.dic` is the vocabulary of the thing being built — playhead, luma, muxing,
keyframe, vignette, transmux — so that the IDE's spellchecker stops reporting a
few hundred fake typos in comments and identifiers.

There are two copies on purpose, because which one an IDE reads has moved around:

- `.idea/dictionaries/project.xml` is the project dictionary. Android Studio and
  IntelliJ pick this up on their own, with no setup, as long as the folder came
  down with the repo — which is why `.gitignore` allows it through while ignoring
  the rest of `.idea`.
- `config/dictionaries/squish.dic` is the same list as a plain word-per-line
  file, for when the above does not take. Add it once under
  **Settings → Editor → Natural Languages → Spelling → Custom Dictionaries → +**
  and point it at this folder.

If a word here is genuinely misspelled, fix the word in the source rather than
adding it. The list is for terms of art, not for typos that have been outvoted.
