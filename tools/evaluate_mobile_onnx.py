r"""Compatibility entrypoint for the mobile ONNX validation pipeline.

This delegates to mobile_onnx_html_report.py so evaluation, HTML reporting, and
Android crop settings stay aligned.

Usage:
  python tools/evaluate_mobile_onnx.py --dataset C:\Users\insta\Desktop\dataset --split val
"""
from __future__ import annotations

from mobile_onnx_html_report import main


if __name__ == "__main__":
    raise SystemExit(main())
