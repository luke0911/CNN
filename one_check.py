#!/usr/bin/env python3
"""
one_check.py — "all-in-one" checker for YOLO OCR preprocessing & ONNX internals

Usage:
  python one_check.py --model Yolo.onnx --image sample.jpg --size 896 --save_npy --run
"""
import argparse, os, sys, json
import numpy as np
import onnx
from onnx import numpy_helper
import cv2

try:
    import onnxruntime as ort
    HAS_ORT = True
except Exception:
    HAS_ORT = False

def human(arr, max_items=8):
    flat = arr.ravel()
    s = ", ".join([f"{x:.4f}" for x in flat[:max_items]])
    if flat.size > max_items:
        s += ", ..."
    return s

def inspect_onnx(model_path, max_nodes=60):
    m = onnx.load(model_path)
    g = m.graph
    info = {}

    inputs = []
    for i in g.input:
        shape = []
        tt = i.type.tensor_type
        if tt.HasField("shape"):
            for d in tt.shape.dim:
                if d.HasField("dim_value"):
                    shape.append(d.dim_value)
                else:
                    shape.append(None)
        dtype = tt.elem_type if tt.HasField("elem_type") else None
        inputs.append({"name": i.name, "shape": shape, "elem_type": dtype})
    info["inputs"] = inputs

    found_consts = []
    for init in g.initializer:
        arr = numpy_helper.to_array(init)
        rec = None
        if arr.size == 1 and float(arr) in (255.0, 1/255.0):
            rec = {"name": init.name, "value": float(arr)}
        elif arr.size in (3,):
            vals = arr.astype(float).tolist()
            rec = {"name": init.name, "value3": [float(v) for v in vals]}
        if rec:
            found_consts.append(rec)
    info["consts"] = found_consts

    ops_interest = ("Div","Mul","Sub","Add","Transpose","Concat","Gather","Cast","Resize")
    nodes = []
    for n in g.node[:max_nodes]:
        if n.op_type in ops_interest:
            nodes.append({"op": n.op_type, "inputs": list(n.input), "outputs": list(n.output)})
    info["early_nodes"] = nodes
    return info

def letterbox(img, dst, color=(0,0,0)):
    h, w = img.shape[:2]
    scale = min(dst / w, dst / h)
    nw, nh = int(w*scale), int(h*scale)
    resized = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_LINEAR)
    out = np.full((dst, dst, 3), color, dtype=resized.dtype)
    pad_x = (dst - nw) // 2
    pad_y = (dst - nh) // 2
    out[pad_y:pad_y+nh, pad_x:pad_x+nw] = resized
    return out, scale, pad_x, pad_y

IMAGENET_MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
IMAGENET_STD  = np.array([0.229, 0.224, 0.225], dtype=np.float32)

def make_tensor(img_bgr, size, rgb=True, div255=True, imagenet=False, layout="NCHW"):
    lb, scale, pad_x, pad_y = letterbox(img_bgr, size)
    if rgb:
        img = cv2.cvtColor(lb, cv2.COLOR_BGR2RGB)
    else:
        img = lb.copy()
    img = img.astype(np.float32)
    if div255:
        img = img / 255.0
    if imagenet:
        img = (img - IMAGENET_MEAN) / IMAGENET_STD
    if layout.upper() == "NCHW":
        img = np.transpose(img, (2,0,1))[None, ...]
    else:
        img = img[None, ...]
    return img

def try_run(model_path, input_name, tensor, providers=None):
    if not HAS_ORT:
        return {"ran": False, "reason": "onnxruntime not available"}
    sess = ort.InferenceSession(model_path, providers=providers or ["CPUExecutionProvider"])
    outs = [o.name for o in sess.get_outputs()]
    res = sess.run(outs, {input_name: tensor})
    stats = []
    for r in res:
        arr = np.array(r)
        stats.append({
            "shape": list(arr.shape),
            "min": float(arr.min()),
            "max": float(arr.max()),
            "mean": float(arr.mean()),
            "sample": human(arr)
        })
    return {"ran": True, "outputs": stats}

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--image", required=True)
    ap.add_argument("--size", type=int, default=896)
    ap.add_argument("--layout", choices=["NCHW","NHWC"], default="NCHW")
    ap.add_argument("--save_npy", action="store_true")
    ap.add_argument("--run", action="store_true")
    ap.add_argument("--providers", default="CPUExecutionProvider")
    args = ap.parse_args()

    print("== Inspecting ONNX ==")
    info = inspect_onnx(args.model)
    print(json.dumps(info, indent=2))

    m = onnx.load(args.model)
    if not m.graph.input:
        print("ERROR: model has no inputs")
        sys.exit(1)
    input_name = m.graph.input[0].name
    print("Primary input name:", input_name)

    img_bgr = cv2.imread(args.image, cv2.IMREAD_COLOR)
    if img_bgr is None:
        print("ERROR: cannot read image:", args.image)
        sys.exit(1)

    combos = [
        ("RGB_/255_noNorm",   dict(rgb=True,  div255=True,  imagenet=False)),
        ("RGB_noDiv_noNorm",  dict(rgb=True,  div255=False, imagenet=False)),
        ("BGR_/255_noNorm",   dict(rgb=False, div255=True,  imagenet=False)),
        ("BGR_noDiv_noNorm",  dict(rgb=False, div255=False, imagenet=False)),
        ("RGB_/255_Imagenet", dict(rgb=True,  div255=True,  imagenet=True)),
        ("BGR_/255_Imagenet", dict(rgb=False, div255=True,  imagenet=True)),
    ]

    results = {}
    for name, opts in combos:
        tensor = make_tensor(img_bgr, args.size, layout=args.layout, **opts)
        results[name] = {
            "shape": list(tensor.shape),
            "min": float(tensor.min()),
            "max": float(tensor.max()),
            "mean": float(tensor.mean()),
            "sample": human(tensor)
        }
        if args.save_npy:
            outp = f"{name}_{args.layout}_{args.size}.npy"
            np.save(outp, tensor)
            results[name]["saved"] = outp

        if args.run:
            providers = [p.strip() for p in args.providers.split(",") if p.strip()]
            try:
                runres = try_run(args.model, input_name, tensor, providers=providers)
            except Exception as e:
                runres = {"ran": False, "error": str(e)}
            results[name]["run"] = runres

    print("== Candidate tensors ==")
    print(json.dumps(results, indent=2))

if __name__ == "__main__":
    main()
