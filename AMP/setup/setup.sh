#!/bin/bash
# Sets up Amie's data folder, AMP: the models, the package jars, the voice scripts and the Talk to Amie template.
# Run it once, from anywhere, and again whenever you like: it resumes downloads and skips what is there.
# About 4 GB of free ONNX models: the BERT models intents are built on, Whisper small.en, the Supertonic voice and
# Llama 3.2 3B. AMIE_QWEN=1 also downloads Qwen2.5 1.5B, for faster, simpler answers.
set -e
# This file is AMP/setup/setup.sh of the repository; the voice files are in its voice/, the jars in a jar-jar beside it
repo="$(cd "$(dirname "$0")/../.." && pwd)"
amp="${AMIE_AMP_DIR:-$HOME/Library/Application Support/amie pilot/AMP}"
# Every model goes under models/: bert, speech, text. A model still in the folder the app used before November 2026
# (pluginDir/config/speech/models, textModels) is left there: the app moves it into models/ when it next starts
speech="$amp/models/speech"
text="$amp/models/text"
old_speech="$amp/pluginDir/config/speech/models"
old_text="$amp/textModels"
mkdir -p "$amp/voice" "$amp/templates" "$amp/pluginDir"
cp "$repo"/voice/scripts/*.sh "$amp/voice/"
cp "$repo/voice/voice-actions.csv" "$amp/"
cp "$repo/voice/talk-to-amie.kts" "$repo/voice/talk-to-amie.json" "$amp/templates/"

# Amie fetches speech.jar and microphone.jar by themselves when the script first opens them; a checkout next to this one is used first
for jar in speech microphone mail browser mac memory face files spotify; do
    [ -f "$repo/../jar-jar/$jar.jar" ] && cp "$repo/../jar-jar/$jar.jar" "$amp/pluginDir/"
done

hf() { # repository, folder
    mkdir -p "$2"
    curl -s "https://huggingface.co/api/models/$1/tree/main?recursive=true" |
        awk '{ gsub(/\{"type":/, "\n&"); print }' | grep '"type":"file"' | sed -E 's/.*"path":"([^"]*)".*/\1/' | grep -v -E '(\.py|\.md|\.gitattributes|\.lock|\.yaml|\.toml)$|^benchmarks/|^test_wavs/|-(en|de)coder\.onnx$|^predictions\.json$' |
        while read -r file; do
            mkdir -p "$2/$(dirname "$file")"
            curl -fL -C - --retry 5 -o "$2/$file" "https://huggingface.co/$1/resolve/main/$file"
        done
}
get() { # repository, file, folder
    mkdir -p "$3"
    curl -fL -C - --retry 5 -o "$3/$(basename "$2")" "https://huggingface.co/$1/resolve/main/$2"
}
bert="$amp/models/bert"
for file in model.onnx tokenizer.json config.json special_tokens_map.json tokenizer_config.json vocab.txt added_tokens.json; do
    get dslim/bert-base-NER "onnx/$file" "$bert"
done
for file in onnx/model.onnx tokenizer.json config.json special_tokens_map.json tokenizer_config.json vocab.txt; do
    get BAAI/bge-base-en-v1.5 "$file" "$bert/bge"
done

[ -d "$old_speech/whisper-small.en" ] || hf csukuangfj/sherpa-onnx-whisper-small.en "$speech/whisper-small.en"
[ -d "$old_text/llama3.2-3b" ] || hf Arm/llama-3-2-3b-instruct-onnx-genai-int4-kquantlast-emb-int8 "$text/llama3.2-3b"
if [ "${AMIE_QWEN:-}" = 1 ]; then [ -d "$old_text/qwen2.5-1.5b" ] || hf Arm/qwen2-5-1-5b-instruct-onnx-genai-int4-kquantlast-emb-int4 "$text/qwen2.5-1.5b"; fi
voice=sherpa-onnx-supertonic-3-tts-int8-2026-05-11
if [ ! -d "$speech/$voice" ] && [ ! -d "$old_speech/$voice" ]; then mkdir -p "$speech" && curl -fL "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$voice.tar.bz2" | tar -xj -C "$speech"; fi
echo "Done: AMP is set up in $amp. In Amie: Templates > Talk to Amie > Load, then Run."
