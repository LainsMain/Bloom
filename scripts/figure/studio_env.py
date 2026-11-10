#!/usr/bin/env python3
"""Writes a soft studio environment (equirectangular Radiance .hdr) for cmgen. Usage: studio_env.py out.hdr"""
import sys
import numpy as np

W, H = 512, 256
lon = (np.arange(W) + 0.5) / W * 2 * np.pi - np.pi          # -pi..pi
lat = np.pi / 2 - (np.arange(H) + 0.5) / H * np.pi          # +pi/2 (up) .. -pi/2
LON, LAT = np.meshgrid(lon, lat)
d = np.stack([np.cos(LAT) * np.sin(LON), np.sin(LAT), np.cos(LAT) * np.cos(LON)], -1)  # +Z at lon 0


def lobe(direction, power, color):
    v = np.array(direction, float); v /= np.linalg.norm(v)
    c = np.clip((d @ v), 0, 1) ** power
    return c[..., None] * np.array(color)


up = np.clip(d[..., 1], -1, 1)[..., None]
env = np.where(up > 0, 0.55 + 0.35 * up, 0.42 + 0.22 * up) * np.array([1.0, 0.97, 0.95])  # soft sky / floor bounce
env = env + lobe((-0.5, 0.75, 0.6), 24, (5.0, 4.8, 4.5))    # key softbox, front-left above
env = env + lobe((0.8, 0.3, 0.4), 10, (1.1, 1.15, 1.3))     # cool fill, right
env = env + lobe((0.2, 0.5, -0.9), 30, (2.5, 2.3, 2.4))     # rim from behind
env = env.astype(np.float32)


def rgbe(img):
    m = img.max(-1)
    e = np.ceil(np.log2(np.maximum(m, 1e-32))).astype(int)
    scale = np.where(m > 1e-32, 255.0 / np.exp2(e) * (np.exp2(e) / np.exp2(e)), 0)
    out = np.zeros(img.shape[:2] + (4,), np.uint8)
    mant = img / np.exp2(e)[..., None] * 256
    out[..., :3] = np.clip(np.where(m[..., None] > 1e-32, mant, 0), 0, 255).astype(np.uint8)
    out[..., 3] = np.where(m > 1e-32, e + 128, 0).astype(np.uint8)
    return out


with open(sys.argv[1], 'wb') as f:
    f.write(b'#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n')
    f.write(f'-Y {H} +X {W}\n'.encode())
    f.write(rgbe(env).tobytes())
