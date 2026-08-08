# Keep data classes used in JSON serialization (Settings.toBlob/fromBlob,
# JsonLists, and all model classes) — org.json uses field names directly.
-keep class com.localagenda.android.data.** { *; }

# Keep org.json classes (Maven artifact, not platform stub)
-keep class org.json.** { *; }

# Keep Parcelable/serializable if any
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# Regras padrão do R8/ProGuard. O app não usa libs nativas nem reflection
# além das do framework — as regras padrão do Android bastam. O org.json é o
# da plataforma (android.jar), não precisa de keep.
