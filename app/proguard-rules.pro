# KIKU R8/ProGuard 규칙

# ── kotlinx.serialization ──────────────────────────────────────
# @Serializable 모델과 생성된 serializer를 보존해야 JSON 파싱이 안 깨진다.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 우리 모델(com.bradlab.kiku.*)의 @Serializable + $$serializer 보존
-keep,includedescriptorclasses class com.bradlab.kiku.**$$serializer { *; }
-keepclassmembers class com.bradlab.kiku.** {
    *** Companion;
}
-keepclasseswithmembers class com.bradlab.kiku.** {
    kotlinx.serialization.KSerializer serializer(...);
}
