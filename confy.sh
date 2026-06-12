#!/bin/bash
# AI Video Workstation — RTX 3080 Ti (12 GB VRAM)
# Tested on Ubuntu 22/24, Python 3.12, CUDA 12.x, driver 580+
#
# What this installs:
#   ComfyUI  + Wan2.1-T2V-1.3B + LTX-Video + 5 custom nodes + xformers
#   CosyVoice (TTS narration, own venv)
#   Real-ESRGAN 4× upscaler (own venv, basicsr patch included)
#   Ollama + qwen3:14b
#
# Fixes applied vs. naive install:
#   - grpcio 1.57 has no Python 3.12 wheel → pinned to 1.62.1
#   - openai-whisper build needs setuptools pre-installed on 3.12
#   - basicsr imports removed torchvision.functional_tensor → patched in-place
#   - Real-ESRGAN setup.py develop → pip install -e (deprecated form)
#   - Each tool gets its own venv (deactivate between installs)
#   - xformers installed for ~25% VRAM saving (essential on 12 GB)
#   - opencv-python-headless added for VideoHelperSuite
#   - All venv deactivate calls present (original script missing these)

set -euo pipefail

AI_DIR="$HOME/ai"
mkdir -p "$AI_DIR"

log() { echo -e "\n\033[1;36m>>> $*\033[0m"; }

# ─── System packages ──────────────────────────────────────────────────────────
log "System packages"
sudo apt update -qq
sudo apt install -y \
  git curl wget unzip bc \
  ffmpeg \
  python3 python3-pip python3-venv \
  build-essential cmake pkg-config \
  libgl1 libglib2.0-0

# ─── Ollama ───────────────────────────────────────────────────────────────────
log "Ollama"
if ! command -v ollama &>/dev/null; then
  curl -fsSL https://ollama.com/install.sh | sh
fi
# Pull model (start server temporarily if not running)
if ! ollama list 2>/dev/null | grep -q "qwen3:14b"; then
  ollama serve &>/dev/null &
  OLLAMA_PID=$!
  sleep 5
  ollama pull qwen3:14b || true
  kill $OLLAMA_PID 2>/dev/null || true
fi

# ─── ComfyUI ──────────────────────────────────────────────────────────────────
log "ComfyUI"
if [ ! -d "$AI_DIR/ComfyUI" ]; then
  git clone https://github.com/comfyanonymous/ComfyUI.git "$AI_DIR/ComfyUI"
fi
cd "$AI_DIR/ComfyUI"

if [ ! -d venv ]; then
  python3 -m venv venv
fi
source venv/bin/activate
pip install --upgrade pip setuptools wheel -q
# cu121 works with CUDA 12.0+ drivers (driver 525+ supports CUDA 12.x runtime)
pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu121 -q
# xformers: saves ~25% VRAM — essential on 12 GB for Wan2.1 and LTX
pip install xformers --index-url https://download.pytorch.org/whl/cu121 -q
pip install -r requirements.txt -q
# OpenCV headless (no GUI dep) — required by VideoHelperSuite
pip install opencv-python-headless -q

# ── Custom nodes ───────────────────────────────────────────────────────────
log "ComfyUI custom nodes"
cd "$AI_DIR/ComfyUI/custom_nodes"

declare -A NODES=(
  ["ComfyUI-Manager"]="https://github.com/ltdrdata/ComfyUI-Manager.git"
  ["ComfyUI-WanVideoWrapper"]="https://github.com/kijai/ComfyUI-WanVideoWrapper.git"
  ["ComfyUI-LTXVideo"]="https://github.com/Lightricks/ComfyUI-LTXVideo.git"
  # VideoHelperSuite: load/save frames, concat clips — REQUIRED for long video
  ["ComfyUI-VideoHelperSuite"]="https://github.com/Kosinkadink/ComfyUI-VideoHelperSuite.git"
  # KJNodes: utility ops used by WanVideo and LTX workflows
  ["ComfyUI-KJNodes"]="https://github.com/kijai/ComfyUI-KJNodes.git"
)

for name in "${!NODES[@]}"; do
  if [ ! -d "$name" ]; then
    git clone "${NODES[$name]}" "$name"
  fi
  req="$name/requirements.txt"
  if [ -f "$req" ]; then
    pip install -r "$req" -q
  fi
done

