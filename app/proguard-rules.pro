# kotlinx.serialization models are handled by the Kotlin serialization plugin.
# Keep generic signatures and annotations used by serializers and OkHttp.
-keepattributes Signature,*Annotation*
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
