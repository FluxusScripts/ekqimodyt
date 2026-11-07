package app.revanced.patches.youtube.layout.oledprotection

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.all.misc.resources.addResources
import app.revanced.patches.shared.misc.mapping.resourceMappingPatch
import app.revanced.patches.shared.misc.settings.preference.ListPreference
import app.revanced.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.revanced.patches.shared.misc.settings.preference.SwitchPreference
import app.revanced.patches.youtube.misc.extension.sharedExtensionPatch
import app.revanced.patches.youtube.misc.playertype.reelWatchPagerMethodMatch
import app.revanced.patches.youtube.misc.settings.PreferenceScreen
import app.revanced.patches.youtube.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/revanced/extension/youtube/patches/OledBurnInProtectionPatch;"

@Suppress("unused")
val oledProtectionPatch = bytecodePatch(
    name = "OLED burn-in protection",
    description = "Adds an OLED burn-in protection button and periodically shifts static Shorts controls.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        resourceMappingPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.26.46",
            "20.31.42",
            "20.37.48",
            "20.40.45",
        ),
    )

    apply {
        addResources("youtube", "layout.oledprotection.oledProtectionPatch")

        PreferenceScreen.SHORTS.addPreferences(
            PreferenceScreenPreference(
                key = "revanced_oled_protection_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = linkedSetOf(
                    SwitchPreference("revanced_oled_protection_enabled"),
                    SwitchPreference("revanced_oled_protection_show_button"),
                    ListPreference("revanced_oled_protection_interval_seconds"),
                    ListPreference("revanced_oled_protection_shift_dp"),
                ),
            ),
        )

        reelWatchPagerMethodMatch.let { match ->
            val viewResultIndex = match[-1]
            val viewRegister = match.method
                .getInstruction<OneRegisterInstruction>(viewResultIndex)
                .registerA

            match.method.addInstruction(
                viewResultIndex + 1,
                "invoke-static { v$viewRegister }, $EXTENSION_CLASS_DESCRIPTOR->onShortsCreate(Landroid/view/View;)V",
            )
        }
    }
}
