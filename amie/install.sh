#!/bin/sh
# Installs the amie interpreter so that amie runs from any shell, on a Mac or on Linux: the built distribution goes
# to PREFIX/lib/amie and PREFIX/bin/amie links to its bin/amie. PREFIX is ~/.local unless --prefix says otherwise;
# /usr/local needs sudo, so build as yourself first and then: sudo amie/install.sh --prefix /usr/local --no-build
#
#   install.sh [--prefix DIR] [--no-build] [--no-jdk] [--uninstall]
#
# amie runs on a JDK 21. Without one, install.sh gets one first: Homebrew's openjdk@21 on a Mac, the openjdk-21
# package on Debian and Ubuntu (apt, with sudo), and where neither can, Eclipse Temurin's JDK unpacked under
# PREFIX/lib/jdk-21, which needs no root. --no-jdk skips that and stops instead.
# --no-build installs what ./gradlew :amie:installDist built last; --uninstall removes the install (not the JDK).
set -e
here=$(cd "$(dirname "$0")" && pwd)
prefix="$HOME/.local"
build=yes
jdk=yes
uninstall=no
while [ $# -gt 0 ]; do
    case "$1" in
        --prefix) prefix="$2"; shift ;;
        --prefix=*) prefix="${1#--prefix=}" ;;
        --no-build) build=no ;;
        --no-jdk) jdk=no ;;
        --uninstall) uninstall=yes ;;
        -h|--help) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "install.sh: unknown option $1" >&2; echo "Try install.sh --help." >&2; exit 2 ;;
    esac
    shift
done
home="$prefix/lib/amie"
link="$prefix/bin/amie"

if [ "$uninstall" = yes ]; then
    [ -L "$link" ] && rm "$link"
    [ -f "$home/lib/amie.jar" ] && rm -r "$home"
    echo "amie is no longer installed under $prefix."
    exit 0
fi

# A JDK 21, found by jdk.sh (sourced in a subshell first, since it exits when there is none) or installed now
has_jdk() { (. "$here/jdk.sh") >/dev/null 2>&1; }

# Temurin's JDK 21 for this machine, unpacked under PREFIX/lib/jdk-21: no root needed, and jdk.sh looks there
temurin() {
    case "$(uname -m)" in
        arm64|aarch64) arch=aarch64 ;;
        x86_64|amd64) arch=x64 ;;
        *) echo "install.sh: no Temurin JDK for $(uname -m); install a JDK 21 yourself and set AMIE_JAVA_HOME." >&2; return 1 ;;
    esac
    case "$(uname -s)" in
        Darwin) os=mac ;;
        Linux) os=linux ;;
        *) echo "install.sh: no Temurin JDK for $(uname -s); install a JDK 21 yourself and set AMIE_JAVA_HOME." >&2; return 1 ;;
    esac
    command -v curl >/dev/null || { echo "install.sh: curl is needed to download a JDK; install it, or a JDK 21, first." >&2; return 1; }
    url="https://api.adoptium.net/v3/binary/latest/21/ga/$os/$arch/jdk/hotspot/normal/eclipse"
    echo "Downloading Eclipse Temurin JDK 21 for $os $arch into $prefix/lib/jdk-21..." >&2
    rm -rf "$prefix/lib/jdk-21.part"
    mkdir -p "$prefix/lib/jdk-21.part"
    curl -fsSL "$url" | tar -xz -C "$prefix/lib/jdk-21.part"
    rm -rf "$prefix/lib/jdk-21"
    mv "$prefix/lib/jdk-21.part"/jdk-21* "$prefix/lib/jdk-21"
    rm -rf "$prefix/lib/jdk-21.part"
}

install_jdk() {
    echo "amie needs a JDK 21 and none was found." >&2
    case "$(uname -s)" in
        Darwin)
            if command -v brew >/dev/null; then
                echo "Installing Homebrew's openjdk@21..." >&2
                brew install openjdk@21
            else
                temurin
            fi ;;
        Linux)
            if command -v apt-get >/dev/null && apt-cache show openjdk-21-jdk-headless >/dev/null 2>&1; then
                echo "Installing openjdk-21-jdk-headless with apt..." >&2
                if [ "$(id -u)" = 0 ]; then apt-get install -y openjdk-21-jdk-headless
                else sudo apt-get install -y openjdk-21-jdk-headless; fi
            else
                temurin
            fi ;;
        *) temurin ;;
    esac
}

if ! has_jdk; then
    if [ "$jdk" = no ]; then
        echo "install.sh: amie needs a JDK 21 and found none; without --no-jdk it would install one." >&2
        exit 2
    fi
    mkdir -p "$prefix/lib"
    install_jdk
    if ! has_jdk; then
        echo "install.sh: a JDK 21 was installed but jdk.sh still finds none; set AMIE_JAVA_HOME to its folder and run again." >&2
        exit 2
    fi
fi
. "$here/jdk.sh"
built="$here/build/install/amie"
if [ "$build" = yes ]; then
    echo "Building amie..." >&2
    (cd "$here/.." && ./gradlew -q :amie:installDist)
elif [ ! -x "$built/bin/amie" ]; then
    echo "install.sh: nothing is built yet, so --no-build has nothing to install: run it without." >&2
    exit 1
fi

mkdir -p "$prefix/lib" "$prefix/bin"
# Only what an earlier install left is replaced; another lib/amie is left alone
if [ -e "$home" ] && [ ! -f "$home/lib/amie.jar" ]; then
    echo "install.sh: $home exists and isn't an amie install; choose another --prefix or move it away." >&2
    exit 1
fi
rm -rf "$home"
cp -R "$built" "$home"
ln -sf "../lib/amie/bin/amie" "$link"

echo "Installed $("$link" --version | head -1) at $link"
case ":$PATH:" in
    *":$prefix/bin:"*) ;;
    *) echo "$prefix/bin isn't on your PATH yet: add it, in ~/.zshrc or ~/.bashrc, with" ; echo "  export PATH=\"$prefix/bin:\$PATH\"" ;;
esac
