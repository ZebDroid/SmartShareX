# Add any ProGuard configurations specific to this extension here.

# javac on JDK 9+ compiles "a" + b string concatenation using
# invokedynamic/StringConcatFactory by default, which doesn't exist in
# android.jar and isn't desugared by Rush's ProGuard step. Suppress the
# unresolved-reference warning rather than failing the build on it —
# the generated code still runs fine on-device via D8 desugaring at the
# app-build stage.
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn java.lang.invoke.**

-keep public class com.zebdroid.smartsharex.SmartShareX {
    public *;
}

# ShareReceiverActivity is referenced only from the manifest, never from
# Java code that R8 can see, and it's started via reflection-like
# PackageManager component-enable calls too — keep it and its name intact.
-keep public class com.zebdroid.smartsharex.ShareReceiverActivity {
    public *;
}

-keeppackagenames gnu.kawa**, gnu.expr**

-optimizationpasses 4
-allowaccessmodification
-mergeinterfacesaggressively

-repackageclasses 'com/zebdroid/smartsharex/repack'
-flattenpackagehierarchy
-dontpreverify
