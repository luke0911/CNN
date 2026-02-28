#!/usr/bin/env python3
"""
M416 모델 생성 스크립트
640x640 → 416x416로 변경하여 추론 속도 50% 향상
"""

from ultralytics import YOLO
from pathlib import Path

def export_m416():
    print("="*80)
    print("M416 모델 생성 (416×416 입력 크기)")
    print("="*80)

    # 사용 가능한 모델 목록
    models = {
        "1": {
            "name": "improved_stairs_v3 (yolov8n)",
            "path": "object_detection/runs/train/improved_stairs_v3/weights/best.pt",
            "output": "앱_모델/Yolo_416.onnx"
        },
        "2": {
            "name": "test_yolov8s",
            "path": "object_detection/runs/train/test_yolov8s/weights/best.pt",
            "output": "앱_모델/Yolo_416_s.onnx"
        }
    }

    print("\n변환할 모델 선택:")
    for key, model in models.items():
        pt_path = Path(model["path"])
        exists = "✅" if pt_path.exists() else "❌"
        size = pt_path.stat().st_size / (1024*1024) if pt_path.exists() else 0
        print(f"{key}. {exists} {model['name']} ({size:.1f}MB)")

    choice = input("\n선택 (1 or 2): ").strip()

    if choice not in models:
        print("❌ 잘못된 선택")
        return

    model_info = models[choice]
    pt_path = Path(model_info["path"])

    if not pt_path.exists():
        print(f"❌ 모델을 찾을 수 없습니다: {pt_path}")
        return

    print(f"\n📦 모델 로드: {pt_path}")
    model = YOLO(str(pt_path))

    print(f"\n🔄 ONNX 변환 시작...")
    print(f"   입력 크기: 416×416")
    print(f"   출력: {model_info['output']}")

    # Export to ONNX
    model.export(
        format="onnx",
        imgsz=416,  # 416×416 크기로 변환
        dynamic=False,
        simplify=True,
        opset=12
    )

    # Move to output location
    exported = pt_path.parent / f"{pt_path.stem}.onnx"
    output_path = Path(model_info["output"])
    output_path.parent.mkdir(parents=True, exist_ok=True)

    if exported.exists():
        exported.rename(output_path)
        size_mb = output_path.stat().st_size / (1024*1024)

        print(f"\n✅ 변환 완료!")
        print(f"   파일: {output_path}")
        print(f"   크기: {size_mb:.1f} MB")
        print(f"\n📊 예상 성능:")
        print(f"   M640 (640×640): 38ms")
        print(f"   M416 (416×416): 17-20ms (-50%) 🔥")
        print(f"\n🚀 다음 단계:")
        print(f"1. 이 파일을 App/app/src/main/assets/에 복사")
        print(f"2. DetTypes.kt에 M416 모델 추가")
        print(f"3. 앱 빌드 및 테스트")
    else:
        print(f"❌ 변환 실패")

if __name__ == "__main__":
    export_m416()