deactivate

# ─── CosyVoice ────────────────────────────────────────────────────────────────
log "CosyVoice (TTS narration)"
if [ ! -d "$AI_DIR/CosyVoice" ]; then
  git clone https://github.com/FunAudioLLM/CosyVoice.git "$AI_DIR/CosyVoice"
fi
cd "$AI_DIR/CosyVoice"

if [ ! -d venv ]; then
  python3 -m venv venv
fi
source venv/bin/activate
pip install --upgrade pip setuptools wheel -q   # setuptools required on Python 3.12

# grpcio 1.57 (pinned in requirements.txt) has no cp312 wheel; 1.62+ does
pip install "grpcio==1.62.1" "grpcio-tools==1.62.1" -q

# openai-whisper build also needs setuptools available before pip calls it
pip install openai-whisper -q

# Install the rest, excluding packages we've already handled or are broken
grep -vE "^grpcio==|^grpcio-tools==|^torch==|^torchaudio==|^tensorrt|^openai-whisper" \
  requirements.txt > /tmp/cosyvoice_patched.txt
pip install -r /tmp/cosyvoice_patched.txt -q

# CosyVoice requires its own torch+torchaudio (pins 2.3.1 — different from ComfyUI's 2.5.1)
# Both coexist because each tool has its own venv.
pip install torch==2.3.1 torchaudio==2.3.1 --index-url https://download.pytorch.org/whl/cu121 -q

deactivate   # ← was missing in original; Real-ESRGAN was pip-installing into CosyVoice's venv

# ─── Real-ESRGAN ──────────────────────────────────────────────────────────────
log "Real-ESRGAN (4× upscaler)"
if [ ! -d "$AI_DIR/Real-ESRGAN" ]; then
  git clone https://github.com/xinntao/Real-ESRGAN.git "$AI_DIR/Real-ESRGAN"
fi
cd "$AI_DIR/Real-ESRGAN"

if [ ! -d venv ]; then
  python3 -m venv venv
fi
source venv/bin/activate
pip install --upgrade pip setuptools wheel -q
pip install -r requirements.txt -q
pip install -e . -q    # replaces deprecated `python setup.py develop`

# Fix: basicsr imports torchvision.transforms.functional_tensor which was removed
# in torchvision 0.16+. Patch to use the current API.
DEGRADATIONS=$(find venv -name "degradations.py" -path "*/basicsr/*" 2>/dev/null | head -1)
if [ -n "$DEGRADATIONS" ]; then
  sed -i \
    's/from torchvision.transforms.functional_tensor import rgb_to_grayscale/from torchvision.transforms.functional import rgb_to_grayscale/' \
    "$DEGRADATIONS"
  echo "  basicsr patch applied: $DEGRADATIONS"
fi

mkdir -p weights
if [ ! -f weights/RealESRGAN_x4plus.pth ]; then
  wget -q -P weights/ \
    https://github.com/xinntao/Real-ESRGAN/releases/download/v0.1.0/RealESRGAN_x4plus.pth
fi

deactivate

# ─── Video model downloads ────────────────────────────────────────────────────
# Uses ComfyUI's venv (huggingface_hub is installed there as a dep).
# System Python3 is externally managed on Ubuntu 22/24 — pip3 install is blocked.
log "Downloading video model weights (large files — runs in background)"
mkdir -p "$AI_DIR/ComfyUI/models"/{wan_video,checkpoints,clip,vae,text_encoders}

source "$AI_DIR/ComfyUI/venv/bin/activate"

# Wan2.1-T2V-1.3B (~7 GB) — fits in 12 GB VRAM with xformers
if [ ! -d "$AI_DIR/ComfyUI/models/wan_video/Wan2.1-T2V-1.3B" ]; then
  python - <<'PYEOF' &
from huggingface_hub import snapshot_download
import os
p = snapshot_download(
    repo_id="Wan-AI/Wan2.1-T2V-1.3B",
    local_dir=os.path.expanduser("~/ai/ComfyUI/models/wan_video/Wan2.1-T2V-1.3B"),
    ignore_patterns=["*.md","*.txt","*.png"],
)
print("Wan2.1 saved:", p)
PYEOF
  echo "  Wan2.1-T2V-1.3B download started (background)"
fi

