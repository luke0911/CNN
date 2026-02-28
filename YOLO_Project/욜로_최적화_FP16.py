#!/usr/bin/env python3
"""
YOLO 모델 FP16 최적화 통합 스크립트
- PT 모델을 FP16으로 변환
- ONNX 모델을 FP16으로 변환
- Ultralytics 공식 방법 지원
- 안정화된 변환 (입출력 타입 유지)

통합된 파일:
- convert_fp16.py
- export_fp16_ultralytics.py
- optimize_onnx.py
- optimize_onnx_v2.py
"""

import os
import sys
import shutil
import torch
from pathlib import Path
from ultralytics import YOLO

try:
    import onnx
    from onnxconverter_common import float16
    HAS_ONNX_CONVERTER = True
except ImportError:
    HAS_ONNX_CONVERTER = False
    print("⚠️ onnxconverter-common 설치 필요: pip install onnxconverter-common")

# ==================== 설정 ====================
BASE_DIR = Path("/Users/idohun/WorkSpace/CNN/YOLO_Project")
OUTPUT_DIR = BASE_DIR / 'optimized_fp16'
OUTPUT_DIR.mkdir(exist_ok=True)


def print_section(title: str):
    """섹션 헤더 출력"""
    print(f"\n{'='*80}")
    print(f"{title}")
    print(f"{'='*80}")


def convert_pt_to_fp16(pt_path: Path, output_name: str = None) -> Path:
    """
    PT 모델을 FP16으로 변환

    Args:
        pt_path: 원본 PT 모델 경로
        output_name: 출력 파일명 (None이면 자동 생성)

    Returns:
        변환된 FP16 PT 모델 경로
    """
    print_section(f"⚡ PT → FP16 변환: {pt_path.name}")

    if not pt_path.exists():
        print(f"❌ 파일을 찾을 수 없습니다: {pt_path}")
        return None

    # 원본 크기 확인
    orig_size = pt_path.stat().st_size / (1024*1024)
    print(f"\n📊 원본 크기: {orig_size:.2f} MB")

    try:
        # 모델 로드
        print("\n📥 모델 로드 중...")
        model = YOLO(str(pt_path))
        checkpoint = torch.load(str(pt_path), map_location='cpu', weights_only=False)

        # FP16 변환
        print("⚡ FP16 변환 중...")
        if hasattr(model.model, 'half'):
            model.model.half()
        else:
            # 수동 변환
            for param in model.model.parameters():
                param.data = param.data.half()

        # 체크포인트의 모델도 FP16으로 변환
        if 'model' in checkpoint:
            checkpoint_model = checkpoint['model']
            if hasattr(checkpoint_model, 'half'):
                checkpoint['model'] = checkpoint_model.half()
            else:
                # state_dict 수동 변환
                if hasattr(checkpoint_model, 'state_dict'):
                    state_dict = checkpoint_model.state_dict()
                    for key in state_dict:
                        if isinstance(state_dict[key], torch.Tensor):
                            state_dict[key] = state_dict[key].half()

        # 저장
        if output_name is None:
            output_name = pt_path.stem + '_fp16.pt'

        output_path = OUTPUT_DIR / output_name
        torch.save(checkpoint, output_path)

        # 결과 확인
        fp16_size = output_path.stat().st_size / (1024*1024)
        reduction = ((orig_size - fp16_size) / orig_size) * 100

        print(f"\n✅ FP16 변환 완료!")
        print(f"📁 저장 위치: {output_path}")
        print(f"📊 FP16 크기: {fp16_size:.2f} MB")
        print(f"📉 크기 감소: {reduction:.1f}%")

        return output_path

    except Exception as e:
        print(f"❌ 변환 실패: {e}")
        import traceback
        traceback.print_exc()
        return None


def export_onnx_fp32_ultralytics(pt_path: Path, img_size: int = 640) -> Path:
    """
    Ultralytics 공식 방법으로 PT → ONNX (FP32) 변환

    Args:
        pt_path: PT 모델 경로
        img_size: 입력 이미지 크기

    Returns:
        변환된 ONNX 모델 경로
    """
    print_section(f"🔧 PT → ONNX (FP32) 변환: {pt_path.name}")

    if not pt_path.exists():
        print(f"❌ 파일을 찾을 수 없습니다: {pt_path}")
        return None

    try:
        # 모델 로드
        print("\n📥 모델 로드 중...")
        model = YOLO(str(pt_path))
        print("✅ 모델 로드 완료")

        # ONNX 변환
        print(f"\n📦 ONNX FP32 변환 중 (이미지 크기: {img_size})...")
        print("   - 그래프 단순화")
        print("   - 정적 배치 (성능 향상)")

        model.export(
            format='onnx',
            imgsz=img_size,
            opset=12,
            simplify=True,
            dynamic=False,
        )

        # 생성된 ONNX 파일 찾기
        exported_onnx = Path(str(pt_path).replace('.pt', '.onnx'))

        if exported_onnx.exists():
            # OUTPUT_DIR로 복사
            output_name = pt_path.stem + '_fp32.onnx'
            output_path = OUTPUT_DIR / output_name
            shutil.copy2(exported_onnx, output_path)

            # 크기 확인
            onnx_size = output_path.stat().st_size / (1024*1024)

            print(f"\n✅ ONNX FP32 변환 완료!")
            print(f"📁 저장 위치: {output_path}")
            print(f"📊 크기: {onnx_size:.2f} MB")

            return output_path
        else:
            print(f"❌ ONNX 파일 생성 실패")
            return None

    except Exception as e:
        print(f"❌ 변환 실패: {e}")
        import traceback
        traceback.print_exc()
        return None


