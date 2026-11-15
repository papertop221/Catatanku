# Aturan R8 untuk build rilis.

# LiteRT-LM dipanggil dari kode native (JNI) lewat nama kelas, metode, dan kolomnya. R8 tidak bisa melihat
# pemakaian itu, jadi seluruh paketnya dipertahankan apa adanya.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Diperlukan pustaka yang membaca tipe generik atau anotasi saat berjalan (Gson, kotlin-reflect di dalam LiteRT-LM).
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# Nama kelas di jejak galat tetap terbaca; ukuran APK nyaris tidak terpengaruh.
-keepattributes SourceFile,LineNumberTable
-dontobfuscate
