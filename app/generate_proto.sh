#!/bin/bash
# Generate Kotlin protobuf files for Android

set -e

PROTO_DIR="../metroproto"
OUT_DIR="src/main/java"

if [ ! -f "$PROTO_DIR/listentogether.proto" ]; then
    echo "Missing proto file at $PROTO_DIR/listentogether.proto"
    echo "Did you initialize submodules? Try: git submodule update --init --recursive"
    exit 1
fi

# Create output directory if it doesn't exist
mkdir -p "$OUT_DIR"

# Generate Java and Kotlin code (lite version for Android)
protoc --java_out=lite:"$OUT_DIR" --kotlin_out="$OUT_DIR" \
    -I="$PROTO_DIR" \
    "$PROTO_DIR/listentogether.proto"

echo "Protobuf files (lite) generated successfully in $OUT_DIR"