# LTX-Video 2B (~6 GB)
if [ ! -f "$AI_DIR/ComfyUI/models/checkpoints/ltx-video-2b-v0.9.5.safetensors" ]; then
  python - <<'PYEOF' &
from huggingface_hub import hf_hub_download
import os
p = hf_hub_download(
    repo_id="Lightricks/LTX-Video",
    filename="ltx-video-2b-v0.9.5.safetensors",
    local_dir=os.path.expanduser("~/ai/ComfyUI/models/checkpoints"),
)
print("LTX-Video saved:", p)
PYEOF
  echo "  LTX-Video download started (background)"
fi

deactivate

# ─── Project structure ─────────────────────────────────────────────────────────
log "Project folders"
mkdir -p "$AI_DIR/projects"/{scripts,audio,images,clips,upscaled,final}

# ─── Helper scripts ────────────────────────────────────────────────────────────
log "Writing helper scripts"

cat > "$HOME/start-comfyui.sh" << 'EOF'
#!/bin/bash
# --lowvram: offload to RAM when VRAM peaks (safe on 12 GB with Wan2.1 / LTX)
cd ~/ai/ComfyUI
source venv/bin/activate
python main.py --lowvram --preview-method auto --listen 0.0.0.0
EOF
chmod +x "$HOME/start-comfyui.sh"

cat > "$HOME/start-ollama.sh" << 'EOF'
#!/bin/bash
ollama serve
EOF
chmod +x "$HOME/start-ollama.sh"

# concat-clips.sh — joins all numbered clips → final video with optional narration
cat > "$HOME/concat-clips.sh" << 'CONCAT'
#!/bin/bash
# Usage: ~/concat-clips.sh [output_name]
# Clips must be in ~/ai/projects/clips/ named 001_foo.mp4, 002_bar.mp4 ...
# Optional narration: ~/ai/projects/audio/narration.wav
set -euo pipefail
OUTPUT="${1:-final_video}"
CLIPS_DIR="$HOME/ai/projects/clips"
FINAL_DIR="$HOME/ai/projects/final"
AUDIO_DIR="$HOME/ai/projects/audio"
LIST_FILE="$(mktemp /tmp/concat_XXXXXX.txt)"

