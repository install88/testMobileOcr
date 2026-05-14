r"""Run the mobile ONNX pipeline on a det-style dataset and write report.html.

Example:
  python tools/mobile_onnx_html_report.py --dataset C:\Users\insta\Desktop\dataset --split val
"""
from __future__ import annotations

import argparse
import base64
import html
import io
import json
import math
import re
import sys
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

PADDING_X = 6
PADDING_Y = 0
CONF_THRESH = 0.25
IOU_THRESH = 0.45
YOLO_SIZE = 640
REC_HEIGHT = 48

MONTH_NAME = r"(?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)"
SEP = "[./\\-,:\\uFF1A]"
DATE_SEP = rf"\s*{SEP}\s*"
FULL_DATE = (
    r"(?<!\d)(?:20|19)\d{6}(?=\d{1,5}(?:\D|$)|[A-Z]|$)"
    rf"|(?<!\d)(?:20|19)\d{{2}}\s+\d{{4}}(?!\d)"
    rf"|\d{{4}}{DATE_SEP}\d{{1,2}}{DATE_SEP}\d{{1,2}}"
    rf"|\d{{1,2}}{DATE_SEP}\d{{1,2}}{DATE_SEP}\d{{4}}"
    rf"|\d{{1,2}}{DATE_SEP}\d{{1,2}}{DATE_SEP}\d{{2}}(?!\d)"
    r"|\d{4}\s*[./\-]\s*\d{4}(?!\d)"
    r"|\d{4}\s+\d{1,2}\s+\d{1,2}"
    r"|\d{1,2}\s+\d{1,2}\s+\d{4}"
    r"|\d{1,2}\s+\d{1,2}\s+\d{2}(?!\d)"
    r"|\d{2,4}\s*\u5e74\s*\d{1,2}\s*\u6708\s*\d{1,2}\s*\u65e5?"
    r"|\d{1,2}\s*\u65e5\s*\d{1,2}\s*\u6708\s*\d{2,4}\s*\u5e74"
    rf"|{MONTH_NAME}\s+\d{{1,2}}\s+\d{{2,4}}"
    rf"|\d{{1,2}}[./\-\s]*{MONTH_NAME}[./\-\s]*\d{{2,4}}"
    r"|\d{6}[./\-]\d{1,2}(?!\d)"
    r"|\d{2,3}\s*[./\-]\s*\d{1,2}\s*[./\-]\s*\d{1,4}"
    r"|(?<!\d)(?:20|19)\d{6}(?!\d{2})"
    r"|(?<!\d)\d{8}(?!\d)"
    r"|(?<!\d)\d{6}(?!\d)"
)
YEAR_MONTH = (
    rf"\d{{4}}{DATE_SEP}\d{{1,2}}(?!\s*(?:{SEP}|\d))"
    rf"|\d{{1,2}}{DATE_SEP}\d{{4}}(?!\d)"
    r"|\d{4}\s+\d{1,2}(?!\s+\d)"
    r"|\d{1,2}\s+\d{4}(?!\d)"
    r"|\d{4}\s*\u5e74\s*\d{1,2}\s*\u6708"
    r"|(?<!\d)\d{6}(?!\d)"
)
PREFIXED = (
    r"(?:\u6709\u6548|\u88fd\u9020|\u5230\u671f|\u8cde\u5473|\u751f\u7523|\u51fa\u5ee0|"
    r"MFG|MFD|PROD|PRO|EXP|BBF|BBE|BB|BEST\s*BEFORE|EXPIRY|DATE|PD|ED|DDM)"
    r"(?:DATE|\u65e5\u671f|\u671f\u9650|\u671f|\u65e5)?"
    r"[\uff1a:\s.\-/]*"
    r"\d{2}"
)
DATE_PATTERN = re.compile(f"(?:{FULL_DATE}|{YEAR_MONTH}|{PREFIXED})", re.IGNORECASE)
EXTRACT_PATTERN = re.compile(f"(?:{FULL_DATE}|{YEAR_MONTH})", re.IGNORECASE)


@dataclass
class Detection:
    bbox: tuple[float, float, float, float]
    conf: float


def extract_date(text: str | None) -> str | None:
    if not text:
        return None
    match = EXTRACT_PATTERN.search(text.upper())
    return match.group().strip() if match else None


def is_date_text(text: str | None) -> bool:
    return bool(text and text != "###" and DATE_PATTERN.search(text.upper()))


def digits_only(text: str | None) -> str:
    return re.sub(r"\D", "", text or "")


