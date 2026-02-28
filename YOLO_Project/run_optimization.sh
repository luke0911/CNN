#!/bin/bash
# Yolo_640_7 완전 최적화 실행 스크립트

cd /Users/idohun/WorkSpace/CNN/YOLO_Project

echo "=================================="
echo "🚀 Yolo_640_7 완전 최적화 시작"
echo "=================================="
echo ""
echo "⚙️  설정:"
echo "  - 프루닝: 30%"
echo "  - Fine-tuning: 10 에포크"
echo "  - 양자화: 건너뛰기"
echo ""
echo "⏱️  예상 소요 시간: 약 35분"
echo ""

# Fine-tuning 자동 실행을 위한 Python 스크립트
python3 << 'PYTHON_EOF'
import os
import sys
import torch
import torch.nn.utils.prune as prune
from ultralytics import YOLO
from pathlib import Path

# 설정
SOURCE_MODEL = 'runs/detect/sign_v8s_img640_ft25_mps/weights/best.pt'
DATASET_YAML = 'datasets_sign/data.yaml'
PRUNING_AMOUNT = 0.3
EPOCHS = 10
BATCH_SIZE = 16
IMG_SIZE = 640
DEVICE = 'mps'
OUTPUT_DIR = Path('optimized_yolo640')
OUTPUT_DIR.mkdir(exist_ok=True)

print("\n" + "="*80)
print("🔧 1단계: 프루닝")
print("="*80)

# 프루닝
model = YOLO(SOURCE_MODEL)
pytorch_model = model.model

pruned_count = 0
for name, module in pytorch_model.named_modules():
    if isinstance(module, (torch.nn.Conv2d, torch.nn.Linear)):
        prune.l1_unstructured(module, name='weight', amount=PRUNING_AMOUNT)
        prune.remove(module, 'weight')
        pruned_count += 1

pruned_path = OUTPUT_DIR / 'yolo640_pruned.pt'
model.save(str(pruned_path))
print(f"✅ {pruned_count}개 레이어 프루닝 완료")
print(f"💾 저장: {pruned_path}")

print("\n" + "="*80)
print("🎓 2단계: Fine-tuning (10 에포크)")
print("="*80)

# Fine-tuning
model = YOLO(str(pruned_path))

print(f"\n🏋️  학습 시작...")
results = model.train(
    data=DATASET_YAML,
    epochs=EPOCHS,
    imgsz=IMG_SIZE,
    batch=BATCH_SIZE,
    device=DEVICE,
    project=str(OUTPUT_DIR),
    name='finetune',
    exist_ok=True,
    patience=5,
    save=True,
    save_period=5,
    verbose=True,
)

finetuned_path = OUTPUT_DIR / 'finetune' / 'weights' / 'best.pt'
print(f"\n✅ Fine-tuning 완료!")
print(f"📂 모델: {finetuned_path}")

print("\n" + "="*80)
print("📦 3단계: ONNX 변환")
print("="*80)

# ONNX 변환
model = YOLO(str(finetuned_path))
model.export(format='onnx', imgsz=IMG_SIZE, opset=11, simplify=True, dynamic=False)

exported = str(finetuned_path).replace('.pt', '.onnx')
final_onnx = OUTPUT_DIR / 'Yolo_640_7_optimized.onnx'
if os.path.exists(exported):
    os.rename(exported, final_onnx)
    onnx_size = os.path.getsize(final_onnx) / (1024*1024)
    print(f"\n✅ ONNX 변환 완료!")
    print(f"📂 파일: {final_onnx}")
    print(f"📊 크기: {onnx_size:.2f} MB")

print("\n" + "="*80)
print("🎉 최적화 완료!")
print("="*80)

# 결과 요약
orig_size = os.path.getsize('Yolo_640_7.onnx') / (1024*1024)
opt_size = os.path.getsize(final_onnx) / (1024*1024)
reduction = (1 - opt_size / orig_size) * 100

print(f"\n📊 크기 비교:")
print(f"  원본 ONNX:     {orig_size:.2f} MB")
print(f"  최적화 ONNX:   {opt_size:.2f} MB")
print(f"  감소율:        {reduction:.1f}%")

print(f"\n📂 생성된 파일:")
print(f"  - {pruned_path} (프루닝)")
print(f"  - {finetuned_path} (Fine-tuned)")
print(f"  - {final_onnx} (최종 ONNX)")

PYTHON_EOF

echo ""
echo "✅ 완료!"
