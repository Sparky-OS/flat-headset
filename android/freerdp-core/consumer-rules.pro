# SPDX-FileCopyrightText: 2026 KDE Connect Developers
# SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL

# FreeRDP's native library resolves this class and its callbacks by their JNI names.
-keep class com.freerdp.freerdpcore.services.LibFreeRDP { *; }

# Preserve any additional JNI entry points introduced by an upstream FreeRDP update.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