def img_to_b64(img: np.ndarray, max_width: int = 760) -> str:
    h, w = img.shape[:2]
    if w > max_width:
        scale = max_width / w
        img = cv2.resize(img, (max_width, max(1, int(h * scale))), interpolation=cv2.INTER_AREA)
    ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 82])
    if not ok:
        return ""
    return base64.b64encode(buf).decode("ascii")


def letterbox(img: np.ndarray) -> tuple[np.ndarray, float, float, float]:
    h, w = img.shape[:2]
    scale = min(YOLO_SIZE / max(1, w), YOLO_SIZE / max(1, h))
    nw, nh = max(1, round(w * scale)), max(1, round(h * scale))
    resized = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_LINEAR)
    canvas = np.full((YOLO_SIZE, YOLO_SIZE, 3), 114, dtype=np.uint8)
    pad_x = (YOLO_SIZE - nw) / 2
    pad_y = (YOLO_SIZE - nh) / 2
    canvas[round(pad_y) : round(pad_y) + nh, round(pad_x) : round(pad_x) + nw] = resized
    rgb = cv2.cvtColor(canvas, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
    return np.transpose(rgb, (2, 0, 1))[None], scale, pad_x, pad_y


def iou(a: Detection, b: Detection) -> float:
    ax1, ay1, ax2, ay2 = a.bbox
    bx1, by1, bx2, by2 = b.bbox
    ix1, iy1 = max(ax1, bx1), max(ay1, by1)
    ix2, iy2 = min(ax2, bx2), min(ay2, by2)
    inter = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
    area_a = max(0.0, ax2 - ax1) * max(0.0, ay2 - ay1)
    area_b = max(0.0, bx2 - bx1) * max(0.0, by2 - by1)
    union = area_a + area_b - inter
    return 0.0 if union <= 0 else inter / union


def gt_bbox(item: dict) -> tuple[float, float, float, float]:
    pts = np.asarray(item["points"], dtype=np.float32)
    xs = pts[:, 0]
    ys = pts[:, 1]
    return float(xs.min()), float(ys.min()), float(xs.max()), float(ys.max())


def best_iou_for_gt(item: dict, detections: list[Detection]) -> float:
    if not detections:
        return 0.0
    gt = Detection(gt_bbox(item), 1.0)
    return max(iou(gt, det) for det in detections)


def yolo_detect(session: ort.InferenceSession, img: np.ndarray) -> list[Detection]:
    x, scale, pad_x, pad_y = letterbox(img)
    output = np.asarray(session.run(None, {session.get_inputs()[0].name: x})[0])
    if output.ndim == 3:
        output = output[0]
    if output.shape[0] <= output.shape[1] and output.shape[0] <= 128:
        output = output.T

    h, w = img.shape[:2]
    dets: list[Detection] = []
    for row in output:
        if row.shape[0] < 5:
            continue
        score = float(row[4:].max()) if row.shape[0] > 5 else float(row[4])
        if score < CONF_THRESH:
            continue
        cx, cy, bw, bh = map(float, row[:4])
        x1 = max(0.0, ((cx - bw / 2) - pad_x) / scale)
        y1 = max(0.0, ((cy - bh / 2) - pad_y) / scale)
        x2 = min(float(w), ((cx + bw / 2) - pad_x) / scale)
        y2 = min(float(h), ((cy + bh / 2) - pad_y) / scale)
        if x2 - x1 >= 2 and y2 - y1 >= 2:
            dets.append(Detection((x1, y1, x2, y2), score))

    kept: list[Detection] = []
    for det in sorted(dets, key=lambda d: d.conf, reverse=True):
        if all(iou(det, old) <= IOU_THRESH for old in kept):
            kept.append(det)
    return kept


def crop_bbox(
    img: np.ndarray,
    bbox: tuple[float, float, float, float],
    pad_x: int = PADDING_X,
    pad_y: int = PADDING_Y,
) -> np.ndarray:
    h, w = img.shape[:2]
    x1, y1, x2, y2 = bbox
    x1 = max(0, int(round(x1)) - pad_x)
    y1 = max(0, int(round(y1)) - pad_y)
    x2 = min(w, int(round(x2)) + pad_x)
    y2 = min(h, int(round(y2)) + pad_y)
    return img[y1:y2, x1:x2].copy()


def ensure_min_height(img: np.ndarray) -> np.ndarray:
    h, w = img.shape[:2]
    if h < REC_HEIGHT:
        scale = REC_HEIGHT / max(1, h)
        return cv2.resize(img, (max(1, int(w * scale)), REC_HEIGHT), interpolation=cv2.INTER_CUBIC)
    return img


def deskew(crop: np.ndarray) -> np.ndarray | None:
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY) if crop.ndim == 3 else crop.copy()
    _, binary = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    ys, xs = np.where(binary > 0)
    if len(xs) < 50:
        return None
    pts = np.column_stack([xs, ys]).astype(np.float32)
    angle = cv2.minAreaRect(pts)[-1]
    if angle < -45:
        angle += 90
    if abs(angle) < 2.0:
        return None
    h, w = crop.shape[:2]
    matrix = cv2.getRotationMatrix2D((w / 2, h / 2), -angle, 1.0)
    return cv2.warpAffine(crop, matrix, (w, h), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)


