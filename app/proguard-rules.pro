# Media3 ships its own consumer ProGuard rules; nothing app-specific to keep yet.

# AutoValue's annotation processor ships inside a runtime dependency
# (MediaPipe's), and its shaded JavaPoet names javax.lang.model, which only
# exists in a compiler. Never loaded on a phone; without these the release
# build stopped at R8 with "Missing class javax.lang.model.SourceVersion".
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**
