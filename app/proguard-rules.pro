# Vector / libxposed 模块：入口类必须保留
-keep class com.yytest.ksimmerse.ImmerseModule { public <init>(); }
-keep class * extends io.github.libxposed.api.XposedModule { public <init>(); }

-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
