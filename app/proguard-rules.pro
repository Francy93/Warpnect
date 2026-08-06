# Phase 0 keeps the app unminified by default. These keep rules preserve the
# future Shizuku bridge surface when release builds enable shrinking.
-keep class rikka.shizuku.** { *; }
