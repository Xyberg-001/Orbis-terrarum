#!/usr/bin/env python3
"""Classify the mod's cached aerial imagery with a land-cover segmentation model.

Reads every tile in config/orbisterrarum/imagery-cache (<source>_<z>_<x>_<y>.img), runs the FLAIR-INC
RGB U-Net (IGN, Etalab 2.0 licence, 15 land-cover classes trained on 20 cm aerial imagery) and writes one
class tile per imagery tile to config/orbisterrarum/ground-classes/<z>_<x>_<y>.png, a grey PNG whose pixel
values are the mod's GroundClass codes:

    0 unknown, 1 grass, 2 tree canopy, 3 paved light, 4 paved dark, 5 bare soil, 6 sand, 7 rock, 8 snow, 9 water

The mod reads those tiles instead of its colour rules wherever they exist. Weights:
https://huggingface.co/IGNF/FLAIR-INC_rgb_15cl_resnet34-unet (FLAIR-INC_rgb_15cl_resnet34-unet_weights.pth)

    pip install torch torchvision segmentation-models-pytorch pillow numpy
    python tools/classify_ground.py --instance <instance>/minecraft/config/orbisterrarum [--device cuda]
"""
import argparse
import os
import re
import sys
import time

import numpy as np
from PIL import Image

try:
    import torch
    import segmentation_models_pytorch as smp
except ImportError:
    print("needs: pip install torch torchvision segmentation-models-pytorch", file=sys.stderr)
    sys.exit(2)

# The checkpoint's head has the full 19-class FLAIR nomenclature (index 0..18): building, pervious surface,
# impervious surface, bare soil, water, coniferous, deciduous, brushwood, vineyard, herbaceous vegetation,
# agricultural land, plowed land, swimming pool, snow, clear cut, mixed, ligneous, greenhouse, other. The
# "15cl" model was trained with the four rare ones weighted out, so they almost never win. -> GroundClass
# codes. Buildings and pools are left "unknown": the map knows those better, and blue is never trusted.
FLAIR_TO_GROUND = np.array([0, 5, 4, 5, 0, 2, 2, 1, 1, 1, 1, 5, 0, 8, 1, 2, 2, 0, 0], dtype=np.uint8)
NUM_CLASSES = 19
MEAN = np.array([105.08, 110.87, 101.82], dtype=np.float32)
STD = np.array([52.17, 45.38, 44.00], dtype=np.float32)
MODEL_SIZE = 512
NAME = re.compile(r"^(.+)_(\d+)_(\d+)_(\d+)\.img$")


def load_model(weights, device):
    model = smp.Unet(encoder_name="resnet34", encoder_weights=None, in_channels=3, classes=NUM_CLASSES)
    sd = torch.load(weights, map_location="cpu", weights_only=False)
    if isinstance(sd, dict) and "state_dict" in sd:
        sd = sd["state_dict"]
    clean = {}
    for k, v in sd.items():
        for prefix in ("model.seg_model.", "seg_model.", "model.", "module."):
            if k.startswith(prefix):
                k = k[len(prefix):]
                break
        clean[k] = v
    expected = model.state_dict()
    clean = {k: v for k, v in clean.items() if k in expected}  # drops e.g. the training loss weights
    bad = [k for k, v in clean.items() if tuple(v.shape) != tuple(expected[k].shape)]
    missing, unexpected = model.load_state_dict(clean, strict=False)
    print("weights: %d tensors loaded, %d missing, %d shape mismatches" % (len(clean) - len(bad), len(missing), len(bad)))
    if missing or bad:
        print("the checkpoint layout is not what this script expects", file=sys.stderr)
        print("first missing:", list(missing)[:5], "mismatched:", bad[:5], file=sys.stderr)
        sys.exit(3)
    return model.to(device).eval()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--instance", required=True, help="the config/orbisterrarum folder")
    ap.add_argument("--weights", default=None)
    ap.add_argument("--device", default="cuda" if torch.cuda.is_available() else "cpu")
    ap.add_argument("--batch", type=int, default=8)
    ap.add_argument("--force", action="store_true", help="redo tiles that already have a class tile")
    ap.add_argument("--only", default="", help="only tiles whose file name contains this text (e.g. _18_1349)")
    ap.add_argument("--limit", type=int, default=0, help="stop after this many tiles (for a quick test)")
    args = ap.parse_args()
    cfg = args.instance
    imagery = os.path.join(cfg, "imagery-cache")
    out = os.path.join(cfg, "ground-classes")
    weights = args.weights or os.path.join(cfg, "models", "FLAIR-INC_rgb_15cl_resnet34-unet_weights.pth")
    if not os.path.exists(weights):
        print("weights not found:", weights, file=sys.stderr)
        sys.exit(2)
    os.makedirs(out, exist_ok=True)
    device = torch.device(args.device)
    print("device:", device)
    model = load_model(weights, device)

    files = []
    for name in sorted(os.listdir(imagery)):
        m = NAME.match(name)
        if not m or (args.only and args.only not in name):
            continue
        z, x, y = m.group(2), m.group(3), m.group(4)
        target = os.path.join(out, "%s_%s_%s.png" % (z, x, y))
        if os.path.exists(target) and not args.force:
            continue
        files.append((os.path.join(imagery, name), target))
    if args.limit:
        files = files[:args.limit]
    print("%d tiles to classify" % len(files), flush=True)
    if not files:
        return

    t0 = time.time()
    done = 0
    mean = torch.tensor(MEAN, device=device).view(1, 3, 1, 1)
    std = torch.tensor(STD, device=device).view(1, 3, 1, 1)
    with torch.no_grad():
        for i in range(0, len(files), args.batch):
            batch = files[i:i + args.batch]
            imgs, sizes, targets = [], [], []
            for src, target in batch:
                try:
                    im = Image.open(src).convert("RGB")
                except Exception as e:  # noqa: BLE001
                    print("skip", src, e)
                    continue
                sizes.append(im.size[0])
                if im.size != (MODEL_SIZE, MODEL_SIZE):
                    im = im.resize((MODEL_SIZE, MODEL_SIZE), Image.BILINEAR)
                imgs.append(np.asarray(im, dtype=np.float32))
                targets.append(target)
            if not imgs:
                continue
            x = torch.from_numpy(np.stack(imgs)).permute(0, 3, 1, 2).to(device)
            x = (x - mean) / std
            logits = model(x)
            pred = logits.argmax(1).cpu().numpy().astype(np.uint8)
            for k, target in enumerate(targets):
                classes = FLAIR_TO_GROUND[pred[k]]
                size = sizes[k]
                if size != MODEL_SIZE:
                    classes = np.asarray(Image.fromarray(classes, "L").resize((size, size), Image.NEAREST))
                Image.fromarray(classes, "L").save(target + ".tmp.png", optimize=True)
                os.replace(target + ".tmp.png", target)
                done += 1
            if done % 400 < args.batch:
                rate = done / max(1e-6, time.time() - t0)
                print("  %d / %d tiles, %.1f tiles/s" % (done, len(files), rate), flush=True)
    print("done: %d class tiles in %s (%.0f s)" % (done, out, time.time() - t0), flush=True)


if __name__ == "__main__":
    main()
