import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Compose su un modulo Android gia' configurato con pockettravel.android.library o .application. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        pluginManager.withPlugin("com.android.library") {
            extensions.configure<LibraryExtension> { buildFeatures.compose = true }
        }
        pluginManager.withPlugin("com.android.application") {
            extensions.configure<ApplicationExtension> { buildFeatures.compose = true }
        }
        dependencies {
            val bom = platform(libs.findLibrary("compose-bom").get())
            add("implementation", bom)
            add("implementation", libs.findLibrary("compose-ui").get())
            add("implementation", libs.findLibrary("compose-material3").get())
            // Test di interfaccia (androidTest) con createComposeRule.
            add("androidTestImplementation", bom)
            add("androidTestImplementation", libs.findLibrary("compose-ui-test-junit4").get())
            add("androidTestImplementation", libs.findLibrary("androidx-test-runner").get())
            add("debugImplementation", libs.findLibrary("compose-ui-test-manifest").get())
        }
    }
}
