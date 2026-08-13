# ==================== Gson 数据边界 ====================

# Gson 反射读取私有 DTO 字段及其泛型参数。DTO 类名不属于磁盘协议，可以继续混淆；字段名
# 必须保留，因为部分同名 JSON 字段刻意未重复声明 @SerializedName。
-keepattributes Signature,*Annotation*
-keep,allowoptimization,allowobfuscation class com.example.lcb.parking.data.level.**Dto
-keepclassmembers class com.example.lcb.parking.data.level.**Dto {
    <fields>;
}
-keep,allowoptimization,allowobfuscation class com.example.lcb.parking.data.state.**Dto
-keepclassmembers class com.example.lcb.parking.data.state.**Dto {
    <fields>;
}

# game-data 显式使用 DTO 隔离磁盘格式，Domain 类型不经 Gson 反射，因此无需保留
# com.example.lcb.parking.domain 下的模型或接口。
