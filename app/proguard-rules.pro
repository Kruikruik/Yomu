-dontobfuscate
# Dépendances optionnelles d'OkHttp absentes sur Android
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# jsoup : dépendance optionnelle absente
-dontwarn com.google.re2j.**

# ---------------------------------------------------------------------------
# Extensions Tachiyomi/Mihon : leur code appelle ces classes par leur nom.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keep class eu.kanade.tachiyomi.** { *; }
-keep class tachiyomi.core.** { *; }
-keep class mihon.core.** { *; }
-keep,allowshrinking class * extends uy.kohesive.injekt.api.FullTypeReference
-keep,allowoptimization class androidx.preference.** { public protected *; }
-keep,allowoptimization class kotlin.** { public protected *; }
-keep,allowoptimization class kotlinx.coroutines.** { public protected *; }
-keep,allowoptimization class kotlinx.serialization.** { public protected *; }
-keep,allowoptimization class okhttp3.** { public protected *; }
-keep,allowoptimization class okio.** { public protected *; }
-keep,allowoptimization class org.jsoup.** { public protected *; }
-keep,allowoptimization class rx.** { public protected *; }
-keep,allowoptimization class uy.kohesive.injekt.** { public protected *; }
-keepclassmembers class * implements java.io.Serializable {
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
-dontwarn sun.misc.**
-dontwarn rx.internal.util.unsafe.**
-dontwarn okhttp3.internal.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.jspecify.annotations.**
