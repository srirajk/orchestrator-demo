#!/usr/bin/env python3
"""Assemble the Conduit white paper with approved editorial figure plates."""

from __future__ import annotations

import io
from pathlib import Path

from PIL import Image
from pypdf import PdfReader, PdfWriter
from reportlab.lib.colors import HexColor
from reportlab.lib.pagesizes import landscape, letter
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen import canvas


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "output/pdf/conduit-enterprise-orchestration-white-paper.pdf"
BASE = ROOT / "output/pdf/.conduit-enterprise-orchestration-white-paper-body.pdf"
ASSETS = ROOT / "docs/assets"

NAVY = HexColor("#06111F")
OFF_WHITE = HexColor("#F5F7FA")
EVIDENCE_BLUE = HexColor("#85AFD3")
GOLD = HexColor("#E8BF4F")
MUTED = HexColor("#A8B5C3")


def fit_image(c: canvas.Canvas, path: Path, box: tuple[float, float, float, float]) -> None:
    x, y, width, height = box
    with Image.open(path) as image:
        source_width, source_height = image.size
    scale = min(width / source_width, height / source_height)
    draw_width = source_width * scale
    draw_height = source_height * scale
    c.drawImage(
        ImageReader(str(path)),
        x + (width - draw_width) / 2,
        y + (height - draw_height) / 2,
        draw_width,
        draw_height,
        preserveAspectRatio=True,
        mask="auto",
    )


def cover_page() -> bytes:
    buffer = io.BytesIO()
    width, height = letter
    c = canvas.Canvas(buffer, pagesize=letter)
    c.setFillColor(NAVY)
    c.rect(0, 0, width, height, fill=1, stroke=0)

    c.setFillColor(EVIDENCE_BLUE)
    c.setFont("Helvetica-Bold", 7.5)
    c.drawString(42, 758, "CONDUIT WHITE PAPER")

    c.setFillColor(OFF_WHITE)
    c.setFont("Helvetica-Bold", 24)
    c.drawString(42, 721, "Conduit: From Agent Sprawl")
    c.drawString(42, 691, "to Governed Enterprise Intelligence")

    c.setFillColor(MUTED)
    c.setFont("Helvetica", 10)
    c.drawString(42, 668, "A governed architecture for independently owned domain intelligence")

    fit_image(
        c,
        ASSETS / "conduit-banking-relationship-flow.jpg",
        (72, 78, width - 144, 565),
    )

    c.setFillColor(GOLD)
    c.setFont("Helvetica-Bold", 7.5)
    c.drawString(42, 39, "VERSION 1.5")
    c.setFillColor(MUTED)
    c.setFont("Helvetica", 7.5)
    c.drawRightString(width - 42, 39, "JULY 2026")
    c.save()
    return buffer.getvalue()


def figure_plate(image: Path, caption: str, figure_number: int) -> bytes:
    buffer = io.BytesIO()
    width, height = landscape(letter)
    c = canvas.Canvas(buffer, pagesize=(width, height))
    c.setFillColor(NAVY)
    c.rect(0, 0, width, height, fill=1, stroke=0)
    fit_image(c, image, (12, 76, width - 24, height - 88))

    c.setFillColor(GOLD)
    c.setFont("Helvetica-Bold", 8)
    c.drawString(25, 44, f"FIGURE {figure_number}")
    c.setFillColor(OFF_WHITE)
    c.setFont("Helvetica", 8.5)
    c.drawString(82, 44, caption)
    c.setFillColor(MUTED)
    c.setFont("Helvetica", 7)
    c.drawRightString(width - 25, 22, "CONDUIT | GOVERNED ENTERPRISE INTELLIGENCE")
    c.save()
    return buffer.getvalue()


def main() -> None:
    if not BASE.exists():
        raise SystemExit(f"Base PDF not found: {BASE}")

    base = PdfReader(str(BASE))
    cover = PdfReader(io.BytesIO(cover_page())).pages[0]
    federation = PdfReader(
        io.BytesIO(
            figure_plate(
                ASSETS / "conduit-agent-sprawl-to-federation.jpg",
                "Governed federation preserves domain ownership while removing fragmented entry points.",
                2,
            )
        )
    ).pages[0]
    experience = PdfReader(
        io.BytesIO(
            figure_plate(
                ASSETS / "conduit-composable-experience-canvas.png",
                "Future vision: governed composition becomes a presentation-independent enterprise experience.",
                3,
            )
        )
    ).pages[0]

    writer = PdfWriter()
    writer.add_page(cover)

    # Preserve the existing body. Figure plates are intentionally unnumbered,
    # so the body's established logical page numbering remains stable.
    for original_index, page in enumerate(base.pages[1:], start=1):
        writer.add_page(page)
        if original_index == 3:  # after the market-gap opening and local-fragmentation argument
            writer.add_page(federation)
        if original_index == 12:  # after read/action separation, before declared execution contracts
            writer.add_page(experience)

    writer.add_metadata(
        {
            "/Title": "Conduit: From Agent Sprawl to Governed Enterprise Intelligence",
            "/Author": "Conduit",
            "/Subject": "Governed enterprise orchestration",
        }
    )

    temporary = OUTPUT.with_suffix(".tmp.pdf")
    with temporary.open("wb") as stream:
        writer.write(stream)
    temporary.replace(OUTPUT)


if __name__ == "__main__":
    main()
