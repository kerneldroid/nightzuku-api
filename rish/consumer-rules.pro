# JNI entry points in librish.so are resolved by name at runtime.
-keepclasseswithmembernames,includedescriptorclasses class rikka.rish.RishHost$Companion {
    native <methods>;
}

-keepclasseswithmembernames,includedescriptorclasses class rikka.rish.RishTerminal$Companion {
    native <methods>;
}
