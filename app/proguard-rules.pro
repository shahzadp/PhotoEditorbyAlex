# ProGuard / R8 rules for the release build.
#
# The goal is obfuscation, not secrecy. Nothing here can make a determined
# reverse engineer fail, because a modified APK running on their own hardware
# cannot be prevented from being read - all R8 does is raise the cost and slow
# casual inspection. What it does buy is the difference between an APK that
# hands over a readable map of the app and one that requires real work.

# ---------------------------------------------------------------------------
# kotlinx.serialization
# ---------------------------------------------------------------------------
# R8 cannot see that the generated serializer is used, because it is looked up
# reflectively by name at runtime. Without this the release build compiles fine
# and then throws SerializationException on the first project save.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.alex.photoeditor.core.**$$serializer { *; }
-keepclassmembers class com.alex.photoeditor.core.** {
    *** Companion;
}
-keepclasseswithmembers class com.alex.photoeditor.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# MediaPipe - EXCLUDED from R8 entirely.
# ---------------------------------------------------------------------------
# This is not a set of -keep rules. MediaPipe must not be processed by R8 at
# all, and the reason is not one that keep rules can express.
#
# MediaPipe ships a stack-walking validator (the class that logs "no caller
# found on the stack for: <class>"). It inspects the *real* runtime call stack
# at class-initialisation time and verifies that the caller is genuine
# MediaPipe code. It compares class names found by walking frames.
#
# R8 rewrites the bytecode: it inlines methods, merges frames and renames
# classes. The validator then walks a stack that no longer contains the name it
# is looking for, and throws IllegalStateException. Observed in the release
# build as:
#
#   java.lang.NoClassDefFoundError: com.google.mediapipe.framework.Graph
#     Caused by: IllegalStateException: no caller found on the stack for: o5.c
#       at com.google.mediapipe.framework.Graph.<clinit>
#
# Keeping the class names does NOT help, because the problem is frames that R8
# merged or inlined away, not names it renamed.
#
# The fix is to disable R8's *optimiser* for the whole app while leaving
# obfuscation and shrinking on. Inlining is what breaks the validator, and
# -dontoptimize is a global directive - R8 has no per-class equivalent. That is
# a real cost, so it is stated plainly here:
#
#   - OBFUSCATION stays ON. Class, method and field names in this app's own
#     code are still renamed, which is the entire point of the release build.
#   - SHRINKING stays ON. Unused classes and methods are still removed, which
#     is most of the size win.
#   - OPTIMISATION is OFF. R8 will no longer inline, merge or devirtualise.
#     The APK gets slightly larger and a little slower to run.
#
# MediaPipe's own ~8 MB keeps its original names either way: it is third-party
# code, and leaving it readable reveals nothing about this app's design.
-dontoptimize

# Keeps MediaPipe's names and members, so the graph bundle and its protobuf
# messages resolve by name at runtime.
-keep class com.google.mediapipe.** { *; }
-keep interface com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# The graph definition inside the .task bundle is deserialised into protobuf
# classes by native code, by name. MediaPipe ships a shaded protobuf runtime,
# so the keep above has to cover the whole namespace rather than a package
# this project can name explicitly.
-keep class com.google.mediapipe.tasks.**.proto.** { *; }
-keepclassmembers enum com.google.mediapipe.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclasseswithmembernames,includedescriptorclasses class com.google.mediapipe.** {
    native <methods>;
}
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod

# ---------------------------------------------------------------------------
# Compose
# ---------------------------------------------------------------------------
# Compose is already obfuscated correctly by the R8 rules shipped in its AAR,
# so it is left alone here. Only the project's own reflection surface is pinned.

# ---------------------------------------------------------------------------
# The app's own model classes
# ---------------------------------------------------------------------------
# Edit state is written to disk as JSON by field *name*. Obfuscating a property
# would rename it, and every project saved by a previous build would fail to
# restore. This is a genuine data-format constraint rather than a shortcut: the
# serial names are part of the on-disk contract.
-keepclassmembers class com.alex.photoeditor.core.** {
    <fields>;
    <init>(...);
}

# ---------------------------------------------------------------------------
# Diagnostics
# ---------------------------------------------------------------------------
# Line numbers are the single most useful thing when reading a crash from a
# release build. Source file and the original file name add nothing an attacker
# does not already have, so they are dropped to keep the mapping file small.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile