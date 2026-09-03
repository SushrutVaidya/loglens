#!/bin/sh
# loglens installer — downloads the prebuilt binary for your platform and puts
# it on your PATH. No JVM, no build.
#
#   curl -fsSL https://raw.githubusercontent.com/SushrutVaidya/loglens/main/install.sh | sh
#
# Override with env vars:
#   VERSION   release tag to install       (default: latest)
#   BINDIR    where to install the binary   (default: /usr/local/bin)
set -eu

REPO="SushrutVaidya/loglens"
VERSION="${VERSION:-latest}"
BINDIR="${BINDIR:-/usr/local/bin}"

# --- pick the right binary for this machine --------------------------------
os="$(uname -s)"
arch="$(uname -m)"

case "$os" in
  Darwin) os="macos" ;;
  Linux)  os="linux" ;;
  *)
    echo "loglens: unsupported OS '$os'." >&2
    echo "On Windows, download loglens-windows-x64.exe from:" >&2
    echo "  https://github.com/$REPO/releases/latest" >&2
    exit 1
    ;;
esac

case "$arch" in
  arm64|aarch64) arch="arm64" ;;
  x86_64|amd64)  arch="x64" ;;
  *) echo "loglens: unsupported architecture '$arch'." >&2; exit 1 ;;
esac

asset="loglens-${os}-${arch}"

if [ "$VERSION" = "latest" ]; then
  url="https://github.com/$REPO/releases/latest/download/$asset"
else
  url="https://github.com/$REPO/releases/download/$VERSION/$asset"
fi

# --- download --------------------------------------------------------------
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

echo "loglens: downloading $asset ($VERSION)"
if command -v curl >/dev/null 2>&1; then
  curl -fsSL "$url" -o "$tmp"
elif command -v wget >/dev/null 2>&1; then
  wget -qO "$tmp" "$url"
else
  echo "loglens: need curl or wget installed." >&2
  exit 1
fi
chmod +x "$tmp"

# --- install (sudo only if BINDIR isn't writable) --------------------------
target="$BINDIR/loglens"
if [ -w "$BINDIR" ]; then
  mv "$tmp" "$target"
else
  echo "loglens: $BINDIR needs elevated permissions, using sudo"
  sudo mv "$tmp" "$target"
fi
trap - EXIT

echo "loglens: installed to $target"

# --- warn if the install dir isn't on PATH ---------------------------------
case ":$PATH:" in
  *":$BINDIR:"*) ;;
  *) echo "loglens: note — $BINDIR is not on your PATH; add it or move the binary." >&2 ;;
esac

"$target" --version
