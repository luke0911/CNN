#!/usr/bin/env python3
"""
YOLO 모델 통합 성능 분석 도구

기능:
1. 모델 정확도 평가 (mAP, Precision, Recall)
2. 모델 정보 분석 (파라미터, 크기, 클래스)
3. 성능 벤치마크 (FPS, 추론 시간)
4. 모델 비교 기능 (정확도, 속도)
5. ONNX/PT 모델 지원

사용 예시:
    # 단일 모델 전체 분석
    python3 통합_성능_분석.py --model runs/train/best.pt --data data.yaml --full

    # 두 모델 비교
    python3 통합_성능_분석.py --model1 old.pt --model2 new.pt --data data.yaml --compare

    # 정확도만 평가
    python3 통합_성능_분석.py --model best.pt --data data.yaml --accuracy

    # 속도만 벤치마크
    python3 통합_성능_분석.py --model best.pt --benchmark
"""

import argparse
import time
from pathlib import Path
from typing import Dict, List, Optional
import yaml

import numpy as np
import torch
import onnx
import onnxruntime as ort
from ultralytics import YOLO


# ==================== 유틸리티 함수 ====================

def print_section(title: str, width: int = 80):
    """섹션 헤더 출력"""
    print(f"\n{'='*width}")
    print(f"{title:^{width}}")
    print(f"{'='*width}")


def print_subsection(title: str, width: int = 80):
    """서브섹션 헤더 출력"""
    print(f"\n{title}")
    print(f"{'-'*width}")


# ==================== 모델 정보 분석 ====================

def analyze_model_info(model_path: Path) -> Dict:
    """
    모델 기본 정보 분석

    Args:
        model_path: 모델 파일 경로

    Returns:
        모델 정보 딕셔너리
    """
    info = {
        'path': str(model_path),
        'name': model_path.name,
        'format': model_path.suffix[1:],  # .pt -> pt
        'size_mb': model_path.stat().st_size / (1024 * 1024),
    }

    try:
        if model_path.suffix == '.pt':
            # PyTorch 모델
            checkpoint = torch.load(model_path, map_location='cpu', weights_only=False)

            if 'names' in checkpoint:
                info['num_classes'] = len(checkpoint['names'])
                info['classes'] = checkpoint['names']

            if 'model' in checkpoint:
                model = checkpoint['model']
                if hasattr(model, 'state_dict'):
                    state_dict = model.state_dict()
                elif isinstance(model, dict):
                    state_dict = model
                else:
                    state_dict = {}

                if state_dict:
                    info['total_params'] = sum(
                        p.numel() for p in state_dict.values()
                        if isinstance(p, torch.Tensor)
                    )

            # Ultralytics YOLO 정보
            try:
                yolo = YOLO(str(model_path))
                info['model_type'] = 'YOLOv8'
                total_params = sum(p.numel() for p in yolo.model.parameters())
                info['total_params'] = total_params
            except:
                pass

        elif model_path.suffix == '.onnx':
            # ONNX 모델
            model = onnx.load(str(model_path))

            # 입력/출력 정보
            info['input_shape'] = [
                dim.dim_value if dim.dim_value > 0 else 'dynamic'
                for dim in model.graph.input[0].type.tensor_type.shape.dim
            ]
            info['output_shape'] = [
                dim.dim_value if dim.dim_value > 0 else 'dynamic'
                for dim in model.graph.output[0].type.tensor_type.shape.dim
            ]

            # 파라미터 수 추정
            total_size = 0
            for init in model.graph.initializer:
                tensor_size = 1
                for dim in init.dims:
                    tensor_size *= dim
                total_size += tensor_size
            info['total_params'] = total_size

            info['ir_version'] = model.ir_version
            info['opset_version'] = model.opset_import[0].version if model.opset_import else None

    except Exception as e:
        info['error'] = str(e)

    return info


def print_model_info(info: Dict):
    """모델 정보 출력"""
    print_subsection(f"모델 정보: {info['name']}")

    print(f"  파일 형식: {info['format'].upper()}")
    print(f"  파일 크기: {info['size_mb']:.2f} MB")

    if 'total_params' in info:
        print(f"  총 파라미터: {info['total_params']:,}")

    if 'num_classes' in info:
        print(f"  클래스 수: {info['num_classes']}")
        if 'classes' in info and len(info['classes']) <= 15:
            print(f"  클래스: {list(info['classes'].values())}")

    if 'input_shape' in info:
        print(f"  입력 형태: {info['input_shape']}")

    if 'output_shape' in info:
        print(f"  출력 형태: {info['output_shape']}")

    if 'ir_version' in info:
        print(f"  IR 버전: {info['ir_version']}")
        print(f"  Opset 버전: {info['opset_version']}")

    if 'error' in info:
        print(f"  ⚠️ 분석 오류: {info['error']}")