def variants(crop: np.ndarray) -> list[tuple[str, np.ndarray]]:
    crop = ensure_min_height(crop)
    h, w = crop.shape[:2]
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY) if crop.ndim == 3 else crop
    clahe = cv2.createCLAHE(clipLimit=3.0, tileGridSize=(4, 4)).apply(gray)
    _, otsu = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    block = max(11, (h // 4) * 2 + 1)
    adapt = cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, block, 4)

    def bgr(g: np.ndarray) -> np.ndarray:
        return cv2.cvtColor(g, cv2.COLOR_GRAY2BGR)

    out = [
        ("original", crop),
        ("clahe", bgr(clahe)),
        ("otsu", bgr(otsu)),
        ("otsu_inverted", bgr(cv2.bitwise_not(otsu))),
        ("adaptive", bgr(adapt)),
    ]
    d = deskew(crop)
    if d is not None:
        gd = cv2.cvtColor(d, cv2.COLOR_BGR2GRAY)
        _, od = cv2.threshold(gd, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        out += [("deskew", d), ("deskew_otsu", bgr(od))]
    if h < 96:
        up = cv2.resize(crop, (w * 2, h * 2), interpolation=cv2.INTER_CUBIC)
        gu = cv2.cvtColor(up, cv2.COLOR_BGR2GRAY)
        _, ou = cv2.threshold(gu, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        out += [("upscale2x", up), ("upscale2x_otsu", bgr(ou))]
    return out


def rec_input(img: np.ndarray) -> np.ndarray:
    h, w = img.shape[:2]
    new_w = max(1, int(math.ceil(w * REC_HEIGHT / max(1, h))))
    resized = cv2.resize(img, (new_w, REC_HEIGHT), interpolation=cv2.INTER_CUBIC)
    rgb = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
    rgb = (rgb - 0.5) / 0.5
    return np.transpose(rgb, (2, 0, 1))[None]


def ctc_decode(output: np.ndarray, keys: list[str]) -> str:
    output = np.asarray(output)
    if output.ndim == 3:
        output = output[0]
    if output.shape[0] == len(keys) + 1:
        output = output.T
    indexes = output.argmax(axis=1)
    chars: list[str] = []
    prev = -1
    for idx in indexes:
        idx = int(idx)
        if idx != 0 and idx != prev:
            dict_idx = idx - 1
            if 0 <= dict_idx < len(keys):
                chars.append(keys[dict_idx])
        prev = idx
    return "".join(chars)


def recognize_crop(session: ort.InferenceSession, keys: list[str], crop: np.ndarray) -> dict:
    best = {"text": "", "date": None, "variant": None, "crop_b64": img_to_b64(crop, max_width=360)}
    for name, variant in variants(crop):
        output = session.run(None, {session.get_inputs()[0].name: rec_input(variant)})[0]
        text = ctc_decode(output, keys)
        date = extract_date(text)
        if text and not best["text"]:
            best.update({"text": text, "date": date, "variant": name})
        if date:
            return {"text": text, "date": date, "variant": name, "crop_b64": best["crop_b64"]}
    return best


def annotate_image(img: np.ndarray, gt_items: list[dict], preds: list[dict]) -> np.ndarray:
    out = img.copy()
    for item in gt_items:
        pts = np.array(item["points"], dtype=np.int32).reshape((-1, 1, 2))
        cv2.polylines(out, [pts], True, (40, 210, 40), 3)
    for idx, pred in enumerate(preds, start=1):
        x1, y1, x2, y2 = [int(round(v)) for v in pred["bbox"]]
        cv2.rectangle(out, (x1, y1), (x2, y2), (40, 80, 230), 3)
        cv2.putText(out, f"P{idx}", (x1, max(20, y1 - 6)), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (40, 80, 230), 2)
    return out


def escape(value: object) -> str:
    return html.escape(str(value), quote=True)


def build_html(records: list[dict], stats: dict) -> str:
    records = sorted(records, key=lambda r: (r["matched"], r["image"]))
    cards: list[str] = []
    for rec in records:
        status = "pass" if rec["matched"] else "fail"
        pred_rows = []
        for idx, pred in enumerate(rec["preds"], start=1):
            pred_rows.append(
                f"""
                <div class="pred">
                  <div><b>P{idx}</b> conf={pred['conf']:.3f} variant={escape(pred.get('variant'))}</div>
                  <div>bbox={escape(pred.get('bbox'))}</div>
                  <div>REC: <code>{escape(pred.get('text') or '')}</code></div>
                  <div>REGEX: <code>{escape(pred.get('date') or 'None')}</code></div>
                  {f'<img class="crop" src="data:image/jpeg;base64,{pred["crop_b64"]}">' if pred.get("crop_b64") else ''}
                </div>
                """
            )
        cards.append(
            f"""
            <section class="card {status}">
              <div class="head">
                <span class="badge">{'PASS' if rec['matched'] else 'FAIL'}</span>
                <span class="name">{escape(rec['image'])}</span>
              </div>
              <img class="main" src="data:image/jpeg;base64,{rec['annotated_b64']}">
              <table>
                <tr><th>GT</th><td>{escape(', '.join(rec['gt_dates']))}</td></tr>
                <tr><th>GT boxes</th><td>{escape(rec['gt_box_count'])}</td></tr>
                <tr><th>Pred boxes</th><td>{escape(len(rec['preds']))}</td></tr>
                <tr><th>Best IoU</th><td>{escape(', '.join(f'{v:.3f}' for v in rec.get('gt_best_ious', [])))}</td></tr>
              </table>
              {''.join(pred_rows) if pred_rows else '<div class="pred">No predictions</div>'}
            </section>
            """
        )

    return f"""<!doctype html>
<html lang="zh-Hant">
<head>
<meta charset="utf-8">
<title>Mobile ONNX OCR Report</title>
<style>
body{{font-family:Arial,'Microsoft JhengHei',sans-serif;margin:0;background:#f4f6f8;color:#1f2933}}
.wrap{{padding:20px}}
h1{{font-size:24px;margin:0 0 12px}}
.stats{{display:flex;gap:12px;flex-wrap:wrap;margin-bottom:16px}}
.stat{{background:white;border-radius:8px;padding:12px 16px;box-shadow:0 1px 4px #0001}}
.stat b{{font-size:22px}}
.grid{{display:grid;grid-template-columns:repeat(auto-fill,minmax(420px,1fr));gap:16px}}
.card{{background:white;border-radius:8px;padding:12px;box-shadow:0 1px 6px #0002;border-left:6px solid #22a06b}}
.card.fail{{border-left-color:#d14343}}
.head{{display:flex;gap:8px;align-items:center;margin-bottom:8px}}
.badge{{font-weight:bold;font-size:12px;background:#e7f8ef;color:#137044;border-radius:999px;padding:3px 8px}}
.fail .badge{{background:#ffe7e7;color:#b42318}}
.name{{font-size:12px;word-break:break-all;color:#52606d}}
.main{{width:100%;display:block;border-radius:6px;border:1px solid #d9e2ec}}
table{{width:100%;border-collapse:collapse;margin:8px 0;font-size:13px}}
th{{width:86px;text-align:left;color:#52606d;vertical-align:top}}
td,th{{border-top:1px solid #edf2f7;padding:5px}}
.pred{{background:#f8fafc;border:1px solid #e4e7eb;border-radius:6px;padding:8px;margin-top:8px;font-size:13px}}
code{{background:#111827;color:#f9fafb;padding:1px 4px;border-radius:4px;word-break:break-all}}
.crop{{display:block;max-width:100%;margin-top:6px;border-radius:4px;border:1px solid #d9e2ec;background:#fff}}
</style>
</head>
<body><div class="wrap">
<h1>Mobile ONNX OCR Report</h1>
<div class="stats">
  <div class="stat"><div>Total</div><b>{stats['total']}</b></div>
  <div class="stat"><div>Crop padding</div><b>x={stats['pad_x']} y={stats['pad_y']}</b></div>
  <div class="stat"><div>Images with pred</div><b>{stats['det_recall']:.1%}</b></div>
  <div class="stat"><div>GT box IoU@0.50</div><b>{stats['iou50']:.1%}</b></div>
  <div class="stat"><div>GT box IoU@0.75</div><b>{stats['iou75']:.1%}</b></div>
  <div class="stat"><div>Mean best IoU</div><b>{stats['mean_iou']:.3f}</b></div>
  <div class="stat"><div>E2E accuracy</div><b>{stats['e2e']:.1%}</b></div>
  <div class="stat"><div>Pass / Fail</div><b>{stats['pass_n']} / {stats['fail_n']}</b></div>
</div>
<p>Green polygons are GT boxes. Orange/red rectangles are YOLO predicted boxes.</p>
<div class="grid">{''.join(cards)}</div>
</div></body></html>"""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--split", default="val")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--output", type=Path, default=Path("outputs/report.html"))
    parser.add_argument("--jsonl", type=Path, default=Path("outputs/mobile_onnx_report.jsonl"))
    parser.add_argument("--pad-x", type=int, default=PADDING_X)
    parser.add_argument("--pad-y", type=int, default=PADDING_Y)
    args = parser.parse_args()

    model_dir = Path("app/src/main/assets/models")
    yolo = ort.InferenceSession(str(model_dir / "yolo_date.onnx"), providers=["CPUExecutionProvider"])
    rec = ort.InferenceSession(str(model_dir / "rec_finetuned.onnx"), providers=["CPUExecutionProvider"])
    keys = [line.rstrip("\r\n") for line in (model_dir / "ppocr_keys_v1.txt").read_text(encoding="utf-8").splitlines()]

    label_path = args.dataset / f"{args.split}_label.txt"
    image_dir = args.dataset / args.split
    lines = [line.strip() for line in label_path.read_text(encoding="utf-8").splitlines() if line.strip()]

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.jsonl.parent.mkdir(parents=True, exist_ok=True)
    records: list[dict] = []
    total = det_hit = pass_n = 0
    gt_box_total = gt_box_iou50 = gt_box_iou75 = 0
    best_iou_sum = 0.0

    with args.jsonl.open("w", encoding="utf-8") as writer:
        for line in lines:
            tab = line.index("\t")
            image_name = line[:tab]
            annotations = json.loads(line[tab + 1 :])
            gt_items = [item for item in annotations if item.get("transcription") != "###" and is_date_text(item.get("transcription"))]
            gt_dates = [extract_date(item.get("transcription")) or item.get("transcription", "") for item in gt_items]
            if not gt_dates:
                continue
            if args.limit and total >= args.limit:
                break

            img = cv2.imread(str(image_dir / image_name))
            if img is None:
                continue

            total += 1
            detections = yolo_detect(yolo, img)
            det_hit += int(bool(detections))
            gt_best_ious = [best_iou_for_gt(item, detections) for item in gt_items]
            gt_box_total += len(gt_best_ious)
            gt_box_iou50 += sum(1 for value in gt_best_ious if value >= 0.50)
            gt_box_iou75 += sum(1 for value in gt_best_ious if value >= 0.75)
            best_iou_sum += sum(gt_best_ious)
            preds: list[dict] = []
            for det in detections:
                crop = crop_bbox(img, det.bbox, pad_x=args.pad_x, pad_y=args.pad_y)
                rec_result = recognize_crop(rec, keys, crop)
                preds.append(
                    {
                        "bbox": [round(v, 2) for v in det.bbox],
                        "conf": round(det.conf, 4),
                        **rec_result,
                    }
                )

            gt_digits = [digits_only(item) for item in gt_dates]
            matched = any(digits_only(pred.get("date")) in gt_digits for pred in preds if pred.get("date"))
            pass_n += int(matched)
            annotated = annotate_image(img, gt_items, preds)
            record = {
                "image": image_name,
                "matched": matched,
                "gt_dates": gt_dates,
                "gt_box_count": len(gt_items),
                "gt_best_ious": [round(value, 4) for value in gt_best_ious],
                "preds": preds,
                "annotated_b64": img_to_b64(annotated),
            }
            records.append(record)
            writer.write(json.dumps({k: v for k, v in record.items() if k != "annotated_b64"}, ensure_ascii=False) + "\n")
            if total % 50 == 0:
                print(f"{total}: det={det_hit/total:.1%} e2e={pass_n/total:.1%}")

    stats = {
        "total": total,
        "det_recall": det_hit / total if total else 0.0,
        "iou50": gt_box_iou50 / gt_box_total if gt_box_total else 0.0,
        "iou75": gt_box_iou75 / gt_box_total if gt_box_total else 0.0,
        "mean_iou": best_iou_sum / gt_box_total if gt_box_total else 0.0,
        "e2e": pass_n / total if total else 0.0,
        "pass_n": pass_n,
        "fail_n": total - pass_n,
        "pad_x": args.pad_x,
        "pad_y": args.pad_y,
    }
    args.output.write_text(build_html(records, stats), encoding="utf-8")
    print(f"HTML report: {args.output.resolve()}")
    print(f"JSONL report: {args.jsonl.resolve()}")
    print(f"images={total} det={stats['det_recall']:.1%} e2e={stats['e2e']:.1%}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

