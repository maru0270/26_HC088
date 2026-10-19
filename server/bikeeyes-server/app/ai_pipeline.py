from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from threading import Lock
from time import perf_counter
from typing import Protocol
import hashlib
import os
import sys
import types


@dataclass(frozen=True)
class ModelConfig:
    stage: str
    model_path: Path
    model_version: str
    confidence: float
    iou: float


@dataclass(frozen=True)
class AiPipelineConfig:
    enabled: bool
    bbox: ModelConfig
    segmentation: ModelConfig
    device: str
    image_size: int
    headless: bool = True

    @classmethod
    def from_env(cls) -> "AiPipelineConfig":
        return cls(
            enabled=os.getenv("BIKE_AI_ENABLED", "false").lower() == "true",
            bbox=ModelConfig(
                stage="BBOX",
                model_path=Path(os.getenv("BIKE_AI_BBOX_MODEL_PATH", "models/bbox/best.pt")),
                model_version=os.getenv("BIKE_AI_BBOX_MODEL_VERSION", "bbox-unconfigured"),
                confidence=float(os.getenv("BIKE_AI_BBOX_CONFIDENCE", "0.25")),
                iou=float(os.getenv("BIKE_AI_BBOX_IOU", "0.70")),
            ),
            segmentation=ModelConfig(
                stage="SEGMENTATION",
                model_path=Path(os.getenv("BIKE_AI_SEG_MODEL_PATH", "models/segmentation/best.pt")),
                model_version=os.getenv("BIKE_AI_SEG_MODEL_VERSION", "seg-unconfigured"),
                confidence=float(os.getenv("BIKE_AI_SEG_CONFIDENCE", "0.25")),
                iou=float(os.getenv("BIKE_AI_SEG_IOU", "0.70")),
            ),
            device=os.getenv("BIKE_AI_DEVICE", "cpu"),
            image_size=int(os.getenv("BIKE_AI_IMAGE_SIZE", "640")),
            headless=os.getenv("BIKE_AI_HEADLESS", "true").lower() == "true",
        )

    def readiness_errors(self) -> list[str]:
        errors = []
        if not self.bbox.model_path.is_file():
            errors.append(f"BBOX model not found: {self.bbox.model_path}")
        if not self.segmentation.model_path.is_file():
            errors.append(f"Segmentation model not found: {self.segmentation.model_path}")
        if not 0 <= self.bbox.confidence <= 1 or not 0 <= self.segmentation.confidence <= 1:
            errors.append("AI confidence thresholds must be between 0 and 1")
        if not 0 <= self.bbox.iou <= 1 or not 0 <= self.segmentation.iou <= 1:
            errors.append("AI IoU thresholds must be between 0 and 1")
        return errors

    def snapshot(self) -> dict:
        return {
            "execution_order": ["BBOX", "SEGMENTATION"],
            "same_original_image": True,
            "device": self.device,
            "image_size": self.image_size,
            "headless": self.headless,
            "bbox": {
                "model_path": str(self.bbox.model_path),
                "model_version": self.bbox.model_version,
                "confidence": self.bbox.confidence,
                "iou": self.bbox.iou,
            },
            "segmentation": {
                "model_path": str(self.segmentation.model_path),
                "model_version": self.segmentation.model_version,
                "confidence": self.segmentation.confidence,
                "iou": self.segmentation.iou,
            },
        }


@dataclass(frozen=True)
class Detection:
    stage: str
    model_version: str
    class_name: str
    class_id: int
    confidence: float
    bbox_xyxy: list[float]
    polygon: list[list[float]] | None = None
    raw: dict = field(default_factory=dict)


@dataclass(frozen=True)
class StageResult:
    stage: str
    model_version: str
    duration_ms: int
    detections: list[Detection]
    error: str | None = None


@dataclass(frozen=True)
class PipelineResult:
    status: str
    image_sha256: str
    source_width: int | None
    source_height: int | None
    config_snapshot: dict
    total_duration_ms: int
    stages: list[StageResult]


class ModelRunner(Protocol):
    def predict(self, image_path: Path) -> list[Detection]: ...


def _prepare_headless_ultralytics_import() -> None:
    """Skip Matplotlib for inference-only servers where WDAC blocks its unsigned DLLs.

    Ultralytics 8.4 imports its semantic-training module eagerly, and that module imports
    pyplot even though YOLO.predict() does not use it. The lightweight modules below are
    intentionally limited to the AI loader and are installed only when explicitly enabled.
    Restarting with BIKE_AI_HEADLESS=false restores the regular Ultralytics import path.
    """
    runtime_root = Path(
        os.getenv(
            "BIKE_AI_RUNTIME_DIR",
            str(Path(__file__).resolve().parents[1] / ".ai-runtime"),
        )
    ).resolve()
    runtime_root.mkdir(parents=True, exist_ok=True)
    os.environ.setdefault("YOLO_CONFIG_DIR", str(runtime_root))
    if "matplotlib" in sys.modules:
        return
    matplotlib = types.ModuleType("matplotlib")
    matplotlib.__path__ = []  # type: ignore[attr-defined]
    pyplot = types.ModuleType("matplotlib.pyplot")
    matplotlib.pyplot = pyplot  # type: ignore[attr-defined]
    sys.modules["matplotlib"] = matplotlib
    sys.modules["matplotlib.pyplot"] = pyplot


