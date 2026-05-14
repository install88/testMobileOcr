"""Export a yoloOcr Python golden JSONL report for Android parity testing.

Usage:
  python tools/export_yoloocr_golden.py --yoloocr-root C:\path\to\yoloOcr --split val

The output records the exact Python pipeline decisions Android should match:
YOLO boxes, padded boxes, preprocessing variant, raw rec text, extracted date,
and GT dates from the PaddleOCR det label file.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def _install_yoloocr_imports(root: Path) -> None:
    sys.path.insert(0, str(root))
    sys.path.insert(0, str(root / "scripts"))


def _digits_only(text: str) -> str:
    return "".join(ch for ch in text if ch.isdigit())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--yoloocr-root", required=True, type=Path)
    parser.add_argument("--split", default="val")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--crop-dir", type=Path)
    args = parser.parse_args()

    root = args.yoloocr_root.resolve()
    _install_yoloocr_imports(root)

    import cv2  # pylint: disable=import-error,import-outside-toplevel
    from ultralytics import YOLO  # pylint: disable=import-error,import-outside-toplevel
    from rapidocr_onnxruntime import RapidOCR  # pylint: disable=import-error,import-outside-toplevel
    from config import DATASET_DIR, MODELS_DIR, REC_MODEL, YOLO_MODEL  # pylint: disable=import-error,import-outside-toplevel
    from date_patterns import extract_date, is_date_text  # pylint: disable=import-error,import-outside-toplevel
    import pipeline as py_pipeline  # pylint: disable=import-error,import-outside-toplevel

    output = args.output or (root / "results" / f"golden_{args.split}.jsonl")
    crop_dir = args.crop_dir or (root / "results" / f"golden_{args.split}_crops")
    output.parent.mkdir(parents=True, exist_ok=True)
    crop_dir.mkdir(parents=True, exist_ok=True)

    yolo = YOLO(str(YOLO_MODEL))
    keys_path = MODELS_DIR / "ppocr_keys_v1.txt"
    ocr = RapidOCR(
        rec_model_path=str(REC_MODEL),
        rec_keys_path=str(keys_path) if keys_path.exists() else None,
    )

    label_path = DATASET_DIR / f"{args.split}_label.txt"
    image_dir = DATASET_DIR / args.split
    lines = [line.strip() for line in label_path.read_text(encoding="utf-8").splitlines() if line.strip()]

    total = matched = 0
    with output.open("w", encoding="utf-8") as writer:
        for line in lines:
            tab = line.index("\t")
            image_name = line[:tab]
            annotations = json.loads(line[tab + 1 :])
            gt_dates = [
                extract_date(ann.get("transcription", "")) or ann.get("transcription", "")
                for ann in annotations
                if ann.get("transcription", "") != "###" and is_date_text(ann.get("transcription", ""))
            ]
            if not gt_dates:
                continue

            image_path = image_dir / image_name
            image = cv2.imread(str(image_path))
            if image is None:
                continue

            total += 1
            det_results = yolo(image, conf=py_pipeline.CONF_THRESH, verbose=False)[0]
            detections = []

            for index, box in enumerate(det_results.boxes):
                x1, y1, x2, y2 = map(int, box.xyxy[0].tolist())
                conf = float(box.conf[0])
                crop = py_pipeline.crop_bbox(image, x1, y1, x2, y2)
                crop_name = f"{Path(image_name).stem}_box{index:02d}.jpg"
                cv2.imwrite(str(crop_dir / crop_name), crop)

                best_text = ""
                best_variant = None
                date = None
                crop_for_rec = py_pipeline._ensure_min_height(crop)  # noqa: SLF001
                for variant_index, variant in enumerate(py_pipeline._preprocess_variants(crop_for_rec)):  # noqa: SLF001
                    result, _ = ocr(variant, use_det=False, use_cls=False, use_rec=True)
                    text = ""
                    if result:
                        text = " ".join(item[0] for item in result if item and isinstance(item[0], str))
                    found = extract_date(text)
                    if text and not best_text:
                        best_text = text
                        best_variant = variant_index
                    if found:
                        best_text = text
                        best_variant = variant_index
                        date = found
                        break

                detections.append(
                    {
                        "bbox": [x1, y1, x2, y2],
                        "conf": round(conf, 6),
                        "crop": str((crop_dir / crop_name).resolve()),
                        "variant_index": best_variant,
                        "rec": best_text,
                        "date": date,
                    },
                )

            pred_digits = [_digits_only(item["date"] or "") for item in detections]
            gt_digits = [_digits_only(item) for item in gt_dates]
            is_matched = any(pred and pred in gt_digits for pred in pred_digits)
            matched += int(is_matched)
            writer.write(
                json.dumps(
                    {
                        "image": image_name,
                        "matched": is_matched,
                        "gt_dates": gt_dates,
                        "detections": detections,
                    },
                    ensure_ascii=False,
                )
                + "\n",
            )

    print(f"wrote {output}")
    print(f"crops {crop_dir}")
    print(f"accuracy {matched}/{total} = {matched / total if total else 0:.3%}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
