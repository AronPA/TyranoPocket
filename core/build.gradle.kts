plugins { `java-library` }
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
tasks.register<JavaExec>("coreChecks") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.local.tyranopocket.core.CoreTests")
    args(rootProject.file("fixtures").absolutePath, layout.buildDirectory.dir("test-work").get().asFile.absolutePath)
    maxHeapSize = "48m"
}
tasks.check { dependsOn("coreChecks") }
