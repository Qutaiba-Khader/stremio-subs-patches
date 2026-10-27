package app.subsense.stremio

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.w3c.dom.Element

private const val EXTENSION = "Lapp/subsense/stremio/SubtitleRefresh;"
private const val BRIDGE = "Lapp/subsense/stremio/M3;"
private const val SUBTITLE_VIEW = "Landroidx/media3/ui/SubtitleView;"
private const val URI = "Landroid/net/Uri;"
private const val STRING = "Ljava/lang/String;"

val STREMIO_TV = Compatibility(
    name = "Stremio",
    packageName = "com.stremio.one",
    apkFileType = ApkFileType.APK,
    appIconColor = 0x5B67F1,
    // Tested on Android TV 1.11.3 and 1.12.0 and the phone app 2.4.0 (same package, same Media3 player). 1.12.0 shrinks and
    // renames Media3, so every Media3 member is found by its structure, never its name; a release where one is not found
    // fails the patch instead of shipping a broken app.
    targets = listOf(AppTarget(version = null, description = "Stremio for Android TV (tested: 1.11.3, 1.12.0) and phones (tested: 2.4.0)")),
)

/** Media3 SubtitleView.setCues(List): the class and the method keep their names (the view is named in Media3's layouts). */
object SubtitleViewSetCuesFingerprint : Fingerprint(
    definingClass = SUBTITLE_VIEW,
    name = "setCues",
    parameters = listOf("Ljava/util/List;"),
)

/**
 * Media3 PlayerView.setPlayer(Player): Stremio's player calls it with itself when the video view is attached. PlayerView is
 * renamed in a shrunk app: it is the class with getSubtitleView() returning the SubtitleView and a one-argument setPlayer.
 */
object PlayerViewSetPlayerFingerprint : Fingerprint(
    name = "setPlayer",
    returnType = "V",
    custom = { method, classDef ->
        method.parameterTypes.size == 1 && classDef.methods.any {
            it.name == "getSubtitleView" && it.returnType == SUBTITLE_VIEW && it.parameterTypes.isEmpty()
        }
    },
)

/** The app's Media3 members the extension uses, found by structure (see each `find…`). */
private class Media3(
    val playerView: String,
    val player: String,
    val playerIsInterface: Boolean,
    val listener: String,
    val addListener: MethodReference,
    val removeListener: MethodReference,
    val mediaItem: MethodReference,
    val localConfig: String,
    val subtitleConfig: String,
    val subtitleConfigUri: FieldReference,
    val tracks: MethodReference,
    val trackGroup: String,
    val formatId: FieldReference,
    val cue: String,
    val textRenderer: String,
)

private fun ClassDef.instanceFieldTypes() = fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.map { it.type }

private fun Method.refs() = implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.let { r -> it.opcode to r.reference } } ?: emptyList()

private fun BytecodePatchContext.single(what: String, test: (ClassDef) -> Boolean): ClassDef {
    val found = mutableListOf<ClassDef>()
    classDefForEach { if (test(it)) found += it }
    if (found.size != 1) throw PatchException("Media3 $what: ${found.size} candidates (${found.take(5).joinToString { it.type }})")
    return found[0]
}

