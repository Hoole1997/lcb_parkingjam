# ==================== 崩溃定位 ====================

# 保留行号供 Crashlytics mapping 还原，同时隐藏真实源码文件名。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ==================== 应用入口与渠道 SDK ====================

# LcbApp 由 Manifest 创建，并覆写两个渠道 Launcher SDK 的 pre-R8 方法；渠道 SDK 还会读取
# 这些覆写成员，因此应用入口保持原类名和成员名。其余 Activity 由 AAPT 自动生成精确规则。
-keep class com.example.lcb.app.LcbApp { *; }

# ==================== Gson 持久化协议 ====================

# Gson 通过反射读取字段和泛型签名。只保留显式声明为 JSON 协议字段的成员；业务类、UI、
# Compose 与广告渲染器仍允许 R8 正常裁剪、优化和混淆。
-keepattributes Signature,*Annotation*
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# 第三方广告、Firebase、Glide、协程等依赖均已携带各自 consumer rules，不在应用层重复
# keep 整个 SDK，避免显著增大包体和削弱 R8 优化。
