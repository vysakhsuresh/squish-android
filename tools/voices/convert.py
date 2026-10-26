"""EDGE_PLAN E5: one AI4Bharat Indic-TTS voice (FastPitch + HiFi-GAN, MIT) as
three TensorFlow Lite models the app runs on the phone (media/audio/NeuralVoice.kt):

  encoder.tflite  ids [1,T] int32, mask [1,T] f32, speaker [1] int32
                  -> features [1,T,C] (pitch already added), logdur [1,T]
  decoder.tflite  features [1,N,C] f32, mask [1,N] f32 -> mel [1,N,80]
  vocoder.tflite  mel [1,80,M] f32 -> wav [1,M*256]
  voice.json      the symbols in id order, the speakers, T, N, M, the sample rate

Between them the app does what FastPitch does between its halves: durations
from the log durations (e^d - 1, at least a frame) and each character's
features repeated that many frames (TtsRules, checked on the JVM).

Run on the CI runner only:  python convert.py <checkpoint dir> <out dir> <lang>
Every stage is checked against the original model before it is written.
"""
import json
import os
import sys
import wave

import numpy as np
import torch

CK, OUT, LANG = sys.argv[1], sys.argv[2], sys.argv[3]
T = 192   # characters a call: the app splits text into sentences under this
N = 1024  # mel frames a decoder call (about 11.9 s)
M = 256   # mel frames a vocoder call (about 3 s), overlapped by the app
os.makedirs(OUT, exist_ok=True)
root = os.path.join(CK, LANG) if os.path.isdir(os.path.join(CK, LANG)) else CK


def log(*a):
    print(*a, flush=True)


# ---- Load ----------------------------------------------------------------------
from TTS.tts.configs.fast_pitch_config import FastPitchConfig
from TTS.tts.models.forward_tts import ForwardTTS
from TTS.vocoder.configs.hifigan_config import HifiganConfig
from TTS.vocoder.models import setup_model as setup_vocoder

fp_dir = os.path.join(root, "fastpitch")
cfg = FastPitchConfig()
cfg.load_json(os.path.join(fp_dir, "config.json"))
spk_file = os.path.join(fp_dir, "speakers.pth")
cfg.model_args.speakers_file = spk_file
cfg.speakers_file = spk_file
model = ForwardTTS.init_from_config(cfg)
model.load_checkpoint(cfg, os.path.join(fp_dir, "best_model.pth"), eval=True)
model.eval()
speakers = torch.load(spk_file, map_location="cpu")
log("speakers", speakers)

vc_dir = os.path.join(root, "hifigan")
vcfg = HifiganConfig()
vcfg.load_json(os.path.join(vc_dir, "config.json"))
vocoder = setup_vocoder(vcfg)
vocoder.load_checkpoint(vcfg, os.path.join(vc_dir, "best_model.pth"), eval=True)
gen = vocoder.model_g
gen.eval()
try:
    gen.remove_weight_norm()
except Exception as e:  # already removed by eval=True on some versions
    log("weight norm:", e)

tok = model.tokenizer
symbols = list(tok.characters.vocab)
log("symbols", len(symbols), symbols[:20])
params = sum(p.numel() for p in model.parameters())
log("fastpitch params", params, "vocoder params", sum(p.numel() for p in gen.parameters()))


# ---- Padding that does not leak --------------------------------------------------
# The app pads every call to T characters and N frames. Coqui's FFTransformerBlock
# masks padded keys in attention but leaves the padded positions' values in
# place from layer to layer, and the 3-wide convolution after the attention
# then reads them into the last real characters - measured: log durations off
# by up to 0.57 padded, exactly the original's unpadded. Zeroing the padded
# positions before each layer is what the unpadded run sees (zeros past the
# end), so it changes nothing there and makes a padded call agree with it.
from TTS.tts.layers.generic.transformer import FFTransformer, FFTransformerBlock
import torch.nn.functional as F


# Inside each layer too: the attention's output at padded positions, and the
# first convolution's, are read by the next convolution. Zeroed before each.
def _fft_forward(self, src, src_mask=None, src_key_padding_mask=None):
    keep = None if src_key_padding_mask is None else (~src_key_padding_mask).to(src.dtype).unsqueeze(1)  # [B,1,T]
    src = src.permute(2, 0, 1)
    src2, enc_align = self.self_attn(src, src, src, attn_mask=src_mask, key_padding_mask=src_key_padding_mask)
    src = src + self.dropout1(src2)
    src = self.norm1(src + src2)
    src = src.permute(1, 2, 0)
    if keep is not None:
        src = src * keep
    h = F.relu(self.conv1(src))
    if keep is not None:
        h = h * keep
    src2 = self.conv2(h)
    src2 = self.dropout2(src2)
    src = src + src2
    src = src.transpose(1, 2)
    src = self.norm2(src)
    src = src.transpose(1, 2)
    return src, enc_align


