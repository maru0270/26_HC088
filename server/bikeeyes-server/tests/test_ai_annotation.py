from pathlib import Path

from PIL import Image

from app.ai_annotation import render_role_labels, save_shape_annotation


def test_one_stored_annotation_supports_user_and_admin_labels(tmp_path: Path):
    source = tmp_path / "source.jpg"
    annotated = tmp_path / "annotated.jpg"
    Image.new("RGB", (320, 240), "white").save(source)
    detections = [{
        "class_name": "pothole",
        "confidence": 0.876,
        "bbox_xyxy": [40, 50, 180, 170],
        "polygon": None,
    }]

    save_shape_annotation(source, annotated, detections)
    stored_bytes = annotated.read_bytes()
    user_bytes = render_role_labels(annotated, detections, show_confidence=False)
    admin_bytes = render_role_labels(annotated, detections, show_confidence=True)

    assert annotated.is_file()
    assert stored_bytes != user_bytes
    assert user_bytes != admin_bytes
    assert len(user_bytes) > 0
    assert len(admin_bytes) > 0
