extension {
    name = "extensions/extension.mpe"
}

android {
    namespace = "app.subsense.stremio"
    // the app's own minSdk: below 24, D8 rewrites Media3's default interface methods into "$-CC" helper classes
    // that exist only in our build, and the first player event crashes the app
    defaultConfig {
        minSdk = 24
    }
}

dependencies {
    // compile-only: the real classes are Stremio's own copy of Media3 at runtime
    compileOnly("androidx.media3:media3-common:1.8.0")
    compileOnly("androidx.media3:media3-ui:1.8.0")
}
