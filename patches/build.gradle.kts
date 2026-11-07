import org.gradle.jvm.tasks.Jar

group = "com.github.fluxusscripts.ekqimodyt"

base {
    archivesName.set("ekqimodyt-patches")
}

val includedPatchPackages = listOf(
    "app/revanced/patches/all/",
    "app/revanced/patches/shared/",
    "app/revanced/patches/youtube/",
    // YouTube's Shorts component patch reuses one shared matcher from YouTube Music.
    "app/revanced/patches/music/shared/",
)

tasks.withType<Jar>().configureEach {
    includeEmptyDirs = false

    eachFile {
        if (
            path.startsWith("app/revanced/patches/") &&
            includedPatchPackages.none(path::startsWith)
        ) {
            exclude()
        }
    }
}

patches {
    about {
        name = "EkqiModYT Patches"
        description = "ReVanced patches with OLED burn-in protection for YouTube Shorts"
        source = "https://github.com/FluxusScripts/ekqimodyt"
        author = "FluxusScripts"
        contact = "https://github.com/FluxusScripts/ekqimodyt/issues"
        website = "https://github.com/FluxusScripts/ekqimodyt"
        license = "GNU General Public License v3.0"
    }
}

dependencies {
    // Required due to smali, or build fails. Can be removed once smali is bumped.
    implementation(libs.guava)

    implementation(libs.apksig)

    // Android API stubs defined here.
    compileOnly(project(":patches:stub"))
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexplicit-backing-fields",
            "-Xcontext-parameters"
        )
    }
}

apply(from = "strings-processing.gradle.kts")
