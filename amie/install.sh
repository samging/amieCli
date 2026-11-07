#!/bin/sh
# Installs the amie interpreter so that amie runs from any shell: the built distribution goes to PREFIX/lib/amie and
# PREFIX/bin/amie links to its bin/amie. PREFIX is ~/.local unless --prefix says otherwise; /usr/local needs sudo,
# so build as yourself first and then: sudo amie/install.sh --prefix /usr/local --no-build
#
#   install.sh [--prefix DIR] [--no-build] [--uninstall]
#
# --no-build installs what ./gradlew :amie:installDist built last; --uninstall removes the two.
set -e
here=$(cd "$(dirname "$0")" && pwd)
prefix="$HOME/.local"
build=yes
uninstall=no
while [ $# -gt 0 ]; do
    case "$1" in
        --prefix) prefix="$2"; shift ;;
        --prefix=*) prefix="${1#--prefix=}" ;;
        --no-build) build=no ;;
        --uninstall) uninstall=yes ;;
        -h|--help) sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
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