# ==================== 정확도 평가 ====================

def evaluate_accuracy(model_path: Path, data_yaml: Path, save_dir: Path = None) -> Dict:
    """
    모델 정확도 평가

    Args:
        model_path: 모델 파일 경로
        data_yaml: 데이터셋 yaml 파일
        save_dir: 결과 저장 디렉토리

    Returns:
        평가 결과 딕셔너리
    """
    print_subsection(f"정확도 평가: {model_path.name}")

    if not data_yaml.exists():
        print(f"  ❌ 데이터셋 파일을 찾을 수 없습니다: {data_yaml}")
        return {}

    with open(data_yaml, 'r', encoding='utf-8') as f:
        data_config = yaml.safe_load(f)

    print(f"  데이터셋: {data_yaml}")
    print(f"  클래스: {data_config['names']}")
    print(f"\n  평가 진행 중...")

    try:
        model = YOLO(str(model_path))

        if save_dir is None:
            save_dir = Path('runs/evaluation')

        results = model.val(
            data=str(data_yaml),
            split='test',
            save_json=False,
            plots=False,
            project=str(save_dir),
            name=model_path.stem,
            verbose=False
        )

        # 결과 정리
        eval_results = {
            'map50': float(results.box.map50),
            'map50_95': float(results.box.map),
            'precision': float(results.box.p) if hasattr(results.box, 'p') else 0.0,
            'recall': float(results.box.r) if hasattr(results.box, 'r') else 0.0,
        }

        # 클래스별 mAP
        class_maps = {}
        for i, name in enumerate(data_config['names']):
            if i < len(results.box.maps):
                class_maps[name] = float(results.box.maps[i])

        eval_results['class_maps'] = class_maps

        # 결과 출력
        print(f"\n  전체 성능:")
        print(f"    mAP50:      {eval_results['map50']:.4f}")
        print(f"    mAP50-95:   {eval_results['map50_95']:.4f}")
        print(f"    Precision:  {eval_results['precision']:.4f}")
        print(f"    Recall:     {eval_results['recall']:.4f}")

        print(f"\n  클래스별 mAP50:")
        for name, map_val in class_maps.items():
            print(f"    {name:20s}: {map_val:.4f}")

        # stairs 클래스 강조
        if 'stairs' in class_maps:
            print(f"\n  🎯 계단(stairs) mAP50: {class_maps['stairs']:.4f}")

        return eval_results

    except Exception as e:
        print(f"  ❌ 평가 실패: {e}")
        import traceback
        traceback.print_exc()
        return {}


# ==================== 성능 벤치마크 ====================

def benchmark_performance(model_path: Path, num_warmup: int = 10,
                         num_runs: int = 50, imgsz: int = 640,
                         device: str = 'cpu') -> Dict:
    """
    모델 추론 속도 벤치마크

    Args:
        model_path: 모델 파일 경로
        num_warmup: 워밍업 횟수
        num_runs: 측정 횟수
        imgsz: 입력 이미지 크기
        device: 디바이스 ('cpu', 'cuda', 'mps')

    Returns:
        벤치마크 결과 딕셔너리
    """
    print_subsection(f"성능 벤치마크: {model_path.name}")

    results = {}

    try:
        if model_path.suffix == '.pt':
            # PyTorch 모델 벤치마크
            model = YOLO(str(model_path))

            # 더미 입력
            dummy_input = np.random.randint(0, 255, (imgsz, imgsz, 3), dtype=np.uint8)

            # 워밍업
            print(f"  워밍업 중... ({num_warmup}회)")
            for _ in range(num_warmup):
                model(dummy_input, device=device, verbose=False)

            # 측정
            print(f"  추론 속도 측정 중... ({num_runs}회)")
            times = []
            for _ in range(num_runs):
                start = time.time()
                model(dummy_input, device=device, verbose=False)
                times.append((time.time() - start) * 1000)

        elif model_path.suffix == '.onnx':
            # ONNX 모델 벤치마크
            session = ort.InferenceSession(
                str(model_path),
                providers=['CPUExecutionProvider']
            )

            input_name = session.get_inputs()[0].name
            output_name = session.get_outputs()[0].name

            # 더미 입력
            dummy_input = np.random.randn(1, 3, imgsz, imgsz).astype(np.float32)

            # 워밍업
            print(f"  워밍업 중... ({num_warmup}회)")
            for _ in range(num_warmup):
                session.run([output_name], {input_name: dummy_input})

            # 측정
            print(f"  추론 속도 측정 중... ({num_runs}회)")
            times = []
            for _ in range(num_runs):
                start = time.time()
                session.run([output_name], {input_name: dummy_input})
                times.append((time.time() - start) * 1000)

        else:
            print(f"  ❌ 지원하지 않는 파일 형식: {model_path.suffix}")
            return {}

        # 통계 계산
        results = {
            'avg_time_ms': float(np.mean(times)),
            'std_time_ms': float(np.std(times)),
            'min_time_ms': float(np.min(times)),
            'max_time_ms': float(np.max(times)),
            'fps': float(1000 / np.mean(times)),
        }

        # 결과 출력
        print(f"\n  추론 시간:")
        print(f"    평균: {results['avg_time_ms']:.2f} ± {results['std_time_ms']:.2f} ms")
        print(f"    범위: {results['min_time_ms']:.2f} ~ {results['max_time_ms']:.2f} ms")
        print(f"    FPS:  {results['fps']:.1f}")

        return results

    except Exception as e:
        print(f"  ❌ 벤치마크 실패: {e}")
        import traceback
        traceback.print_exc()
        return {}


