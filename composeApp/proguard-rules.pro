# R8 rules for the release build.
#
# Deliberately almost empty. Everything this app depends on — the Compose runtime, the Compose
# compiler's generated code, androidx.activity — ships its own `consumer-rules.pro` inside its
# artifact, so R8 already knows what to keep. The app itself uses no reflection, no serialisation
# and no JNI, which is what makes that enough: nothing here is reached by name at runtime, so
# nothing here needs naming.
#
# A rule added below should say which class is reached reflectively and by what, or it is a rule
# nobody will dare delete later.

# The entry point named by the manifest as a string, not a reference. AGP generates a keep rule
# for it from the merged manifest, so this is belt-and-braces — and the one place where losing the
# class would be a launch crash rather than a missing feature.
-keep class cx.m42.unitool.MainActivity { <init>(); }

# Line numbers survive obfuscation so a Play Console stack trace can be read. The source file name
# itself is renamed to a constant, which gives away nothing and keeps the mapping file the only
# way to resolve a trace.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
