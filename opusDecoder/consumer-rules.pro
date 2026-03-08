# Proguard rules specific to the Opus extension.

# This prevents the names of native methods from being obfuscated.
-keep,includedescriptorclasses class androidx.media3.decoder.opus.** {
    native <methods>;
}

# Some members of this class are being accessed from native methods. Keep them unobfuscated.
-keep class androidx.media3.decoder.SimpleDecoderOutputBuffer {
    *;
}
