# Gson reads these private/internal DTO fields reflectively. Keep only the persistence/content
# boundary classes; Domain and UI code remain fully optimizable.
-keepattributes Signature,*Annotation*
-keep class com.example.lcb.parking.data.level.**Dto { <fields>; }
-keep class com.example.lcb.parking.data.state.**Dto { <fields>; }
