"""EDGE_PLAN E5, second stage: the three programs tools/voices/convert.py saved,
as TensorFlow Lite, each checked against what the original model gave for the
same inputs, and the sample sentence spoken through the TFLite models alone.

Run on the CI runner, in its own environment (litert-torch, which wants another
transformers than Coqui does):  python tflite.py <dir from stage one>
"""
import json
import os
import sys
import wave

import numpy as np
import torch

try:
    import litert_torch as edge_torch      # the converter's name from 2025
except ImportError:
    import ai_edge_torch as edge_torch

D = sys.argv[1]
meta = json.load(open(os.path.join(D, "voice.json"), encoding="utf-8"))
s = np.load(os.path.join(D, "samples.npz"))
N, M, HOP = meta["maxFrames"], meta["vocoderFrames"], meta["hop"]


def log(*a):
    print(*a, flush=True)


def convert(name, n_in):
    module = torch.export.load(os.path.join(D, f"{name}.pt2")).module()
    args = tuple(s[f"{name}_in{i}"] for i in range(n_in))
    edge = edge_torch.convert(module, tuple(torch.from_numpy(a) for a in args))
    path = os.path.join(D, f"{name}.tflite")
    edge.export(path)
    got = edge(*args)
    got = got if isinstance(got, (tuple, list)) else (got,)
    i = 0
    while f"{name}_out{i}" in s:
        want = s[f"{name}_out{i}"]
        g = np.asarray(got[i])
        log(name, f"out{i}", "tflite vs original: max abs", float(np.abs(g - want).max()), "scale", float(np.abs(want).max()))
        i += 1
    log(name, os.path.getsize(path), "bytes")
    os.remove(os.path.join(D, f"{name}.pt2"))
    return edge


enc = convert("encoder", 3)
dec = convert("decoder", 2)
voc = convert("vocoder", 1)

# The sample, through the TFLite models alone, as the app runs them.
x, mk, spk = s["encoder_in0"], s["encoder_in1"], s["encoder_in2"]
t = len(s["ids"])
feats, logdur = enc(x, mk, spk)
d = np.maximum(1.0, np.exp(np.asarray(logdur)[0, :t]) - 1.0).round().astype(np.int64)
ex = np.repeat(np.asarray(feats)[0, :t], d, axis=0)
n = ex.shape[0]
f = np.zeros((1, N, ex.shape[1]), np.float32); f[0, :n] = ex
my = np.zeros((1, N), np.float32); my[0, :n] = 1
mel = np.asarray(dec(f, my))[0, :n]
wav = []
for st in range(0, n, M):
    chunk = np.zeros((1, 80, M), np.float32)
    part = mel[st:st + M]
    chunk[0, :, :part.shape[0]] = part.T
    wav.append(np.asarray(voc(chunk))[0, :part.shape[0] * HOP])
wav = np.concatenate(wav)
pcm = (np.clip(wav, -1, 1) * 32767).astype(np.int16)
with wave.open(os.path.join(D, "sample.wav"), "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(meta["sampleRate"]); w.writeframes(pcm.tobytes())
log("sample", n, "frames,", len(pcm) / meta["sampleRate"], "s, peak", float(np.abs(wav).max()))
os.remove(os.path.join(D, "samples.npz"))
log("stage two done")
