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


# ---- Smaller: weights in eight bits --------------------------------------------
# At float32 the three are about 265 MB a language. Weights as int8 with float
# arithmetic (ai-edge-quantizer's dynamic_wi8_afp32) is a quarter of that; each
# quantized model is run on the same inputs as the float one and kept only if
# it stays close - the vocoder by the sound's signal-to-noise, the others by
# their largest error against their own scale.
from ai_edge_litert.interpreter import Interpreter
from ai_edge_quantizer import quantizer, recipe


_seen = set()


def run_tflite(path, args):
    it = Interpreter(model_path=path)
    it.allocate_tensors()
    if path not in _seen:
        _seen.add(path)
        log(os.path.basename(path), "inputs", [(d["name"], list(d["shape"]), str(d["dtype"])) for d in it.get_input_details()],
            "outputs", [(d["name"], list(d["shape"])) for d in it.get_output_details()])
    ins = it.get_input_details()
    # In the order the arguments were given: the converter names them args_0, args_1 ...
    order = sorted(ins, key=lambda d: (d["name"].rsplit("_", 1)[-1].zfill(4) if d["name"].startswith("serving_default_args") or d["name"].startswith("args") else d["name"], d["index"]))
    for d, a in zip(order, args):
        it.set_tensor(d["index"], a.astype(d["dtype"]))
    it.invoke()
    return [it.get_tensor(d["index"]) for d in sorted(it.get_output_details(), key=lambda d: d["index"])]


def quantize(name, n_in, audio=False):
    path = os.path.join(D, f"{name}.tflite")
    q = os.path.join(D, f"{name}.q.tflite")
    qt = quantizer.Quantizer(path)
    qt.load_quantization_recipe(recipe.dynamic_wi8_afp32())
    qt.quantize().export_model(q)
    args = tuple(s[f"{name}_in{i}"] for i in range(n_in))
    want = run_tflite(path, args)
    got = run_tflite(q, args)
    ok = True
    for w, g in zip(want, got):
        if audio:
            noise = float(np.mean((g - w) ** 2)); signal = float(np.mean(w ** 2))
            snr = 10 * np.log10(signal / max(noise, 1e-12))
            log(name, "int8 vs float: SNR %.1f dB" % snr)
            ok = ok and snr >= 20.0
        else:
            rel = float(np.abs(g - w).max()) / max(float(np.abs(w).max()), 1e-6)
            log(name, "int8 vs float: largest error %.2f%% of its scale" % (rel * 100))
            ok = ok and rel <= 0.06
    size_f, size_q = os.path.getsize(path), os.path.getsize(q)
    if ok:
        os.replace(q, path)
        log(name, "kept int8:", size_q, "bytes (float was", size_f, ")")
    else:
        os.remove(q)
        log(name, "int8 too far from float; kept float:", size_f, "bytes")


quantize("encoder", 3)
quantize("decoder", 2)
quantize("vocoder", 1, audio=True)
enc = lambda *a: run_tflite(os.path.join(D, "encoder.tflite"), a)
dec = lambda *a: run_tflite(os.path.join(D, "decoder.tflite"), a)[0]
voc = lambda *a: run_tflite(os.path.join(D, "vocoder.tflite"), a)[0]

# The sample, through the TFLite models alone, as the app runs them.
x, mk, spk = s["encoder_in0"], s["encoder_in1"], s["encoder_in2"]
t = len(s["ids"])
outs = enc(x, mk, spk)
# By rank, as the app takes them: features [1,T,C], log durations [1,T].
feats = next(o for o in outs if np.asarray(o).ndim == 3)
logdur = next(o for o in outs if np.asarray(o).ndim == 2)
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
