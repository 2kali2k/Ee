# Ee — app-level ProGuard/R8 rules.
# M0 ships unminified; rules accumulate here per module as release hardening
# lands (M5). Keep the VFS SPI entry points when minification is enabled:
#
# -keep class app.ee.core.fs.FsProvider { *; }
# -keepclassmembers class app.ee.core.fs.** { *; }
