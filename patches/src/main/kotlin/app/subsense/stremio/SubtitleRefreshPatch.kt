package app.subsense.stremio

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

private const val EXTENSION = "Lapp/subsense/stremio/SubtitleRefresh;"

val STREMIO_TV = Compatibility(
    name = "Stremio",
    packageName = "com.stremio.one",
    apkFileType = ApkFileType.APK,
    appIconColor = 0x5B67F1,
    // Tested on Android TV 1.11.3 and the phone app 2.4.0 (same package, same Media3 player); the hooks are Media3 library
    // methods, so later releases are accepted and checked when patching (a release where a hook no longer matches fails
    // the patch instead of shipping a broken app).
    targets = listOf(AppTarget(version = null, description = "Stremio for Android TV (tested: 1.11.3) and phones (tested: 2.4.0)")),
)

/**
 * Media3 PlayerView.setPlayer(Player): Stremio's player wrapper calls it with itself when the video view is attached.
 * A library class, so the name does not depend on Stremio's own code.
 */
object PlayerViewSetPlayerFingerprint : Fingerprint(
    definingClass = "Landroidx/media3/ui/PlayerView;",
    name = "setPlayer",
    parameters = listOf("Landroidx/media3/common/Player;"),
)

/** Media3 SubtitleView.setCues(List): every subtitle line on its way to the screen passes here. */
object SubtitleViewSetCuesFingerprint : Fingerprint(
    definingClass = "Landroidx/media3/ui/SubtitleView;",
    name = "setCues",
    parameters = listOf("Ljava/util/List;"),
)

@Suppress("unused")
val subtitleRefreshPatch = bytecodePatch(
    name = "Subtitle auto-refresh",
    description = "When a SubSense entry that is still processing is selected, its finished subtitle replaces it " +
        "during playback with no reload, and a toast says so.",
    default = true,
) {
    compatibleWith(STREMIO_TV)
    extendWith("extensions/extension.mpe")

    execute {
        PlayerViewSetPlayerFingerprint.method.addInstructions(
            0,
            "invoke-static {p0, p1}, $EXTENSION->onPlayerSet(Landroid/view/View;Landroidx/media3/common/Player;)V",
        )
        SubtitleViewSetCuesFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p0, p1}, $EXTENSION->onSetCues(Landroid/view/View;Ljava/util/List;)Ljava/util/List;
                move-result-object p1
            """,
        )
    }
}

@Suppress("unused")
val rtlLinesPatch = bytecodePatch(
    name = "Right-to-left subtitle lines",
    description = "Arabic and Hebrew subtitle lines are laid out right to left, so their punctuation shows on the " +
        "right side (Stremio forces every line left to right).",
    default = true,
) {
    compatibleWith(STREMIO_TV)
    extendWith("extensions/extension.mpe")

    execute {
        SubtitleViewSetCuesFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p1}, Lapp/subsense/stremio/RtlLines;->fix(Ljava/util/List;)Ljava/util/List;
                move-result-object p1
            """,
        )
    }
}

@Suppress("unused")
val appNamePatch = resourcePatch(
    name = "Test app name",
    description = "Names the app \"Stremio Subs\" so the test build is told apart from the original on the launcher.",
    default = true,
) {
    compatibleWith(STREMIO_TV)

    execute {
        document("res/values/strings.xml").use { document ->
            val children = document.documentElement.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i) as? Element ?: continue
                if (node.tagName == "string" && node.getAttribute("name") == "app_name") node.textContent = "Stremio Subs"
            }
        }
    }
}
