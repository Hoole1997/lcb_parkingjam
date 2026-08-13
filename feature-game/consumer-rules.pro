# ==================== WebView JavaScript Bridge ====================

# WebView 通过注解和方法名反射调用 JavaScript Bridge。仅保护 feature-game 包中带
# @JavascriptInterface 的成员；Bridge 类本身允许改名，Compose/View 代码仍可完整优化。
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class com.example.lcb.parking.feature.game.** {
    @android.webkit.JavascriptInterface <methods>;
}

# Android Manifest 与 XML 中的组件/自定义 View 构造器由 AAPT 自动生成精确 keep 规则，
# Compose 和 kotlinx.coroutines 依赖也自带 consumer rules，无需保留整个框架包。