class UltralyticsModelRunner:
    """Lazy YOLO runner. Ultralytics is imported only when AI is enabled."""

    def __init__(self, config: ModelConfig, device: str, image_size: int, headless: bool = True):
        if headless:
            _prepare_headless_ultralytics_import()
        from ultralytics import YOLO

        self.config = config
        self.device = device
        self.image_size = image_size
        self.model = YOLO(str(config.model_path))

    def predict(self, image_path: Path) -> list[Detection]:
        predictions = self.model.predict(
            source=str(image_path),
            conf=self.config.confidence,
            iou=self.config.iou,
            imgsz=self.image_size,
            device=self.device,
            verbose=False,
        )
        detections: list[Detection] = []
        for result in predictions:
            if result.boxes is None:
                continue
            polygons = result.masks.xy if result.masks is not None else []
            for index, box in enumerate(result.boxes):
                class_id = int(box.cls.item())
                polygon = None
                if index < len(polygons):
                    polygon = [[float(x), float(y)] for x, y in polygons[index].tolist()]
                detections.append(
                    Detection(
                        stage=self.config.stage,
                        model_version=self.config.model_version,
                        class_name=str(result.names[class_id]),
                        class_id=class_id,
                        confidence=float(box.conf.item()),
                        bbox_xyxy=[float(value) for value in box.xyxy[0].tolist()],
                        polygon=polygon,
                    )
                )
        return detections


class SerialRoadDamagePipeline:
    """Runs BBOX first and segmentation second against the same untouched file."""

    def __init__(
        self,
        bbox_runner: ModelRunner,
        segmentation_runner: ModelRunner,
        config_snapshot: dict | None = None,
    ):
        self.stages = (
            ("BBOX", bbox_runner),
            ("SEGMENTATION", segmentation_runner),
        )
        self.config_snapshot = config_snapshot or {}

    def analyze(self, image_path: Path) -> PipelineResult:
        resolved = image_path.resolve(strict=True)
        image_hash = hashlib.sha256(resolved.read_bytes()).hexdigest()
        source_width, source_height = _jpeg_dimensions(resolved)
        pipeline_started = perf_counter()
        stage_results: list[StageResult] = []

        for stage_name, runner in self.stages:
            stage_started = perf_counter()
            try:
                detections = runner.predict(resolved)
                stage_results.append(
                    StageResult(
                        stage=stage_name,
                        model_version=_runner_version(runner),
                        duration_ms=round((perf_counter() - stage_started) * 1000),
                        detections=detections,
                    )
                )
            except Exception as exception:
                stage_results.append(
                    StageResult(
                        stage=stage_name,
                        model_version=_runner_version(runner),
                        duration_ms=round((perf_counter() - stage_started) * 1000),
                        detections=[],
                        error=f"{type(exception).__name__}: {exception}",
                    )
                )

        failed_count = sum(stage.error is not None for stage in stage_results)
        status = "COMPLETED" if failed_count == 0 else "FAILED" if failed_count == 2 else "PARTIAL"
        return PipelineResult(
            status=status,
            image_sha256=image_hash,
            source_width=source_width,
            source_height=source_height,
            config_snapshot=self.config_snapshot,
            total_duration_ms=round((perf_counter() - pipeline_started) * 1000),
            stages=stage_results,
        )


def _runner_version(runner: ModelRunner) -> str:
    config = getattr(runner, "config", None)
    return getattr(config, "model_version", runner.__class__.__name__)


def _jpeg_dimensions(image_path: Path) -> tuple[int | None, int | None]:
    """Reads JPEG dimensions without adding an image-library dependency to the base server."""
    try:
        with image_path.open("rb") as image:
            if image.read(2) != b"\xff\xd8":
                return None, None
            while True:
                marker_start = image.read(1)
                if not marker_start:
                    return None, None
                if marker_start != b"\xff":
                    continue
                marker = image.read(1)
                while marker == b"\xff":
                    marker = image.read(1)
                if marker in {b"\xd8", b"\xd9"}:
                    continue
                length_bytes = image.read(2)
                if len(length_bytes) != 2:
                    return None, None
                segment_length = int.from_bytes(length_bytes, "big")
                if marker and marker[0] in {
                    0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7,
                    0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF,
                }:
                    image.read(1)
                    height = int.from_bytes(image.read(2), "big")
                    width = int.from_bytes(image.read(2), "big")
                    return width, height
                image.seek(max(segment_length - 2, 0), 1)
    except (OSError, ValueError):
        return None, None


class AiPipelineProvider:
    def __init__(self, config: AiPipelineConfig):
        self.config = config
        self._pipeline: SerialRoadDamagePipeline | None = None
        self._lock = Lock()
        self._analysis_lock = Lock()

    def get(self) -> SerialRoadDamagePipeline:
        errors = self.config.readiness_errors()
        if errors:
            raise RuntimeError("; ".join(errors))
        with self._lock:
            if self._pipeline is None:
                self._pipeline = SerialRoadDamagePipeline(
                    UltralyticsModelRunner(
                        self.config.bbox,
                        self.config.device,
                        self.config.image_size,
                        self.config.headless,
                    ),
                    UltralyticsModelRunner(
                        self.config.segmentation,
                        self.config.device,
                        self.config.image_size,
                        self.config.headless,
                    ),
                    self.config.snapshot(),
                )
            return self._pipeline

    def analyze(self, image_path: Path) -> PipelineResult:
        # A single model instance must not be entered concurrently by API and queue workers.
        with self._analysis_lock:
            return self.get().analyze(image_path)
