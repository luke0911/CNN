#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
YOLO 모델 프루닝 최적화 통합 스크립트
- 프루닝 (Pruning): 불필요한 가중치 제거
- Fine-tuning: 프루닝 후 재학습으로 정확도 회복
- 양자화 (Quantization): INT8 양자화 적용
- ONNX 변환: 최적화된 모델을 ONNX로 변환

통합된 파일:
- optimize_models.py
- optimize_yolo640_full.py
- structured_pruning.py
"""

import os
import sys
import torch
import torch.nn.utils.prune as prune
from ultralytics import YOLO
from pathlib import Path
import yaml

# ==================== 설정 ====================
BASE_DIR = Path("/Users/idohun/WorkSpace/CNN/YOLO_Project")
OUTPUT_DIR = BASE_DIR / 'optimized_pruned'
OUTPUT_DIR.mkdir(exist_ok=True)

# 기본 설정
DEFAULT_PRUNING_AMOUNT = 0.3  # 30% 가중치 제거
DEFAULT_EPOCHS = 15
DEFAULT_BATCH_SIZE = 16
DEFAULT_IMG_SIZE = 640
DEFAULT_DEVICE = 'mps'  # 'mps' (Mac), 'cpu', 'cuda'


def print_section(title: str):
    """섹션 헤더 출력"""
    print(f"\n{'='*80}")
    print(f"{title}")
    print(f"{'='*80}")


def count_parameters(model):
    """모델의 파라미터 수 계산"""
    return sum(p.numel() for p in model.parameters())


def calculate_sparsity(model):
    """모델의 희소성 (0 값 비율) 계산"""
    total_params = 0
    zero_params = 0

    for param in model.parameters():
        if isinstance(param, torch.Tensor):
            total_params += param.numel()
            zero_params += (param == 0).sum().item()

    return (zero_params / total_params * 100) if total_params > 0 else 0


def apply_pruning(model, amount: float = 0.3, method: str = 'l1_unstructured'):
    """
    모델에 프루닝 적용

    Args:
        model: PyTorch 모델
        amount: 프루닝 비율 (0.0 ~ 1.0)
        method: 프루닝 방법 ('l1_unstructured', 'random_unstructured' 등)

    Returns:
        프루닝이 적용된 모델
    """
    print(f"\n🔨 프루닝 적용 중...")
    print(f"   방법: {method}")
    print(f"   비율: {amount * 100:.0f}%")

    pruned_layers = 0

    for name, module in model.named_modules():
        # Conv2d, Linear 레이어에만 프루닝 적용
        if isinstance(module, (torch.nn.Conv2d, torch.nn.Linear)):
            if method == 'l1_unstructured':
                prune.l1_unstructured(module, name='weight', amount=amount)
            elif method == 'random_unstructured':
                prune.random_unstructured(module, name='weight', amount=amount)
            else:
                print(f"⚠️ 지원하지 않는 프루닝 방법: {method}")
                return model

            pruned_layers += 1

    print(f"✅ {pruned_layers}개 레이어에 프루닝 적용 완료")

    # 희소성 계산
    sparsity = calculate_sparsity(model)
    print(f"📊 현재 희소성: {sparsity:.2f}%")

    return model


def make_pruning_permanent(model):
    """프루닝을 영구적으로 적용 (마스크 제거)"""
    print(f"\n🔧 프루닝 영구 적용 중...")

    for name, module in model.named_modules():
        if isinstance(module, (torch.nn.Conv2d, torch.nn.Linear)):
            try:
                prune.remove(module, 'weight')
            except ValueError:
                # 프루닝이 적용되지 않은 레이어는 스킵
                pass

    print(f"✅ 프루닝 영구 적용 완료")
    return model


def finetune_model(model, dataset_yaml: Path, epochs: int, batch_size: int,
                   img_size: int, device: str, project_name: str):
    """
    프루닝된 모델 Fine-tuning

    Args:
        model: YOLO 모델
        dataset_yaml: 데이터셋 설정 파일
        epochs: 학습 에포크 수
        batch_size: 배치 크기
        img_size: 이미지 크기
        device: 디바이스 ('cpu', 'cuda', 'mps')
        project_name: 프로젝트 이름

    Returns:
        학습된 모델
    """
    print_section(f"🎓 Fine-tuning 시작")

    print(f"\n⚙️ 설정:")
    print(f"   데이터셋: {dataset_yaml}")
    print(f"   에포크: {epochs}")
    print(f"   배치 크기: {batch_size}")
    print(f"   이미지 크기: {img_size}")
    print(f"   디바이스: {device}")

    if not dataset_yaml.exists():
        print(f"❌ 데이터셋 파일을 찾을 수 없습니다: {dataset_yaml}")
        return None

    try:
        results = model.train(
            data=str(dataset_yaml),
            epochs=epochs,
            batch=batch_size,
            imgsz=img_size,
            device=device,
            project=str(OUTPUT_DIR),
            name=project_name,
            patience=5,  # Early stopping
            save=True,
            verbose=True,
            plots=True,
        )

        print(f"\n✅ Fine-tuning 완료!")
        return model

    except Exception as e:
        print(f"❌ Fine-tuning 실패: {e}")
        import traceback
        traceback.print_exc()
        return None


def export_to_onnx(model, img_size: int, output_name: str):
    """
    모델을 ONNX로 변환

    Args:
        model: YOLO 모델
        img_size: 입력 이미지 크기
        output_name: 출력 파일명

    Returns:
        ONNX 파일 경로
    """
    print_section(f"📦 ONNX 변환")

    try:
        print(f"\n⚙️ ONNX 변환 중... (이미지 크기: {img_size})")

        onnx_file = model.export(
            format='onnx',
            imgsz=img_size,
            opset=11,
            simplify=True,
            dynamic=False,
        )

        # 출력 디렉토리로 복사
        output_path = OUTPUT_DIR / output_name

        if os.path.exists(onnx_file):
            import shutil
            shutil.copy2(onnx_file, output_path)

            onnx_size = output_path.stat().st_size / (1024 * 1024)

            print(f"\n✅ ONNX 변환 완료!")
            print(f"📁 저장 위치: {output_path}")
            print(f"📊 크기: {onnx_size:.2f} MB")

            return output_path
        else:
            print(f"❌ ONNX 파일 생성 실패")
            return None

    except Exception as e:
        print(f"❌ ONNX 변환 실패: {e}")
        import traceback
        traceback.print_exc()
        return None


def optimize_pipeline(source_model: Path, dataset_yaml: Path,
                      pruning_amount: float = 0.3, epochs: int = 15,
                      batch_size: int = 16, img_size: int = 640,
                      device: str = 'mps'):
    """
    완전한 최적화 파이프라인 실행

    Args:
        source_model: 원본 모델 경로
        dataset_yaml: 데이터셋 설정 파일
        pruning_amount: 프루닝 비율
        epochs: Fine-tuning 에포크
        batch_size: 배치 크기
        img_size: 이미지 크기
        device: 디바이스

    Returns:
        최적화된 모델 경로들
    """
    print_section(f"🚀 YOLO 완전 최적화 파이프라인")

    print(f"\n📋 설정:")
    print(f"   원본 모델: {source_model}")
    print(f"   프루닝 비율: {pruning_amount*100:.0f}%")
    print(f"   Fine-tuning 에포크: {epochs}")
    print(f"   배치 크기: {batch_size}")
    print(f"   이미지 크기: {img_size}")
    print(f"   디바이스: {device}")

    if not source_model.exists():
        print(f"❌ 모델 파일을 찾을 수 없습니다: {source_model}")
        return None

    # 1단계: 모델 로드
    print_section("📥 1단계: 모델 로드")
    model = YOLO(str(source_model))
    pytorch_model = model.model

    orig_params = count_parameters(pytorch_model)
    orig_size = source_model.stat().st_size / (1024 * 1024)

    print(f"\n📊 원본 모델:")
    print(f"   파라미터: {orig_params:,}")
    print(f"   파일 크기: {orig_size:.2f} MB")

    # 2단계: 프루닝
    print_section("🔨 2단계: 프루닝")
    pytorch_model = apply_pruning(pytorch_model, amount=pruning_amount)

    # 프루닝된 모델 저장
    pruned_path = OUTPUT_DIR / f"{source_model.stem}_pruned.pt"
    torch.save(pytorch_model.state_dict(), pruned_path)

    print(f"\n💾 프루닝된 모델 저장: {pruned_path}")

    # 3단계: Fine-tuning
    if dataset_yaml and dataset_yaml.exists():
        model = finetune_model(
            model, dataset_yaml, epochs, batch_size,
            img_size, device, f"{source_model.stem}_finetuned"
        )

        if model is None:
            print("⚠️ Fine-tuning 실패, 프루닝된 모델만 저장됩니다.")
            return {'pruned': pruned_path}

        # Fine-tuned 모델 경로
        finetuned_path = OUTPUT_DIR / f"{source_model.stem}_finetuned" / "weights" / "best.pt"

    else:
        print("⚠️ 데이터셋 파일이 없어 Fine-tuning을 건너뜁니다.")
        finetuned_path = pruned_path
        model = YOLO(str(pruned_path))

    # 4단계: 프루닝 영구 적용
    print_section("🔧 4단계: 프루닝 영구 적용")
    pytorch_model = make_pruning_permanent(model.model)

    # 최종 모델 저장
    final_pt_path = OUTPUT_DIR / f"{source_model.stem}_optimized.pt"
    torch.save(pytorch_model.state_dict(), final_pt_path)

    print(f"\n💾 최종 PT 모델 저장: {final_pt_path}")

    # 5단계: ONNX 변환
    onnx_path = export_to_onnx(
        model, img_size,
        f"{source_model.stem}_optimized.onnx"
    )

    # 결과 요약
    print_section("✅ 최적화 완료!")

    print(f"\n📦 생성된 파일:")
    print(f"   1. 프루닝 모델: {pruned_path}")
    if finetuned_path != pruned_path:
        print(f"   2. Fine-tuned 모델: {finetuned_path}")
    print(f"   3. 최종 PT 모델: {final_pt_path}")
    if onnx_path:
        print(f"   4. ONNX 모델: {onnx_path}")

    final_size = final_pt_path.stat().st_size / (1024 * 1024)
    final_params = count_parameters(pytorch_model)

    print(f"\n📊 최적화 결과:")
    print(f"   파라미터 감소: {orig_params:,} → {final_params:,} ({(1 - final_params/orig_params)*100:.1f}% 감소)")
    print(f"   크기 감소: {orig_size:.2f} MB → {final_size:.2f} MB ({(1 - final_size/orig_size)*100:.1f}% 감소)")

    return {
        'pruned': pruned_path,
        'finetuned': finetuned_path if finetuned_path != pruned_path else None,
        'final_pt': final_pt_path,
        'onnx': onnx_path
    }


def main():
    print_section("🔨 YOLO 모델 프루닝 최적화 도구")

    print("\n이 도구는 다음 단계를 수행합니다:")
    print("  1. 모델 로드 및 분석")
    print("  2. 프루닝 (30% 가중치 제거)")
    print("  3. Fine-tuning (정확도 회복)")
    print("  4. 프루닝 영구 적용")
    print("  5. ONNX 변환")

    # 모델 경로 입력
    model_file = input("\n원본 PT 모델 경로 입력: ").strip()
    source_model = Path(model_file) if model_file else None

    if not source_model or not source_model.exists():
        print("❌ 유효하지 않은 모델 경로입니다.")
        return

    # 데이터셋 경로 입력
    dataset_file = input("데이터셋 YAML 경로 입력 (건너뛰려면 Enter): ").strip()
    dataset_yaml = Path(dataset_file) if dataset_file else None

    if dataset_yaml and not dataset_yaml.exists():
        print("⚠️ 데이터셋 파일을 찾을 수 없습니다. Fine-tuning을 건너뜁니다.")
        dataset_yaml = None

    # 설정 입력
    print(f"\n⚙️ 최적화 설정 (Enter로 기본값 사용):")

    pruning = input(f"프루닝 비율 (기본 {DEFAULT_PRUNING_AMOUNT*100:.0f}%): ").strip()
    pruning_amount = float(pruning) / 100 if pruning else DEFAULT_PRUNING_AMOUNT

    if dataset_yaml:
        epochs = input(f"Fine-tuning 에포크 (기본 {DEFAULT_EPOCHS}): ").strip()
        epochs = int(epochs) if epochs else DEFAULT_EPOCHS

        batch = input(f"배치 크기 (기본 {DEFAULT_BATCH_SIZE}): ").strip()
        batch_size = int(batch) if batch else DEFAULT_BATCH_SIZE
    else:
        epochs = 0
        batch_size = DEFAULT_BATCH_SIZE

    img = input(f"이미지 크기 (기본 {DEFAULT_IMG_SIZE}): ").strip()
    img_size = int(img) if img else DEFAULT_IMG_SIZE

    dev = input(f"디바이스 (기본 {DEFAULT_DEVICE}): ").strip()
    device = dev if dev else DEFAULT_DEVICE

    # 확인
    print(f"\n📋 최종 설정:")
    print(f"   원본 모델: {source_model}")
    print(f"   데이터셋: {dataset_yaml if dataset_yaml else '없음 (Fine-tuning 건너뜀)'}")
    print(f"   프루닝 비율: {pruning_amount*100:.0f}%")
    if dataset_yaml:
        print(f"   Fine-tuning: {epochs} 에포크, 배치 {batch_size}")
    print(f"   이미지 크기: {img_size}")
    print(f"   디바이스: {device}")

    confirm = input("\n진행하시겠습니까? (y/n): ").strip().lower()

    if confirm != 'y':
        print("취소되었습니다.")
        return

    # 최적화 실행
    result = optimize_pipeline(
        source_model, dataset_yaml,
        pruning_amount, epochs, batch_size,
        img_size, device
    )

    if result:
        print("\n✅ 모든 과정이 완료되었습니다!")
    else:
        print("\n❌ 최적화 실패")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n\n종료합니다.")
        sys.exit(0)
