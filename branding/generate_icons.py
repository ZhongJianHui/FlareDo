#!/usr/bin/env python3
"""Generate every raster application icon from the checked-in SVG master."""

from __future__ import annotations

import io
import subprocess
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
BRANDING = ROOT / "branding"
MASTER_SVG = BRANDING / "flaredo-mark.svg"
FOREGROUND_SVG = BRANDING / "flaredo-foreground.svg"
MASTER_PNG = BRANDING / "flaredo-mark-1024.png"


def render_svg(source: Path, width: int, height: int) -> Image.Image:
    data = subprocess.check_output(
        ["rsvg-convert", "-w", str(width), "-h", str(height), str(source)],
    )
    return Image.open(io.BytesIO(data)).convert("RGBA")


def contain_icon(width: int, height: int, scale: float = 1.0) -> Image.Image:
    canvas = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    side = max(1, int(min(width, height) * scale))
    icon = render_svg(MASTER_SVG, side, side)
    canvas.alpha_composite(icon, ((width - side) // 2, (height - side) // 2))
    return canvas


def save_existing_pngs(directory: Path) -> None:
    for target in directory.rglob("*.png"):
        with Image.open(target) as existing:
            width, height = existing.size
        scale = 0.72 if width != height else 1.0
        contain_icon(width, height, scale).save(target, format="PNG", optimize=True)


def generate_android() -> None:
    density_sizes = {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192,
    }
    for density, size in density_sizes.items():
        image = contain_icon(size, size)
        directory = ROOT / "app" / "src" / "main" / "res" / f"mipmap-{density}"
        image.save(directory / "ic_launcher.webp", format="WEBP", lossless=True)
        image.save(directory / "ic_launcher_round.webp", format="WEBP", lossless=True)
    contain_icon(512, 512).save(ROOT / "app" / "src" / "main" / "ic_launcher-playstore.png")


def generate_desktop() -> None:
    resources = ROOT / "desktopApp" / "resources"
    icon = contain_icon(1024, 1024)
    icon.resize((512, 512), Image.Resampling.LANCZOS).save(resources / "ic_launcher.png")
    icon.save(
        resources / "ic_launcher.ico",
        format="ICO",
        sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (128, 128), (256, 256)],
    )
    icon.save(resources / "ic_launcher.icns", format="ICNS")
    save_existing_pngs(resources / "appx")


def generate_apple() -> None:
    save_existing_pngs(ROOT / "appleApp" / "ios" / "Assets.xcassets")
    save_existing_pngs(ROOT / "appleApp" / "ios" / "SafariOpenInFlare")
    for icon_dir in list((ROOT / "appleApp" / "ios").glob("AppIcon*.icon")) + [
        ROOT / "appleApp" / "macos" / "AppIcon.icon",
    ]:
        assets = icon_dir / "Assets"
        if not assets.exists():
            continue
        for target in assets.glob("*.svg"):
            target.write_text(FOREGROUND_SVG.read_text(encoding="utf-8"), encoding="utf-8")


def main() -> None:
    MASTER_PNG.write_bytes(subprocess.check_output(["rsvg-convert", str(MASTER_SVG)]))
    generate_android()
    generate_desktop()
    generate_apple()
    contain_icon(512, 512).save(ROOT / "metadata" / "en-US" / "images" / "icon.png")


if __name__ == "__main__":
    main()
