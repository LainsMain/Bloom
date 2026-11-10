#!/usr/bin/env python3
"""
Builds app/src/main/assets/figure/figure.glb: the exercise demo figure.

Source: Quaternius "Universal Base Characters" (Standard, CC0) — Superhero_Female + Hair_Buns +
Eyebrows_Female. The underwear in the original texture is repainted as a sports top and leggings by
rasterising the mesh in UV space and classifying every texel by its 3-D bind-pose position (so the
outfit edges are crisp and continuous across UV seams). Simple props (mat, boxes, towel) are added as
extra nodes that the app positions per exercise.

Usage: scripts/figure/build_figure.py "<path to 'Universal Base Characters[Standard]'>"
Needs numpy and Pillow.
"""
import io
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gltf_io import Gltf, GlbWriter  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'figure', 'figure.glb')
TEX = 1024  # output texture size
BAKE = 2048  # bake resolution (downsampled for anti-aliasing)


def srgb(hexstr):
    h = hexstr.lstrip('#')
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], dtype=np.float64)


TOP = srgb('#C9567D')        # rose sports top
TOP_TRIM = srgb('#F2B8CB')   # light rose edge binding
LEGGINGS = srgb('#47344F')   # deep plum leggings
BAND = srgb('#C9567D')       # rose waistband stripe
HAIR_TINT = np.array([0.36, 0.24, 0.17])  # multiplies the greyscale hair texture (sRGB space)


# ---------------------------------------------------------------- outfit classification
def outfit(p, n):
    """Returns (region id, trim weight) per bind-pose point. 0 skin, 1 top, 2 leggings, 3 waistband, 4 top trim."""
    x, y, z = p[:, 0], p[:, 1], p[:, 2]
    ax = np.abs(x)
    region = np.zeros(len(p), np.int8)

    # Leggings: high waist (covers the briefs, top 1.045 m) down to just above the ankle.
    waist = 1.078 + 0.006 * (z < 0)
    hem = 0.112 + 0.012 * (z > -0.05)  # hem a touch lower at the heel
    legs = (y > hem) & (y < waist) & (ax < 0.36)
    region[legs] = 2
    region[legs & (y > waist - 0.034)] = 3

    # Sports top (longline): covers the bandeau (1.269–1.38 m) with a scoop neck and racerback straps.
    torso = ax < 0.2
    band_lo = 1.232
    # Front/back by surface direction (the front of the neck sits behind z = 0).
    nz = n[:, 2] / np.maximum(np.linalg.norm(n, axis=1), 1e-6)
    front = (nz > 0.1) | ((nz > -0.1) & (z > 0.0))
    neckline = 1.392 + 2.2 * x * x
    body = torso & (y > band_lo) & (((front) & (y < neckline)) | ((~front) & (y < 1.335)))
    # Front straps rise from the cups over the shoulder.
    strap_c = 0.083 - 0.15 * np.clip(y - 1.40, 0, None)
    straps = torso & (y >= 1.33) & (y < 1.52) & (np.abs(ax - strap_c) < 0.024) & (z > -0.02)
    # Racerback: straps converge to a centre panel between the shoulder blades.
    rb_half = 0.028 + 0.55 * np.clip(1.47 - y, 0, None) ** 1.3
    rb_half = np.where(y > 1.47, 0.028 + 0.9 * (y - 1.47), rb_half)
    racer = torso & (nz < -0.15) & (y >= 1.30) & (y < 1.50) & (ax < np.minimum(rb_half, 0.12))
    # Connect front straps and racerback over the top of the shoulder.
    over = torso & (y > 1.44) & (y < 1.52) & (np.abs(nz) <= 0.15) & (np.abs(ax - strap_c) < 0.026)
    top = body | straps | racer | over
    region[top] = 1
    # Binding along the lower edge of the top.
    region[top & (y < band_lo + 0.018)] = 4
    return region