FFTransformer.forward = _fft_forward


def _masked_forward(self, x, mask=None, g=None):
    keep = None
    if mask is not None and mask.ndim == 3:
        keep = mask.to(x.dtype)
        mask = ~mask.squeeze(1).bool()
    for layer in self.fft_layers:
        if keep is not None:
            x = x * keep
        x, _ = layer(x, src_key_padding_mask=mask)
    return x * keep if keep is not None else x


FFTransformerBlock.forward = _masked_forward


# ---- The halves, as ForwardTTS.inference runs them ------------------------------
class Encoder(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, ids, mask, speaker):
        # As ForwardTTS.inference (coqui-tts 0.28): the speaker conditions the
        # encoder, the durations are read before the pitch is added, and the
        # decoder is run with no speaker.
        x_mask = mask.unsqueeze(1)                       # [1,1,T]
        g = self.m._get_speaker_conditioning({"speaker_ids": speaker.long(), "d_vectors": None}, "emb_g",
                                             normalize_d_vector=False, normalize_embedding=False)
        o_en, x_mask, g, _ = self.m._forward_encoder(ids.long(), x_mask, g)
        logdur = self.m.duration_predictor(o_en, x_mask)  # [1,1,T]
        pitch_emb, _, _ = self.m._forward_pitch_predictor(o_en, x_mask)
        o_en = o_en + pitch_emb
        return (o_en * x_mask).transpose(1, 2), logdur.squeeze(1) * mask


class Decoder(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, feats, mask):
        # ForwardTTS._forward_decoder after its expansion, which the app does.
        y_mask = mask.unsqueeze(1)                       # [1,1,N]
        o = feats.transpose(1, 2) * y_mask              # [1,C,N]
        if hasattr(self.m, "pos_encoder"):
            o = self.m.pos_encoder(o, y_mask)
        o_de = self.m.decoder(o, y_mask, g=None)        # [1,80,N]
        return o_de.transpose(1, 2) * mask.unsqueeze(-1)


class Vocoder(torch.nn.Module):
    def __init__(self, g):
        super().__init__()
        self.g = g

    def forward(self, mel):
        return self.g(mel).reshape(1, -1)


enc, dec, voc = Encoder(model).eval(), Decoder(model).eval(), Vocoder(gen).eval()


def durations(logdur, scale=1.0):
    d = (np.exp(logdur) - 1.0) * scale
    return np.maximum(1.0, d).round().astype(np.int64)


def split_pipeline(text, speaker_id):
    ids = tok.text_to_ids(text)
    t = len(ids)
    assert t <= T, f"{t} characters"
    x = np.zeros((1, T), np.int32); x[0, :t] = ids
    mk = np.zeros((1, T), np.float32); mk[0, :t] = 1
    spk = np.array([speaker_id], np.int32)
    with torch.no_grad():
        feats, logdur = enc(torch.from_numpy(x), torch.from_numpy(mk), torch.from_numpy(spk))
    feats = feats.numpy()[0, :t]
    d = durations(logdur.numpy()[0, :t])
    ex = np.repeat(feats, d, axis=0)
    n = ex.shape[0]
    assert n <= N, f"{n} frames"
    f = np.zeros((1, N, feats.shape[1]), np.float32); f[0, :n] = ex
    my = np.zeros((1, N), np.float32); my[0, :n] = 1
    with torch.no_grad():
        mel = dec(torch.from_numpy(f), torch.from_numpy(my)).numpy()[0, :n]
    return ids, x, mk, spk, f, my, mel


def original(text, speaker_id):
    ids = torch.tensor([tok.text_to_ids(text)], dtype=torch.long)
    with torch.no_grad():
        out = model.inference(ids, aux_input={"speaker_ids": torch.tensor([speaker_id])})
    return out["model_outputs"][0].numpy()           # [n,80]


sample = {
    "ml": "നമസ്കാരം, ഇത് സ്ക്വിഷിന്റെ ശബ്ദമാണ്.",
    "hi": "नमस्ते, यह स्क्विश की आवाज़ है।",
    "en": "hello, this is the voice of squish.",
    "en+hi": "hello, this is the voice of squish.",
}.get(LANG, "hello")

