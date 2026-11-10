"""Minimal glTF 2.0 reader/writer helpers (numpy only) for the Bloom figure pipeline."""
import json, os, struct
import numpy as np

COMP = {5126: np.float32, 5123: np.uint16, 5125: np.uint32, 5121: np.uint8, 5122: np.int16, 5120: np.int8}
NCOMP = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4, 'MAT4': 16}


class Gltf:
    def __init__(self, path):
        self.path = path
        self.base = os.path.dirname(path)
        self.j = json.load(open(path))
        self.bins = [open(os.path.join(self.base, b['uri']), 'rb').read() for b in self.j['buffers']]

    def acc(self, i):
        a = self.j['accessors'][i]
        bv = self.j['bufferViews'][a['bufferView']]
        n = NCOMP[a['type']]
        dt = COMP[a['componentType']]
        off = bv.get('byteOffset', 0) + a.get('byteOffset', 0)
        item = np.dtype(dt).itemsize * n
        stride = bv.get('byteStride', 0) or item
        raw = self.bins[bv['buffer']]
        if stride == item:
            arr = np.frombuffer(raw, dt, a['count'] * n, off).reshape(a['count'], n)
        else:
            arr = np.stack([np.frombuffer(raw, dt, n, off + k * stride) for k in range(a['count'])])
        if a.get('normalized') and dt != np.float32:
            arr = arr.astype(np.float32) / np.iinfo(dt).max
        return arr.copy()

    def node_index(self, name):
        for i, n in enumerate(self.j['nodes']):
            if n.get('name') == name:
                return i
        raise KeyError(name)


def q2m(q):
    x, y, z, w = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def local_matrix(n):
    m = np.eye(4)
    m[:3, :3] = q2m(n.get('rotation', [0, 0, 0, 1])) @ np.diag(n.get('scale', [1, 1, 1]))
    m[:3, 3] = n.get('translation', [0, 0, 0])
    return m


def world_matrices(nodes):
    par = {}
    for i, n in enumerate(nodes):
        for c in n.get('children', []):
            par[c] = i
    out = {}

    def w(i):
        if i not in out:
            out[i] = (w(par[i]) if i in par else np.eye(4)) @ local_matrix(nodes[i])
        return out[i]
    for i in range(len(nodes)):
        w(i)
    return out


class GlbWriter:
    """Accumulates buffer views/accessors and writes a single .glb."""

    def __init__(self):
        self.j = {'asset': {'version': '2.0', 'generator': 'bloom figure pipeline'}, 'buffers': [{}], 'bufferViews': [],
                  'accessors': [], 'nodes': [], 'meshes': [], 'materials': [], 'textures': [], 'images': [],
                  'samplers': [{'magFilter': 9729, 'minFilter': 9987, 'wrapS': 10497, 'wrapT': 10497}], 'skins': [],
                  'scenes': [{'nodes': []}], 'scene': 0}
        self.blob = bytearray()

    def _view(self, data, target=None):
        while len(self.blob) % 4:
            self.blob.append(0)
        off = len(self.blob)
        self.blob += data
        v = {'buffer': 0, 'byteOffset': off, 'byteLength': len(data)}
        if target:
            v['target'] = target
        self.j['bufferViews'].append(v)
        return len(self.j['bufferViews']) - 1

    def accessor(self, arr, kind, target=None, minmax=False, normalized=False):
        arr = np.ascontiguousarray(arr)
        ctype = {np.dtype(np.float32): 5126, np.dtype(np.uint16): 5123, np.dtype(np.uint32): 5125, np.dtype(np.uint8): 5121}[arr.dtype]
        view = self._view(arr.tobytes(), target)
        a = {'bufferView': view, 'componentType': ctype, 'count': int(arr.shape[0]), 'type': kind}
        if normalized:
            a['normalized'] = True
        if minmax:
            a['min'] = [float(x) for x in arr.reshape(arr.shape[0], -1).min(0)]
            a['max'] = [float(x) for x in arr.reshape(arr.shape[0], -1).max(0)]
        self.j['accessors'].append(a)
        return len(self.j['accessors']) - 1

    def image(self, data, mime):
        view = self._view(data)
        self.j['images'].append({'bufferView': view, 'mimeType': mime})
        self.j['textures'].append({'source': len(self.j['images']) - 1, 'sampler': 0})
        return len(self.j['textures']) - 1

    def write(self, path):
        while len(self.blob) % 4:
            self.blob.append(0)
        self.j['buffers'][0]['byteLength'] = len(self.blob)
        for k in [k for k, v in self.j.items() if isinstance(v, list) and not v]:
            del self.j[k]
        js = json.dumps(self.j, separators=(',', ':')).encode()
        while len(js) % 4:
            js += b' '
        total = 12 + 8 + len(js) + 8 + len(self.blob)
        with open(path, 'wb') as f:
            f.write(struct.pack('<III', 0x46546C67, 2, total))
            f.write(struct.pack('<II', len(js), 0x4E4F534A))
            f.write(js)
            f.write(struct.pack('<II', len(self.blob), 0x004E4942))
            f.write(self.blob)