# ---------------------------------------------------------------- UV-space rasteriser
def bake_positions(P, N, UV, I, size):
    """For each texel covered by a triangle: interpolated bind position + normal."""
    pos = np.zeros((size, size, 3), np.float32)
    nor = np.zeros((size, size, 3), np.float32)
    cov = np.zeros((size, size), bool)
    uv = UV * size - 0.5
    for t in I:
        a, b, c = uv[t[0]], uv[t[1]], uv[t[2]]
        x0 = int(max(np.floor(min(a[0], b[0], c[0])), 0)); x1 = int(min(np.ceil(max(a[0], b[0], c[0])), size - 1))
        y0 = int(max(np.floor(min(a[1], b[1], c[1])), 0)); y1 = int(min(np.ceil(max(a[1], b[1], c[1])), size - 1))
        if x1 < x0 or y1 < y0:
            continue
        gx, gy = np.meshgrid(np.arange(x0, x1 + 1), np.arange(y0, y1 + 1))
        d = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
        if abs(d) < 1e-12:
            continue
        w0 = ((b[1] - c[1]) * (gx - c[0]) + (c[0] - b[0]) * (gy - c[1])) / d
        w1 = ((c[1] - a[1]) * (gx - c[0]) + (a[0] - c[0]) * (gy - c[1])) / d
        w2 = 1 - w0 - w1
        e = -0.02  # slight conservative fill so edges are covered
        m = (w0 >= e) & (w1 >= e) & (w2 >= e)
        if not m.any():
            continue
        yy, xx = gy[m], gx[m]
        W = np.stack([w0[m], w1[m], w2[m]], 1)
        pos[yy, xx] = W @ P[t]
        nor[yy, xx] = W @ N[t]
        cov[yy, xx] = True
    return pos, nor, cov


def dilate(img, mask, steps):
    """Spread covered texels into uncovered ones (avoids seam bleeding in mipmaps)."""
    img = img.copy(); mask = mask.copy()
    for _ in range(steps):
        acc = np.zeros_like(img, dtype=np.float64); cnt = np.zeros(mask.shape)
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            sm = np.roll(mask, (dy, dx), (0, 1)); si = np.roll(img, (dy, dx), (0, 1))
            acc += si * sm[..., None]; cnt += sm
        grow = (~mask) & (cnt > 0)
        img[grow] = (acc[grow] / cnt[grow][:, None]).astype(img.dtype)
        mask = mask | grow
    return img, mask


def jpeg(arr, q=90):
    b = io.BytesIO(); Image.fromarray(arr.astype(np.uint8)).save(b, 'JPEG', quality=q, optimize=True); return b.getvalue()


def png(arr):
    b = io.BytesIO(); Image.fromarray(arr.astype(np.uint8)).save(b, 'PNG', optimize=True); return b.getvalue()


def load_rgb(path, size=None):
    im = Image.open(path).convert('RGB')
    if size:
        im = im.resize((size, size), Image.LANCZOS)
    return np.asarray(im).astype(np.float64)


# ---------------------------------------------------------------- props
def box_mesh():
    """Unit cube centred on the origin, flat normals."""
    faces = [((1, 0, 0), (0, 1, 0)), ((-1, 0, 0), (0, 1, 0)), ((0, 1, 0), (0, 0, 1)), ((0, -1, 0), (0, 0, 1)),
             ((0, 0, 1), (0, 1, 0)), ((0, 0, -1), (0, 1, 0))]
    P, N, UV, I = [], [], [], []
    for nrm, up in faces:
        nrm = np.array(nrm, float); up = np.array(up, float); r = np.cross(up, nrm)
        base = len(P)
        for su, sv in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
            P.append(0.5 * nrm + 0.5 * su * r + 0.5 * sv * up); N.append(nrm); UV.append(((su + 1) / 2, (sv + 1) / 2))
        I += [base, base + 1, base + 2, base, base + 2, base + 3]
    return np.array(P, np.float32), np.array(N, np.float32), np.array(UV, np.float32), np.array(I, np.uint16)


