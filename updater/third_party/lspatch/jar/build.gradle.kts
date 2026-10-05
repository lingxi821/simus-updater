val verCode: Int by rootProject.extra
val verName: String by rootProject.extra
val androidSourceCompatibility: JavaVersion by rootProject.extra
val androidTargetCompatibility: JavaVersion by rootProject.extra

plugins {
    id("java-library")
}

java {
    sourceCompatibility = androidSourceCompatibility
    targetCompatibility = androidTargetCompatibility
}

dependencies {
    implementation(projects.patch)
}

fun Jar.configure(variant: String) {
    // The CLI patcher is invoked as `lspatch`; name the artifact after it, not after its packaging.
    archiveBaseName.set("lspatch-v$verName-$verCode-$variant")
    destinationDirectory.set(file("${rootProject.projectDir}/out/$variant"))
    manifest {
        attributes("Main-Class" to "org.lsposed.patch.LSPatch")
    }
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.map { configuration ->
        configuration.map { if (it.isDirectory) it else zipTree(it) }
    })

    into("assets") {
        from("src/main/assets")
        from("${rootProject.projectDir}/out/assets/$variant")
    }

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.MF", "META-INF/*.txt", "META-INF/versions/**")
}

// The jar folds out/assets/<variant> into its own assets (see configure). Depend on the concrete
// tasks that populate that directory, not the aggregator lifecycle task: only the concrete producers
// declare it as an output, so only naming them satisfies Gradle's input/output validation.
tasks.register<Jar>("buildDebug") {
    dependsOn(":meta-loader:copyDexDebug")
    dependsOn(":patch-loader:copyDexDebug")
    dependsOn(":patch-loader:copySoDebug")
    configure("debug")
}

tasks.register<Jar>("buildRelease") {
    dependsOn(":meta-loader:copyDexRelease")
    dependsOn(":patch-loader:copyDexRelease")
    dependsOn(":patch-loader:copySoRelease")
    configure("release")
}