first_speaker = sorted(speakers.values())[0] if isinstance(speakers, dict) else 0
ids, x, mk, spk, f, my, mel = split_pipeline(sample, first_speaker)
ref = original(sample, first_speaker)
log("split mel", mel.shape, "original", ref.shape)
if mel.shape == ref.shape:
    log("split vs original: max abs", float(np.abs(mel - ref).max()))
else:
    log("WARNING: frame counts differ (split %d, original %d)" % (mel.shape[0], ref.shape[0]))

# Where the frames differ: the original's own durations, against the split's
# padded to T and unpadded, so a difference is put down to the padding or not.
with torch.no_grad():
    ids_t = torch.tensor([tok.text_to_ids(sample)], dtype=torch.long)
    out = model.inference(ids_t, aux_input={"speaker_ids": torch.tensor([first_speaker])})
    d_orig = model.format_durations(out["durations_log"], torch.ones_like(out["durations_log"])).squeeze().numpy().astype(np.int64)
    t = len(ids)
    _, lp = enc(torch.from_numpy(x), torch.from_numpy(mk), torch.from_numpy(spk))
    _, lu = enc(torch.from_numpy(x[:, :t]), torch.from_numpy(mk[:, :t]), torch.from_numpy(spk))
d_pad = durations(lp.numpy()[0, :t])
d_unpad = durations(lu.numpy()[0, :t])
log("durations original", d_orig.tolist())
log("durations padded  ", d_pad.tolist())
log("durations unpadded", d_unpad.tolist())
log("log-duration gap padded vs unpadded: max", float(np.abs(lp.numpy()[0, :t] - lu.numpy()[0, :t]).max()),
    "| unpadded vs original:", float(np.abs(lu.numpy()[0, :t] - out["durations_log"].squeeze().numpy()).max()))
# A voice that speaks differently padded is not shipped.
if mel.shape != ref.shape or not np.array_equal(d_pad, d_orig):
    raise SystemExit("the padded run does not speak as the original does - padding still leaks")
mel_gap = float(np.abs(mel - ref).max())
if mel_gap > 1e-2 * float(np.abs(ref).max()):
    raise SystemExit(f"the padded mel is {mel_gap} from the original's")
log("padded run agrees with the original: durations equal, mel within", mel_gap)


# ---- Saved for the second stage (tools/voices/tflite.py, its own environment) ----
# Coqui and the TFLite converter want different transformers, so they cannot
# share one: each half goes out as a torch.export program, with its sample
# inputs and what the original gave for them, and the second stage converts
# and checks against those.
mel_chunk = np.zeros((1, 80, M), np.float32)
k = min(M, mel.shape[0]); mel_chunk[0, :, :k] = mel[:k].T
samples = {}
for name, module, args in (("encoder", enc, (x, mk, spk)), ("decoder", dec, (f, my)), ("vocoder", voc, (mel_chunk,))):
    targs = tuple(torch.from_numpy(a) for a in args)
    with torch.no_grad():
        want = module(*targs)
    want = want if isinstance(want, (tuple, list)) else (want,)
    program = torch.export.export(module, targs)
    torch.export.save(program, os.path.join(OUT, f"{name}.pt2"))
    for i, a in enumerate(args):
        samples[f"{name}_in{i}"] = a
    for i, w in enumerate(want):
        samples[f"{name}_out{i}"] = w.numpy()
    log(name, "exported")
samples["ids"] = np.array(ids, np.int64)
np.savez(os.path.join(OUT, "samples.npz"), **samples)

# Coqui's own reading of text, for the app's to be checked against
# (tools/jvm/TtsRulesChecks.kt reads these out of voice.json).
reference = [sample, "Hello, World! 123 - (test); done: yes.", "  two   spaces\tand\na line  "]
reference += list(cfg.test_sentences or [])[:3]
tests = [{"text": t, "ids": [int(i) for i in tok.text_to_ids(t)]} for t in reference]
char_to_id = {c: int(tok.characters.char_to_id(c)) for c in dict.fromkeys(symbols) if c}

json.dump({
    "lang": LANG,
    "symbols": symbols,
    "ids": char_to_id,
    "tests": tests,
    "speakers": {str(k): int(v) for k, v in (speakers.items() if isinstance(speakers, dict) else [])},
    "maxChars": T, "maxFrames": N, "vocoderFrames": M,
    "hop": 256, "sampleRate": 22050, "channels": int(f.shape[2]),
    "lowercase": True,
    "source": "AI4Bharat Indic-TTS v1 (MIT), github.com/AI4Bharat/Indic-TTS",
}, open(os.path.join(OUT, "voice.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
log("stage one done")