def convert_onnx_to_fp16(onnx_path: Path, keep_io_types: bool = True, output_name: str = None) -> Path:
    """
    ONNX 모델을 FP16으로 변환

    Args:
        onnx_path: 원본 ONNX 모델 경로
        keep_io_types: 입출력 타입 유지 여부 (호환성을 위해 True 권장)
        output_name: 출력 파일명 (None이면 자동 생성)

    Returns:
        변환된 FP16 ONNX 모델 경로
    """
    if not HAS_ONNX_CONVERTER:
        print("❌ onnxconverter-common이 설치되지 않았습니다.")
        return None

    mode = "안정화" if keep_io_types else "완전"
    print_section(f"⚡ ONNX → FP16 변환 ({mode}): {onnx_path.name}")

    if not onnx_path.exists():
        print(f"❌ 파일을 찾을 수 없습니다: {onnx_path}")
        return None

    # 원본 크기 확인
    orig_size = onnx_path.stat().st_size / (1024*1024)
    print(f"\n📊 원본 크기: {orig_size:.2f} MB")

    try:
        # ONNX 모델 로드
        print("\n📥 ONNX 모델 로드 중...")
        model = onnx.load(str(onnx_path))
        print("✅ 로드 완료")

        print(f"   입력: {model.graph.input[0].name}")
        print(f"   출력: {model.graph.output[0].name}")

        # FP16 변환
        print(f"\n⚡ FP32 → FP16 변환 중...")
        if keep_io_types:
            print("   (입출력은 FP32 유지, 내부 연산만 FP16)")
        else:
            print("   (모든 연산을 FP16으로 변환)")

        model_fp16 = float16.convert_float_to_float16(
            model,
            keep_io_types=keep_io_types
        )

        # 저장
        if output_name is None:
            suffix = '_fp16_stable' if keep_io_types else '_fp16'
            output_name = onnx_path.stem + suffix + '.onnx'

        output_path = OUTPUT_DIR / output_name
        onnx.save(model_fp16, str(output_path))

        # 결과 확인
        fp16_size = output_path.stat().st_size / (1024*1024)
        reduction = ((orig_size - fp16_size) / orig_size) * 100

        print(f"\n✅ FP16 변환 완료!")
        print(f"📁 저장 위치: {output_path}")
        print(f"📊 FP16 크기: {fp16_size:.2f} MB")
        print(f"📉 크기 감소: {reduction:.1f}%")

        # 검증
        print(f"\n🔍 변환 검증 중...")
        import onnxruntime as ort
        session = ort.InferenceSession(str(output_path), providers=['CPUExecutionProvider'])
        print(f"✅ ONNX Runtime에서 로드 가능")

        return output_path

    except Exception as e:
        print(f"❌ 변환 실패: {e}")
        import traceback
        traceback.print_exc()
        return None


def main():
    print_section("⚡ YOLO 모델 FP16 최적화 도구")

    print("\n사용 가능한 기능:")
    print("  1. PT 모델 → FP16 PT")
    print("  2. PT 모델 → ONNX (FP32) → FP16 ONNX (전체 파이프라인)")
    print("  3. ONNX 모델 → FP16 ONNX (안정화 버전)")
    print("  4. ONNX 모델 → FP16 ONNX (완전 변환)")
    print("  0. 종료")

    while True:
        choice = input("\n선택 (0-4): ").strip()

        if choice == '0':
            break

        elif choice == '1':
            pt_file = input("PT 모델 경로 입력: ").strip()
            pt_path = Path(pt_file) if pt_file else None

            if not pt_path or not pt_path.exists():
                print("❌ 유효하지 않은 경로입니다.")
                continue

            convert_pt_to_fp16(pt_path)

        elif choice == '2':
            pt_file = input("PT 모델 경로 입력: ").strip()
            pt_path = Path(pt_file) if pt_file else None

            if not pt_path or not pt_path.exists():
                print("❌ 유효하지 않은 경로입니다.")
                continue

            img_size = input("이미지 크기 (기본 640): ").strip()
            img_size = int(img_size) if img_size else 640

            # PT → ONNX FP32
            onnx_fp32 = export_onnx_fp32_ultralytics(pt_path, img_size)

            if onnx_fp32:
                # ONNX FP32 → FP16
                convert_onnx_to_fp16(onnx_fp32, keep_io_types=True)

        elif choice == '3':
            onnx_file = input("ONNX 모델 경로 입력: ").strip()
            onnx_path = Path(onnx_file) if onnx_file else None

            if not onnx_path or not onnx_path.exists():
                print("❌ 유효하지 않은 경로입니다.")
                continue

            convert_onnx_to_fp16(onnx_path, keep_io_types=True)

        elif choice == '4':
            onnx_file = input("ONNX 모델 경로 입력: ").strip()
            onnx_path = Path(onnx_file) if onnx_file else None

            if not onnx_path or not onnx_path.exists():
                print("❌ 유효하지 않은 경로입니다.")
                continue

            convert_onnx_to_fp16(onnx_path, keep_io_types=False)

        else:
            print("잘못된 선택입니다.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n\n종료합니다.")
        sys.exit(0)
