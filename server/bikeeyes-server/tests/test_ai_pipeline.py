from pathlib import Path

from app.ai_pipeline import AiPipelineConfig, Detection, ModelConfig, SerialRoadDamagePipeline


class FakeRunner:
    def __init__(self, stage: str, version: str, calls: list, should_fail: bool = False):
        self.config = ModelConfig(stage, Path("unused.pt"), version, 0.25, 0.7)
        self.calls = calls
        self.should_fail = should_fail

    def predict(self, image_path: Path):
        self.calls.append((self.config.stage, image_path, image_path.read_bytes()))
        if self.should_fail:
            raise RuntimeError(f"{self.config.stage} failed")
        return [
            Detection(
                stage=self.config.stage,
                model_version=self.config.model_version,
                class_name="test",
                class_id=0,
                confidence=0.9,
                bbox_xyxy=[1.0, 2.0, 3.0, 4.0],
                polygon=[[1.0, 2.0]] if self.config.stage == "SEGMENTATION" else None,
            )
        ]


def test_pipeline_runs_bbox_then_segmentation_on_same_original(tmp_path):
    image = tmp_path / "original.jpg"
    original = b"unchanged-original-image"
    image.write_bytes(original)
    calls = []
    pipeline = SerialRoadDamagePipeline(
        FakeRunner("BBOX", "bbox-v1", calls),
        FakeRunner("SEGMENTATION", "seg-v1", calls),
    )

    result = pipeline.analyze(image)

    assert result.status == "COMPLETED"
    assert [call[0] for call in calls] == ["BBOX", "SEGMENTATION"]
    assert calls[0][1] == calls[1][1] == image.resolve()
    assert calls[0][2] == calls[1][2] == original
    assert image.read_bytes() == original
    assert [stage.model_version for stage in result.stages] == ["bbox-v1", "seg-v1"]
    assert result.config_snapshot == {}


def test_segmentation_still_runs_when_bbox_stage_fails(tmp_path):
    image = tmp_path / "original.jpg"
    image.write_bytes(b"image")
    calls = []
    pipeline = SerialRoadDamagePipeline(
        FakeRunner("BBOX", "bbox-v1", calls, should_fail=True),
        FakeRunner("SEGMENTATION", "seg-v1", calls),
    )

    result = pipeline.analyze(image)

    assert result.status == "PARTIAL"
    assert [call[0] for call in calls] == ["BBOX", "SEGMENTATION"]
    assert result.stages[0].error == "RuntimeError: BBOX failed"
    assert len(result.stages[1].detections) == 1


def test_pipeline_reports_failed_only_when_both_stages_fail(tmp_path):
    image = tmp_path / "original.jpg"
    image.write_bytes(b"image")
    calls = []
    pipeline = SerialRoadDamagePipeline(
        FakeRunner("BBOX", "bbox-v1", calls, should_fail=True),
        FakeRunner("SEGMENTATION", "seg-v1", calls, should_fail=True),
    )

    result = pipeline.analyze(image)

    assert result.status == "FAILED"
    assert [call[0] for call in calls] == ["BBOX", "SEGMENTATION"]


def test_pipeline_records_jpeg_source_dimensions_and_config_snapshot(tmp_path):
    image = tmp_path / "original.jpg"
    # Minimal SOI + SOF0 segment containing width=32 and height=16.
    image.write_bytes(
        b"\xff\xd8" + b"\xff\xc0" + b"\x00\x0b" + b"\x08"
        + b"\x00\x10" + b"\x00\x20" + b"\x01\x01\x11\x00" + b"\xff\xd9"
    )
    calls = []
    snapshot = {"device": "cpu", "image_size": 640}
    pipeline = SerialRoadDamagePipeline(
        FakeRunner("BBOX", "bbox-v1", calls),
        FakeRunner("SEGMENTATION", "seg-v1", calls),
        snapshot,
    )

    result = pipeline.analyze(image)

    assert result.source_width == 32
    assert result.source_height == 16
    assert result.config_snapshot == snapshot


def test_ai_config_headless_can_be_rolled_back_with_environment(monkeypatch):
    monkeypatch.setenv("BIKE_AI_HEADLESS", "true")
    headless = AiPipelineConfig.from_env()
    assert headless.headless is True
    assert headless.snapshot()["headless"] is True

    monkeypatch.setenv("BIKE_AI_HEADLESS", "false")
    regular = AiPipelineConfig.from_env()
    assert regular.headless is False
    assert regular.snapshot()["headless"] is False
