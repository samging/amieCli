# amie runs on a JDK 21. Sourced by a sh script, this finds one and exports JAVA_HOME to it: AMIE_JAVA_HOME, JAVA_HOME
# when it already is one, the Mac's java_home, Homebrew's openjdk@21, the Temurin install.sh unpacks under ~/.local/lib
# or /usr/local/lib, sdkman's, /usr/lib/jvm, one Gradle downloaded; else the java on the PATH when that is a 21. Gradle splices it into the built bin/amie; ./amie at the repository root
# sources it. Finding none, it says so and exits with 2.
amie_is21() { [ -n "$1" ] && [ -x "$1/bin/java" ] && "$1/bin/java" -version 2>&1 | head -1 | grep -q '"21[.\"]'; }
amie_jdk=""
for amie_candidate in "$AMIE_JAVA_HOME" "$JAVA_HOME" "$(/usr/libexec/java_home -v 21 2>/dev/null)" \
        /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
        /home/linuxbrew/.linuxbrew/opt/openjdk@21 "$HOME"/.local/lib/jdk-21/Contents/Home "$HOME"/.local/lib/jdk-21 \
        /usr/local/lib/jdk-21/Contents/Home /usr/local/lib/jdk-21 "$HOME"/.sdkman/candidates/java/21* /usr/lib/jvm/*21* \
        "$HOME"/.gradle/jdks/*/Contents/Home "$HOME"/.gradle/jdks/*; do
    if amie_is21 "$amie_candidate"; then amie_jdk="$amie_candidate"; break; fi
done
if [ -n "$amie_jdk" ]; then
    JAVA_HOME="$amie_jdk"; export JAVA_HOME
elif java -version 2>&1 | head -1 | grep -q '"21[.\"]'; then
    unset JAVA_HOME
else
    echo "amie needs a JDK 21 and found none: install one (Temurin, Homebrew's openjdk@21, sdkman's 21-tem, ...) or set AMIE_JAVA_HOME to its folder." >&2
    exit 2
fi
