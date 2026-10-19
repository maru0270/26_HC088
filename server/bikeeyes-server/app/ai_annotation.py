from __future__ import annotations

from io import BytesIO
from pathlib import Path
from typing import Iterable

from PIL import Image, ImageDraw, ImageFont


CLASS_COLORS: dict[str, tuple[int, int, int]] = {
    "linear": (255, 214, 0),
    "alligator": (255, 0, 180),
    "pothole": (239, 68, 68),
    "flood_area": (14, 165, 233),
    "manhole": (59, 130, 246),
    "bollard": (249, 115, 22),
}
DEFAULT_COLOR = (34, 197, 94)


def _color(class_name: str) -> tuple[int, int, int]:
    return CLASS_COLORS.get(class_name.strip().lower(), DEFAULT_COLOR)


def _font(size: int) -> ImageFont.ImageFont:
    candidates = (
        Path(r"C:\Windows\Fonts\malgunbd.ttf"),
        Path(r"C:\Windows\Fonts\arialbd.ttf"),
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"),
    )
    for candidate in candidates:
        if candidate.is_file():
            return ImageFont.truetype(str(candidate), size=size)
    return ImageFont.load_default()


def save_shape_annotation(
    source: Path,
    destination: Path,
    detections: Iterable[dict],
) -> None:
    """Persist one image containing only boxes/masks; no role-specific text."""
    with Image.open(source) as opened:
        image = opened.convert("RGB")
    image = _draw_detection_shapes(image, detections)
    destination.parent.mkdir(parents=True, exist_ok=True)
    image.save(destination, format="JPEG", quality=92, optimize=True)


def _draw_detection_shapes(image: Image.Image, detections: Iterable[dict]) -> Image.Image:
    """Draw detection boxes and masks onto a copy of the supplied image."""
    image = image.copy().convert("RGB")
    overlay = Image.new("RGBA", image.size, (0, 0, 0, 0))
    overlay_draw = ImageDraw.Draw(overlay)
    draw = ImageDraw.Draw(image)
    line_width = max(3, round(min(image.size) / 240))
    for detection in detections:
        color = _color(str(detection["class_name"]))
        polygon = detection.get("polygon") or []
        if len(polygon) >= 3:
            points = [(float(point[0]), float(point[1])) for point in polygon]
            overlay_draw.polygon(points, fill=(*color, 55), outline=(*color, 230), width=line_width)
        bbox = detection.get("bbox_xyxy") or []
        if len(bbox) == 4:
            draw.rectangle(tuple(float(value) for value in bbox), outline=color, width=line_width)
    return Image.alpha_composite(image.convert("RGBA"), overlay).convert("RGB")


def _draw_role_labels(
    image: Image.Image,
    detections: Iterable[dict],
    *,
    show_confidence: bool,
) -> Image.Image:
    image = image.copy().convert("RGB")
    draw = ImageDraw.Draw(image)
    font = _font(max(18, round(min(image.size) / 35)))
    padding = max(4, round(min(image.size) / 220))
    for detection in detections:
        bbox = detection.get("bbox_xyxy") or []
        if len(bbox) != 4:
            continue
        class_name = str(detection["class_name"])
        label = class_name
        if show_confidence:
            label += f" {float(detection['confidence']):.2f}"
        x = max(0, int(float(bbox[0])))
        y = max(0, int(float(bbox[1])))
        text_box = draw.textbbox((x, y), label, font=font, stroke_width=1)
        text_height = text_box[3] - text_box[1]
        y = max(0, y - text_height - padding * 2)
        text_box = draw.textbbox((x, y), label, font=font, stroke_width=1)
        draw.rectangle(
            (text_box[0] - padding, text_box[1] - padding,
             text_box[2] + padding, text_box[3] + padding),
            fill=_color(class_name),
        )
        draw.text((x, y), label, fill=(0, 0, 0), font=font, stroke_width=1)
    return image


def render_role_labels(
    annotated_source: Path,
    detections: Iterable[dict],
    *,
    show_confidence: bool,
) -> bytes:
    """Add labels in memory. Confidence is never written to the stored image."""
    with Image.open(annotated_source) as opened:
        image = opened.convert("RGB")
    image = _draw_role_labels(image, detections, show_confidence=show_confidence)
    output = BytesIO()
    image.save(output, format="JPEG", quality=92, optimize=True)
    return output.getvalue()


def render_detection_image(
    source: Path,
    detections: Iterable[dict],
    *,
    show_confidence: bool,
) -> bytes:
    """Render eligible shapes and role-specific labels from the untouched source."""
    detection_list = list(detections)
    with Image.open(source) as opened:
        image = opened.convert("RGB")
    image = _draw_detection_shapes(image, detection_list)
    image = _draw_role_labels(image, detection_list, show_confidence=show_confidence)
    output = BytesIO()
    image.save(output, format="JPEG", quality=92, optimize=True)
    return output.getvalue()