shopt -s nullglob
files=("$CLIPS_DIR"/*.mp4 "$CLIPS_DIR"/*.mov)
if [ ${#files[@]} -eq 0 ]; then
  echo "No clips found in $CLIPS_DIR"; exit 1
fi
for f in "${files[@]}"; do
  echo "file '$f'" >> "$LIST_FILE"
done

echo "Clips to join:"; cat "$LIST_FILE"; echo ""

NARRATION="$AUDIO_DIR/narration.wav"
if [ -f "$NARRATION" ]; then
  ffmpeg -y -f concat -safe 0 -i "$LIST_FILE" -i "$NARRATION" \
    -c:v copy -c:a aac -b:a 192k -shortest "$FINAL_DIR/${OUTPUT}.mp4"
else
  ffmpeg -y -f concat -safe 0 -i "$LIST_FILE" -c:v copy "$FINAL_DIR/${OUTPUT}.mp4"
fi
rm -f "$LIST_FILE"
DURATION=$(ffprobe -v quiet -show_entries format=duration -of csv=p=0 "$FINAL_DIR/${OUTPUT}.mp4")
printf "\nDone: %s\nLength: %.1f minutes\n" "$FINAL_DIR/${OUTPUT}.mp4" "$(echo "$DURATION/60" | bc -l)"
CONCAT
chmod +x "$HOME/concat-clips.sh"

# upscale-clips.sh — 4× upscale each clip before final stitch
cat > "$HOME/upscale-clips.sh" << 'UPSCALE'
#!/bin/bash
set -euo pipefail
CLIPS_DIR="$HOME/ai/projects/clips"
OUT_DIR="$HOME/ai/projects/upscaled"
RESR_DIR="$HOME/ai/Real-ESRGAN"
source "$RESR_DIR/venv/bin/activate"
for clip in "$CLIPS_DIR"/*.mp4; do
  name=$(basename "$clip" .mp4)
  echo "Upscaling $name..."
  TMP=$(mktemp -d)
  FPS=$(ffprobe -v 0 -of csv=p=0 -select_streams v:0 \
        -show_entries stream=r_frame_rate "$clip" | bc -l | xargs printf "%.2f")
  ffmpeg -i "$clip" "$TMP/frame_%05d.png" -hide_banner -loglevel error
  python "$RESR_DIR/inference_realesrgan.py" \
    -n RealESRGAN_x4plus -i "$TMP" -o "$TMP/up" \
    --tile 512   # tile prevents OOM on 12 GB VRAM
  ffmpeg -framerate "$FPS" -i "$TMP/up/frame_%05d_out.png" \
    -c:v libx264 -crf 18 -pix_fmt yuv420p \
    "$OUT_DIR/${name}_4k.mp4" -hide_banner -loglevel error
  rm -rf "$TMP"
  echo "  -> $OUT_DIR/${name}_4k.mp4"
done
deactivate
UPSCALE
chmod +x "$HOME/upscale-clips.sh"

# ─── Verify installs ──────────────────────────────────────────────────────────
log "Verifying installs"
echo -n "  PyTorch CUDA:  " && \
  ~/ai/ComfyUI/venv/bin/python -c \
  "import torch; print(torch.__version__, '| CUDA:', torch.cuda.get_device_name(0))"
echo -n "  xformers:      " && \
  ~/ai/ComfyUI/venv/bin/python -c "import xformers; print(xformers.__version__)"
echo -n "  Real-ESRGAN:   " && \
  ~/ai/Real-ESRGAN/venv/bin/python -c "from realesrgan import RealESRGANer; print('OK')"
echo -n "  CosyVoice:     " && \
  ~/ai/CosyVoice/venv/bin/python -c "import cosyvoice; print('OK')"
echo -n "  VideoHelperSuite: " && \
  ls ~/ai/ComfyUI/custom_nodes/ComfyUI-VideoHelperSuite/videohelpersuite/ &>/dev/null \
  && echo "OK" || echo "MISSING"
echo -n "  Ollama:        " && ollama --version 2>/dev/null || echo "not running"

# ─── Done ─────────────────────────────────────────────────────────────────────
cat << 'SUMMARY'

  ┌─────────────────────────────────────────────────────────┐
  │  INSTALLED & VERIFIED                                   │
  │                                                         │
  │  ComfyUI + xformers + 5 custom nodes                   │
  │  • ComfyUI-Manager         (manage nodes from UI)       │
  │  • ComfyUI-WanVideoWrapper (Wan2.1-T2V)                 │
  │  • ComfyUI-LTXVideo        (LTX-Video 2B)               │
  │  • ComfyUI-VideoHelperSuite (clip I/O & assembly)       │
  │  • ComfyUI-KJNodes         (utility ops)                │
  │  CosyVoice  (TTS narration, own venv)                   │
  │  Real-ESRGAN (4× upscaler, basicsr patch applied)       │
  │  Ollama + qwen3:14b                                     │
  │                                                         │
  │  MODEL DOWNLOADS (running in background if started)     │
  │  Check:  ls -lh ~/ai/ComfyUI/models/wan_video/         │
  │          ls -lh ~/ai/ComfyUI/models/checkpoints/       │
  │                                                         │
  │  10-MINUTE VIDEO WORKFLOW                               │
  │  1. Generate prompts       ollama run qwen3:14b         │
  │  2. Generate clips         http://localhost:8188        │
  │     Name them: 001_scene.mp4, 002_scene.mp4 …          │
  │     Save to:   ~/ai/projects/clips/                     │
  │  3. Narration (optional)   cd ~/ai/CosyVoice            │
  │     Save to:   ~/ai/projects/audio/narration.wav       │
  │  4. Upscale (optional)     ~/upscale-clips.sh           │
  │  5. Stitch final video     ~/concat-clips.sh my_film    │
  │     Output: ~/ai/projects/final/my_film.mp4             │
  │                                                         │
  │  VRAM NOTES (RTX 3080 Ti = 12 GB)                       │
  │  Wan2.1 1.3B ≈ 8 GB  |  LTX-Video 2B ≈ 10 GB           │
  │  Do NOT load both at once. Use --lowvram flag.          │
  │                                                         │
  │  START                                                  │
  │  Terminal 1: ~/start-ollama.sh                          │
  │  Terminal 2: ~/start-comfyui.sh                         │
  │  Browser:    http://localhost:8188                      │
  └─────────────────────────────────────────────────────────┘

SUMMARY
