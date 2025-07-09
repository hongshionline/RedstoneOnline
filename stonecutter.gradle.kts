plugins {
    id("dev.kikugie.stonecutter")
    alias(libs.plugins.publishing)
}
stonecutter active "1.21.6-fabric" /* [SC] DO NOT EDIT */

stonecutter registerChiseled tasks.register("chiseledBuild", stonecutter.chiseled) {
    group = "chiseled"
    ofTask("build")
}

stonecutter registerChiseled tasks.register("chiseledRunClient", stonecutter.chiseled) {
    group = "chiseled"
    ofTask("runClient")
}

stonecutter registerChiseled tasks.register("chiseledPublishMods", stonecutter.chiseled) {
    group = "chiseled"
    ofTask("publishMods")
}