private fun BytecodePatchContext.findMedia3(): Media3 {
    val setPlayer = PlayerViewSetPlayerFingerprint.originalMethod
    val playerView = setPlayer.definingClass
    val player = setPlayer.parameterTypes[0].toString()
    val playerDef = classDefBy(player)
    val playerIsInterface = AccessFlags.INTERFACE.isSet(playerDef.accessFlags)
    for (m in listOf("getCurrentPosition" to "J", "isPlaying" to "Z")) {
        if (playerDef.methods.none { it.name == m.first && it.returnType == m.second && it.parameterTypes.isEmpty() }) {
            throw PatchException("Media3 Player.${m.first} not found on $player")
        }
    }

    // add/removeListener: PlayerView.setPlayer takes its listener off the old player first and puts it on the new one last
    // (Player.removeListener / addListener) — the calls on the Player whose one argument is an interface PlayerView's own
    // listener field implements
    val listenerTypes = classDefBy(playerView).instanceFieldTypes().mapNotNull { classDefByOrNull(it) }.flatMap { it.interfaces }.toSet()
    val calls = setPlayer.refs().map { it.second }.filterIsInstance<MethodReference>()
        .filter { it.definingClass == player && it.parameterTypes.size == 1 && it.returnType == "V" && it.parameterTypes[0].toString() in listenerTypes }
    if (calls.size < 2 || calls.first().name == calls.last().name) throw PatchException("Media3 Player.add/removeListener not found in PlayerView.setPlayer")
    val listener = calls.first().parameterTypes[0].toString()

    // SubtitleConfiguration: one Uri, four Strings, two ints (uri, mimeType, language, label, id, selection and role flags),
    // all final (its Builder holds the same, not final)
    val sc = single("MediaItem.SubtitleConfiguration") { c ->
        val f = c.instanceFieldTypes()
        f.size == 7 && f.count { it == URI } == 1 && f.count { it == STRING } == 4 && f.count { it == "I" } == 2 &&
            c.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.all { AccessFlags.FINAL.isSet(it.accessFlags) }
    }
    val scUri = sc.fields.first { it.type == URI && !AccessFlags.STATIC.isSet(it.accessFlags) }
    // LocalConfiguration: one Uri, and its constructor goes through the SubtitleConfigurations
    val lc = single("MediaItem.LocalConfiguration") { c ->
        c.type != sc.type && c.instanceFieldTypes().count { it == URI } == 1 && c.methods.any { m ->
            m.name == "<init>" && m.refs().any { it.first == Opcode.CHECK_CAST && (it.second as TypeReference).type == sc.type }
        }
    }
    // getCurrentMediaItem: the Player's no-argument method returning the class that holds a LocalConfiguration
    val mediaItem = playerDef.methods.filter { m ->
        m.parameterTypes.isEmpty() && classDefByOrNull(m.returnType)?.instanceFieldTypes()?.contains(lc.type) == true
    }.singleOrNull() ?: throw PatchException("Media3 Player.getCurrentMediaItem not found")

    // Tracks.Group(TrackGroup, boolean, int[], boolean[]); getCurrentTracks: the Player's no-argument method returning the
    // class that casts to Tracks.Group
    val group = single("Tracks.Group") { c ->
        c.methods.any { it.name == "<init>" && it.parameterTypes.map(CharSequence::toString).let { p -> p.size == 4 && p[1] == "Z" && p[2] == "[I" && p[3] == "[Z" } }
    }
    val trackGroup = group.methods.first { it.name == "<init>" && it.parameterTypes.size == 4 }.parameterTypes[0].toString()
    val tracks = playerDef.methods.filter { m ->
        m.parameterTypes.isEmpty() && classDefByOrNull(m.returnType)?.methods?.any { x ->
            x.refs().any { it.first == Opcode.CHECK_CAST && (it.second as TypeReference).type == group.type }
        } == true
    }.singleOrNull() ?: throw PatchException("Media3 Player.getCurrentTracks not found")
    // Format: TrackGroup's array; Format.id: the first String toString() reads ("Format(" + id + ", " + label …)
    val format = classDefBy(trackGroup).instanceFieldTypes().singleOrNull { it.startsWith("[L") }?.substring(1)
        ?: throw PatchException("Media3 TrackGroup.formats not found")
    val formatId = classDefBy(format).methods.firstOrNull { it.name == "toString" && it.parameterTypes.isEmpty() }?.refs()
        ?.map { it.second }?.filterIsInstance<FieldReference>()?.firstOrNull { it.definingClass == format && it.type == STRING }
        ?: throw PatchException("Media3 Format.id not found")

    // Cue: its constructor starts (CharSequence text, Alignment, Alignment, Bitmap, …)
    val cue = single("Cue") { c ->
        c.methods.any { m ->
            m.name == "<init>" && m.parameterTypes.map(CharSequence::toString).take(4) ==
                listOf("Ljava/lang/CharSequence;", "Landroid/text/Layout\$Alignment;", "Landroid/text/Layout\$Alignment;", "Landroid/graphics/Bitmap;")
        }
    }
    // TextRenderer: the class with Media3's "Subtitle decoding failed" message
    val textRenderer = classDefByStrings("Subtitle decoding failed. streamFormat=").map { it.type }.distinct().singleOrNull()
        ?: throw PatchException("Media3 TextRenderer not found")

    return Media3(
        playerView, player, playerIsInterface, listener, calls.last(), calls.first(), mediaItem, lc.type, sc.type, scUri,
        tracks, trackGroup, formatId, cue.type, textRenderer,
    )
}

/** Replaces the stand-in M3.<name> with `smali` (static, `registers` in all, the parameters last). */
private fun BytecodePatchContext.writeBridge(name: String, registers: Int, smali: String) {
    val bridge = mutableClassDefBy(BRIDGE)
    val stub = bridge.methods.first { it.name == name }
    bridge.methods.remove(stub)
    bridge.methods.add(
        ImmutableMethod(
            bridge.type, name, stub.parameters.map { ImmutableMethodParameter(it.type, null, null) }, stub.returnType,
            stub.accessFlags, null, null, MutableMethodImplementation(registers),
        ).toMutable().apply { addInstructions(0, smali) },
    )
}