def main(src):
    body_dir = os.path.join(src, 'Base Characters', 'Godot - UE')
    hair_dir = os.path.join(src, 'Hairstyles', 'Rigged to Head Bone', 'glTF (Godot -Unreal)')
    g = Gltf(os.path.join(body_dir, 'Superhero_Female_FullBody.gltf'))
    hair = Gltf(os.path.join(hair_dir, 'Hair_Buns.gltf'))
    brows = Gltf(os.path.join(hair_dir, 'Eyebrows_Female.gltf'))

    joint_names = [g.j['nodes'][j]['name'] for j in g.j['skins'][0]['joints']]

    # ---- body texture bake
    prim = next(p for m in g.j['meshes'] if m['name'] == 'Superhero_Female' for p in m['primitives'])
    P = g.acc(prim['attributes']['POSITION']); N = g.acc(prim['attributes']['NORMAL'])
    UV = g.acc(prim['attributes']['TEXCOORD_0']); I = g.acc(prim['indices']).reshape(-1, 3)
    print('baking outfit…')
    pos, nor, cov = bake_positions(P, N, UV, I, BAKE)
    region = np.zeros((BAKE, BAKE), np.int8)
    region[cov] = outfit(pos[cov], nor[cov])

    skin = load_rgb(os.path.join(src, 'Base Characters', 'Textures', 'T_Superhero_Female_Light_BaseColor.png'), BAKE)
    # Anything that was underwear in the original texture must end up clothed (the two variants differ
    # only in the underwear colour).
    dark = load_rgb(os.path.join(body_dir, 'T_Superhero_Female_Dark_BaseColor.png'), BAKE)
    under = np.abs(dark - skin).sum(2) > 40
    missed = under & cov & (region == 0)
    if missed.any():
        ys = pos[missed][:, 1]
        print(f'  underwear texels outside the outfit: {missed.sum()} (y {ys.min():.3f}..{ys.max():.3f}) — covering them')
        region[missed] = np.where(pos[missed][:, 1] > 1.15, 1, 2)
    region[under & ~cov] = 0
    normal = load_rgb(os.path.join(body_dir, 'T_Superhero_Female_Normal.png'), BAKE)
    rough = load_rgb(os.path.join(body_dir, 'T_Superhero_Female_Roughness.png'), BAKE)
    # Flat fabric colours: lighting and the (softened) normal map give the shape.
    color = skin.copy()
    for rid, col in ((1, TOP), (2, LEGGINGS), (3, BAND), (4, TOP_TRIM)):
        color[region == rid] = col
    clothed = region > 0
    # Fabric is smoother and more matte than skin.
    flat = np.array([128.0, 128.0, 255.0])
    normal[clothed] = flat + (normal[clothed] - flat) * 0.55
    rough[clothed] = np.maximum(rough[clothed], 0) * 0 + np.array([0, 230, 0])  # glTF: G = roughness
    # Spread clothing colour across UV island borders so mip levels don't bleed skin into seams.
    c2, _ = dilate(np.where(cov[..., None], color, 0), cov, 6)
    color = np.where(cov[..., None], color, c2)

    def down(a):
        return np.asarray(Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)).resize((TEX, TEX), Image.LANCZOS))

    w = GlbWriter()
    t_color = w.image(jpeg(down(color), 92), 'image/jpeg')
    t_normal = w.image(jpeg(down(normal), 95), 'image/jpeg')
    mr = rough.copy(); mr[..., 0] = 0; mr[..., 2] = 0  # metallic (B) = 0
    t_mr = w.image(jpeg(np.asarray(Image.fromarray(np.clip(mr, 0, 255).astype(np.uint8)).resize((512, 512), Image.LANCZOS)), 90), 'image/jpeg')
    hair_img = load_rgb(os.path.join(hair_dir, 'T_Hair_2_BaseColor.png'), 512) * HAIR_TINT
    t_hair = w.image(jpeg(hair_img, 90), 'image/jpeg')
    t_hair_n = w.image(jpeg(load_rgb(os.path.join(hair_dir, 'T_Hair_2_Normal.png'), 512), 92), 'image/jpeg')
    t_eye = w.image(png(load_rgb(os.path.join(body_dir, 'T_Eye_Brown.png'))), 'image/png')

    mats = w.j['materials']
    mats.append({'name': 'skin', 'pbrMetallicRoughness': {'baseColorTexture': {'index': t_color}, 'metallicFactor': 0.0,
                                                          'roughnessFactor': 1.0, 'metallicRoughnessTexture': {'index': t_mr}},
                 'normalTexture': {'index': t_normal}})
    mats.append({'name': 'hair', 'doubleSided': True, 'pbrMetallicRoughness': {'baseColorTexture': {'index': t_hair}, 'metallicFactor': 0.0, 'roughnessFactor': 0.62},
                 'normalTexture': {'index': t_hair_n}})
    mats.append({'name': 'eyes', 'pbrMetallicRoughness': {'baseColorTexture': {'index': t_eye}, 'metallicFactor': 0.0, 'roughnessFactor': 0.25}})
    # Factors are linear: warm wood-ish furniture, a muted plum mat, an off-white towel.
    mats.append({'name': 'prop', 'pbrMetallicRoughness': {'baseColorFactor': [0.30, 0.22, 0.17, 1], 'metallicFactor': 0.0, 'roughnessFactor': 0.7}})
    mats.append({'name': 'mat', 'pbrMetallicRoughness': {'baseColorFactor': [0.24, 0.10, 0.20, 1], 'metallicFactor': 0.0, 'roughnessFactor': 0.9}})
    mats.append({'name': 'towel', 'pbrMetallicRoughness': {'baseColorFactor': [0.62, 0.62, 0.58, 1], 'metallicFactor': 0.0, 'roughnessFactor': 1.0}})
    M_SKIN, M_HAIR, M_EYES, M_PROP, M_MAT, M_TOWEL = range(6)

    # ---- nodes: copy the skeleton (same names / rest transforms)
    src_nodes = g.j['nodes']
    keep = [i for i, n in enumerate(src_nodes) if 'mesh' not in n]
    remap = {old: new for new, old in enumerate(keep)}
    for old in keep:
        n = src_nodes[old]
        nn = {k: v for k, v in n.items() if k in ('name', 'translation', 'rotation', 'scale')}
        kids = [remap[c] for c in n.get('children', []) if c in remap]
        if kids:
            nn['children'] = kids
        w.j['nodes'].append(nn)
    armature = remap[g.node_index('Armature')]
    ib = g.acc(g.j['skins'][0]['inverseBindMatrices']).astype(np.float32)
    w.j['skins'].append({'name': 'Armature', 'joints': [remap[j] for j in g.j['skins'][0]['joints']],
                         'inverseBindMatrices': w.accessor(ib, 'MAT4')})

    def skinned(gl, mesh_name, mat, name):
        pr = next(p for m in gl.j['meshes'] if m['name'] == mesh_name for p in m['primitives'])
        names = [gl.j['nodes'][j]['name'] for j in gl.j['skins'][0]['joints']]
        jmap = np.array([joint_names.index(nm) for nm in names])
        a = pr['attributes']
        J = jmap[gl.acc(a['JOINTS_0']).astype(int)].astype(np.uint16)
        Wt = gl.acc(a['WEIGHTS_0']).astype(np.float32)
        Wt /= np.maximum(Wt.sum(1, keepdims=True), 1e-8)
        attrs = {'POSITION': w.accessor(gl.acc(a['POSITION']).astype(np.float32), 'VEC3', 34962, minmax=True),
                 'NORMAL': w.accessor(gl.acc(a['NORMAL']).astype(np.float32), 'VEC3', 34962),
                 'TEXCOORD_0': w.accessor(gl.acc(a['TEXCOORD_0']).astype(np.float32), 'VEC2', 34962),
                 'JOINTS_0': w.accessor(J, 'VEC4', 34962), 'WEIGHTS_0': w.accessor(Wt, 'VEC4', 34962)}
        idx = gl.acc(pr['indices']).astype(np.uint16 if gl.acc(pr['indices']).max() < 65535 else np.uint32).ravel()
        w.j['meshes'].append({'name': name, 'primitives': [{'attributes': attrs, 'indices': w.accessor(idx, 'SCALAR', 34963), 'material': mat}]})
        w.j['nodes'].append({'name': name, 'mesh': len(w.j['meshes']) - 1, 'skin': 0})
        w.j['nodes'][armature].setdefault('children', []).append(len(w.j['nodes']) - 1)

    skinned(g, 'Superhero_Female', M_SKIN, 'body')
    skinned(g, 'Eyes', M_EYES, 'eyes')
    skinned(brows, 'Eyebrows_Female', M_HAIR, 'brows')
    skinned(hair, 'Hair_Buns', M_HAIR, 'hair')
    w.j['scenes'][0]['nodes'].append(armature)

    # ---- props (positioned and shown per exercise by the app)
    bp, bn, buv, bi = box_mesh()
    acc_p = w.accessor(bp, 'VEC3', 34962, minmax=True); acc_n = w.accessor(bn, 'VEC3', 34962)
    acc_uv = w.accessor(buv, 'VEC2', 34962); acc_i = w.accessor(bi, 'SCALAR', 34963)
    for name, mat in (('box', M_PROP), ('mat', M_MAT), ('towel', M_TOWEL)):
        w.j['meshes'].append({'name': name, 'primitives': [{'attributes': {'POSITION': acc_p, 'NORMAL': acc_n, 'TEXCOORD_0': acc_uv},
                                                            'indices': acc_i, 'material': mat}]})
    mesh_ids = {m['name']: i for i, m in enumerate(w.j['meshes'])}
    props = [('prop_mat', 'mat')] + [(f'prop_box_{k}', 'box') for k in range(6)] + [('prop_towel', 'towel')]
    for name, mesh in props:
        w.j['nodes'].append({'name': name, 'mesh': mesh_ids[mesh], 'scale': [0.001, 0.001, 0.001], 'translation': [0, -10, 0]})
        w.j['scenes'][0]['nodes'].append(len(w.j['nodes']) - 1)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    w.write(OUT)
    print('wrote', OUT, os.path.getsize(OUT) // 1024, 'KB')
    # Debug preview of the baked texture next to the build output.
    Image.fromarray(down(color).astype(np.uint8)).save(os.path.join(ROOT, 'build', 'figure_basecolor.png'))


if __name__ == '__main__':
    main(sys.argv[1])
