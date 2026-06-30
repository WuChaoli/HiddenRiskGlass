#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

import uvicorn


ROOT = Path(__file__).resolve().parent


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run the deployable APK update server.")
    parser.add_argument("--host", default="0.0.0.0", help="Host to bind. Default: 0.0.0.0")
    parser.add_argument("--port", type=int, default=10203, help="Port to bind. Default: 10203")
    parser.add_argument("--reload", action="store_true", help="Reload the server when source files change.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    uvicorn.run(
        "app.main:app",
        app_dir=str(ROOT),
        host=args.host,
        port=args.port,
        reload=args.reload,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
