#!/usr/bin/env bash
# build-base.sh — Build ảnh nền dùng chung (chạy 1 lần, hoặc khi đổi pubspec.base.yaml)
# Dùng:  ./build-base.sh                  build + gắn nhãn ghim nếu máy chưa có
#        ./build-base.sh --retag-pinned   đè nhãn ghim đang trỏ vào ảnh khác
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export DOCKER_BUILDKIT=1
IMAGE="grading-base:latest"

RETAG=0
if [ "${1:-}" = "--retag-pinned" ]; then RETAG=1; fi

echo "Building $IMAGE (lần đầu có thể mất vài phút)..."
docker build -f "$HERE/Dockerfile.base" -t "$IMAGE" "$HERE"

# Backend KHÔNG chạy bằng `latest` mà ghim nhãn phiên bản (xem ghi chú ở application.yml).
# Máy mới clone chỉ có `latest`, trong khi recorder/capture/validate gọi thẳng
# `docker run <nhãn-ghim>` và không tự build — thiếu nhãn là chết ngay ở bước soạn đề.
# Đọc nhãn ghim từ chính application.yml để chỉ có MỘT nguồn sự thật.
YML="$HERE/../grader/src/main/resources/application.yml"
PINNED=""
if [ -f "$YML" ]; then
  PINNED="$(sed -n 's/^[[:space:]]*base-image:[[:space:]]*${GRADER_BASE_IMAGE:\(.*\)}[[:space:]]*$/\1/p' "$YML" | head -n 1)"
  if [ -z "$PINNED" ]; then
    PINNED="$(sed -n 's/^[[:space:]]*base-image:[[:space:]]*\([^[:space:]#]*\)[[:space:]]*$/\1/p' "$YML" | head -n 1)"
  fi
fi

if [ -n "$PINNED" ] && [ "$PINNED" != "$IMAGE" ]; then
  PINNED_ID="$(docker images -q "$PINNED" || true)"
  LATEST_ID="$(docker images -q "$IMAGE" || true)"
  if [ -n "$PINNED_ID" ] && [ "$PINNED_ID" != "$LATEST_ID" ] && [ "$RETAG" -eq 0 ]; then
    # Nhãn ghim là bản đóng băng dùng để chấm điểm: không âm thầm kéo sang ảnh mới.
    echo "⚠️  Nhãn ghim $PINNED đã có sẵn và trỏ vào ảnh KHÁC — script không tự đè."
    echo "    Nếu thật sự muốn đè: ./build-base.sh --retag-pinned"
  else
    docker tag "$IMAGE" "$PINNED"
    echo "✅ Đã gắn nhãn ghim: $PINNED"
  fi
  echo "✅ $IMAGE + $PINNED đã sẵn sàng. Các đề thi sẽ build trong vài giây."
else
  echo "✅ $IMAGE đã sẵn sàng. Các đề thi sẽ build trong vài giây."
fi
