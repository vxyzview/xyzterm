# ==========================================
# Stage 1: Build Environment
# ==========================================
FROM eclipse-temurin:21-jdk AS builder

# Set environment variables for Android SDK
ENV ANDROID_HOME=/opt/android-sdk
ENV PATH=${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools

# Install system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    git \
    wget \
    curl \
    ca-certificates \
    unzip \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# Download and install Android SDK command-line tools
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip -O /tmp/cmdline-tools.zip && \
    unzip -q /tmp/cmdline-tools.zip -d /tmp && \
    mv /tmp/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    rm /tmp/cmdline-tools.zip

# Accept Android SDK licenses and install the NDK used by the Rust JNI build
RUN yes | sdkmanager --licenses || true && sdkmanager "ndk;30.0.16248370"

# Rust toolchain pinned by rust/rust-toolchain.toml (1.97.0)
ENV RUSTUP_HOME=/usr/local/rustup
ENV CARGO_HOME=/usr/local/cargo
ENV PATH=${PATH}:${CARGO_HOME}/bin
ENV ANDROID_NDK_HOME=${ANDROID_HOME}/ndk/30.0.16248370
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain 1.97.0 && \
    rustup target add aarch64-linux-android armv7-linux-androideabi

# Set the working directory
WORKDIR /app

# Copy the project files
# Note: Ensure submodules are checked out on host before building:
#   git submodule update --init --recursive
COPY . .

# Set up build arguments for release signing (optional)
# BUILD_TASK can be assembleDebug, assembleRelease, etc.
ARG BUILD_TASK=assembleDebug
ARG RELEASE_KEYSTORE_BASE64=""
ARG RELEASE_PROPERTIES_BASE64=""

# Set env to match the signing logic in app/build.gradle.kts
ENV GITHUB_ACTIONS=true

# Decode credentials (if provided), ensure submodules are present, and run the Gradle build task
RUN if [ -n "$RELEASE_KEYSTORE_BASE64" ] && [ -n "$RELEASE_PROPERTIES_BASE64" ]; then \
        echo "Decoding release keys..." && \
        echo "$RELEASE_KEYSTORE_BASE64" | base64 -d > /tmp/xed.keystore && \
        echo "$RELEASE_PROPERTIES_BASE64" | base64 -d > /tmp/signing.properties; \
    fi && \
    chmod +x gradlew && \
    ./gradlew ${BUILD_TASK} --no-daemon

# ==========================================
# Stage 2: Export Built APKs (BuildKit friendly)
# ==========================================
FROM scratch AS export-stage
COPY --from=builder /app/app/build/outputs/apk/ /