# ==================== 모델 비교 ====================

def compare_models(model1_path: Path, model2_path: Path,
                  data_yaml: Optional[Path] = None,
                  benchmark: bool = True) -> Dict:
    """
    두 모델 비교

    Args:
        model1_path: 첫 번째 모델 경로
        model2_path: 두 번째 모델 경로
        data_yaml: 데이터셋 yaml (정확도 비교용)
        benchmark: 속도 벤치마크 수행 여부

    Returns:
        비교 결과 딕셔너리
    """
    print_section("모델 비교")

    comparison = {
        'model1': model1_path.name,
        'model2': model2_path.name,
    }

    # 1. 기본 정보 비교
    print("\n📋 기본 정보 비교")
    print(f"{'항목':<20} {'모델1 ('+model1_path.name+')':<40} {'모델2 ('+model2_path.name+')'}")
    print("-" * 100)

    info1 = analyze_model_info(model1_path)
    info2 = analyze_model_info(model2_path)

    print(f"{'파일 크기 (MB)':<20} {info1['size_mb']:>39.2f}  {info2['size_mb']:>39.2f}")

    if 'total_params' in info1 and 'total_params' in info2:
        print(f"{'파라미터 수':<20} {info1['total_params']:>39,}  {info2['total_params']:>39,}")

    comparison['info1'] = info1
    comparison['info2'] = info2

    # 2. 정확도 비교
    if data_yaml and data_yaml.exists():
        print("\n")
        print_subsection("📊 정확도 비교")

        print("\n🔵 모델1 평가:")
        eval1 = evaluate_accuracy(model1_path, data_yaml)

        print("\n🟢 모델2 평가:")
        eval2 = evaluate_accuracy(model2_path, data_yaml)

        if eval1 and eval2:
            print("\n비교 요약:")
            print(f"{'메트릭':<20} {'모델1':<15} {'모델2':<15} {'차이':<15}")
            print("-" * 65)

            for metric in ['map50', 'map50_95', 'precision', 'recall']:
                val1 = eval1.get(metric, 0)
                val2 = eval2.get(metric, 0)
                diff = val2 - val1
                diff_pct = (diff / val1 * 100) if val1 > 0 else 0

                print(f"{metric:<20} {val1:>14.4f}  {val2:>14.4f}  {diff:>+7.4f} ({diff_pct:>+5.1f}%)")

            # stairs 클래스 비교
            if 'stairs' in eval1.get('class_maps', {}) and 'stairs' in eval2.get('class_maps', {}):
                stairs1 = eval1['class_maps']['stairs']
                stairs2 = eval2['class_maps']['stairs']
                stairs_diff = stairs2 - stairs1
                stairs_pct = (stairs_diff / stairs1 * 100) if stairs1 > 0 else 0

                print(f"\n🎯 계단(stairs) mAP50:")
                print(f"  모델1: {stairs1:.4f}")
                print(f"  모델2: {stairs2:.4f}")
                print(f"  차이:  {stairs_diff:+.4f} ({stairs_pct:+.1f}%)")

            comparison['eval1'] = eval1
            comparison['eval2'] = eval2

    # 3. 성능 비교
    if benchmark:
        print("\n")
        print_subsection("⚡ 성능 비교")

        print("\n🔵 모델1 벤치마크:")
        bench1 = benchmark_performance(model1_path)

        print("\n🟢 모델2 벤치마크:")
        bench2 = benchmark_performance(model2_path)

        if bench1 and bench2:
            print("\n비교 요약:")
            print(f"{'메트릭':<20} {'모델1':<15} {'모델2':<15} {'차이':<15}")
            print("-" * 65)

            time1 = bench1['avg_time_ms']
            time2 = bench2['avg_time_ms']
            time_diff = time2 - time1
            time_pct = (time_diff / time1 * 100) if time1 > 0 else 0

            fps1 = bench1['fps']
            fps2 = bench2['fps']
            fps_diff = fps2 - fps1
            fps_pct = (fps_diff / fps1 * 100) if fps1 > 0 else 0

            print(f"{'추론 시간 (ms)':<20} {time1:>14.2f}  {time2:>14.2f}  {time_diff:>+7.2f} ({time_pct:>+5.1f}%)")
            print(f"{'FPS':<20} {fps1:>14.1f}  {fps2:>14.1f}  {fps_diff:>+7.1f} ({fps_pct:>+5.1f}%)")

            comparison['bench1'] = bench1
            comparison['bench2'] = bench2

    return comparison


