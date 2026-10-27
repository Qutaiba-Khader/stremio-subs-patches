extension {
    name = "extensions/extension.mpe"
}

android {
    namespace = "app.subsense.stremio"
    // the app's own minSdk (24): kept from when the extension implemented Media3's listener interface itself
    defaultConfig {
        minSdk = 24
    }
}

// No Media3 dependency (0.6.0): Stremio 1.12.0 shrinks and renames Media3, so the extension never names a Media3 class.
// It reaches Media3 only through M3, whose stand-in methods the patch replaces with the app's own members.