/** The extension's door to Media3 (M3), written for this app. Shared by the patches below. */
private val media3BridgePatch = bytecodePatch {
    extendWith("extensions/extension.mpe")

    execute {
        val m = findMedia3()
        val invoke = if (m.playerIsInterface) "invoke-interface" else "invoke-virtual"
        val p = m.player
        fun ref(r: MethodReference) = "${r.definingClass}->${r.name}(${r.parameterTypes.joinToString("")})${r.returnType}"
        writeBridge("position", 3, "check-cast p0, $p\n$invoke {p0}, $p->getCurrentPosition()J\nmove-result-wide v0\nreturn-wide v0")
        writeBridge("playing", 2, "check-cast p0, $p\n$invoke {p0}, $p->isPlaying()Z\nmove-result v0\nreturn v0")
        writeBridge("addListener", 2, "check-cast p0, $p\ncheck-cast p1, ${m.listener}\n$invoke {p0, p1}, ${ref(m.addListener)}\nreturn-void")
        writeBridge("removeListener", 2, "check-cast p0, $p\ncheck-cast p1, ${m.listener}\n$invoke {p0, p1}, ${ref(m.removeListener)}\nreturn-void")
        writeBridge("mediaItem", 2, "check-cast p0, $p\n$invoke {p0}, ${ref(m.mediaItem)}\nmove-result-object v0\nreturn-object v0")
        writeBridge("tracks", 2, "check-cast p0, $p\n$invoke {p0}, ${ref(m.tracks)}\nmove-result-object v0\nreturn-object v0")
        writeBridge("formatId", 2, "check-cast p0, ${m.formatId.definingClass}\niget-object v0, p0, ${m.formatId.definingClass}->${m.formatId.name}:$STRING\nreturn-object v0")
        writeBridge("subtitleViewOf", 2, "check-cast p0, ${m.playerView}\ninvoke-virtual {p0}, ${m.playerView}->getSubtitleView()$SUBTITLE_VIEW\nmove-result-object v0\nreturn-object v0")
        writeBridge("setCues", 2, "check-cast p0, $SUBTITLE_VIEW\ninvoke-virtual {p0, p1}, $SUBTITLE_VIEW->setCues(Ljava/util/List;)V\nreturn-void")
        for ((name, type) in listOf(
            "listenerType" to m.listener, "playerViewType" to m.playerView, "subtitleViewType" to SUBTITLE_VIEW,
            "localConfigType" to m.localConfig, "subtitleConfigType" to m.subtitleConfig, "trackGroupType" to m.trackGroup,
            "cueType" to m.cue, "textRendererType" to m.textRenderer,
        )) writeBridge(name, 1, "const-class v0, $type\nreturn-object v0")

        // End of every SubtitleConfiguration constructor: its Uri goes through onSubtitleUri (the first argument's register
        // is free by then). 1.12.0 inlines the builder that the earlier hook used, so the configuration itself is the hook.
        val sc = mutableClassDefBy(m.subtitleConfig)
        val uri = "${m.subtitleConfigUri.definingClass}->${m.subtitleConfigUri.name}:$URI"
        sc.methods.filter { it.name == "<init>" && it.parameterTypes.isNotEmpty() && it.parameterTypes[0].toString().let { t -> t.startsWith("L") || t.startsWith("[") } }
            .forEach { ctor ->
                val returns = ctor.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
                for (i in returns.reversed()) {
                    ctor.addInstructions(
                        i,
                        """
                            iget-object p1, p0, $uri
                            invoke-static {p1}, $EXTENSION->onSubtitleUri($URI)$URI
                            move-result-object p1
                            iput-object p1, p0, $uri
                        """,
                    )
                }
            }
        if (sc.methods.none { it.name == "<init>" }) throw PatchException("Media3 SubtitleConfiguration has no constructor")
    }
}

@Suppress("unused")
val subtitleRefreshPatch = bytecodePatch(
    name = "Subtitle auto-refresh",
    description = "When a SubSense entry that is still processing is selected, its finished subtitle replaces it " +
        "during playback with no reload, and a toast says so.",
    default = true,
) {
    compatibleWith(STREMIO_TV)
    dependsOn(media3BridgePatch)

    execute {
        PlayerViewSetPlayerFingerprint.method.addInstructions(
            0,
            "invoke-static {p0, p1}, $EXTENSION->onPlayerSet(Landroid/view/View;Ljava/lang/Object;)V",
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
    dependsOn(media3BridgePatch)

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
