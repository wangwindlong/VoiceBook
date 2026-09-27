# sherpa-onnx JNI:libsherpa-onnx-jni.so 通过反射回调 com.k2fsa.sherpa.onnx 的
# Kotlin 类(native 侧按名字查找字段/方法),R8 不可重命名、不可删成员。
# 类来源:voice/libs/sherpa-classes.jar(本地文件依赖,无自带 consumer rules)
-keep class com.k2fsa.sherpa.onnx.** { *; }
