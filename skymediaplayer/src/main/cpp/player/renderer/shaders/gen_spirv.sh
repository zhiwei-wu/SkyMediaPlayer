#!/usr/bin/env bash
# 把本目录的 GLSL 着色器编译为 SPIR-V，并生成 ../sky_vk_shaders.h（uint32_t 数组）。
# 依赖 glslc（shaderc）。改完 .vert/.frag/lut.glsl 后运行本脚本即可。
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/../sky_vk_shaders.h"
GLSLC="${GLSLC:-glslc}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

emit() { # name file
  local name="$1" file="$2"
  "$GLSLC" -O -I "$DIR" -mfmt=c "$file" -o "$TMP/out.txt"
  printf 'static const uint32_t %s[] = %s;\n' "$name" "$(cat "$TMP/out.txt")"
  printf 'static const size_t %sSize = sizeof(%s);\n\n' "$name" "$name"
}

{
  echo "#pragma once"
  echo
  echo "// Auto-generated SPIR-V shader bytecode (LUT-enabled). DO NOT EDIT BY HAND."
  echo "// Regenerate via renderer/shaders/gen_spirv.sh"
  echo "// BT.601 limited range YUV -> full range RGB + GPUImage 512x512 LUT"
  echo "// (LUT sampler at binding=3, enable/intensity via push_constant float lutEnabled)"
  echo
  echo "// Vertex shader (fullscreen quad)"
  emit vertexShaderSPIRV "$DIR/quad.vert"
  echo "// YUV420P fragment shader (3 planes: Y, U, V)"
  emit fragmentShaderYUV420PSPIRV "$DIR/yuv420p.frag"
  echo "// NV12 fragment shader (Y plane + interleaved UV)"
  emit fragmentShaderNV12SPIRV "$DIR/nv12.frag"
  echo "// NV21 fragment shader (Y plane + interleaved VU)"
  emit fragmentShaderNV21SPIRV "$DIR/nv21.frag"
  echo "// RGBA fragment shader (direct output)"
  emit fragmentShaderRGBASPIRV "$DIR/rgba.frag"
} > "$OUT"

echo "Generated $OUT"