# ==================== 메인 함수 ====================

def main():
    parser = argparse.ArgumentParser(
        description='YOLO 모델 통합 성능 분석 도구',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
예시:
  # 전체 분석 (정확도 + 속도)
  python3 통합_성능_분석.py --model best.pt --data data.yaml --full

  # 정확도만 평가
  python3 통합_성능_분석.py --model best.pt --data data.yaml --accuracy

  # 속도만 벤치마크
  python3 통합_성능_분석.py --model best.pt --benchmark

  # 두 모델 비교
  python3 통합_성능_분석.py --model1 old.pt --model2 new.pt --data data.yaml --compare

  # 모델 정보만 확인
  python3 통합_성능_분석.py --model best.pt --info
        """
    )

    # 단일 모델 분석
    parser.add_argument('--model', type=str, help='모델 파일 경로 (.pt 또는 .onnx)')
    parser.add_argument('--data', type=str, help='데이터셋 yaml 파일 경로')

    # 모델 비교
    parser.add_argument('--model1', type=str, help='비교할 첫 번째 모델')
    parser.add_argument('--model2', type=str, help='비교할 두 번째 모델')
    parser.add_argument('--compare', action='store_true', help='두 모델 비교 모드')

    # 분석 옵션
    parser.add_argument('--info', action='store_true', help='모델 정보만 출력')
    parser.add_argument('--accuracy', action='store_true', help='정확도만 평가')
    parser.add_argument('--benchmark', action='store_true', help='속도만 벤치마크')
    parser.add_argument('--full', action='store_true', help='전체 분석 (정보+정확도+속도)')

    # 벤치마크 설정
    parser.add_argument('--warmup', type=int, default=10, help='워밍업 횟수 (기본: 10)')
    parser.add_argument('--runs', type=int, default=50, help='측정 횟수 (기본: 50)')
    parser.add_argument('--imgsz', type=int, default=640, help='입력 이미지 크기 (기본: 640)')
    parser.add_argument('--device', type=str, default='cpu', help='디바이스 (기본: cpu)')

    # 결과 저장
    parser.add_argument('--save-dir', type=str, default='runs/analysis',
                       help='결과 저장 디렉토리')

    args = parser.parse_args()

    # 비교 모드
    if args.compare or (args.model1 and args.model2):
        if not args.model1 or not args.model2:
            print("❌ 비교 모드에는 --model1과 --model2가 필요합니다.")
            return

        model1_path = Path(args.model1)
        model2_path = Path(args.model2)

        if not model1_path.exists() or not model2_path.exists():
            print("❌ 모델 파일을 찾을 수 없습니다.")
            return

        data_yaml = Path(args.data) if args.data else None

        compare_models(
            model1_path,
            model2_path,
            data_yaml,
            benchmark=not args.accuracy  # accuracy only 모드가 아니면 벤치마크 수행
        )
        return

    # 단일 모델 분석
    if not args.model:
        parser.print_help()
        return

    model_path = Path(args.model)

    if not model_path.exists():
        print(f"❌ 모델 파일을 찾을 수 없습니다: {model_path}")
        return

    print_section(f"모델 분석: {model_path.name}")

    # 기본 정보
    if args.info or args.full or (not args.accuracy and not args.benchmark):
        info = analyze_model_info(model_path)
        print_model_info(info)

    # 정확도 평가
    if args.accuracy or args.full:
        if not args.data:
            print("\n⚠️ 정확도 평가를 위해서는 --data 옵션이 필요합니다.")
        else:
            data_yaml = Path(args.data)
            save_dir = Path(args.save_dir)
            evaluate_accuracy(model_path, data_yaml, save_dir)

    # 성능 벤치마크
    if args.benchmark or args.full:
        benchmark_performance(
            model_path,
            num_warmup=args.warmup,
            num_runs=args.runs,
            imgsz=args.imgsz,
            device=args.device
        )

    print("\n✅ 분석 완료!")


if __name__ == "__main__":
    main()
